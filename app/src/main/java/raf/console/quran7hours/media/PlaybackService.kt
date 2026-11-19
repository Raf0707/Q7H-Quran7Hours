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
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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

/** Independent Hadr, Tafsir and Hafiz players with three sessions; output stays mutually exclusive. */
@UnstableApi
class PlaybackService : MediaSessionService() {
    companion object { const val CHANNEL_HINT = "q7h.audio.channel" }
    private lateinit var hadr: ExoPlayer
    private lateinit var tafsirAyah: ExoPlayer
    private lateinit var hafizAyah: ExoPlayer
    private val ayah: ExoPlayer get() = if(activeAyahChannel == AudioChannel.HAFIZ) hafizAyah else tafsirAyah
    private var activeAyahChannel = AudioChannel.AYAH
    private fun <T> withAyahChannel(channel: AudioChannel, block: () -> T): T {
        val previous = activeAyahChannel
        activeAyahChannel = channel
        return try { block() } finally { activeAyahChannel = previous }
    }
    private data class AyahEngineState(
        var cycleKey: String? = null,
        var playedForCurrentReciter: Int = 0,
        var recitersCompleted: Int = 0,
        var internalTransition: Boolean = false,
        var internalSeek: Boolean = false,
        var rangePasses: Int = 0,
        var rangeReciter: Int = 0,
        var lastSeekGeneration: Int = 0
    )
    private val tafsirEngineState = AyahEngineState()
    private val hafizEngineState = AyahEngineState()
    private val ayahState: AyahEngineState get() =
        if (activeAyahChannel == AudioChannel.HAFIZ) hafizEngineState else tafsirEngineState
    private fun rangeKey(key: String): String = if(activeAyahChannel == AudioChannel.HAFIZ)
        key.replace("q7h_range_ayah_","q7h_range_hafiz_")
           .replace("q7h_ayah_seek_","q7h_hafiz_seek_") else key
    private lateinit var hadrSession: MediaSession
    private lateinit var ayahSession: MediaSession
    private lateinit var hafizSession: MediaSession
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
    private var lastHadrRangeSignature: String? = null
    private var hadrRangePasses = 0


    private fun resetHadrN() {
        hadrNKey = null
        hadrNCompleted = 0
        lastBoundaryHandled = null
    }

    /** Respond to the second range slider without clearing the selected range. */
    private fun applyRequestedAyahSeek() {
        val generation=hadrPrefs.getInt(rangeKey("q7h_ayah_seek_generation"),0)
        if(generation==ayahState.lastSeekGeneration || !::tafsirAyah.isInitialized) return
        ayahState.lastSeekGeneration=generation
        if(!hadrPrefs.getBoolean(rangeKey("q7h_range_ayah_enabled"),false)) return
        val surah=hadrPrefs.getInt(rangeKey("q7h_ayah_seek_target_s"),-1)
        val verse=hadrPrefs.getInt(rangeKey("q7h_ayah_seek_target_a"),-1)
        if(!QuranCatalog.isValidAyah(surah,verse)) return
        val startS=hadrPrefs.getInt(rangeKey("q7h_range_ayah_start_s"),-1)
        val startA=hadrPrefs.getInt(rangeKey("q7h_range_ayah_start_a"),-1)
        val endS=hadrPrefs.getInt(rangeKey("q7h_range_ayah_end_s"),-1)
        val endA=hadrPrefs.getInt(rangeKey("q7h_range_ayah_end_a"),-1)
        if(surah<startS || surah==startS && verse<startA ||
            surah>endS || surah==endS && verse>endA) return
        val parts=ayah.currentMediaItem?.mediaId.orEmpty().split(':')
        if(parts.size!=5 || parts[0]!="ayah") return
        val reciter=Reciter(parts[3],parts[4])
        ayahState.internalTransition=true
        ayah.setMediaItem(MediaItem.Builder().setMediaId(
            "ayah:$surah:$verse:${reciter.repository}:${reciter.branch}")
            .setUri(reciter.audioUrl(surah,verse)).build())
        ayah.repeatMode=Player.REPEAT_MODE_OFF
        ayah.prepare()
        ayah.play()
    }

