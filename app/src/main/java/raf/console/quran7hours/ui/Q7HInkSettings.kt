package raf.console.quran7hours.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import raf.console.quran7hours.data.InkPaletteColor
import raf.console.quran7hours.data.LearningDatabase
import raf.console.quran7hours.data.UserPreferences

internal object Q7HInkToolLabels {
    val names = mapOf(
        "marker" to "Маркер", "marker_ruler" to "Маркер с линейкой",
        "marker_single" to "Однослойный маркер",
        "marker_ruler_single" to "Однослойный маркер с линейкой",
        "pen_ruler" to "Карандаш с линейкой", "smart_marker" to "Умный выделитель аята", "pen" to "Карандаш",
        "cover" to "Скрыть аят", "text" to "Текст",
        "box" to "Рамка", "eraser" to "Ластик"
    )
}

/** The same Room palette drives the drawing toolbar and the editable settings list. */
@Composable
internal fun Q7HInkSettingsSection(prefs: UserPreferences) {
    val context = LocalContext.current
    val db = remember(context.applicationContext) { LearningDatabase(context.applicationContext) }
    var colors by remember(db) { mutableStateOf(db.inkColors()) }
    var newColor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<InkPaletteColor?>(null) }
    var pinned by remember { mutableStateOf(prefs.inkPinTajweedRow) }
    var defaultTool by remember { mutableStateOf(prefs.inkDefaultTool) }
    var toolOrder by remember { mutableStateOf(prefs.inkToolOrder) }
    fun refresh() { colors = db.inkColors() }

    Text("Графические заметки", style = MaterialTheme.typography.headlineSmall)
    // Calibrate installed images. Profiles live in app files and are backed up;
    // original Qur'an images/fonts are not copied into the user's backup.
    var calibration by remember(context) {
        mutableStateOf(Q7HSmartAyahCalibration.status(context))
    }
    LaunchedEffect(context) {
        while (true) {
            calibration = Q7HSmartAyahCalibration.status(context)
            kotlinx.coroutines.delay(1400L)
        }
    }
    HorizontalDivider()
    Text("Умный выделитель · калибровка 604 страниц",
        style = MaterialTheme.typography.titleMedium)
    Text("Проверяет геометрию на исходных изображениях Мусхафа, " +
        "доступных на устройстве. Использует QPC-разметку и реальные пиксели: " +
        "высоту букв, огласовки и пробелы между словами. " +
        "Непрошедшие страницы не размечаются вслепую. " +
        "Для другого набора изображений калибровку нужно запустить повторно.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            Q7HSmartAyahCalibration.start(context,
                Q7HSmartAyahCalibration.selectedVariant(context,prefs))
            calibration = Q7HSmartAyahCalibration.status(context)
        }, enabled = !calibration.running, modifier = Modifier.weight(1f)) {
            Text("Калибровать 604 страницы", maxLines = 2)
        }
        if (calibration.running) OutlinedButton(onClick = {
            Q7HSmartAyahCalibration.stop(context)
            calibration = Q7HSmartAyahCalibration.status(context)
        }) { Text("Стоп") }
    }
    if (calibration.processed > 0 || calibration.running) {
        LinearProgressIndicator(progress = { calibration.processed / 604f },
            modifier = Modifier.fillMaxWidth())
        Text("${calibration.processed}/604 · обработано: ${calibration.success} · " +
            "нет геометрии или изображения: ${calibration.failed}" +
            (if (calibration.running) " · выполняется" else ""),
            style = MaterialTheme.typography.labelSmall)
    }
    Text("Калибровка не изменяет Коран. Расчётные профили сохраняются в резервных " +
        "копиях; изображения и QPC-файлы должны быть загружены. " +
        "Перед использованием проверьте несколько сложных страниц вручную.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    HorizontalDivider()
    Text(
        "В первом ряду палитры располагаются цвета таджвида — это сделано для тех, " +
            "кто заучивает Коран, чтобы учитель графически помечал цветами ошибки " +
            "ученика. Например, забыл мадд лазим — красным, забыл носовое звучание " +
            "(гунну) — зелёным. Остальные цвета подходят для любых целей, включая " +
            "группировку аятов по смыслу или этапам заучивания. Вы можете указать " +
            "назначение каждого цвета, изменить его или добавить собственный.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Закрепить ряд цветов таджвида")
            Text("Первый ряд останется сверху; остальные цвета прокручиваются отдельно.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = pinned, onCheckedChange = {
            pinned = it; prefs.inkPinTajweedRow = it
        })
    }
    Text("Цвета и их назначения", style = MaterialTheme.typography.titleMedium)
    // Only colors offered by the compact drawing palette are shown here.
    // Legacy presets stay in Room/backup to avoid destroying old user data.
    val neutralColors = setOf(0xFF202020L, 0xFFFFFFFFL, 0xFFD8C5A6L, 0xFFAF72DCL)
    val ordered = colors.filter { it.tajweed || it.custom || it.argb in neutralColors }
        .sortedWith(compareBy<InkPaletteColor> { if (it.tajweed) 0 else if (it.argb in neutralColors) 1 else 2 }
            .thenBy { it.sortOrder })
    ordered.forEachIndexed { index, item ->
        key(item.id) {
            var meaning by remember(item.id) { mutableStateOf(item.purpose) }
            LaunchedEffect(item.purpose) { if (meaning != item.purpose) meaning = item.purpose }
            LaunchedEffect(item.id, meaning) {
                if (meaning != item.purpose) { delay(450); db.updateInkPurpose(item.id, meaning) }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(onClick = { editing = item },
                    modifier = Modifier.size(34.dp),
                    shape = CircleShape, color = Color(item.argb.toInt()),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {}
                OutlinedTextField(
                    value = meaning,
                    onValueChange = { meaning = it.take(160) },
                    modifier = Modifier.weight(1f),
                    singleLine = false, maxLines = 2,
                    label = { Text(if (item.tajweed) "Таджвид" else "Назначение", fontSize = 11.sp) }
                )
                Column {
                    IconButton(onClick = {
                        db.moveInkColor(item.id, -1); refresh()
                    }, enabled = index > 0 && (!pinned || ordered[index-1].tajweed == item.tajweed),
                        modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.KeyboardArrowUp, "Раньше", Modifier.size(20.dp))
                    }
                    IconButton(onClick = {
                        db.moveInkColor(item.id, 1); refresh()
                    }, enabled = index < ordered.lastIndex && (!pinned || ordered[index+1].tajweed == item.tajweed),
                        modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.KeyboardArrowDown, "Позже", Modifier.size(20.dp))
                    }
                }
                if (item.custom) IconButton(onClick = {
                    db.deleteInkColor(item.id); refresh()
                }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Default.DeleteOutline, "Удалить пользовательский цвет", Modifier.size(20.dp))
                }
            }
        }
    }
    OutlinedButton(onClick = { newColor = true }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.AddCircleOutline, null)
        Spacer(Modifier.width(8.dp))
        Text("Добавить цвет в палитру")
    }
    HorizontalDivider()
    Text("Графические инструменты", style = MaterialTheme.typography.titleMedium)
    Text("Инструмент по умолчанию", style = MaterialTheme.typography.labelLarge)
    // Both highlighters are explicitly promoted as the most useful choices.
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        UserPreferences.INK_TOOLS.forEach { id ->
                FilterChip(selected = defaultTool == id, onClick = {
                    defaultTool = id; prefs.inkDefaultTool = id
                }, label = { Text(Q7HInkToolLabels.names[id].orEmpty(), fontSize = 12.sp) },
                    leadingIcon = { if (id in listOf("marker","marker_ruler"))
                        Icon(Icons.Default.Star, null, Modifier.size(14.dp)) })
            }
    }
    Text("Рекомендуются маркер, маркер с линейкой и их однослойные варианты. " +
        "Однослойные варианты не затемняют отмеченное место при повторном проходе " +
        "одним цветом. Инструмент с линейкой удерживает горизонтальный угол 0°.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("Порядок инструментов", style = MaterialTheme.typography.labelLarge)
    toolOrder.forEachIndexed { index, id ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${index + 1}. ${Q7HInkToolLabels.names[id].orEmpty()}", Modifier.weight(1f))
            IconButton(onClick = {
                val next = toolOrder.toMutableList()
                val previous = next[index - 1]; next[index - 1] = id; next[index] = previous
                toolOrder = next; prefs.inkToolOrder = next
            }, enabled = index > 0) { Icon(Icons.Default.ArrowUpward, "Выше") }
            IconButton(onClick = {
                val next = toolOrder.toMutableList()
                val following = next[index + 1]; next[index + 1] = id; next[index] = following
                toolOrder = next; prefs.inkToolOrder = next
            }, enabled = index < toolOrder.lastIndex) { Icon(Icons.Default.ArrowDownward, "Ниже") }
        }
    }
    OutlinedButton(onClick = {
        toolOrder = UserPreferences.INK_TOOLS; prefs.inkToolOrder = toolOrder
    }) { Text("Восстановить порядок инструментов") }
    HorizontalDivider()
    Text("Сворачивание панели рисования", style = MaterialTheme.typography.titleMedium)
    Text("Первый ряд (отмена, повтор, удаление, сохранение и сворачивание) виден всегда, даже при минимальном сворачивании.",
        style = MaterialTheme.typography.bodySmall)
    Q7HInkCollapseCheckbox("Толщина маркера", prefs.inkCollapsedSlider) { prefs.inkCollapsedSlider = it }
    Q7HInkCollapseCheckbox("Инструменты", prefs.inkCollapsedTools) { prefs.inkCollapsedTools = it }
    Q7HInkCollapseCheckbox("Первый ряд цветов таджвида", prefs.inkCollapsedTajweed) { prefs.inkCollapsedTajweed = it }
    Text("Жесты палитры", style = MaterialTheme.typography.titleMedium)
    Text("Жесты действуют на свободном фоне палитры и на полосках захвата. " +
         "Нажатия на инструменты, цвета, кнопки сохранения и слайдер работают как обычно. " +
         "В свернутом квадратике долгое удержание всегда перемещает его.",
         style = MaterialTheme.typography.bodySmall,
         color = MaterialTheme.colorScheme.onSurfaceVariant)
    var doubleTap by remember { mutableStateOf(prefs.inkDoubleTapSquare) }
    var longPress by remember { mutableStateOf(prefs.inkLongPressCompact) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Двойное касание — свернуть до квадрата", Modifier.weight(1f))
        Switch(checked = doubleTap, onCheckedChange = {
            doubleTap = it; prefs.inkDoubleTapSquare = it
        })
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Долгое удержание — свернуть до строки", Modifier.weight(1f))
        Switch(checked = longPress, onCheckedChange = {
            longPress = it; prefs.inkLongPressCompact = it
        })
    }
    HorizontalDivider()
    Q7HSmartMarkerSettings(prefs)
    if (newColor || editing != null) {
        Q7HInkColorEditor(
            initial = editing,
            onDismiss = { newColor = false; editing = null },
            onSave = { color, purpose ->
                val old = editing
                if (old == null) db.addInkColor(color, purpose)
                else {
                    db.updateInkColor(old.id, color)
                    db.updateInkPurpose(old.id, purpose)
                }
                refresh(); newColor = false; editing = null
            }
        )
    }
}

