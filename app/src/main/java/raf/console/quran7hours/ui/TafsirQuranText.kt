package raf.console.quran7hours.ui

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import raf.console.quran7hours.data.TafsirHafsTypeface
import raf.console.quran7hours.data.TafsirQuranScript
import java.util.Locale

/**
 * Arabic Unicode text, always the SAME actual font in both modes.
 * Unlike QCF4_Hafs_01_W, the Unicode Hafs font contains real Arabic codepoints.
 * Accurate tajweed comes from verified UTF-16 spans, not from the font itself.
 */
@Composable
fun TafsirQuranText(
    arabicFromAzan: String,
    verified: TafsirQuranScript.Ayah?,
    tajweed: Boolean
) {
    val context = LocalContext.current
    val typeface by produceState<Typeface?>(null, context.applicationContext) {
        value = TafsirHafsTypeface.get(context.applicationContext)
    }
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.4f
    val foreground = MaterialTheme.colorScheme.onSurface.toArgb()
    val arabic = verified?.arabic ?: arabicFromAzan
    val content = remember(arabic, verified, tajweed, dark) {
        val s = SpannableString(arabic)
        if (tajweed && verified != null) {
            verified.marks.forEach { mark ->
                val c = colorForTajweedRule(mark.rule, dark) ?: return@forEach
                if (mark.start in 0 until mark.end && mark.end <= arabic.length) {
                    s.setSpan(
                        ForegroundColorSpan(c.toArgb()),
                        mark.start,
                        mark.end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
        }
        s
    }
    AndroidView(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        factory = { ctx ->
            TextView(ctx).apply {
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                textDirection = View.TEXT_DIRECTION_RTL
                gravity = Gravity.RIGHT
                textLocale = Locale("ar")
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 29f)
                setLineSpacing(8f * resources.displayMetrics.density, 1.23f)
                includeFontPadding = true
                breakStrategy = android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY
                hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
        update = { view ->
            view.typeface = typeface ?: Typeface.create("serif", Typeface.NORMAL)
            view.setTextColor(foreground)
            view.text = content
        }
    )
}

private fun colorForTajweedRule(rule: String, dark: Boolean): Color? = when (rule) {
    "lamShamsiyah", "droppedLetter", "hamzatWaslSilent", "idghamBilaGhunnah" ->
        if (dark) Color(0xFFAAAAAA) else Color(0xFF777777)
    "idghamGhunnah", "generalGhunnah", "ikhfaaLight" ->
        if (dark) Color(0xFF68DF92) else Color(0xFF229C50)
    "ikhfaaHeavy" -> if (dark) Color(0xFF64E4D0) else Color(0xFF168E7D)
    "iqlaab" -> if (dark) Color(0xFFB9E66E) else Color(0xFF548E21)
    "qalqalah" -> if (dark) Color(0xFF7FDBFF) else Color(0xFF1685C6)
    "tafkhim" -> if (dark) Color(0xFF83B8FF) else Color(0xFF2D64AF)
    "maddNatural", "maddNaturalMiniature" ->
        if (dark) Color(0xFFFFC266) else Color(0xFFAA7117)
    // Depends on how the reader stops; do not mark a possibly incorrect rule.
    "maddSukoon" -> null
    "maddSeparated" -> if (dark) Color(0xFFFF94DA) else Color(0xFFBC3585)
    "maddConnected" -> if (dark) Color(0xFFFF8D83) else Color(0xFFD23C3C)
    "maddNecessary" -> if (dark) Color(0xFFFF7773) else Color(0xFFB52F2E)
    else -> null
}
