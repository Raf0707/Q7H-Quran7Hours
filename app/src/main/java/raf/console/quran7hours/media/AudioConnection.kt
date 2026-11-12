package raf.console.quran7hours.media

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.suspendCancellableCoroutine
import raf.console.quran7hours.data.QuranCatalog
import kotlin.coroutines.resume

/** The local Yasser ad-Dosari recordings, shared by every reciter. */
enum class PrefacePart(val fileName: String, val title: String) {
    ISTIAZA("istiadha.mp3", "Истиаза"),
    BASMALA("basmala.mp3", "Басмала")
}

enum class AudioChannel(val key: String) { HADR("hadr"), AYAH("ayah") }

/** FIVE mutually exclusive modes of ayah playback. */
enum class AyahEndMode(val title: String) {
    STOP("Остановиться после аята"),
    REPEAT("Повторять аят без ограничений"),
    NEXT("Следующий аят"),
    ALL_RECITERS("Все чтецы по очереди"),
    REPEAT_N("Повторить аят N раз");

    fun toRepeatMode(): Int = when (this) {
        STOP, ALL_RECITERS, REPEAT_N -> Player.REPEAT_MODE_OFF
        REPEAT -> Player.REPEAT_MODE_ONE
        NEXT -> Player.REPEAT_MODE_ALL
    }
}

class AudioConnection(context: Context, private val channel: AudioChannel) {
    private val app = context.applicationContext
    private val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
    private val future: ListenableFuture<MediaController> = MediaController.Builder(app, token)
        .setConnectionHints(Bundle().apply { putString(PlaybackService.CHANNEL_HINT, channel.key) })
        .buildAsync()
    private var controller: MediaController? = null

    suspend fun connect(): MediaController = suspendCancellableCoroutine { continuation ->
        future.addListener({
            try {
                val ready = future.get()
                controller = ready
                if (continuation.isActive) continuation.resume(ready)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.cancel(error)
            }
        }, ContextCompat.getMainExecutor(app))
    }

    fun close() = MediaController.releaseFuture(future)

    fun playHadr(uri: Uri, startPositionMs: Long = 0L) {
        if (channel != AudioChannel.HADR) return
        controller?.run {
            if (currentMediaItem?.mediaId == "hadr") {
                seekTo(startPositionMs.coerceAtLeast(0L))
                if (playbackState == Player.STATE_IDLE) prepare()
            } else {
                setMediaItem(
                    MediaItem.Builder().setMediaId("hadr").setUri(uri).build(),
                    startPositionMs.coerceAtLeast(0L)
                )
                prepare()
            }
            repeatMode = Player.REPEAT_MODE_OFF
            play()
        }
    }

    fun playAyah(reciter: Reciter, surah: Int, ayah: Int, endMode: AyahEndMode) {
        if (channel != AudioChannel.AYAH || !QuranCatalog.isValidAyah(surah, ayah)) return
        val id = "ayah:$surah:$ayah:${reciter.repository}:${reciter.branch}"
        // The service gets an explicit new playlist on every new request: reset its N counters.
        controller?.run {
            setMediaItem(MediaItem.Builder().setMediaId(id).setUri(reciter.audioUrl(surah, ayah)).build())
            repeatMode = endMode.toRepeatMode()
            prepare()
            play()
        }
    }

    fun playPreface(part: PrefacePart) {
        if (channel != AudioChannel.AYAH) return
        val uri = Uri.parse("asset:///quran/${part.fileName}")
        controller?.run {
            setMediaItem(MediaItem.Builder().setMediaId("preface:${part.name}").setUri(uri).build())
            repeatMode = Player.REPEAT_MODE_OFF
            prepare()
            play()
        }
    }

    fun setAyahEndMode(mode: AyahEndMode) {
        if (channel == AudioChannel.AYAH) controller?.repeatMode = mode.toRepeatMode()
    }
}