    private fun resetAyahCycle() {
        ayahState.cycleKey = null
        ayahState.playedForCurrentReciter = 0
        ayahState.recitersCompleted = 0
    }

    /** Seek behind a timing boundary without turning off the MediaSession. */
    private fun hadrSeekWithinSegment(position: Long) {
        internalHadrSeek = true
        hadr.seekTo(position)
    }

    /** Repeat a range or a single EXPLICITLY selected segment, never its predecessor. */
    private fun applyHadrSegmentMode() {
        if (!::hadr.isInitialized || !hadr.isPlaying || restoreWaitingForReady) return
        if (hadrPrefs.getBoolean("q7h_range_hadr_enabled",false)) {
            applyHadrRange()
            return
        }
        val category = hadrPrefs.getString("hadr_active_segment",null) ?: return
        val segment = runCatching { QuranCatalog.Segment.valueOf(category) }.getOrNull() ?: return
        val number = hadrPrefs.getInt("hadr_active_number",-1)
        val marks = hadrTimings.marks(segment)
        val index = marks.indexOfFirst { it.number == number }
        if (index < 0) return
        val start = marks[index].positionMs ?: return
        val end = marks.getOrNull(index+1)?.positionMs
            ?: hadr.duration.takeIf { index == marks.lastIndex && it > 0 && it != C.TIME_UNSET }
            ?: return
        if (end <= start) return
        val pos = hadr.currentPosition
        // A manual selection of page 4 MUST NOT be sent back to looping page 3.
        if (pos < start || pos > end + 500L) return
        val mode = hadrPrefs.getString("hadr_mode_${segment.name}_$number","NEXT") ?: "NEXT"
        if (mode == "NEXT") return
        val marker = "${segment.name}:$number:$mode"
        if (hadrNKey != marker) {
            hadrNKey=marker; hadrNCompleted=0; lastBoundaryHandled=null
        }
        if (pos < end - 450L) {
            if (lastBoundaryHandled == marker) lastBoundaryHandled=null
            return
        }
        if (pos < end - 180L || lastBoundaryHandled == marker) return
        lastBoundaryHandled = marker
        when (mode) {
            "REPEAT" -> hadrSeekWithinSegment(start)
            "STOP" -> { hadr.pause(); hadrSeekWithinSegment(end) }
            "REPEAT_N" -> {
                hadrNCompleted++
                val n = hadrPrefs.getInt("hadr_repeat_n",3).coerceIn(1,1000)
                if (hadrNCompleted < n) hadrSeekWithinSegment(start)
                else { hadr.pause(); hadrSeekWithinSegment(end) }
            }
        }
        saveHadrPosition(force=true)
    }

