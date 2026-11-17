package raf.console.quran7hours.ui

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import raf.console.quran7hours.data.InkPoint
import raf.console.quran7hours.data.InkStroke
import raf.console.quran7hours.data.MushafStore

/**
 * Stage 10/11: fail-safe integration layer. Q7M1 is used ONLY for a verified
 * copy of one of the exact four image packs. Stage 11 compensates a measured
 * per-page horizontal/vertical translation relative to original Classic Light.
 * Missing/modified WebPs and corrupt model/calibration blocks still use v7.
 *
 * Manual annotations are never passed here and are never sampled from a
 * screenshot. All existing strokes and notes remain owned by LearningDatabase.
 */
internal object Q7HSmartAyahV10 {
    private const val TAG = "Q7HSmartAyahV10"

    fun includes(context: Context, page: Int, ref: Q7HSmartAyahMarker.Ref): Boolean {
        if (page !in 1..604) return false
        val found = runCatching { Q7HCompactModelAssets.load(context)?.page(page)
            ?.findVerse(ref.surah, ref.ayah) != null }.getOrNull()
        return found ?: Q7HSmartAyahMarker.includes(context, page, ref)
    }

    fun select(
        context: Context,
        store: MushafStore,
        variant: MushafStore.Variant,
        bitmap: Bitmap,
        page: Int,
        x: Float, y: Float, color: Long,
        requested: Q7HSmartAyahMarker.Ref? = null,
    ): Q7HSmartAyahMarker.Selection? {
        fun v7() = Q7HSmartAyahMarker.select(context, bitmap, page, x, y, color, requested)
        if (page !in 1..604 || !x.isFinite() || !y.isFinite()) return null
        val frame = store.imageFrame(bitmap) ?: return v7()
        val shift = Q7HAyahSourceTrust.calibration(context,variant,page,frame.sourceSha256)
            ?: return v7()
        val model = Q7HCompactModelAssets.load(context) ?: return v7()
        val mapping = Q7HAyahGeometryMapper.Frame(
            frame.sourceWidth,frame.sourceHeight,frame.decodedWidth,frame.decodedHeight,
            frame.cropLeft,frame.cropTop,frame.visibleWidth,frame.visibleHeight,
            shift.dx,shift.dy
        )
        if (!mapping.validFor(model)) return v7()
        val modelPage = try { model.page(page) } catch (error: Exception) {
            Log.w(TAG, "Q7M1 page $page is unavailable; fallback to v7", error)
            return v7()
        }
        // Requested selection is used for a verse crossing into this page.
        // If it's not on this page, do not inadvertently highlight another verse.
        val verse = if (requested != null) modelPage.findVerse(requested.surah, requested.ayah)
            else Q7HAyahGeometryMapper.hitTest(model, modelPage, mapping, x, y)
        if (verse == null) return null
        val rects = Q7HAyahGeometryMapper.rectangles(model, verse, mapping)
        if (rects.isEmpty() || rects.size != verse.segments.size) return v7()
        val tag = "smart:${verse.surah}:${verse.ayah}:$page"
        val strokes = rects.map { rect ->
            InkStroke("smart_marker", color, rect.strokeWidth(mapping),
                listOf(InkPoint(rect.left, rect.centerY), InkPoint(rect.right, rect.centerY)),tag)
        }
        return Q7HSmartAyahMarker.Selection(Q7HSmartAyahMarker.Ref(verse.surah, verse.ayah), strokes)
    }
}
