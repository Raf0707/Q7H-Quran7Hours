package raf.console.quran7hours.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Only a light/dark visual effect; switching Tajweed never invokes this. */
internal data class ThemeRevealFrame(
    val previous: Bitmap,
    val next: Bitmap,
    val previousBackdrop: Color,
    val nextBackdrop: Color
)

private val revealPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

@Composable
internal fun TelegramThemeReveal(
    frame: ThemeRevealFrame,
    progress: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val p = progress.coerceIn(0f, 1f)
        // Circular reveal from the top-right theme toggle.
        val cx = size.width * 0.91f
        val cy = size.height * 0.075f
        val radius = hypot(max(cx, size.width - cx), max(cy, size.height - cy)) * p

        fun dst(bitmap: Bitmap): RectF {
            val fit = min(size.width / bitmap.width, size.height / bitmap.height)
            val w = bitmap.width * fit
            val h = bitmap.height * fit
            return RectF((size.width-w)/2, (size.height-h)/2,
                (size.width+w)/2, (size.height+h)/2)
        }
        drawIntoCanvas { composeCanvas ->
            val canvas = composeCanvas.nativeCanvas
            // The new page lies underneath. The previous page is cut away in
            // an expanding circle, like Telegram theme switching.
            canvas.drawColor(frame.nextBackdrop.toArgb())
            canvas.drawBitmap(frame.next, null, dst(frame.next), revealPaint)
            val checkpoint = canvas.save()
            val circle = Path().apply { addCircle(cx, cy, radius, Path.Direction.CW) }
            canvas.clipOutPath(circle)
            canvas.drawColor(frame.previousBackdrop.toArgb())
            canvas.drawBitmap(frame.previous, null, dst(frame.previous), revealPaint)
            canvas.restoreToCount(checkpoint)
        }
    }
}