    /** Hadr ranges use only actual recorded marks and end at the next mark. */
    private fun applyHadrRange() {
        val segment = runCatching {
            QuranCatalog.Segment.valueOf(hadrPrefs.getString("q7h_range_hadr_segment","") ?: "")
        }.getOrNull() ?: return
        val first = hadrPrefs.getInt("q7h_range_hadr_start",-1)
        val last = hadrPrefs.getInt("q7h_range_hadr_end",-1)
        val marks = hadrTimings.marks(segment)
        val startIndex = marks.indexOfFirst { it.number == first }
        val endIndex = marks.indexOfFirst { it.number == last }
        if (startIndex < 0 || endIndex < startIndex) return
        val start = marks[startIndex].positionMs ?: return
        val end = marks.getOrNull(endIndex + 1)?.positionMs
            ?: hadr.duration.takeIf { endIndex == marks.lastIndex && it > 0 && it != C.TIME_UNSET }
            ?: return
        if (start >= end) return
        val mode = hadrPrefs.getString("q7h_range_hadr_mode","STOP") ?: "STOP"
        val generation=hadrPrefs.getInt("q7h_range_hadr_generation",0)
        val signature = "${segment.name}:$first:$last:$mode:$generation"
        if (lastHadrRangeSignature != signature) {
            lastHadrRangeSignature=signature
            hadrRangePasses=0
            lastBoundaryHandled=null
        }
        val pos=hadr.currentPosition
        if (pos < start || pos > end + 600L) return
        if (pos < end - 450L) {
            if (lastBoundaryHandled == signature) lastBoundaryHandled=null
            return
        }
        if (pos < end - 180L || lastBoundaryHandled == signature) return
        lastBoundaryHandled = signature
        hadrRangePasses++
        val n = hadrPrefs.getInt("hadr_repeat_n",3).coerceIn(1,1000)
        val again = mode == "REPEAT" || mode == "REPEAT_N" && hadrRangePasses < n
        if (mode == "NEXT") {
            hadrPrefs.edit().putBoolean("q7h_range_hadr_enabled",false).apply()
            lastBoundaryHandled=null
            return // Continue the full 7-hour MP3 after the interval.
        }
        if (again) {
            hadrSeekWithinSegment(start)
        } else {
            hadr.pause()
            hadrSeekWithinSegment(end)
            hadrPrefs.edit().putBoolean("q7h_range_hadr_enabled",false).apply()
        }
        saveHadrPosition(force=true)
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
            it.name == hadrPrefs.getString(if(activeAyahChannel == AudioChannel.HAFIZ) "hafizEndMode" else "ayahEndMode", "STOP")
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
    }.getOrDefault(emptyList()).let { all -> Q7HPersonQueue.enabled(all, hadrPrefs) }

    /** Consume a verse-ended event when the range player is active. */
    private fun onAyahRangeFinished(error: Boolean): Boolean {
        if (!hadrPrefs.getBoolean(rangeKey("q7h_range_ayah_enabled"),false)) return false
        val fields = ayah.currentMediaItem?.mediaId.orEmpty().split(':')
        if (fields.size != 5 || fields[0] != "ayah") return false
        val surah=fields[1].toIntOrNull() ?: return false
        val verse=fields[2].toIntOrNull() ?: return false
        val endSurah=hadrPrefs.getInt(rangeKey("q7h_range_ayah_end_s"),-1)
        val endAyah=hadrPrefs.getInt(rangeKey("q7h_range_ayah_end_a"),-1)
        if (!QuranCatalog.isValidAyah(endSurah,endAyah)) return false
        val mode=currentAyahMode()
        val next=QuranCatalog.nextAyah(surah,verse)
        if (next != null && (next.first < endSurah ||
                next.first == endSurah && next.second <= endAyah)) {
            playRangeVerse(next.first,next.second,fields[3],fields[4])
            return true
        }
        val startS=hadrPrefs.getInt(rangeKey("q7h_range_ayah_start_s"),surah)
        val startA=hadrPrefs.getInt(rangeKey("q7h_range_ayah_start_a"),verse)
        val allowedPasses = when (mode) {
            AyahEndMode.ALL_RECITERS -> hadrPrefs.getInt("ayah_all_reciters_repeat_count",1)
            AyahEndMode.REPEAT_N -> hadrPrefs.getInt("ayah_repeat_n",3)
            else -> 1
        }.coerceIn(1,1000)
        ayahState.rangePasses++
        if (!error && (mode == AyahEndMode.REPEAT || ayahState.rangePasses < allowedPasses)) {
            playRangeVerse(startS,startA,fields[3],fields[4])
            return true
        }
        ayahState.rangePasses=0
        if (mode == AyahEndMode.NEXT) {
            hadrPrefs.edit().putBoolean(rangeKey("q7h_range_ayah_enabled"),false).apply()
            val following=QuranCatalog.nextAyah(surah,verse)
            if (following != null) {
                playRangeVerse(following.first,following.second,fields[3],fields[4])
                ayah.repeatMode=Player.REPEAT_MODE_ALL
            }
            return true
        }
        if (mode == AyahEndMode.ALL_RECITERS) {
            val all=orderedReciters()
            val at=all.indexOfFirst { it.repository == fields[3] && it.branch == fields[4] }
            if (all.isNotEmpty() && (at < 0 || at + 1 < all.size)) {
                val nextReciter = all[if (at < 0) 0 else at + 1]
                ayahState.rangeReciter++
                playRangeVerse(startS,startA,nextReciter.repository,nextReciter.branch)
                return true
            }
        }
        hadrPrefs.edit().putBoolean(rangeKey("q7h_range_ayah_enabled"),false).apply()
        return true
    }

