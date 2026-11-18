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
        Text("Выделение и воспроизведение аятов", style = MaterialTheme.typography.titleMedium)
        Text("Однократное касание всегда показывает или скрывает основную панель Мусхафа. " +
            "Для двух остальных действий выберите одно из двух расположений.",
            style = MaterialTheme.typography.bodySmall)
        val swapped=prefs.readerSwapAyahGestures
        Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
            FilterChip(selected=!swapped,
                onClick={prefs.readerSwapAyahGestures=false},
                label={Text("Двойное — выделить / снять · Долгое — воспроизвести")})
            FilterChip(selected=swapped,
                onClick={prefs.readerSwapAyahGestures=true},
                label={Text("Двойное — воспроизвести · Долгое — выделить / снять")})
        }
        Text("Повторный жест по уже выделенному аяту снимает только его умное выделение. " +
            "Плеер Мусхафа использует тех же чтецов и режимы, что и Тафсир.",
            style=MaterialTheme.typography.bodySmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Text("Дополнительные жесты с таймером",style=MaterialTheme.typography.titleMedium)
        Text("Необязательное действие после продолжительного удержания пальца. " +
            "Отключено по умолчанию. Основные двойное и долгое нажатия не меняет.",
            style=MaterialTheme.typography.bodySmall)
        val timed=prefs.readerTimedGestureAction
        val choices=listOf(
            "off" to "Выключено", "eraser" to "Ластик", "drawing" to "Графическая панель",
            "controls" to "Основные кнопки", "navigation" to "Сменить навигацию"
        )
        Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
            choices.chunked(2).forEach { group ->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    group.forEach { (key,label) ->
                        FilterChip(selected=timed==key,onClick={prefs.readerTimedGestureAction=key},
                            label={Text(label,maxLines=2)},modifier=Modifier.weight(1f))
                    }
                    if(group.size==1) Spacer(Modifier.weight(1f))
                }
            }
        }
        if(timed!="off") {
            val seconds=prefs.readerTimedGestureMs/1000f
            Text("Дополнительное удержание: ${"%.1f".format(seconds)} с",
                style=MaterialTheme.typography.labelMedium)
            Slider(value=seconds.coerceIn(1.5f,10f),
                onValueChange={prefs.readerTimedGestureMs=(it*1000f).toLong()},
                valueRange=1.5f..10f,steps=16)
            Text("Отсчёт начинается после системного долгого нажатия. " +
                "Для режима смены навигации переключаются обычная и нижняя панели.",
                style=MaterialTheme.typography.bodySmall)
            Text("Положение индикатора",style=MaterialTheme.typography.labelMedium)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf("left" to "Слева", "right" to "Справа", "top" to "Сверху")
                    .forEach { (id,label) ->
                        FilterChip(selected=prefs.inkSmartCountdownSide==id,
                            onClick={prefs.inkSmartCountdownSide=id},label={Text(label)})
                    }
            }
        }

    }
}

