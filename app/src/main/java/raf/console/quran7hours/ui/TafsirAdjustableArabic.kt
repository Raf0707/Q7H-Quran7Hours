package raf.console.quran7hours.ui

import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import raf.console.quran7hours.data.TafsirHafsTypeface
import java.util.Locale
import java.io.File
import java.security.MessageDigest
import androidx.compose.ui.graphics.luminance
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * A sized variant of the existing OriginalQpcTafsirText.
 * This is deliberately separate: other QPC consumers (especially Hafiz Diary)
 * keep their existing renderer and the existing 1:6 / 1:7 Hanafi mapping unchanged.
 * Colors remain embedded in the original fonts; we do NOT repaint Tajweed rules.
 */
private class AdjustableQpcTypefaceSpan(private val font: Typeface) : MetricAffectingSpan() {
    override fun updateMeasureState(p: TextPaint) { p.typeface = font }
    override fun updateDrawState(p: TextPaint) { p.typeface = font }
}

private class AdjustableQpcLineBox(
    private val ascentPx: Int,
    private val descentPx: Int
) : LineHeightSpan {
    override fun chooseHeight(
        text: CharSequence, start: Int, end: Int, spanstartv: Int, v: Int,
        fm: Paint.FontMetricsInt
    ) {
        fm.ascent = -ascentPx
        fm.top = -ascentPx
        fm.descent = descentPx
        fm.bottom = descentPx
    }
}

/**
 * QCF V4 is an OpenType COLR/CPAL font with SIX palettes.
 * Palette 0 is for light pages. Palette 1 is ALREADY the official dark-page
 * palette, including WHITE letters, contrast-safe verse-number ornament
 * outlines and the original night Tajweed colors.
 *
 * Android TextView/Typeface.Builder does not expose a public CPAL palette
 * selector. Reorder ONLY the palette indices in an in-memory font copy so
 * Skia's default palette (index 0) resolves to the original palette 1.
 * Neither glyph geometry nor the individual Tajweed colors are synthesized.
 */