    private fun playRangeVerse(surah:Int,verse:Int,repository:String,branch:String) {
        if (!QuranCatalog.isValidAyah(surah,verse)) return
        val reciter=Reciter(repository,branch)
        ayahState.internalTransition=true
        ayah.setMediaItem(MediaItem.Builder()
            .setMediaId("ayah:$surah:$verse:$repository:$branch")
            .setUri(reciter.audioUrl(surah,verse)).build())
        ayah.repeatMode=Player.REPEAT_MODE_OFF
        ayah.prepare()
        ayah.play()
    }

    /** Dedicated finite playback machine; NEVER enqueue 43 * 1000 MP3 items. */
    private fun onSpecialAyahFinished(error: Boolean = false) {
        if (!::tafsirAyah.isInitialized) return
        val mode = currentAyahMode()
        if (mode != AyahEndMode.REPEAT_N && mode != AyahEndMode.ALL_RECITERS) return
        val parts = ayah.currentMediaItem?.mediaId.orEmpty().split(':')
        if (parts.size != 5 || parts[0] != "ayah") return // Preface must never be repeated.
        val surah = parts[1].toIntOrNull() ?: return
        val verse = parts[2].toIntOrNull() ?: return
        if (!QuranCatalog.isValidAyah(surah, verse)) return
        val key = "$surah:$verse:${mode.name}"
        if (ayahState.cycleKey != key) {
            ayahState.cycleKey = key
            ayahState.playedForCurrentReciter = 0
            ayahState.recitersCompleted = 0
        }
        val n = when (mode) {
            AyahEndMode.ALL_RECITERS -> hadrPrefs.getInt("ayah_all_reciters_repeat_count", 1)
            else -> hadrPrefs.getInt("ayah_repeat_n", 3)
        }.coerceIn(1, 1000)

        // A network 404 is skipped rather than retried a thousand times.
        if (!error && ayahState.playedForCurrentReciter + 1 < n) {
            ayahState.playedForCurrentReciter++
            ayahState.internalSeek = true
            ayah.seekTo(0L)
            ayah.play()
            return
        }
        ayahState.playedForCurrentReciter = 0
        if (mode == AyahEndMode.REPEAT_N) {
            resetAyahCycle()
            return // Last of N repeats ended naturally.
        }
        val order = orderedReciters()
        val currentIndex = order.indexOfFirst { it.repository == parts[3] && it.branch == parts[4] }
        ayahState.recitersCompleted++
        if (order.isEmpty() || (currentIndex >= 0 && currentIndex + 1 >= order.size) ||
            ayahState.recitersCompleted >= order.size) {
            resetAyahCycle()
            return // Each selected reciter has read the verse exactly N times.
        }
        // If the playing voice has just been unchecked, continue at the first
        // enabled voice instead of accidentally including an unchecked reciter.
        val next = order[if (currentIndex < 0) 0 else currentIndex + 1]
        val mediaId = "ayah:$surah:$verse:${next.repository}:${next.branch}"
        ayahState.internalTransition = true
        ayah.setMediaItem(MediaItem.Builder().setMediaId(mediaId)
            .setUri(next.audioUrl(surah, verse)).build())
        ayah.repeatMode = Player.REPEAT_MODE_OFF
        ayah.prepare()
        ayah.play()
    }

