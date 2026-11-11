package raf.console.quran7hours.media

import androidx.media3.common.MediaItem
import android.net.Uri
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.PlaybackException
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import raf.console.quran7hours.data.QuranCatalog

/**
 * Two independent ExoPlayer engines, two sessions, one mutually exclusive audio output.
 * Hadr is a prepared local MP3 with on-disk listening checkpoints.
 */
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

    /** Synchronous, tiny checkpoint. Do not overwrite restored progress before source is ready. */
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

    /** Prepared while paused: all future controllers see the correct mediaId and position. */
    private fun restoreHadrOnLaunch() {
        val exists = runCatching {
            assets.openFd("quran/quran_7_hours.mp3").use { it.length > 10_000_000L }
        }.getOrDefault(false)
        if (!exists) {
            Log.e("Q7H-Hadr", "Local MP3 missing or compressed in APK: quran/quran_7_hours.mp3")
            return
        }
        val saved = hadrPrefs.getLong("hadrPositionMs", 0L).coerceAtLeast(0L)
        restoringHadr = true
        restoreWaitingForReady = true
        restoreTargetMs = saved
        try {
            hadr.setMediaItem(
                MediaItem.Builder().setMediaId("hadr")
                    .setUri(Uri.parse("asset:///quran/quran_7_hours.mp3")).build(),
                saved
            )
            hadr.playWhenReady = false
            hadr.prepare()
            // Keep the checkpoint intact until playback advances or the user seeks.
            lastSavedMs = saved
            lastSavedRealtime = android.os.SystemClock.elapsedRealtime()
        } finally {
            restoringHadr = false
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Local uncompressed MP3 asset. Media3 resolves asset:/// via AssetDataSource.
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
                    // Protect saved progress against an early Media3 timeline update.
                    if (kotlin.math.abs(hadr.currentPosition - restored) > 750L)
                        hadr.seekTo(restored)
                }
                if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED)
                    saveHadrPosition(force = true)
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                saveHadrPosition(force = true)
            }
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                // A user seek while preparing supersedes the last saved checkpoint.
                if (restoreWaitingForReady && reason == Player.DISCONTINUITY_REASON_SEEK)
                    restoreTargetMs = newPosition.positionMs.coerceAtLeast(0L)
                saveHadrPosition(force = true)
            }
            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                if (playbackParameters != PlaybackParameters.DEFAULT) {
                    hadr.playbackParameters = PlaybackParameters.DEFAULT
                }
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
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = updateNextAyah()
            override fun onRepeatModeChanged(repeatMode: Int) {
                if (repeatMode == Player.REPEAT_MODE_ALL) updateNextAyah()
                else clearFollowingAyahs()
            }
        })
        restoreHadrOnLaunch()
        hadrSession = MediaSession.Builder(this, hadr).setId("q7h_hadr").build()
        ayahSession = MediaSession.Builder(this, ayah).setId("q7h_ayah").build()
        // Persist progress even if the reader Activity has been closed and audio
        // continues from this foreground service.
        persistenceScope.launch {
            while (isActive) {
                delay(1_000L)
                if (hadr.isPlaying) saveHadrPosition()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession {
        return if (controllerInfo.connectionHints.getString(CHANNEL_HINT) == AudioChannel.AYAH.key)
            ayahSession else hadrSession
    }

    private fun clearFollowingAyahs() {
        val index = ayah.currentMediaItemIndex
        if (index >= 0 && index + 1 < ayah.mediaItemCount) {
            ayah.removeMediaItems(index + 1, ayah.mediaItemCount)
        }
    }

    private fun updateNextAyah() {
        if (ayah.repeatMode != Player.REPEAT_MODE_ALL) return
        val id = ayah.currentMediaItem?.mediaId.orEmpty()
        val fields = id.split(':')
        if (fields.size != 5 || fields[0] != "ayah") return
        val surah = fields[1].toIntOrNull() ?: return
        val verse = fields[2].toIntOrNull() ?: return
        val next = QuranCatalog.nextAyah(surah, verse)
        if (next == null) {
            // The last ayah must stop, not restart the whole playlist.
            ayah.repeatMode = Player.REPEAT_MODE_OFF
            return
        }
        val repo = fields[3]
        val branch = fields[4]
        val expectedId = "ayah:${next.first}:${next.second}:$repo:$branch"
        val current = ayah.currentMediaItemIndex
        if (current < 0) return
        if ((current + 1 until ayah.mediaItemCount).none { ayah.getMediaItemAt(it).mediaId == expectedId }) {
            val mediaItem = MediaItem.Builder().setMediaId(expectedId)
                .setUri(Reciter(repo, branch).audioUrl(next.first, next.second)).build()
            ayah.addMediaItem(current + 1, mediaItem)
        }
        // Keep the queue bounded during continuous reading.
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
