package raf.console.quran7hours.ui

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Stage 10: pure coordinate mapping and safe hit testing. No bitmap analysis,
 * no annotation data and no dependency on Compose/Android.
 *
 * Q7M1 coordinates refer to the *uncropped* Classic Light WebP (960 x 2133).
 * Stage 11 adds calibrated per-variant/page offsets after exact-source verification.
 * The reader displays a trimmed Bitmap in ContentScale.Fit; InkPageLayer expects
 * 0..1 coordinates relative to that trimmed bitmap, not to the screen.
 */
internal object Q7HAyahGeometryMapper {
    internal data class Frame(
        val sourceWidth: Int, val sourceHeight: Int,
        val decodedWidth: Int, val decodedHeight: Int,
        val cropLeft: Int, val cropTop: Int,
        val visibleWidth: Int, val visibleHeight: Int,
        // Per-variant/per-page translation relative to original Classic Light.
        val offsetX: Float = 0f, val offsetY: Float = 0f,
    ) {
        fun validFor(model: Q7HCompactModelV1): Boolean =
            sourceWidth == model.width && sourceHeight == model.height &&
                sourceWidth > 0 && sourceHeight > 0 &&
                decodedWidth > 0 && decodedHeight > 0 &&
                visibleWidth > 0 && visibleHeight > 0 &&
                cropLeft >= 0 && cropTop >= 0 &&
                cropLeft + visibleWidth <= decodedWidth &&
                cropTop + visibleHeight <= decodedHeight &&
                offsetX.isFinite() && offsetY.isFinite() &&
                abs(offsetX) <= 24f && abs(offsetY) <= 24f
    }

    internal data class Rect(
        val left: Float, val top: Float, val right: Float, val bottom: Float,
    ) {
        val centerY: Float get() = (top + bottom) / 2f
        /** InkPageLayer uses the page's displayed width as the unit for stroke width. */
        fun strokeWidth(frame: Frame): Float =
            (bottom - top) * frame.visibleHeight / frame.visibleWidth
    }

    private fun xToNative(x: Float, frame: Frame): Float =
        (x * frame.visibleWidth + frame.cropLeft) * frame.sourceWidth / frame.decodedWidth - frame.offsetX
    private fun yToNative(y: Float, frame: Frame): Float =
        (y * frame.visibleHeight + frame.cropTop) * frame.sourceHeight / frame.decodedHeight - frame.offsetY

    private fun inSeal(x: Float, y: Float, seal: Q7HCompactModelV1.Seal): Boolean =
        abs(x - seal.centerX) <= seal.protectedHalfWidth + 3f &&
            abs(y - seal.centerY) <= seal.protectedHalfWidth + 8f

    /**
     * Returns null for blank spaces, surah cards, Basmala cards, verse seals
     * and ambiguous overlapping boxes. A null hit is NOT a reason to select
     * another ayah with a fallback OCR heuristic on a verified model page.
     */
    fun hitTest(
        model: Q7HCompactModelV1, page: Q7HCompactModelV1.Page,
        frame: Frame, normalizedX: Float, normalizedY: Float,
    ): Q7HCompactModelV1.Verse? {
        if (!frame.validFor(model) || !normalizedX.isFinite() || !normalizedY.isFinite() ||
            normalizedX !in 0f..1f || normalizedY !in 0f..1f) return null
        val x = xToNative(normalizedX, frame)
        val y = yToNative(normalizedY, frame)
        // Reject ornaments even when line padding puts them inside verse boxes.
        if (page.verses.any { inSeal(x, y, it.seal) }) return null
        var hit: Q7HCompactModelV1.Verse? = null
        for (verse in page.verses) {
            if (verse.segments.any { s ->
                val b = s.bounds
                x >= b.left && x <= b.right && y >= b.top && y <= b.bottom
            }) {
                if (hit != null && hit !== verse) return null
                hit = verse
            }
        }
        return hit
    }

    /** Never draw empty, inverted, clipped-out or out-of-bounds rectangles. */
    fun rectangles(
        model: Q7HCompactModelV1, verse: Q7HCompactModelV1.Verse, frame: Frame,
    ): List<Rect> {
        if (!frame.validFor(model)) return emptyList()
        val output = ArrayList<Rect>(verse.segments.size)
        for (seg in verse.segments) {
            val b = seg.bounds
            val x0 = (b.left + frame.offsetX) * frame.decodedWidth / frame.sourceWidth - frame.cropLeft
            val x1 = (b.right + frame.offsetX) * frame.decodedWidth / frame.sourceWidth - frame.cropLeft
            val y0 = (b.top + frame.offsetY) * frame.decodedHeight / frame.sourceHeight - frame.cropTop
            val y1 = (b.bottom + frame.offsetY) * frame.decodedHeight / frame.sourceHeight - frame.cropTop
            val left = max(0f, x0)
            val right = min(frame.visibleWidth.toFloat(), x1)
            val top = max(0f, y0)
            val bottom = min(frame.visibleHeight.toFloat(), y1)
            if (right - left < 2f || bottom - top < 3f) return emptyList()
            output += Rect(left / frame.visibleWidth, top / frame.visibleHeight,
                right / frame.visibleWidth, bottom / frame.visibleHeight)
        }
        return output
    }
}