private object Q7HOriginalQpcNightPalette {
    private fun read16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 255) shl 8) or (data[offset + 1].toInt() and 255)

    private fun read32(data: ByteArray, offset: Int): Long =
        ((read16(data, offset).toLong() shl 16) or read16(data, offset + 2).toLong())

    private fun write16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value ushr 8).toByte()
        data[offset + 1] = value.toByte()
    }

    private fun write32(data: ByteArray, offset: Int, value: Long) {
        data[offset] = (value ushr 24).toByte()
        data[offset + 1] = (value ushr 16).toByte()
        data[offset + 2] = (value ushr 8).toByte()
        data[offset + 3] = value.toByte()
    }

    private fun inBounds(data: ByteArray, offset: Long, length: Long): Boolean =
        offset >= 0 && length >= 0 && offset <= data.size.toLong() &&
            length <= data.size.toLong() - offset

    private fun sumWords(data: ByteArray, start: Int, length: Int): Long {
        var sum = 0L
        val end = start + length
        var i = start
        while (i < end) {
            var word = 0L
            repeat(4) { b ->
                val j = i + b
                word = (word shl 8) or
                    (if (j < end) data[j].toLong() and 0xFFL else 0L)
            }
            sum = (sum + word) and 0xFFFFFFFFL
            i += 4
        }
        return sum
    }

    fun createDarkFontBytes(data: ByteArray): ByteArray? {
        if (data.size < 40 || read32(data, 0) != 0x00010000L) return null
        val tableCount = read16(data, 4)
        if (tableCount !in 1..128 || !inBounds(data, 12, tableCount.toLong() * 16)) return null
        var cpalRecord = -1
        var headRecord = -1
        for (n in 0 until tableCount) {
            val record = 12 + n * 16
            when (String(data, record, 4, Charsets.US_ASCII)) {
                "CPAL" -> cpalRecord = record
                "head" -> headRecord = record
            }
        }
        if (cpalRecord == -1 || headRecord == -1) return null
        val cpalOffset = read32(data, cpalRecord + 8)
        val cpalLength = read32(data, cpalRecord + 12)
        val headOffset = read32(data, headRecord + 8)
        if (!inBounds(data, cpalOffset, cpalLength) || cpalLength < 16 ||
            !inBounds(data, headOffset, 12)) return null
        val cp = cpalOffset.toInt()
        val paletteCount = read16(data, cp + 4)
        val entriesPerPalette = read16(data, cp + 2)
        val colorCount = read16(data, cp + 6)
        if (read16(data, cp) !in 0..1 || paletteCount < 2 || entriesPerPalette == 0 ||
            cpalLength < 12 + paletteCount.toLong() * 2) return null
        val indicesAt = cp + 12
        val firstIndex = read16(data, indicesAt)
        val nightIndex = read16(data, indicesAt + 2)
        val colorsAt = read32(data, cp + 8)
        if (firstIndex + entriesPerPalette > colorCount ||
            nightIndex + entriesPerPalette > colorCount ||
            colorsAt + colorCount.toLong() * 4 > cpalLength) return null
        // The Quran's original night palette has white foreground at slot 0.
        // Avoid changing an unexpected or already-night font pack.
        val lightBase = cp + colorsAt.toInt() + firstIndex * 4
        val nightBase = cp + colorsAt.toInt() + nightIndex * 4
        val lightBlack = (0..2).all { (data[lightBase + it].toInt() and 255) == 0 }
        val nightWhite = (0..2).all { (data[nightBase + it].toInt() and 255) == 255 }
        if (!lightBlack || !nightWhite) return null

        val modified = data.copyOf()
        write16(modified, indicesAt, nightIndex)
        write16(modified, indicesAt + 2, firstIndex)
        // Restore OpenType checksums after the two CPAL index swaps.
        write32(modified, cpalRecord + 4,
            sumWords(modified, cp, cpalLength.toInt()))
        val checksumAdjustmentAt = headOffset.toInt() + 8
        write32(modified, checksumAdjustmentAt, 0L)
        write32(modified, checksumAdjustmentAt,
            (0xB1B0AFBAL - sumWords(modified, 0, modified.size)) and 0xFFFFFFFFL)
        // Android Typeface.Builder has no public ByteBuffer constructor.
        // Preserve the original night palette bytes; their loading is handled
        // by a small private disk cache below.
        return modified
    }
}

/** Loads a recolored copy via the supported Typeface.Builder(File) API (26+). */
private object Q7HNightQpcFontCache {
    private const val MAX_FILES = 36

    @Synchronized
    fun load(context: Context, page: Int, bytes: ByteArray): Typeface {
        val dir = File(context.cacheDir, "q7h_night_qpc").apply {
            if (!isDirectory && !mkdirs()) error("Cannot create QPC font cache")
        }
        // Hash the actual font bytes so an app update never reuses a stale file.
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .take(8)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val destination = File(dir, "p${page}_$hash.ttf")
        if (!destination.isFile || destination.length() != bytes.size.toLong()) {
            val temporary = File.createTempFile("qpc_${page}_", ".tmp", dir)
            try {
                temporary.writeBytes(bytes)
                if (!temporary.renameTo(destination)) {
                    temporary.copyTo(destination, overwrite = true)
                }
            } finally {
                temporary.delete()
            }
        }
        val font = Typeface.Builder(destination).build()
            ?: error("Android could not load QPC font for page $page")
        destination.setLastModified(System.currentTimeMillis())
        // Avoid retaining hundreds of temporary TTF copies while browsing.
        dir.listFiles()
            ?.filter { it.isFile && it.extension == "ttf" }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_FILES)
            ?.forEach { it.delete() }
        return font
    }
}


/**
 * Adds real measured horizontal space around a QPC rubʿ al-hizb ornament.
 * Markers are identified in OriginalQpcTafsirRepository from the source JSON
 * word metadata ("۞ ..."), so each page can use its OWN glyph code safely.
 * ReplacementSpan is used because leading RTL spaces can be swallowed by
 * Android TextView and ordinary Unicode stars don't match QPC glyph codes.
 */
