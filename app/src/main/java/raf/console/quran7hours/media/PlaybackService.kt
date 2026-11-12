package raf.console.quran7hours.media

import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.PlaybackException
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import raf.console.quran7hours.data.QuranCatalog
import raf.console.quran7hours.data.TimingCatalog

/** Two ExoPlayer engines, two sessions and one mutually exclusive audio output. */
@UnstableApi
class PlaybackService : MediaSessionService() {
    companion object { const val CHANNEL_HINT = "q7h.audio.channel" }
    private lateinit var hadr: ExoPlayer
    private lateinit var ayah: ExoPlayer
    private lateinit var hadrSession: MediaSession
    private lateinit var ayahSession: MediaSession
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val hadrPrefs by lazy { getSharedPreferences("q7h_compose", MODE_PRIVATE) }
    private var restoringHadr = false
    private var restoreWaitingForReady = false
    private var restoreTargetMs = 0L
    private var lastSavedMs = -1L
    private var lastSavedRealtime = 0L
    private val hadrTimings by lazy { TimingCatalog(this) }
    private var lastBoundaryHandled: String? = null

    // These are service-owned, not Compose-owned: playback continues in the background.
    private var hadrNKey: String? = null
    private var hadrNCompleted = 0
    private var internalHadrSeek = false
    private var ayahCycleKey: String? = null
    private var ayahPlayedForCurrentReciter = 0
    private var ayahRecitersCompleted = 0
    private var internalAyahTransition = false
    private var internalAyahSeek = false

    private fun resetHadrN() {
        hadrNKey = null
        hadrNCompleted = 0
        lastBoundaryHandled = null
    }

    private fun resetAyahCycle() {
        ayahCycleKey = null
        ayahPlayedForCurrentReciter = 0
        ayahRecitersCompleted = 0
    }

    /** Seek behind a timing boundary without turning off the MediaSession. */
    private fun hadrSeekWithinSegment(position: Long) {
        internalHadrSeek = true
        hadr.seekTo(position)
    }

    /** Exact timed segment repeat; supports all five segment types. */
    private fun applyHadrSegmentMode() {
        if (!::hadr.isInitialized || !hadr.isPlaying || restoreWaitingForReady) return
        val category = hadrPrefs.getString("hadr_active_segment", null) ?: return
        val segment = runCatching { QuranCatalog.Segment.valueOf(category) }.getOrNull() ?: return
        val marks = hadrTimings.marks(segment)
        val pos = hadr.currentPosition
        val currentIndex = marks.indexOfLast { it.positionMs != null && it.positionMs!! <= pos }
        for (index in listOf(currentIndex - 1, currentIndex)) {
            if (index !in marks.indices) continue
            val item = marks[index]
            val start = item.positionMs ?: continue
            // Missing editable timings are NOT permission to loop the rest of the MP3.
            // Only the actual last segment may terminate at the full recording end.
            val end = marks.getOrNull(index + 1)?.positionMs
                ?: (if (index == marks.lastIndex)
                    hadr.duration.takeIf { it > 0L && it != C.TIME_UNSET }
                    else null)
                ?: continue
            if (end <= start) continue
            val mode = hadrPrefs.getString("hadr_mode_${segment.name}_${item.number}", "NEXT") ?: "NEXT"
            if (mode == "NEXT") continue
            val n = hadrPrefs.getInt("hadr_repeat_n", 3).coerceIn(1, 1000)
            val marker = "${segment.name}:${item.number}:$mode:${if (mode == "REPEAT_N") n else 0}"
            if (mode == "REPEAT_N" && hadrNKey != marker && pos in start until end) {
                hadrNKey = marker
                hadrNCompleted = 0
            }
            if (pos < end - 500L && lastBoundaryHandled == marker) lastBoundaryHandled = null
            if (pos < end - 150L || pos > end + 1000L || lastBoundaryHandled == marker) continue
            lastBoundaryHandled = marker
            when (mode) {
                "REPEAT" -> hadrSeekWithinSegment(start)
                "STOP" -> {
                    hadr.pause()
                    hadrSeekWithinSegment(end)
                }
                "REPEAT_N" -> {
                    if (hadrNKey != marker) {
                        hadrNKey = marker
                        hadrNCompleted = 0
                    }
                    hadrNCompleted++
                    if (hadrNCompleted < n) {
                        hadrSeekWithinSegment(start)
                    } else {
                        hadr.pause()
                        hadrSeekWithinSegment(end)
                        // Do not restart N when resuming the next segment.
                        hadrNKey = null
                        hadrNCompleted = 0
                    }
                }
            }
            saveHadrPosition(force = true)
            break
        }
    }

