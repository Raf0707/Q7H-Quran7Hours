package raf.console.quran7hours.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** Every interactive Material3 component inherits one complete scheme. */
object AccentPalettes {
    val all = listOf(
        Color(0xFF006E70), Color(0xFF385F9B), Color(0xFF8065B3), Color(0xFF9B5B73),
        Color(0xFF476D4B), Color(0xFFB26B40), Color(0xFF4A7082), Color(0xFF80654D)
    )
}

@Composable
fun Q7HTheme(mode: String, palette: Int, contrast: String, readingBackground: Color? = null, content: @Composable () -> Unit) {
    val dark = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val high = contrast == "high"
    val medium = contrast == "medium"
    val accent = AccentPalettes.all[palette.coerceIn(0, AccentPalettes.all.lastIndex)]
    val primary = when {
        dark && high -> lerp(accent, Color.White, 0.67f)
        dark && medium -> lerp(accent, Color.White, 0.48f)
        dark -> lerp(accent, Color.White, 0.38f)
        high -> lerp(accent, Color.Black, 0.24f)
        else -> accent
    }
    // Reader background comes from the edge pixels of the user's actual image.
    // Do not use a fixed blue-grey theme behind a pure-black dark mushaf.
    val bg = if (dark) (readingBackground ?: Color.Black) else Color.White
    val surface = if (dark) (if (high) bg else lerp(bg, Color.White, 0.055f)) else Color.White
    val ink = if (dark) Color.White else Color(0xFF172024)
    val secondary = if (dark) lerp(primary, Color.White, 0.20f) else lerp(primary, Color(0xFF666666), 0.25f)
    val container = if (dark) lerp(primary, bg, if (high) 0.68f else 0.76f) else lerp(primary, Color.White, if (high) 0.78f else 0.88f)
    val onContainer = if (dark) Color.White else lerp(primary, Color.Black, 0.35f)
    val scheme = if (dark) darkColorScheme(
        primary = primary, onPrimary = Color.Black, primaryContainer = container, onPrimaryContainer = onContainer,
        secondary = secondary, onSecondary = Color.Black,
        secondaryContainer = container, onSecondaryContainer = onContainer,
        tertiary = secondary, onTertiary = Color.Black,
        tertiaryContainer = container, onTertiaryContainer = onContainer,
        background = bg, onBackground = ink, surface = surface, onSurface = ink,
        surfaceVariant = lerp(surface, primary, if (high) 0.13f else 0.06f), onSurfaceVariant = if (high) Color.White else Color(0xFFBFCBCD),
        outline = if (high) Color.White else Color(0xFF829499), outlineVariant = lerp(surface, ink, 0.20f),
        inverseSurface = Color.White, inverseOnSurface = Color.Black, surfaceTint = primary
    ) else lightColorScheme(
        primary = primary, onPrimary = Color.White, primaryContainer = container, onPrimaryContainer = onContainer,
        secondary = secondary, onSecondary = Color.White,
        secondaryContainer = container, onSecondaryContainer = onContainer,
        tertiary = secondary, onTertiary = Color.White,
        tertiaryContainer = container, onTertiaryContainer = onContainer,
        background = bg, onBackground = ink, surface = surface, onSurface = ink,
        surfaceVariant = lerp(surface, primary, if (high) 0.11f else 0.045f), onSurfaceVariant = if (high) Color.Black else Color(0xFF47585C),
        outline = if (high) Color.Black else Color(0xFF829499), outlineVariant = lerp(surface, ink, 0.18f),
        inverseSurface = Color.Black, inverseOnSurface = Color.White, surfaceTint = primary
    )
    MaterialTheme(colorScheme = scheme, content = content)
}