    private fun ayahListener(channel: AudioChannel): Player.Listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = withAyahChannel(channel) {
            if (isPlaying) {
                hadr.pause()
                if (channel == AudioChannel.HAFIZ) tafsirAyah.pause() else hafizAyah.pause()
            }
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = withAyahChannel(channel) {
            if (!ayahState.internalTransition) {
                resetAyahCycle()
                ayahState.rangePasses=0
                ayahState.rangeReciter=0
            }
            ayahState.internalTransition=false
            if (!hadrPrefs.getBoolean(rangeKey("q7h_range_ayah_enabled"),false)) updateNextAyah()
        }
        override fun onRepeatModeChanged(repeatMode: Int) = withAyahChannel(channel) {
            if (!hadrPrefs.getBoolean(rangeKey("q7h_range_ayah_enabled"),false)) {
                if (repeatMode == Player.REPEAT_MODE_ALL) updateNextAyah() else clearFollowingAyahs()
            }
        }
        override fun onPlaybackStateChanged(playbackState: Int) = withAyahChannel(channel) {
            if (playbackState == Player.STATE_ENDED && !onAyahRangeFinished(false)) onSpecialAyahFinished()
        }
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo, reason: Int) = withAyahChannel(channel) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                if (!ayahState.internalSeek) resetAyahCycle()
                ayahState.internalSeek=false
            }
        }
        override fun onPlayerError(error: PlaybackException) = withAyahChannel(channel) {
            Log.w("Q7H-Ayah", "Reciter stream failed; skipping this reciter",error)
            if (!onAyahRangeFinished(true)) onSpecialAyahFinished(error=true)
        }
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
        // Both ayah engines read from the SAME bounded cache as preloading.
        // The Hadr engine remains unchanged (local MP3, no network).
        val ayahSources = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(Q7HAyahStreamCache.factory(this))
        tafsirAyah = ExoPlayer.Builder(this)
            .setMediaSourceFactory(ayahSources).build().apply {
            setAudioAttributes(audioAttributes, true)
            setHandleAudioBecomingNoisy(true)
        }
        hafizAyah = ExoPlayer.Builder(this)
            .setMediaSourceFactory(ayahSources).build().apply {
            setAudioAttributes(audioAttributes, true)
            setHandleAudioBecomingNoisy(true)
        }
        hadr.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) { tafsirAyah.pause(); hafizAyah.pause() }
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
                    if (!internalHadrSeek) {
                        resetHadrN()
                        hadrRangePasses=0
                        lastHadrRangeSignature=null
                    }
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
        tafsirAyah.addListener(ayahListener(AudioChannel.AYAH))
        hafizAyah.addListener(ayahListener(AudioChannel.HAFIZ))
        restoreHadrOnLaunch()
        hadrSession = MediaSession.Builder(this, hadr).setId("q7h_hadr").build()
        ayahSession = MediaSession.Builder(this, tafsirAyah).setId("q7h_ayah").build()
        hafizSession = MediaSession.Builder(this, hafizAyah).setId("q7h_hafiz").build()
        persistenceScope.launch {
            while (isActive) {
                delay(150L)
                withAyahChannel(AudioChannel.AYAH) { applyRequestedAyahSeek() }
                withAyahChannel(AudioChannel.HAFIZ) { applyRequestedAyahSeek() }
                if (hadr.isPlaying) {
                    applyHadrSegmentMode()
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastSavedRealtime >= 1_000L) saveHadrPosition()
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession =
        when(controllerInfo.connectionHints.getString(CHANNEL_HINT)) {
            AudioChannel.AYAH.key -> ayahSession
            AudioChannel.HAFIZ.key -> hafizSession
            else -> hadrSession
        }

    private fun clearFollowingAyahs() {
        val index = ayah.currentMediaItemIndex
        if (index >= 0 && index + 1 < ayah.mediaItemCount)
            ayah.removeMediaItems(index + 1, ayah.mediaItemCount)
    }

    /** Existing NEXT mode: append the next verse in the same reciter. */
    private fun updateNextAyah() {
        if (hadrPrefs.getBoolean(rangeKey("q7h_range_ayah_enabled"),false) ||
            ayah.repeatMode != Player.REPEAT_MODE_ALL) return
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
        hafizSession.release()
        hadr.release()
        tafsirAyah.release()
        hafizAyah.release()
        super.onDestroy()
    }
}