    private fun saveHadrPosition(force: Boolean = false) {
        if (!::hadr.isInitialized || restoringHadr || restoreWaitingForReady ||
            hadr.currentMediaItem?.mediaId != "hadr") return
        val position = hadr.currentPosition.coerceAtLeast(0L)
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && lastSavedMs >= 0 &&
            kotlin.math.abs(lastSavedMs - position) < 800L && now - lastSavedRealtime < 1_000L) return
        if (hadrPrefs.edit().putLong("hadrPositionMs", position).commit()) {
            lastSavedMs = position
            lastSavedRealtime = now
        } else Log.w("Q7H-Hadr", "Failed to commit listening position")
    }

    private fun restoreHadrOnLaunch() {
        val exists = runCatching {
            assets.openFd("quran/quran_7_hours.mp3").use { it.length > 10_000_000L }
        }.getOrDefault(false)
        if (!exists) {
            Log.e("Q7H-Hadr", "Local MP3 missing/compressed: quran/quran_7_hours.mp3")
            return
        }
        val saved = hadrPrefs.getLong("hadrPositionMs", 0L).coerceAtLeast(0L)
        restoringHadr = true
        restoreWaitingForReady = true
        restoreTargetMs = saved
        try {
            hadr.setMediaItem(
                MediaItem.Builder().setMediaId("hadr")
                    .setUri(Uri.parse("asset:///quran/quran_7_hours.mp3")).build(), saved
            )
            hadr.playWhenReady = false
            hadr.prepare()
            lastSavedMs = saved
            lastSavedRealtime = android.os.SystemClock.elapsedRealtime()
        } finally { restoringHadr = false }
    }

    private fun currentAyahMode(): AyahEndMode =
        AyahEndMode.entries.firstOrNull {
            it.name == hadrPrefs.getString("ayahEndMode", "STOP")
        } ?: AyahEndMode.STOP

    private fun orderedReciters(): List<Reciter> = runCatching {
        val raw = hadrPrefs.getString("ayah_reciter_order", "[]") ?: "[]"
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length().coerceAtMost(43)) {
                val obj = array.optJSONObject(i) ?: continue
                val repository = obj.optString("repository")
                val branch = obj.optString("branch", "main")
                if (repository.matches(Regex("q7h_[A-Za-z0-9_-]+")) &&
                    branch.matches(Regex("[A-Za-z0-9._/-]+"))) {
                    add(Reciter(repository, branch))
                }
            }
        }.distinctBy { it.repository }
    }.getOrDefault(emptyList())

    /** Dedicated finite playback machine; NEVER enqueue 43 * 1000 MP3 items. */
    private fun onSpecialAyahFinished(error: Boolean = false) {
        if (!::ayah.isInitialized) return
        val mode = currentAyahMode()
        if (mode != AyahEndMode.REPEAT_N && mode != AyahEndMode.ALL_RECITERS) return
        val parts = ayah.currentMediaItem?.mediaId.orEmpty().split(':')
        if (parts.size != 5 || parts[0] != "ayah") return // Preface must never be repeated.
        val surah = parts[1].toIntOrNull() ?: return
        val verse = parts[2].toIntOrNull() ?: return
        if (!QuranCatalog.isValidAyah(surah, verse)) return
        val key = "$surah:$verse:${mode.name}"
        if (ayahCycleKey != key) {
            ayahCycleKey = key
            ayahPlayedForCurrentReciter = 0
            ayahRecitersCompleted = 0
        }
        val n = when (mode) {
            AyahEndMode.ALL_RECITERS -> hadrPrefs.getInt("ayah_all_reciters_repeat_count", 1)
            else -> hadrPrefs.getInt("ayah_repeat_n", 3)
        }.coerceIn(1, 1000)

        // A network 404 is skipped rather than retried a thousand times.
        if (!error && ayahPlayedForCurrentReciter + 1 < n) {
            ayahPlayedForCurrentReciter++
            internalAyahSeek = true
            ayah.seekTo(0L)
            ayah.play()
            return
        }
        ayahPlayedForCurrentReciter = 0
        if (mode == AyahEndMode.REPEAT_N) {
            resetAyahCycle()
            return // Last of N repeats ended naturally.
        }
        val order = orderedReciters()
        val currentIndex = order.indexOfFirst { it.repository == parts[3] && it.branch == parts[4] }
        ayahRecitersCompleted++
        if (order.isEmpty() || currentIndex < 0 || ayahRecitersCompleted >= order.size) {
            resetAyahCycle()
            return // Each available reciter has read the verse exactly N times.
        }
        val next = order[(currentIndex + 1) % order.size]
        val mediaId = "ayah:$surah:$verse:${next.repository}:${next.branch}"
        internalAyahTransition = true
        ayah.setMediaItem(MediaItem.Builder().setMediaId(mediaId)
            .setUri(next.audioUrl(surah, verse)).build())
        ayah.repeatMode = Player.REPEAT_MODE_OFF
        ayah.prepare()
        ayah.play()
    }

    override fun onCreate() {
        super.onCreate()
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .setUsage(C.USAGE_MEDIA)
            .build()
        hadr = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(audioAttributes, true)
            setHandleAudioBecomingNoisy(true)
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = false
        }
        ayah = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(audioAttributes, true)
            setHandleAudioBecomingNoisy(true)
        }
        hadr.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) ayah.pause()
                saveHadrPosition(force = true)
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && restoreWaitingForReady) {
                    restoreWaitingForReady = false
                    val duration = hadr.duration
                    val restored = if (duration > 0L && duration != C.TIME_UNSET)
                        restoreTargetMs.coerceIn(0L, duration) else restoreTargetMs
                    if (kotlin.math.abs(hadr.currentPosition - restored) > 750L) hadr.seekTo(restored)
                }
                if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED)
                    saveHadrPosition(force = true)
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                saveHadrPosition(force = true)
            }
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int
            ) {
                if (restoreWaitingForReady && reason == Player.DISCONTINUITY_REASON_SEEK)
                    restoreTargetMs = newPosition.positionMs.coerceAtLeast(0L)
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    if (!internalHadrSeek) resetHadrN() // Manual seek starts a new N series.
                    internalHadrSeek = false
                }
                saveHadrPosition(force = true)
            }
            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                if (playbackParameters != PlaybackParameters.DEFAULT)
                    hadr.playbackParameters = PlaybackParameters.DEFAULT
            }
            override fun onPlayerError(error: PlaybackException) {
                saveHadrPosition(force = true)
                Log.e("Q7H-Hadr", "Ошибка чтения / декодирования quran_7_hours.mp3", error)
            }
        })
        ayah.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) hadr.pause()
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (!internalAyahTransition) resetAyahCycle()
                internalAyahTransition = false
                updateNextAyah()
            }
            override fun onRepeatModeChanged(repeatMode: Int) {
                if (repeatMode == Player.REPEAT_MODE_ALL) updateNextAyah()
                else clearFollowingAyahs()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) onSpecialAyahFinished()
            }
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    if (!internalAyahSeek) resetAyahCycle()
                    internalAyahSeek = false
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                Log.w("Q7H-Ayah", "Reciter stream failed; skipping this reciter", error)
                onSpecialAyahFinished(error = true)
            }
        })
        restoreHadrOnLaunch()
        hadrSession = MediaSession.Builder(this, hadr).setId("q7h_hadr").build()
        ayahSession = MediaSession.Builder(this, ayah).setId("q7h_ayah").build()
        persistenceScope.launch {
            while (isActive) {
                delay(150L)
                if (hadr.isPlaying) {
                    applyHadrSegmentMode()
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastSavedRealtime >= 1_000L) saveHadrPosition()
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession =
        if (controllerInfo.connectionHints.getString(CHANNEL_HINT) == AudioChannel.AYAH.key)
            ayahSession else hadrSession

    private fun clearFollowingAyahs() {
        val index = ayah.currentMediaItemIndex
        if (index >= 0 && index + 1 < ayah.mediaItemCount)
            ayah.removeMediaItems(index + 1, ayah.mediaItemCount)
    }

    /** Existing NEXT mode: append the next verse in the same reciter. */
    private fun updateNextAyah() {
        if (ayah.repeatMode != Player.REPEAT_MODE_ALL) return
        val fields = ayah.currentMediaItem?.mediaId.orEmpty().split(':')
        if (fields.size != 5 || fields[0] != "ayah") return
        val surah = fields[1].toIntOrNull() ?: return
        val verse = fields[2].toIntOrNull() ?: return
        val next = QuranCatalog.nextAyah(surah, verse)
        if (next == null) {
            ayah.repeatMode = Player.REPEAT_MODE_OFF
            return
        }
        val repository = fields[3]
        val branch = fields[4]
        val expectedId = "ayah:${next.first}:${next.second}:$repository:$branch"
        val current = ayah.currentMediaItemIndex
        if (current < 0) return
        if ((current + 1 until ayah.mediaItemCount).none {
                ayah.getMediaItemAt(it).mediaId == expectedId
            }) {
            val mediaItem = MediaItem.Builder().setMediaId(expectedId)
                .setUri(Reciter(repository, branch).audioUrl(next.first, next.second)).build()
            ayah.addMediaItem(current + 1, mediaItem)
        }
        if (current > 3) ayah.removeMediaItems(0, current - 1)
    }

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        saveHadrPosition(force = true)
        super.onTaskRemoved(rootIntent)
    }
    override fun onTrimMemory(level: Int) {
        saveHadrPosition(force = true)
        super.onTrimMemory(level)
    }
    override fun onDestroy() {
        persistenceScope.cancel()
        saveHadrPosition(force = true)
        hadrSession.release()
        ayahSession.release()
        hadr.release()
        ayah.release()
        super.onDestroy()
    }
}