private class Q7HRubStarSidePaddingSpan(
    private val sidePaddingPx: Float,
    private val qpcTypeface: Typeface
) : android.text.style.ReplacementSpan() {
    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        val glyphPaint = Paint(paint).apply { typeface = qpcTypeface }
        return ceil(glyphPaint.measureText(text, start, end) + sidePaddingPx * 2f).toInt()
    }

    override fun draw(
        canvas: android.graphics.Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        // Draw the genuine colored QPC glyph with its original Typeface/CPAL.
        val glyphPaint = Paint(paint).apply { typeface = qpcTypeface }
        canvas.drawText(text, start, end, x + sidePaddingPx, y.toFloat(), glyphPaint)
    }
}

private object AdjustableQpcFonts {
    private val fonts = object : LinkedHashMap<String, Typeface>(36, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Typeface>?): Boolean = size > 24
    }
    fun get(context: Context, page: Int, tajweed: Boolean, dark: Boolean): Typeface? {
        val path = "quran/qpc-v4/fonts/${if (tajweed) "v4" else "v2"}/p$page.ttf"
        val key = "$path:${if (dark) "night" else "day"}"
        synchronized(fonts) { fonts[key]?.let { return it } }
        val app = context.applicationContext
        val font = runCatching {
            if (dark && tajweed) {
                val bytes = app.assets.open(path).use { it.readBytes() }
                val darkBytes = Q7HOriginalQpcNightPalette.createDarkFontBytes(bytes)
                val nightTypeface = darkBytes?.let {
                    runCatching { Q7HNightQpcFontCache.load(app, page, it) }
                        .onFailure { error ->
                            Log.e("Q7HTafsirDisplay", "Cannot load dark QPC font $path", error)
                        }
                        .getOrNull()
                }
                nightTypeface ?: run {
                    Log.e("Q7HTafsirDisplay", "Dark QCF V4 palette unavailable for $path; using readable V2")
                    Typeface.createFromAsset(app.assets, "quran/qpc-v4/fonts/v2/p$page.ttf")
                }
            } else {
                Typeface.createFromAsset(app.assets, path)
            }
        }.onFailure { Log.e("Q7HTafsirDisplay", "Cannot load $key", it) }
            .getOrNull() ?: return null
        synchronized(fonts) { fonts[key] = font }
        return font
    }
}

/**
 * QCF fonts use Arabic Presentation Form codepoints as *page-specific word glyphs*.
 * Some Android text-layout paths pick a Unicode fallback when the Typeface exists
 * only as a MetricAffectingSpan. In that case U+FCCF is interpreted as a generic
 * Arabic ligature rather than as the page-120 glyph قُلْ.
 *
 * Set the physical page font as the BASE Typeface of the TextView. Most ayahs
 * belong to one physical QCF page and need no font spans at all. For the few
 * crossing a QCF page boundary, spans override the base face on other runs.
 * This is deliberately separate from Hafiz Diary, and doesn't change verse data
 * or the V2/V4 color palette.
 */
