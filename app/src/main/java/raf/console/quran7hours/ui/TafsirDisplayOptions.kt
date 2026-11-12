package raf.console.quran7hours.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Defaults deliberately reproduce the existing Tafsir design. */
internal data class TafsirDisplayOptions(
    val showArabic: Boolean = true,
    val showDictionary: Boolean = true,
    val showTranslation: Boolean = true,
    val showTafsir: Boolean = true,
    val arabicSp: Float = 29f,
    val dictionarySp: Float = 19f,
    val translationSp: Float = 16f,
    val tafsirSp: Float = 14f
)

internal class TafsirDisplayPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("q7h_tafsir_display", Context.MODE_PRIVATE)
    private fun read(): TafsirDisplayOptions = TafsirDisplayOptions(
        showArabic = prefs.getBoolean("arabic", true),
        showDictionary = prefs.getBoolean("dictionary", true),
        showTranslation = prefs.getBoolean("translation", true),
        showTafsir = prefs.getBoolean("tafsir", true) && prefs.getBoolean("translation", true),
        arabicSp = prefs.getFloat("arabic_sp", 29f).coerceIn(18f, 48f),
        dictionarySp = prefs.getFloat("dictionary_sp", 19f).coerceIn(12f, 32f),
        translationSp = prefs.getFloat("translation_sp", 16f).coerceIn(11f, 30f),
        tafsirSp = prefs.getFloat("tafsir_sp", 14f).coerceIn(11f, 30f)
    )
    var value by mutableStateOf(read())
        private set

    fun change(update: (TafsirDisplayOptions) -> TafsirDisplayOptions) {
        val updated = update(value)
        // A tafsir without the full translation is not a supported display mode.
        val v = if (updated.showTranslation) updated else updated.copy(showTafsir = false)
        value = v
        prefs.edit()
            .putBoolean("arabic", v.showArabic)
            .putBoolean("dictionary", v.showDictionary)
            .putBoolean("translation", v.showTranslation)
            .putBoolean("tafsir", v.showTafsir)
            .putFloat("arabic_sp", v.arabicSp)
            .putFloat("dictionary_sp", v.dictionarySp)
            .putFloat("translation_sp", v.translationSp)
            .putFloat("tafsir_sp", v.tafsirSp)
            .apply()
    }

    fun reset() {
        prefs.edit().clear().apply()
        value = TafsirDisplayOptions()
    }
}

internal object SharedTafsirDisplayOptions {
    private val instances = WeakHashMap<Context, TafsirDisplayPreferences>()
    fun get(context: Context): TafsirDisplayPreferences = synchronized(instances) {
        val app = context.applicationContext
        instances.getOrPut(app) { TafsirDisplayPreferences(app) }
    }
}

/** One settings section shared by the Tafsir gear menu and app Settings. */
@Composable
internal fun TafsirDisplaySettingsSection(state: TafsirDisplayPreferences) {
    val value = state.value
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Отображение тафсира", style = MaterialTheme.typography.titleMedium)
        TafsirDisplayCheckbox("Арабский текст", value.showArabic) { checked ->
            state.change { it.copy(showArabic = checked) }
        }
        TafsirDisplayCheckbox("Словарь аята", value.showDictionary) { checked ->
            state.change { it.copy(showDictionary = checked) }
        }
        TafsirDisplayCheckbox("Перевод", value.showTranslation) { checked ->
            state.change {
                it.copy(showTranslation = checked, showTafsir = checked && it.showTafsir)
            }
        }
        TafsirDisplayCheckbox(
            "Тафсир (только вместе с переводом)",
            value.showTafsir,
            enabled = value.showTranslation
        ) { checked ->
            state.change { it.copy(showTafsir = checked) }
        }
        if (!value.showTranslation) {
            Text("Чтобы включить тафсир, сначала включите перевод.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("Номер аята, воспроизведение и выбор чтеца видны всегда.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        fontSlider("Шрифт арабского текста", value.arabicSp, 18f..48f, 29f,
            change = { x -> state.change { it.copy(arabicSp = x) } })
        fontSlider("Шрифт словаря аята", value.dictionarySp, 12f..32f, 19f,
            change = { x -> state.change { it.copy(dictionarySp = x) } })
        fontSlider("Шрифт перевода", value.translationSp, 11f..30f, 16f,
            change = { x -> state.change { it.copy(translationSp = x) } })
        fontSlider("Шрифт тафсира", value.tafsirSp, 11f..30f, 14f,
            change = { x -> state.change { it.copy(tafsirSp = x) } })
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = { state.reset() }, modifier = Modifier.fillMaxWidth()) {
            Text("Вернуть заводские настройки тафсира")
        }
    }
}

@Composable
private fun TafsirDisplayCheckbox(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, enabled = enabled, onCheckedChange = onChange)
        Text(label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable
private fun fontSlider(
    label: String, size: Float, range: ClosedFloatingPointRange<Float>,
    factoryDefault: Float, change: (Float) -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text("${size.roundToInt()} sp", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        IconButton(onClick = { change(factoryDefault) }, modifier = Modifier.size(40.dp)) {
            Icon(androidx.compose.material.icons.Icons.Default.Refresh,
                "Вернуть $label: ${factoryDefault.roundToInt()} sp")
        }
    }
    Slider(
        value = size.coerceIn(range.start, range.endInclusive),
        onValueChange = { change(it.roundToInt().toFloat()) },
        valueRange = range,
        steps = (range.endInclusive - range.start).toInt() - 1,
        modifier = Modifier.fillMaxWidth()
    )
}
