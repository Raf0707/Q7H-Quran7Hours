package raf.console.quran7hours.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import raf.console.quran7hours.data.InkStroke
import kotlin.math.min

/**
 * A display-only indicator for the currently playing ayah (light beige #F8F3EA).
 * It is drawn between the Mushaf bitmap and the user's InkPageLayer; no Room
 * objects, saved markers, calibration results, or exported annotations change.
 * Its verse segments are the canonical smart-marker geometry on the CLEAN page.
 */
@Composable
internal fun Q7HPlayingAyahOverlay(
    bitmap: Bitmap,
    strokes: List<InkStroke>,
    darkPage: Boolean,
    modifier: Modifier = Modifier
) {
    if (strokes.isEmpty()) return
    Canvas(modifier) {
        if (bitmap.width <= 0 || bitmap.height <= 0) return@Canvas
        val fit = min(size.width / bitmap.width.toFloat(),
            size.height / bitmap.height.toFloat())
        val width = bitmap.width * fit
        val height = bitmap.height * fit
        val left = (size.width - width) / 2f
        val top = (size.height - height) / 2f
        // Near-opaque beige is distinguishable from a white Mushaf page.
        // The same hue is more translucent on dark pages to preserve contrast.
        val beige = Color(0xFFF8F3EA).copy(alpha = if (darkPage) .34f else .94f)
        strokes.forEach { mark ->
            if (mark.tool != "smart_marker" || mark.points.size < 2) return@forEach
            val a = mark.points.first()
            val b = mark.points.last()
            val x0 = left + minOf(a.x, b.x) * width
            val x1 = left + maxOf(a.x, b.x) * width
            val bandHeight = (mark.width * width).coerceAtLeast(2f)
            val y0 = top + a.y * height - bandHeight / 2f
            val segmentWidth = x1 - x0
            if (segmentWidth <= 0f) return@forEach
            val radius = min(bandHeight / 2f, segmentWidth / 2f)
            drawRoundRect(
                color = beige,
                topLeft = Offset(x0, y0),
                size = Size(segmentWidth, bandHeight),
                cornerRadius = CornerRadius(radius)
            )
        }
    }
}