@Composable
internal fun Q7HAdjustableOriginalQpcText(
    ayah: OriginalQpcAyah,
    tajweed: Boolean,
    fontSizeSp: Float
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.45f
    val textColor = if (dark) android.graphics.Color.WHITE
        else MaterialTheme.colorScheme.onSurface.toArgb()
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { context ->
            TextView(context).apply {
                gravity = Gravity.RIGHT
                textDirection = View.TEXT_DIRECTION_RTL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                textAlignment = View.TEXT_ALIGNMENT_GRAVITY
                textLocale = Locale("ar")
                includeFontPadding = false
                setLineSpacing(0f, 1f)
                breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
                hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
                setTextIsSelectable(false)
                setPadding(0, 0, 0, 0)
            }
        },
        update = { view ->
            view.setTextColor(textColor)
            // Theme is part of the signature: rebuild when switching dark/light.
            val signature = listOf(ayah, tajweed, fontSizeSp, dark)
            if (view.tag != signature) {
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp)
                val parts = ayah.parts.filter { it.glyphs.isNotBlank() }
                val fonts = parts.map { part ->
                    AdjustableQpcFonts.get(view.context, part.page, tajweed, dark)
                }
                if (parts.isEmpty() || fonts.any { it == null }) {
                    // Never print QCF presentation-form codes in a system font.
                    Log.e("Q7HTafsirDisplay", "Missing QCF font for pages ${parts.map { it.page }}")
                    view.text = ""
                } else {
                    // CRITICAL: use the actual page Typeface as the TextView base.
                    // Span-only assignment can trigger a fallback for Arabic
                    // Presentation Forms even when the font has that glyph.
                    view.typeface = fonts.first()!!
                    val builder = SpannableStringBuilder()
                    val probe = TextPaint(view.paint)
                    val bounds = Rect()
                    var inkTop = 0
                    var inkBottom = 0
                    parts.forEachIndexed { index, part ->
                        if (index > 0) builder.append(' ')
                        val firstChar = builder.length
                        // The source QPC glyph stream is never modified.
                        builder.append(part.glyphs)
                        val font = fonts[index]!!
                        // Offset metadata comes from the readable "۞" in the
                        // matching page layout JSON, not from the ambiguous PUA.
                        // Works on ALL QPC pages, not only 36:28 or 40:41.
                        for (offset in part.rubStarOffsets.distinct()) {
                            if (offset !in part.glyphs.indices ||
                                part.glyphs[offset].isWhitespace()) continue
                            val glyphAt = firstChar + offset
                            builder.setSpan(
                                Q7HRubStarSidePaddingSpan(
                                    8f * view.resources.displayMetrics.density, font
                                ),
                                glyphAt, glyphAt + 1,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                            Log.d("Q7HTafsirDisplay", "QPC rub star padded on font page ${part.page}, offset=$offset")
                        }
                        // The first face is already assigned to the WHOLE TextView.
                        // Apply per-run overrides only when another page font is needed.
                        if (index > 0 && font !== fonts.first()) {
                            builder.setSpan(
                                AdjustableQpcTypefaceSpan(font),
                                firstChar, builder.length,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                        }
                        probe.typeface = font
                        probe.getTextBounds(part.glyphs, 0, part.glyphs.length, bounds)
                        inkTop = min(inkTop, bounds.top)
                        inkBottom = max(inkBottom, bounds.bottom)
                    }
                    // Keep the existing compact line-height policy and typography.
                    val safety = ceil(view.textSize * 0.13).toInt()
                    val above = max(ceil(view.textSize * 0.78).toInt(), -inkTop + safety)
                    val below = max(ceil(view.textSize * 0.25).toInt(), inkBottom + safety)
                    if (builder.isNotEmpty()) {
                        builder.setSpan(
                            AdjustableQpcLineBox(above, below), 0, builder.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                    view.text = builder
                    Log.d(
                        "Q7HTafsirDisplay",
                        "QCF ${if (tajweed) "V4" else "V2"}, pages=${parts.map { it.page }}, " +
                            "base Typeface=${view.typeface.toString()}, chars=${builder.length}"
                    )
                }
                view.tag = signature
            }
        }
    )
}

/** Unicode-only safety fallback, using exactly the application's existing Hafs Typeface. */
@Composable
internal fun Q7HAdjustableUnicodeArabicText(
    arabicFromAzan: String,
    verifiedArabic: String?,
    fontSizeSp: Float
) {
    val context = LocalContext.current
    val typeface by produceState<Typeface?>(null, context.applicationContext) {
        value = TafsirHafsTypeface.get(context.applicationContext)
    }
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.45f
    val textColor = if (dark) android.graphics.Color.WHITE
        else MaterialTheme.colorScheme.onSurface.toArgb()
    val arabic = verifiedArabic?.takeIf { it.isNotBlank() } ?: arabicFromAzan
    AndroidView(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        factory = { ctx ->
            TextView(ctx).apply {
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                textDirection = View.TEXT_DIRECTION_RTL
                gravity = Gravity.RIGHT
                textLocale = Locale("ar")
                setLineSpacing(8f * resources.displayMetrics.density, 1.23f)
                includeFontPadding = true
                breakStrategy = android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY
                hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
        update = { view ->
            view.typeface = typeface ?: Typeface.create("serif", Typeface.NORMAL)
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp)
            view.setTextColor(textColor)
            if (view.text.toString() != arabic) view.text = arabic
        }
    )
}
