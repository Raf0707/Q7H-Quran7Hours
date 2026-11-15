package raf.console.quran7hours.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import java.util.WeakHashMap

/** Stable, independent keys: changing one slider never changes another. */
internal object Q7HSliderKeys {
    const val NAV_SURAH = "nav_surah"
    const val NAV_AYAH = "nav_ayah"
    const val NAV_PAGE = "nav_page"
    const val HADR_MAIN = "hadr_main"
    const val HADR_RANGE = "hadr_range"
    const val AYAH_MAIN = "ayah_main"
    const val AYAH_MINI = "ayah_mini"
    const val AYAH_RANGE = "ayah_range"
    const val TAFSIR_ARABIC = "tafsir_arabic"
    const val TAFSIR_DICTIONARY = "tafsir_dictionary"
    const val TAFSIR_TRANSLATION = "tafsir_translation"
    const val TAFSIR_TAFSIR = "tafsir_tafsir"
    const val HAFIZ_ARABIC = "hafiz_arabic"
    const val HAFIZ_DICTIONARY = "hafiz_dictionary"
    const val HAFIZ_TRANSLATION = "hafiz_translation"
    const val HAFIZ_TAFSIR = "hafiz_tafsir"
}

private val allDirections = listOf(
    Triple(Q7HSliderKeys.NAV_SURAH, "Навигация · сура", true),
    Triple(Q7HSliderKeys.NAV_AYAH, "Навигация · аят", true),
    Triple(Q7HSliderKeys.NAV_PAGE, "Навигация · страница", true),
    Triple(Q7HSliderKeys.HADR_MAIN, "Коран за 7 часов · время", false),
    Triple(Q7HSliderKeys.HADR_RANGE, "Коран за 7 часов · отрезок", false),
    Triple(Q7HSliderKeys.AYAH_MAIN, "Аудио по аятам · время", false),
    Triple(Q7HSliderKeys.AYAH_MINI, "Тафсир и дневник · нижний мини-плеер", false),
    Triple(Q7HSliderKeys.AYAH_RANGE, "Аудио по аятам · отрезок", false),
    Triple(Q7HSliderKeys.TAFSIR_ARABIC, "Тафсир · арабский шрифт", false),
    Triple(Q7HSliderKeys.TAFSIR_DICTIONARY, "Тафсир · шрифт словаря", false),
    Triple(Q7HSliderKeys.TAFSIR_TRANSLATION, "Тафсир · шрифт перевода", false),
    Triple(Q7HSliderKeys.TAFSIR_TAFSIR, "Тафсир · шрифт толкования", false),
    Triple(Q7HSliderKeys.HAFIZ_ARABIC, "Дневник · арабский шрифт", false),
    Triple(Q7HSliderKeys.HAFIZ_DICTIONARY, "Дневник · шрифт словаря", false),
    Triple(Q7HSliderKeys.HAFIZ_TRANSLATION, "Дневник · шрифт перевода", false),
    Triple(Q7HSliderKeys.HAFIZ_TAFSIR, "Дневник · шрифт толкования", false)
)

internal class Q7HSliderDirectionPreferences(context: Context) {
    private val store = context.applicationContext.getSharedPreferences("q7h_individual_slider_rtl", Context.MODE_PRIVATE)
    private val defaults = allDirections.associate { it.first to it.third }
    var values by mutableStateOf(defaults.mapValues { (key, fallback) ->
        store.getBoolean(key, fallback)
    })
        private set

    fun rtl(key: String): Boolean = values[key] ?: false

    fun update(key: String, rtl: Boolean) {
        if (key !in defaults) return
        values = values + (key to rtl)
        store.edit().putBoolean(key, rtl).apply()
    }
}

internal object Q7HSliderDirections {
    private val cache = WeakHashMap<Context, Q7HSliderDirectionPreferences>()
    fun get(context: Context): Q7HSliderDirectionPreferences = synchronized(cache) {
        val app = context.applicationContext
        cache.getOrPut(app) { Q7HSliderDirectionPreferences(app) }
    }
}

@Composable
internal fun Q7HSliderRtlSettingsSection() {
    val context = LocalContext.current
    val prefs = androidx.compose.runtime.remember(context.applicationContext) {
        Q7HSliderDirections.get(context.applicationContext)
    }
    Column {
        Text("Направление слайдеров", style = MaterialTheme.typography.titleLarge)
        Text("Каждый Switch RTL действует отдельно. В RTL также переставляются стрелки и подписи времени.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        allDirections.forEachIndexed { index, (key, title, _) ->
            if (index == 3 || index == 8) HorizontalDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = prefs.rtl(key), onCheckedChange = { prefs.update(key, it) })
            }
        }
    }
}
