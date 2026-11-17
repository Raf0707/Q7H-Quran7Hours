package raf.console.quran7hours.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import raf.console.quran7hours.data.UserPreferences
import kotlin.math.roundToInt

/** Shared by the drawing palette and application settings. Persisted in q7h_compose backup. */
@Composable
internal fun Q7HSmartMarkerSettings(prefs: UserPreferences) {
    // Observe updates from either entry point without restarting the app.
    val revision = prefs.inkSmartSettingsRevision
    var radius by remember(revision) { mutableFloatStateOf(prefs.inkSmartRadius) }
    var width by remember(revision) { mutableFloatStateOf(prefs.inkSmartBorderWidth) }
    var border by remember(revision) { mutableStateOf(prefs.inkSmartBorderEnabled) }
    val options = listOf("single" to "Одно", "double" to "Двойное", "long" to "Удержание",
        "timed" to "Удержание с таймером", "off" to "Выкл.")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Форма умного выделителя", style = MaterialTheme.typography.titleMedium)
        Text("Радиус скругления · ${(radius*100).roundToInt()}%",
            style = MaterialTheme.typography.labelMedium)
        Slider(value=radius, onValueChange={ radius=it; prefs.inkSmartRadius=it },valueRange=0f..1f)
        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
            Text("Прямоугольник", style=MaterialTheme.typography.labelSmall)
            Text("Овальный маркер",style=MaterialTheme.typography.labelSmall)
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("Цветная рамка", modifier=Modifier.weight(1f))
            Switch(checked=border, onCheckedChange={ border=it; prefs.inkSmartBorderEnabled=it })
        }
        if(border) {
            Text("Толщина рамки · ${"%.1f".format(width)} dp", style=MaterialTheme.typography.labelMedium)
            Slider(value=width,onValueChange={width=it;prefs.inkSmartBorderWidth=it},valueRange=.4f..6f)
        }
        HorizontalDivider()
        Text("Жесты умного выделителя",style=MaterialTheme.typography.titleMedium)
        Text("Выделение аята",style=MaterialTheme.typography.labelMedium)
        Q7HSmartGesturePicker(prefs.inkSmartMarkGesture, options) { value ->
            prefs.inkSmartMarkGesture=value
            if(value != "off" && value==prefs.inkSmartClearGesture)
                prefs.inkSmartClearGesture="off"
        }
        Text("Удаление выделения аята",style=MaterialTheme.typography.labelMedium)
        Q7HSmartGesturePicker(prefs.inkSmartClearGesture, options) { value ->
            prefs.inkSmartClearGesture=value
            if(value != "off" && value==prefs.inkSmartMarkGesture)
                prefs.inkSmartMarkGesture="off"
        }
        val seconds = prefs.inkSmartHoldSeconds
        Text("Таймер после обычного удержания · $seconds сек",style=MaterialTheme.typography.labelMedium)
        Slider(value=seconds.toFloat(),onValueChange={prefs.inkSmartHoldSeconds=it.roundToInt()},
            valueRange=1f..10f,steps=8)
        Text("Индикатор обратного отсчёта",style=MaterialTheme.typography.labelMedium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            listOf("left" to "Слева", "right" to "Справа", "top" to "Сверху").forEach { (id,label) ->
                FilterChip(selected=prefs.inkSmartCountdownSide==id,
                    onClick={prefs.inkSmartCountdownSide=id},label={Text(label)})
            }
        }
        Text("Индикатор смещается к свободной стороне, если касание близко к краю. " +
            "Отпустите палец до окончания отсчёта, чтобы отменить действие.",
            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Q7HSmartGesturePicker(value:String, options:List<Pair<String,String>>, onValue:(String)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick={expanded=true}) {
            Text(options.firstOrNull { it.first==value }?.second ?: "Выбрать")
        }
        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
            options.forEach { (key,label) ->
                DropdownMenuItem(text={Text(label)},onClick={onValue(key);expanded=false})
            }
        }
    }
}
