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

private object AdjustableQpcFonts {
    private val fonts = object : LinkedHashMap<String, Typeface>(36, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Typeface>?): Boolean = size > 24
    }
    fun get(context: Context, page: Int, tajweed: Boolean): Typeface? {
        val path = "quran/qpc-v4/fonts/${if (tajweed) "v4" else "v2"}/p$page.ttf"
        val cached = synchronized(fonts) { fonts[path] }
        if (cached != null) return cached
        val font = runCatching { Typeface.createFromAsset(context.applicationContext.assets, path) }
            .onFailure { Log.e("Q7HTafsirDisplay", "Cannot load $path", it) }
            .getOrNull() ?: return null
        synchronized(fonts) { fonts[path] = font }
        return font
    }
}

@Composable
internal fun Q7HAdjustableOriginalQpcText(
    ayah: OriginalQpcAyah,
    tajweed: Boolean,
    fontSizeSp: Float
) {
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { context ->
            TextView(context).apply {
                gravity = Gravity.RIGHT
                textDirection = View.TEXT_DIRECTION_RTL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                textAlignment = View.TEXT_ALIGNMENT_GRAVITY
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
            val signature = Triple(ayah, tajweed, fontSizeSp)
            if (view.tag != signature) {
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp)
                val builder = SpannableStringBuilder()
                val probe = TextPaint(view.paint)
                val bounds = Rect()
                var inkTop = 0
                var inkBottom = 0
                var missingFont = false
                ayah.parts.forEachIndexed { index, part ->
                    if (index > 0) builder.append(' ')
                    val start = builder.length
                    builder.append(part.glyphs)
                    val font = AdjustableQpcFonts.get(view.context, part.page, tajweed)
                    if (font == null) {
                        missingFont = true
                    } else {
                        builder.setSpan(
                            AdjustableQpcTypefaceSpan(font), start, builder.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        probe.typeface = font
                        probe.getTextBounds(part.glyphs, 0, part.glyphs.length, bounds)
                        inkTop = min(inkTop, bounds.top)
                        inkBottom = max(inkBottom, bounds.bottom)
                    }
                }
                if (missingFont) {
                    // Never draw QPC PUA codes in a Unicode fallback font.
                    view.text = ""
                } else {
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
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
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
