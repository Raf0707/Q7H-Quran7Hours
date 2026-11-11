package raf.console.quran7hours.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import raf.console.quran7hours.R
import raf.console.quran7hours.data.TafsirQuranScript

private val QuranTypeface = FontFamily(Font(R.font.tafsir_quran))

@Composable
fun TafsirQuranText(arabicFromAzan: String, verified: TafsirQuranScript.Ayah?, tajweed: Boolean) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.4f
    // Only render the alternate text and colors if it matches the Azan ayah.
    val text = verified?.arabic ?: arabicFromAzan
    val content = remember(text, verified, tajweed, dark) {
        if (tajweed && verified != null) colorize(verified, dark) else AnnotatedString(text)
    }
    Text(
        text = content,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        fontSize = 29.sp,
        lineHeight = 53.sp,
        textAlign = TextAlign.Right,
        color = MaterialTheme.colorScheme.onSurface,
        style = TextStyle(fontFamily = QuranTypeface, textDirection = TextDirection.Rtl)
    )
}

private fun colorize(ayah: TafsirQuranScript.Ayah, dark: Boolean): AnnotatedString =
    buildAnnotatedString {
        append(ayah.arabic)
        ayah.marks.forEach { m ->
            if (m.start >= 0 && m.end <= ayah.arabic.length && m.start < m.end) {
                tajweedColor(m.rule, dark)?.let { color ->
                    addStyle(SpanStyle(color = color), m.start, m.end)
                }
            }
        }
    }

private fun tajweedColor(rule: String, dark: Boolean): Color? {
    // From MIT-licensed Quran-Tajweed-Engine catalogue. Dark variant boosts contrast.
    val rgb = when (rule) {
        "lamShamsiyah", "droppedLetter", "hamzatWaslSilent", "idghamBilaGhunnah" ->
            if (dark) Color(0xFFAAAAAA) else Color(0xFF777777)
        "idghamGhunnah", "generalGhunnah", "ikhfaaLight" ->
            if (dark) Color(0xFF68DF92) else Color(0xFF229C50)
        "ikhfaaHeavy" -> if (dark) Color(0xFF64E4D0) else Color(0xFF168E7D)
        "iqlaab" -> if (dark) Color(0xFFB9E66E) else Color(0xFF548E21)
        "qalqalah" -> if (dark) Color(0xFF7FDBFF) else Color(0xFF1685C6)
        "tafkhim" -> if (dark) Color(0xFF83B8FF) else Color(0xFF2D64AF)
        "maddNatural", "maddNaturalMiniature" -> if (dark) Color(0xFFFFC266) else Color(0xFFAA7117)
        // Stop-based maddSukoon is context-dependent; not painted by default.
        "maddSukoon" -> return null
        "maddSeparated" -> if (dark) Color(0xFFFF94DA) else Color(0xFFBC3585)
        "maddConnected" -> if (dark) Color(0xFFFF8D83) else Color(0xFFD23C3C)
        "maddNecessary" -> if (dark) Color(0xFFFF7773) else Color(0xFFB52F2E)
        else -> return null
    }
    return rgb
}
