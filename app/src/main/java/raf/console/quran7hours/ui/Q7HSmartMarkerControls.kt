package raf.console.quran7hours.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import raf.console.quran7hours.data.InkPaletteColor
import raf.console.quran7hours.data.LearningDatabase
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
        Text("Подсветка воспроизводимого аята", style=MaterialTheme.typography.titleMedium)
        Text("Если у аята уже есть сохранённое цветное выделение, выберите, как " +
            "оно будет выглядеть во время воспроизведения. " +
            "Изменение касается только экрана, исходный цвет останется в заметках.",
            style=MaterialTheme.typography.bodySmall)
        val playedColorMode=prefs.readerPlaybackHighlightMode
        Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
            FilterChip(selected=playedColorMode=="replace",
                onClick={prefs.readerPlaybackHighlightMode="replace"},
                label={Text("На время воспроизведения заменять сохранённый цвет")})
            FilterChip(selected=playedColorMode=="keep",
                onClick={prefs.readerPlaybackHighlightMode="keep"},
                label={Text("Оставлять сохранённый цвет без дополнительной подсветки")})
        }
        Q7HPlaybackMarkerColorPicker(prefs)
        Text("Если аят не выделен, выбранный цвет воспроизведения показывается в обоих режимах. " +
            "После остановки восстанавливается исходное оформление.",
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


/** Uses the same Room-backed Tajweed palette as the ink editor, but writes only
 * the transient playback swatch to UserPreferences. Existing ink is untouched. */
@Composable
private fun Q7HPlaybackMarkerColorPicker(prefs: UserPreferences) {
    val context = LocalContext.current
    val db = remember(context.applicationContext) {
        LearningDatabase(context.applicationContext)
    }
    val palette by produceState<List<InkPaletteColor>>(emptyList(), db) {
        value = withContext(Dispatchers.IO) { db.inkColors() }
    }
    var showRgb by remember { mutableStateOf(false) }
    // Reading this state makes a newly selected color immediately observable.
    @Suppress("UNUSED_VARIABLE")
    val revision = prefs.inkSmartSettingsRevision
    val selected = prefs.readerPlaybackHighlightColor
    val tajweed = palette.filter { it.tajweed }.sortedBy { it.sortOrder }
    val standard = listOf(
        0xFF202020L to "Чёрный",
        0xFFFFFFFFL to "Белый",
        0xFFD8C5A6L to "Бежевый",
        0xFFAF72DCL to "Фиолетовый"
    )
    val extras = (standard + palette.filter { it.custom }
        .sortedBy { it.sortOrder }.map { it.argb to (it.purpose.ifBlank { "Свой цвет" }) } +
        listOf(prefs.readerPlaybackCustomColor to "Последний RGB"))
        .distinctBy { it.first }
        .filterNot { option -> tajweed.any { it.argb == option.first } }
    Text("Цвет подсветки при воспроизведении", style = MaterialTheme.typography.titleSmall)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Surface(color = Color(selected.toInt()), shape = RoundedCornerShape(8.dp),
            modifier = Modifier.size(34.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {}
        Text("#%06X".format(selected and 0xFFFFFFL),
            style = MaterialTheme.typography.labelMedium)
        if (selected == 0xFFD8C5A6L) Text("По умолчанию", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        tajweed.forEach { swatch ->
            Q7HPlaybackSwatch(swatch.argb, swatch.purpose.ifBlank { "Таджвид" }, selected) {
                prefs.readerPlaybackHighlightColor = swatch.argb
            }
        }
        VerticalDivider(Modifier.height(29.dp).padding(start = 5.dp, end = 5.dp),
            color = MaterialTheme.colorScheme.outlineVariant)
        extras.forEach { (argb, name) ->
            Q7HPlaybackSwatch(argb, name, selected) {
                prefs.readerPlaybackHighlightColor = argb
            }
        }
        IconButton(onClick = { showRgb = true }, modifier = Modifier.size(38.dp)) {
            Icon(Icons.Default.AddCircleOutline, "Свой RGB-цвет для воспроизведения")
        }
    }
    Text("Первые цвета — правила таджвида; затем дополнительные и пользовательские. " +
        "Кнопка + позволяет задать собственный RGB-цвет. Подсветка не меняет сохранённые заметки.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (showRgb) Q7HPlaybackRgbDialog(initial = selected,
        onDismiss = { showRgb = false },
        onSave = {
            prefs.readerPlaybackCustomColor = it
            prefs.readerPlaybackHighlightColor = it
            showRgb = false
        })
}

@Composable
private fun Q7HPlaybackSwatch(color: Long, name: String, selected: Long, onClick: () -> Unit) {
    val chosen = selected == color
    Surface(onClick = onClick,
        modifier = Modifier.padding(2.dp).size(30.dp),
        color = Color(color.toInt()), shape = CircleShape,
        border = BorderStroke(if (chosen) 3.dp else 1.dp,
            if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (chosen) Icon(Icons.Default.Check, "Выбран цвет: $name",
                tint = if ((color and 0xFFFFFFL) in setOf(0xFFFFFFL, 0xFFD8C5L)) Color.Black
                    else Color.White,
                modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun Q7HPlaybackRgbDialog(initial: Long, onDismiss: () -> Unit,
                                 onSave: (Long) -> Unit) {
    var red by remember(initial) { mutableFloatStateOf(((initial shr 16) and 255L).toFloat()) }
    var green by remember(initial) { mutableFloatStateOf(((initial shr 8) and 255L).toFloat()) }
    var blue by remember(initial) { mutableFloatStateOf((initial and 255L).toFloat()) }
    var hex by remember(initial) { mutableStateOf("#%06X".format(initial and 0xFFFFFFL)) }
    fun current(): Long = 0xFF000000L or
        (red.roundToInt().coerceIn(0,255).toLong() shl 16) or
        (green.roundToInt().coerceIn(0,255).toLong() shl 8) or
        blue.roundToInt().coerceIn(0,255).toLong()
    fun syncHex() { hex = "#%06X".format(current() and 0xFFFFFFL) }
    val valid = hex.removePrefix("#").let { digits ->
        digits.length == 6 && digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Цвет подсветки плеера · RGB") },
        text = {
            Column(Modifier.q7hClearFocusOnBackgroundTap(),
                verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Surface(color = Color(current().toInt()),
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {}
                OutlinedTextField(value = hex, onValueChange = { input ->
                    hex = input.take(7)
                    if (hex.removePrefix("#").length == 6) {
                        hex.removePrefix("#").toLongOrNull(16)?.let { rgb ->
                            red = ((rgb shr 16) and 255).toFloat()
                            green = ((rgb shr 8) and 255).toFloat()
                            blue = (rgb and 255).toFloat()
                        }
                    }
                }, label = { Text("HEX · #RRGGBB") },
                    isError = !valid, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                listOf("R · Красный", "G · Зелёный", "B · Синий").forEachIndexed { index, title ->
                    val amount = when (index) { 0 -> red; 1 -> green; else -> blue }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, modifier = Modifier.width(90.dp),
                            style = MaterialTheme.typography.labelSmall)
                        Slider(value = amount, onValueChange = { next ->
                            when (index) { 0 -> red = next; 1 -> green = next; else -> blue = next }
                            syncHex()
                        }, valueRange = 0f..255f, modifier = Modifier.weight(1f))
                    }
                }
                Text("Будет сохранён только цвет подсветки воспроизводимого аята.",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = valid,
            onClick = { onSave(0xFF000000L or (hex.removePrefix("#").toLong(16))) }) {
            Text("Применить")
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}