/** Hex entry plus RGB sliders: no third-party color picker or special permission. */
@Composable
internal fun Q7HInkColorEditor(
    initial: InkPaletteColor?,
    onDismiss: () -> Unit,
    onSave: (Long, String) -> Unit
) {
    val first = initial?.argb ?: 0xFF8A5DE9L
    var red by remember(initial?.id) { mutableFloatStateOf(((first shr 16) and 255).toFloat()) }
    var green by remember(initial?.id) { mutableFloatStateOf(((first shr 8) and 255).toFloat()) }
    var blue by remember(initial?.id) { mutableFloatStateOf((first and 255).toFloat()) }
    var purpose by remember(initial?.id) { mutableStateOf(initial?.purpose.orEmpty()) }
    var hex by remember(initial?.id) { mutableStateOf("#%06X".format(first and 0xFFFFFF)) }
    fun pack(): Long = 0xFF000000L or (red.toLong() shl 16) or (green.toLong() shl 8) or blue.toLong()
    fun updateHex() { hex = "#%06X".format(pack() and 0xFFFFFF) }
    val valid = hex.trim().removePrefix("#").takeIf {
        it.length == 6 && it.all { ch -> ch.isDigit() || ch.lowercaseChar() in 'a'..'f' }
    }?.toLongOrNull(16)
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Новый цвет" else "Изменить цвет") },
        text = {
            Column(Modifier.q7hClearFocusOnBackgroundTap(),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(color = Color(pack().toInt()), shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().height(38.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {}
                OutlinedTextField(value = hex, onValueChange = { value ->
                    hex = value.take(9)
                    val parsed=hex.removePrefix("#").toLongOrNull(16)
                    if (hex.removePrefix("#").length == 6 && parsed != null) {
                        red = ((parsed shr 16) and 255).toFloat()
                        green = ((parsed shr 8) and 255).toFloat()
                        blue = (parsed and 255).toFloat()
                    }
                }, label = { Text("HEX: #RRGGBB") }, singleLine = true)
                listOf("Красный" to red, "Зелёный" to green, "Синий" to blue)
                    .forEachIndexed { index, (title, value) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(title, Modifier.width(76.dp), fontSize = 12.sp)
                            Slider(value = value, onValueChange = {
                                when (index) { 0 -> red = it; 1 -> green = it; else -> blue = it }
                                updateHex()
                            }, valueRange = 0f..255f, modifier = Modifier.weight(1f))
                        }
                    }
                OutlinedTextField(value = purpose, onValueChange = { purpose = it.take(160) },
                    label = { Text("Назначение цвета") }, maxLines = 2)
            }
        },
        confirmButton = { TextButton(onClick = {
            if (valid != null) onSave(0xFF000000L or valid, purpose)
        }, enabled = valid != null) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun Q7HInkCollapseCheckbox(title: String, initial: Boolean, update: (Boolean) -> Unit) {
    var checked by remember(initial) { mutableStateOf(initial) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked=checked,onCheckedChange={checked=it;update(it)})
        Text(title)
    }
}
