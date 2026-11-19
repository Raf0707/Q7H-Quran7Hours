package raf.console.quran7hours.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.SystemClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.roundToInt
import raf.console.quran7hours.data.UserPreferences
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.outlined.Info
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import raf.console.quran7hours.data.*
import kotlin.math.hypot
import kotlin.math.min

@Composable
internal fun LearningBookmarkIcon(icon: String, tint: Color, modifier: Modifier = Modifier) {
    // No circular background. White/black marks get a contour tracing the actual
    // bookmark shape by drawing the same vector slightly larger behind itself.
    val luminance = (tint.red * .2126f + tint.green * .7152f + tint.blue * .0722f)
    val needsOutline = luminance > .88f || luminance < .14f
    val outline = if (luminance > .88f) Color(0xFF3C3C3C) else Color(0xFFE8E8E8)
    Box(modifier = modifier.defaultMinSize(minWidth=24.dp,minHeight=24.dp),
        contentAlignment = Alignment.Center) {
        val vector = Q7HBookmarkIconCatalog.vector(icon)
        if (needsOutline) Icon(vector, null, tint = outline,
            modifier = Modifier.fillMaxSize())
        Icon(vector, null, tint = tint, modifier = Modifier.fillMaxSize(if (needsOutline) .86f else 1f))
    }
}

/** A compact dropdown-style popup positioned at the center of the window, not the toolbar. */
@Composable
internal fun LearningBookmarkMenu(
    expanded: Boolean,
    page: Int,
    types: List<BookmarkType>,
    bookmarks: List<LearningBookmark>,
    onDismiss: () -> Unit,
    onToggle: (BookmarkType) -> Unit,
    onEdit: (BookmarkType) -> Unit,
    onCreate: () -> Unit,
    onRemove: () -> Unit,
    titleOverride: String? = null,
    selectedTypeIds: Set<Long>? = null,
    showDeleteOverride: Boolean? = null
) {
    if (!expanded) return

    // Popup is hosted inside the RTL toolbar. Place it in WINDOW coordinates so
    // its center doesn't follow the bookmark button on the right of the screen.
    val centerOfWindow = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset = IntOffset(
                x = (windowSize.width - popupContentSize.width) / 2,
                y = (windowSize.height - popupContentSize.height) / 2
            )
        }
    }
    val configuration = LocalConfiguration.current
    val popupWidth = minOf(configuration.screenWidthDp.dp * 0.90f, 400.dp)
    val popupHeight = minOf(configuration.screenHeightDp.dp * 0.76f, 560.dp)

    Popup(
        popupPositionProvider = centerOfWindow,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        // Reader's toolbar is RTL; menu actions should read LEFT to RIGHT:
        // bookmark icon + description at the left, edit pencil at the right.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Surface(
                modifier = Modifier.width(popupWidth).heightIn(max = popupHeight),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 5.dp,
                shadowElevation = 12.dp
            ) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        titleOverride ?: "Закладки · страница $page",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                    )
                    // The delete action remains first, before the color choices.
                    // Do not show it when the current page has no bookmarks.
                    if (showDeleteOverride ?: (selectedTypeIds?.isNotEmpty() == true ||
                        (selectedTypeIds == null && bookmarks.any { it.page == page }))) {
                        DropdownMenuItem(
                            text = { Text("Удалить закладку") },
                            leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null) },
                            onClick = onRemove
                        )
                        HorizontalDivider()
                    }
                    // Sort by kind rather than relying on the old magic index 8.
                    val orderedTypes = types.sortedWith(compareBy<BookmarkType> {
                        if (it.icon == "bookmark") 0 else 1
                    }.thenBy { it.id })
                    orderedTypes.forEachIndexed { index, type ->
                        if (index == 0 || orderedTypes[index - 1].icon == "bookmark" && type.icon != "bookmark") {
                            if (index > 0) HorizontalDivider()
                            Text(
                                if (type.icon == "bookmark") "Цветные закладки" else "Функциональные закладки",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        val selected = selectedTypeIds?.contains(type.id) ?: bookmarks.any { it.page == page && it.typeId == type.id }
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f).clickable { onToggle(type) }
                                    .padding(start = 16.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                LearningBookmarkIcon(
                                    icon = type.icon,
                                    tint = Color(type.color.toInt()),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        type.name,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1
                                    )
                                    if (type.purpose.isNotBlank()) {
                                        Text(
                                            type.purpose,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 2
                                        )
                                    }
                                }
                                if (selected) {
                                    Spacer(Modifier.width(6.dp))
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "Выбрана",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            // Separate hit target: tapping the pencil edits, never toggles.
                            IconButton(
                                onClick = { onEdit(type) },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = "Изменить: ${type.name}",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Своя закладка") },
                        leadingIcon = { Icon(Icons.Default.AddCircleOutline, contentDescription = null) },
                        onClick = onCreate
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Q7HBookmarkModeTabs(functional:Boolean,onChange:(Boolean)->Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(selected=!functional,onClick={onChange(false)},
            shape=SegmentedButtonDefaults.itemShape(0,2),icon={}) { Text("Цветная") }
        SegmentedButton(selected=functional,onClick={onChange(true)},
            shape=SegmentedButtonDefaults.itemShape(1,2),icon={}) { Text("Функц.") }
    }
}

/** Identical editor for Mushaf page bookmarks and ayah bookmarks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookmarkTypeEditor(
    initial: BookmarkType?,
    onDismiss: () -> Unit,
    onSave: (String, String, Long, String) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name ?: "") }
    var purpose by remember(initial?.id) { mutableStateOf(initial?.purpose ?: "") }
    var functional by remember(initial?.id) { mutableStateOf(initial?.icon?.let { it != "bookmark" } ?: false) }
    var selectedIcon by remember(initial?.id) {
        mutableStateOf(initial?.icon?.takeIf { it != "bookmark" } ?: "star")
    }
    var search by remember(initial?.id) { mutableStateOf("") }
    val raw = initial?.color ?: 0xFF467FDEL
    var r by remember(initial?.id) { mutableFloatStateOf(((raw shr 16) and 255).toFloat()) }
    var g by remember(initial?.id) { mutableFloatStateOf(((raw shr 8) and 255).toFloat()) }
    var b by remember(initial?.id) { mutableFloatStateOf((raw and 255).toFloat()) }
    val color = 0xFF000000L or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
    val resultIcons = remember(search) { Q7HBookmarkIconCatalog.find(search) }
    val ctx=androidx.compose.ui.platform.LocalContext.current
    Q7HBottomNavSettings.load(ctx)
    val tabsAtBottom=Q7HBottomNavSettings.enabled
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Своя закладка" else "Редактировать закладку") },
        text = {
            Column(modifier=Modifier.fillMaxWidth().heightIn(max=535.dp)
                .q7hClearFocusOnBackgroundTap()) {
                if (!tabsAtBottom) Q7HBookmarkModeTabs(functional) { functional=it }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement=Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(60) },
                    label = { Text("Название") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = purpose, onValueChange = { purpose = it.take(280) },
                    label = { Text(if (functional) "Функция закладки" else "Для чего использую") },
                    minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Например: аяты для повторения и размышления") }
                )
                if (!functional) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).background(Color(color.toInt()), CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                        Spacer(Modifier.width(12.dp))
                        Text("#%06X".format(color and 0xFFFFFFL))
                    }
                    listOf(Triple("Красный", r, 0), Triple("Зелёный", g, 1), Triple("Синий", b, 2))
                        .forEach { (title, value, channel) ->
                            Text(title, style = MaterialTheme.typography.labelSmall)
                            Slider(value = value, onValueChange = {
                                when (channel) { 0 -> r = it; 1 -> g = it; else -> b = it }
                            }, valueRange = 0f..255f)
                        }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LearningBookmarkIcon(selectedIcon, Color(color.toInt()), Modifier.size(42.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Иконка: ${Q7HBookmarkIconCatalog.name(selectedIcon)}",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    OutlinedTextField(
                        value = search, onValueChange = { search = it.take(70) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        label = { Text("Поиск иконки (название, категория)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Text("Найдено: ${resultIcons.size} из ${Q7HBookmarkIconCatalog.items.size}",
                        style = MaterialTheme.typography.labelSmall)
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 76.dp),
                        modifier = Modifier.fillMaxWidth().height(248.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        gridItems(resultIcons, key = { it.key }) { item ->
                            val active = selectedIcon == item.key
                            Surface(
                                onClick = { selectedIcon = item.key },
                                shape = RoundedCornerShape(10.dp),
                                color = if (active) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.height(80.dp)
                            ) {
                                Column(
                                    Modifier.fillMaxSize().padding(4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(item.vector, contentDescription = item.label,
                                        tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(27.dp))
                                    Text(item.label, maxLines = 2, fontSize = 10.sp,
                                        textAlign = TextAlign.Center,
                                        lineHeight = 11.sp)
                                }
                            }
                        }
                    }
                    Text("Выберите значок. Он появится в списке закладок и рядом с аятом.",
                        style = MaterialTheme.typography.labelSmall)
                }
                if (onDelete != null) TextButton(onClick = onDelete) {
                    Text("Удалить свою категорию")
                }
                }
                if (tabsAtBottom) {
                    HorizontalDivider()
                    Q7HBookmarkModeTabs(functional) { functional=it }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim(), purpose.trim(), color, if (functional) selectedIcon else "bookmark") },
                enabled = name.isNotBlank() && (!functional || (purpose.isNotBlank() && selectedIcon.isNotBlank()))
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
internal fun LearningBookmarksDrawer(
    bookmarks: List<LearningBookmark>,
    onOpen: (Int) -> Unit,
    onRemove: (Long) -> Unit,
    onNote: (LearningBookmark) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Text("Мои закладки (${bookmarks.size})", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(14.dp))
        if (bookmarks.isEmpty()) {
            Text("Нажмите значок закладки на странице, чтобы добавить её.",
                modifier = Modifier.padding(16.dp))
        } else LazyColumn(contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
            items(bookmarks, key = { it.id }) { mark ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { onOpen(mark.page) }, modifier = Modifier.weight(1f)) {
                            LearningBookmarkIcon(mark.type.icon, Color(mark.type.color.toInt()))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                                Text("Страница ${mark.page} · ${mark.type.name}")
                                if (mark.type.purpose.isNotBlank()) Text(mark.type.purpose,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        IconButton(onClick = { onNote(mark) }) { Icon(Icons.Default.NoteAdd, "Заметка") }
                        IconButton(onClick = { onRemove(mark.id) }) { Icon(Icons.Default.DeleteOutline, "Удалить") }
                    }
                }
            }
        }
    }
}

private fun targetLabel(note: LearningNote): String = when (note.scope) {
    NoteScope.NONE -> "Самостоятельная заметка"
    NoteScope.MANZIL -> "Манзиль ${note.number}"
    NoteScope.JUZ -> "Джуз ${note.number}"
    NoteScope.HIZB -> "Хизб ${note.number}"
    NoteScope.SURAH -> "Сура ${note.number}"
    NoteScope.PAGE -> "Страница ${note.number}"
    NoteScope.AYAH -> "Аят ${note.surah}:${note.firstAyah}"
    NoteScope.AYAH_RANGE -> "Аяты ${note.surah}:${note.firstAyah}–${note.lastAyah}"
}

@Composable
internal fun LearningNotesDrawer(
    notes: List<LearningNote>, bookmarks: List<LearningBookmark>,
    onCreate: () -> Unit, onEdit: (LearningNote) -> Unit,
    onOpen: (LearningNote) -> Unit, onDelete: (Long) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Button(onClick = onCreate, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Новая заметка")
        }
        if (notes.isEmpty()) Text("Заметок пока нет. Можно писать без закладки или привязать к аяту, странице, суре и другим разделам.",
            modifier = Modifier.padding(16.dp))
        else LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp)) {
            items(notes, key = { it.id }) { note ->
                ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(note.title.ifBlank { "Без заголовка" }, style = MaterialTheme.typography.titleMedium)
                        Text(targetLabel(note) + if (note.bookmarkId != null) " · Связана с закладкой" else "",
                            color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                        Text(note.body, maxLines = 3, style = MaterialTheme.typography.bodyMedium)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { onOpen(note) }) { Text("Перейти") }
                            IconButton(onClick = { onEdit(note) }) { Icon(Icons.Default.Edit, "Редактировать") }
                            IconButton(onClick = { onDelete(note.id) }) { Icon(Icons.Default.DeleteOutline, "Удалить") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun LearningNoteEditor(
    initial: LearningNote,
    bookmarks: List<LearningBookmark>,
    onDismiss: () -> Unit,
    onSave: (LearningNote) -> Unit
) {
    var title by remember(initial.id) { mutableStateOf(initial.title) }
    var text by remember(initial.id) { mutableStateOf(initial.body) }
    var scope by remember(initial.id) { mutableStateOf(initial.scope) }
    var number by remember(initial.id) { mutableStateOf(initial.number.toString()) }
    var surah by remember(initial.id) { mutableStateOf(initial.surah.toString()) }
    var firstAyah by remember(initial.id) { mutableStateOf(initial.firstAyah.toString()) }
    var lastAyah by remember(initial.id) { mutableStateOf(initial.lastAyah.toString()) }
    var linkedId by remember(initial.id) { mutableStateOf(initial.bookmarkId) }
    var linkedMenu by remember { mutableStateOf(false) }
    var scopeMenu by remember { mutableStateOf(false) }
    val nums = number.toIntOrNull() ?: 0
    val s = surah.toIntOrNull() ?: 0
    val a1 = firstAyah.toIntOrNull() ?: 0
    val a2 = lastAyah.toIntOrNull() ?: 0
    val valid = when (scope) {
        NoteScope.NONE -> true
        NoteScope.PAGE -> nums in 1..604
        NoteScope.SURAH -> nums in 1..114
        NoteScope.JUZ -> nums in 1..30
        NoteScope.HIZB -> nums in 1..60
        NoteScope.MANZIL -> nums in 1..7
        NoteScope.AYAH -> QuranCatalog.isValidAyah(s, a1)
        NoteScope.AYAH_RANGE -> QuranCatalog.isValidAyah(s, a1) &&
            QuranCatalog.isValidAyah(s, a2) && a1 <= a2
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Заметка") }, text = {
        Column(Modifier.q7hClearFocusOnBackgroundTap().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(value = title, onValueChange = { title = it.take(160) },
                label = { Text("Название") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = text, onValueChange = { text = it },
                label = { Text("Текст заметки") }, minLines = 4, maxLines = 9,
                modifier = Modifier.fillMaxWidth())
            Box {
                OutlinedButton(onClick = { scopeMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Привязка: ${scope.title}")
                    Icon(Icons.Default.ArrowDropDown, null)
                }
                DropdownMenu(expanded = scopeMenu, onDismissRequest = { scopeMenu = false }) {
                    NoteScope.entries.forEach { option -> DropdownMenuItem(
                        text = { Text(option.title) }, onClick = { scope = option; scopeMenu = false }) }
                }
            }
            when (scope) {
                NoteScope.NONE -> Unit
                NoteScope.AYAH, NoteScope.AYAH_RANGE -> {
                    OutlinedTextField(value = surah, onValueChange = { surah = it.filter(Char::isDigit).take(3) },
                        label = { Text("Номер суры (1–114)") })
                    OutlinedTextField(value = firstAyah, onValueChange = { firstAyah = it.filter(Char::isDigit).take(3) },
                        label = { Text("Первый аят") })
                    if (scope == NoteScope.AYAH_RANGE) OutlinedTextField(
                        value = lastAyah, onValueChange = { lastAyah = it.filter(Char::isDigit).take(3) },
                        label = { Text("Последний аят") })
                }
                else -> OutlinedTextField(value = number,
                    onValueChange = { number = it.filter(Char::isDigit).take(3) },
                    label = { Text("Номер: ${scope.title}") })
            }
            if (!valid) Text("Проверьте номер раздела или аятов", color = MaterialTheme.colorScheme.error)
            Box {
                OutlinedButton(onClick = { linkedMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(bookmarks.firstOrNull { it.id == linkedId }?.let {
                        "Закладка: ${it.type.name} · стр. ${it.page}"
                    } ?: "Без закладки")
                    Icon(Icons.Default.ArrowDropDown, null)
                }
                DropdownMenu(expanded = linkedMenu, onDismissRequest = { linkedMenu = false },
                    modifier = Modifier.heightIn(max = 320.dp)) {
                    DropdownMenuItem(text = { Text("Отвязать от закладки") }, onClick = {
                        linkedId = null; linkedMenu = false
                    })
                    bookmarks.forEach { mark -> DropdownMenuItem(
                        text = { Text("${mark.type.name} · стр. ${mark.page}") }, onClick = {
                            linkedId = mark.id; linkedMenu = false
                        }) }
                }
            }
        }
    }, confirmButton = { TextButton(enabled = valid && (title.isNotBlank() || text.isNotBlank()),
        onClick = { onSave(initial.copy(title = title, body = text, scope = scope, number = nums,
            surah = s, firstAyah = a1, lastAyah = a2, bookmarkId = linkedId)) }) {
        Text("Сохранить")
    } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}

/** Every ayah on the current page, no tafsir, separate local note per (surah,ayah). */
@Composable
internal fun HafizDiaryPanel(
    page: Int,
    tafsir: TafsirStore,
    prefs: UserPreferences,
    db: LearningDatabase,
    tajweed: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    // Q7H_HAFIZ_QPC_DIARY_20261112_V1
    // One shared TafsirStore source and atomic QPC glyph/font preparation.
    val context = androidx.compose.ui.platform.LocalContext.current
    val prepared by produceState<QpcPreparedPage?>(null, page, tafsir) {
        value = QpcPreparedPages.page(context, tafsir, page)
    }
    val verses = prepared?.verses
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val backgroundTap = remember { MutableInteractionSource() }
    Column(Modifier.fillMaxSize()
        .clickable(interactionSource = backgroundTap, indication = null) {
            focus.clearFocus(); keyboard?.hide()
        }.padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onNext, enabled = page < 604) {
                Icon(Icons.Default.ChevronLeft, "Следующая страница")
            }
            Text("Дневник хафиза · стр. $page", modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = onPrevious, enabled = page > 1) {
                Icon(Icons.Default.ChevronRight, "Предыдущая страница")
            }
        }
        val loaded = verses
        if (loaded == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        } else if (loaded.isEmpty()) Text("Для этой страницы не найдены аяты в локальном тафсире.",
            modifier = Modifier.padding(16.dp))
        else {
            val listState = remember(page) {
                val (index, offset) = prefs.panelViewport("hafiz_$page")
                androidx.compose.foundation.lazy.LazyListState(index.coerceIn(0, (loaded.size-1).coerceAtLeast(0)), offset)
            }
            LaunchedEffect(listState, page) {
                snapshotFlow {
                    listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
                }.collect { (index, offset) ->
                    prefs.savePanelViewport("hafiz_$page", index, offset)
                }
            }
            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)) {
            items(loaded, key = { "${it.surah}:${it.ayah}" }) { verse ->
                HafizVerseCard(verse, db, prepared?.qpc?.get(verse.surah to verse.ayah), tajweed)
            }
            }
        }
    }
}

@Composable
private fun HafizVerseCard(
    verse: TafsirStore.Verse,
    db: LearningDatabase,
    qpc: OriginalQpcAyah?,
    tajweed: Boolean
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val backgroundTap = remember { MutableInteractionSource() }
    var note by remember(verse.surah, verse.ayah) { mutableStateOf(db.hafizNote(verse.surah, verse.ayah)) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.clickable(interactionSource = backgroundTap, indication = null) {
            focus.clearFocus(); keyboard?.hide()
        }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${verse.surah}:${verse.ayah}", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
            if (qpc != null) {
                // Original page-specific QPC: V4 embedded Tajweed when ON, V2 when OFF.
                OriginalQpcTafsirText(qpc, tajweed)
            } else {
                // Preserve readable Azan Arabic; never display QPC PUA codes without fonts.
                TafsirQuranText(arabicFromAzan = verse.arabic, verified = null, tajweed = false)
            }
            if (verse.translation.isNotBlank()) Text(verse.translation,
                style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(value = note, onValueChange = { next ->
                note = next
                // Persist on every edit: closes/crashes do not need a Save button.
                db.saveHafizNote(verse.surah, verse.ayah, next)
            }, label = { Text("Мои ошибки, подсказки и замечания") },
                minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    focus.clearFocus(); keyboard?.hide()
                }))
        }
    }
}

/**
 * White marker on a white page and black marker on a black page would vanish.
 * Only MARKER tools receive a display-time contrast correction. The original
 * ARGB is kept in Room/backup, so swapping theme is reversible and pencils,
 * eraser, text, outline tools and user-defined colors are never altered.
 */
internal fun q7hMarkerVisibleArgb(argb: Long, tool: String, darkPage: Boolean): Long {
    val marker = tool == "marker" || tool == "marker_ruler" ||
        tool == "marker_single" || tool == "marker_ruler_single" || tool == "smart_marker"
    if (!marker) return argb
    val alpha = argb and 0xFF000000L
    return when {
        !darkPage && (argb and 0xFFFFFFL) == 0xFFFFFFL -> alpha or 0x202020L
        darkPage && (argb and 0xFFFFFFL) in setOf(0x202020L, 0x000000L) -> alpha or 0xFFFFFFL
        else -> argb
    }
}

/** Brush strokes remain in image-relative coordinates, never baked into Qur'an WebP. */
@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
internal fun InkPageLayer(
    bitmap: Bitmap,
    strokes: SnapshotStateList<InkStroke>,
    tool: String,
    inkColor: Long,
    width: Float,
    onChanged: () -> Unit,
    onDraft: (InkStroke?) -> Unit,
    onSmartTap: (InkPoint) -> Unit,
    onSmartClear: (InkPoint) -> Unit,
    onSmartPlay: (InkPoint) -> Unit = {},
    onTimedGesture: () -> Unit = {},
    onSingleTap: () -> Unit = {},
    onEraserActivate: () -> Unit = {},
    readerSmartGestures: Boolean = false,
    readerEraser: Boolean = false,
    smartPrefs: UserPreferences,
    editable: Boolean,
    coverColor: Color,
    darkPage: Boolean,
    transientHighlights: List<InkStroke> = emptyList(),
    modifier: Modifier = Modifier
) {
    val active = remember(strokes) { mutableStateListOf<InkPoint>() }
    var inProgress by remember(strokes) { mutableStateOf(false) }
    var textPosition by remember(strokes) { mutableStateOf<InkPoint?>(null) }
    var textEntry by remember(strokes) { mutableStateOf("") }
    val current = rememberUpdatedState(onChanged)
    val draftCallback=rememberUpdatedState(onDraft)
    val smartCallback=rememberUpdatedState(onSmartTap)
    val clearCallback=rememberUpdatedState(onSmartClear)
    val playCallback=rememberUpdatedState(onSmartPlay)
    val timedCallback=rememberUpdatedState(onTimedGesture)
    val singleCallback=rememberUpdatedState(onSingleTap)
    val eraserCallback=rememberUpdatedState(onEraserActivate)
    val gestureScope = rememberCoroutineScope()
    // Read snapshot state so changing marker settings recomposes the overlay.
    @Suppress("UNUSED_VARIABLE")
    val smartRevision = smartPrefs.inkSmartSettingsRevision
    val countdownSide = smartPrefs.inkSmartCountdownSide
    val markerRadius = smartPrefs.inkSmartRadius
    val markerBorder = smartPrefs.inkSmartBorderEnabled
    val markerBorderWidth = smartPrefs.inkSmartBorderWidth
    data class Countdown(val x: Float, val y: Float, val remaining: Float, val total: Float)
    var countdown by remember { mutableStateOf<Countdown?>(null) }
    // In reading mode the canvas remains immutable: only Room ink strokes can
    // be added/removed. An activated reader eraser can drag over marks without
    // opening the complete drawing toolbar.
    val drawingTool = if (!editable && readerEraser) "eraser" else tool
    val drawInput = if (editable || readerEraser) Modifier.pointerInput(
        bitmap, strokes, drawingTool, inkColor, width, editable, readerEraser
    ) {
        fun point(offset: Offset): InkPoint? {
            val fit = min(size.width / bitmap.width.toFloat(), size.height / bitmap.height.toFloat())
            val w = bitmap.width * fit; val h = bitmap.height * fit
            val left = (size.width - w) / 2f; val top = (size.height - h) / 2f
            if (offset.x !in left..(left+w) || offset.y !in top..(top+h)) return null
            return InkPoint(((offset.x-left)/w).coerceIn(0f,1f), ((offset.y-top)/h).coerceIn(0f,1f))
        }
        fun erase(p: InkPoint) {
            val hit = strokes.indexOfLast { stroke ->
                q7hInkEraseHit(stroke, p, bitmap.width.toFloat()/bitmap.height, tap=false)
            }
            if(hit>=0) {strokes.removeAt(hit);current.value()}
        }
        detectDragGestures(
            onDragStart = { offset ->
                draftCallback.value(null);active.clear()
                point(offset)?.let { p ->
                    if(drawingTool=="eraser") erase(p)
                    else if(drawingTool!="text" && drawingTool!="smart_marker") active.add(p)
                }
                inProgress=true
            },
            onDrag={change,_->
                change.consume()
                point(change.position)?.let { p ->
                    if(drawingTool=="eraser") erase(p)
                    else if(inProgress && drawingTool!="text" && drawingTool!="smart_marker") {
                        val straight=drawingTool=="marker_ruler" || drawingTool=="marker_ruler_single" || drawingTool=="pen_ruler"
                        active.add(if(straight && active.isNotEmpty()) p.copy(y=active.first().y) else p)
                        if(active.isNotEmpty()) draftCallback.value(InkStroke(drawingTool,inkColor,width,active.toList()))
                    }
                }
            },
            onDragCancel={active.clear();inProgress=false;draftCallback.value(null)},
            onDragEnd={
                if(drawingTool!="eraser" && drawingTool!="text" && drawingTool!="smart_marker" && active.isNotEmpty()) {
                    strokes.add(InkStroke(drawingTool,inkColor,width,active.toList()))
                    current.value()
                }
                active.clear();inProgress=false;draftCallback.value(null)
            }
        )
    } else Modifier
    // Reader and editor use the SAME two gestures. Single tap is reserved for
    // toolbar visibility in reading mode and cannot be rebound to another action.
    // The optional extra-timed action is independent and disabled by default.
    val swapReaderActions = smartPrefs.readerSwapAyahGestures
    val timedAction = smartPrefs.readerTimedGestureAction
    val timedMs = smartPrefs.readerTimedGestureMs
    val tapInput = if(editable || readerSmartGestures) Modifier.pointerInput(
        bitmap, strokes, drawingTool, editable, readerSmartGestures,
        swapReaderActions, timedAction, timedMs
    ) {
        var timedJob: Job? = null
        fun imagePoint(offset: Offset): InkPoint? {
            val fit=min(size.width/bitmap.width.toFloat(),size.height/bitmap.height.toFloat())
            val w=bitmap.width*fit;val h=bitmap.height*fit
            val l=(size.width-w)/2f;val t=(size.height-h)/2f
            if(offset.x !in l..(l+w) || offset.y !in t..(t+h)) return null
            return InkPoint(((offset.x-l)/w).coerceIn(0f,1f), ((offset.y-t)/h).coerceIn(0f,1f))
        }
        fun editTap(offset: Offset) {
            val point=imagePoint(offset)?:return
            when(drawingTool) {
                "text" -> {textEntry="";textPosition=point}
                "eraser" -> {
                    val hit=strokes.indexOfLast {
                        q7hInkEraseHit(it,point,bitmap.width.toFloat()/bitmap.height,true)
                    }
                    if(hit>=0){strokes.removeAt(hit);current.value()}
                }
                "smart_marker" -> Unit
                else -> {strokes.add(InkStroke(drawingTool,inkColor,width,listOf(point)));current.value()}
            }
        }
        detectTapGestures(
            onPress={
                try { tryAwaitRelease() } finally {
                    timedJob?.cancel();timedJob=null;countdown=null
                }
            },
            onTap={offset->
                if(!editable) {
                    if(readerEraser) imagePoint(offset)?.let {p->
                        val hit=strokes.indexOfLast {
                            q7hInkEraseHit(it,p,bitmap.width.toFloat()/bitmap.height,true)
                        }
                        if(hit>=0){strokes.removeAt(hit);current.value()}
                    }
                    singleCallback.value()
                } else editTap(offset)
            },
            onDoubleTap={offset: Offset ->
                imagePoint(offset)?.let { point ->
                    if(swapReaderActions) playCallback.value(point)
                    else smartCallback.value(point)
                }
            },
            onLongPress={offset ->
                imagePoint(offset)?.let { point ->
                    if(swapReaderActions) smartCallback.value(point)
                    else playCallback.value(point)
                    if(timedAction!="off") {
                        timedJob?.cancel()
                        timedJob=gestureScope.launch {
                            val began=SystemClock.uptimeMillis()
                            while(true) {
                                val fraction=(1f-(SystemClock.uptimeMillis()-began).toFloat()/timedMs)
                                    .coerceIn(0f,1f)
                                countdown=Countdown(offset.x,offset.y,fraction,timedMs/1000f)
                                if(fraction<=0f) break
                                delay(40L)
                            }
                            countdown=null
                            timedCallback.value()
                        }
                    }
                }
            }
        )
    } else Modifier
    BoxWithConstraints(modifier.fillMaxSize()) {
    Canvas(Modifier.fillMaxSize().then(drawInput).then(tapInput)) {
        val fit = min(size.width / bitmap.width, size.height / bitmap.height)
        val w = bitmap.width * fit; val h = bitmap.height * fit
        val left = (size.width - w) / 2f; val top = (size.height - h) / 2f
        fun render(stroke: InkStroke, opaqueOneLayer: Boolean = false) {
            val pts = stroke.points
            if (pts.isEmpty() || stroke.tool == "text") return
            val col = (if (stroke.tool == "cover") coverColor
                else Color(q7hMarkerVisibleArgb(stroke.color, stroke.tool, darkPage).toInt())
                ).copy(alpha = when (stroke.tool) {
                "marker", "marker_ruler" -> .32f
                // Within the offscreen mask always draw opaque: transparency is
                // applied ONCE when the combined mask is composited into the page.
                "marker_single", "marker_ruler_single", "smart_marker" -> if (opaqueOneLayer) 1f else .32f
                // A memorization cover must NEVER reveal the underlying ayah.
                "cover" -> 1f
                else -> 1f
            })
            val sw = (stroke.width * w).coerceAtLeast(2f)
            if (stroke.tool == "smart_marker" && pts.size >= 2) {
                // The actual image-derived glyph/harakat band defines the height.
                // A round-rect has fully rounded corners but does NOT extend
                // horizontally outside the measured ayah span (unlike round caps).
                val a = pts.first(); val b = pts.last()
                val x0 = left + minOf(a.x, b.x) * w
                val x1 = left + maxOf(a.x, b.x) * w
                val y0 = top + a.y * h - sw / 2f
                if (x1 > x0) {
                    val radius = minOf(sw / 2f, (x1-x0)/2f) * markerRadius
                    drawRoundRect(col, topLeft=Offset(x0,y0),size=Size(x1-x0,sw),
                        cornerRadius=CornerRadius(radius))
                }
            } else if (stroke.tool == "box" && pts.size >= 2) {
                val a = pts.first(); val b = pts.last()
                drawRect(col, topLeft = Offset(left + minOf(a.x,b.x)*w, top + minOf(a.y,b.y)*h),
                    size = Size(kotlin.math.abs(a.x-b.x)*w, kotlin.math.abs(a.y-b.y)*h),
                    style = Stroke(sw))
            } else if (pts.size == 1) {
                drawCircle(col, radius = sw / 2f, center = Offset(left+pts[0].x*w, top+pts[0].y*h))
            } else {
                val path = Path().apply {
                    moveTo(left+pts[0].x*w, top+pts[0].y*h)
                    pts.drop(1).forEach { lineTo(left+it.x*w, top+it.y*h) }
                }
                drawPath(path, col, style = Stroke(sw,
                    cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        val draftStroke = if (inProgress && active.isNotEmpty())
            InkStroke(tool, inkColor, width, active.toList()) else null
        // Playback markers are display-only. Never mutate or save them in Room:
        // changing a verse or stopping Media3 removes this overlay immediately.
        // The selected verse is stored in Room as smart:sura:ayah:page; its
        // temporary playback overlay has playback:sura:ayah:page.  Never delete,
        // recolor, or save either marker here: this is a display-only decision.
        val activePlayedTags = transientHighlights.asSequence()
            .map { it.text }
            .filter { it.startsWith("playback:") }
            .map { "smart:" + it.removePrefix("playback:") }
            .toSet()
        val existingSmartTags = strokes.asSequence()
            .filter { it.tool == "smart_marker" && it.text in activePlayedTags }
            .map { it.text }.toSet()
        // Default: replace the already-colored ayah with beige WHILE audible.
        // Alternative: retain its original color; do not add beige on top.
        val replaceSaved = smartPrefs.readerPlaybackHighlightMode == "replace"
        val visibleSaved = if (replaceSaved && existingSmartTags.isNotEmpty())
            strokes.filterNot { it.tool == "smart_marker" && it.text in existingSmartTags }
        else strokes.toList()
        val visiblePlayback = if (!replaceSaved && existingSmartTags.isNotEmpty())
            transientHighlights.filterNot { temporary ->
                temporary.text.startsWith("playback:") &&
                    ("smart:" + temporary.text.removePrefix("playback:")) in existingSmartTags
            }
        else transientHighlights
        val onPage = visibleSaved + listOfNotNull(draftStroke) + visiblePlayback
        // A single-layer marker means ONE alpha mask per ARGB color across all
        // strokes using either one-layer variant. Touching/overlapping strokes
        // remain equally translucent; they cannot darken each other.
        val oneLayerIds = setOf("marker_single", "marker_ruler_single", "smart_marker")
        // Draw translucent markers underneath handwriting/pen/cover strokes.
        // This preserves the readability of existing user annotations without
        // changing any persisted data, alpha or marker geometry.
        onPage.filter { it.tool in oneLayerIds }
            .groupBy { it.color }.forEach { (_, sameColor) ->
                val layerPaint = Paint().apply { alpha = .32f }
                drawContext.canvas.saveLayer(
                    Rect(left, top, left + w, top + h), layerPaint)
                try {
                    sameColor.forEach { render(it, opaqueOneLayer = true) }
                } finally {
                    drawContext.canvas.restore()
                }
            }
        // The optional border is painted over the shared translucent mask so
        // its appearance is stable even if adjacent verses touch.
        if (markerBorder) onPage.filter { it.tool=="smart_marker" && it.points.size>=2 }
            .forEach { ink ->
                val a=ink.points.first(); val b=ink.points.last()
                val x0=left+minOf(a.x,b.x)*w; val x1=left+maxOf(a.x,b.x)*w
                val hInk=(ink.width*w).coerceAtLeast(2f)
                val y0=top+a.y*h-hInk/2f
                val r=minOf(hInk/2f,(x1-x0)/2f)*markerRadius
                val borderThickness=markerBorderWidth.dp.toPx().coerceAtMost(hInk*.25f)
                val inset=borderThickness/2f
                if(x1-x0>borderThickness*2f && hInk>borderThickness*2f) drawRoundRect(
                    color=Color(q7hMarkerVisibleArgb(ink.color,ink.tool,darkPage).toInt()).copy(alpha=.88f),
                    topLeft=Offset(x0+inset,y0+inset),
                    size=Size(x1-x0-2f*inset,hInk-2f*inset),
                    cornerRadius=CornerRadius((r-inset).coerceAtLeast(0f)),
                    style=Stroke(width=borderThickness)
                )
            }
        onPage.filterNot { it.tool in oneLayerIds }.forEach { render(it) }
    }
    // Circular countdown is drawn in SCREEN coordinates while the user holds
    // a finger, but the action is always resolved in image coordinates.
    countdown?.let { c ->
        val d = LocalDensity.current
        val ring = with(d) { 54.dp.toPx() }
        val pad = with(d) { 10.dp.toPx() }
        val hostW = with(d) { maxWidth.toPx() }
        val hostH = with(d) { maxHeight.toPx() }
        val desiredX = when(countdownSide) {
            "right" -> c.x + 18f
            "top" -> c.x - ring/2f
            else -> c.x - ring - 18f
        }
        val nearLeft = desiredX < pad
        val x = if(nearLeft) c.x-ring/2f else desiredX
        val desiredY = if(nearLeft || countdownSide=="top") {
            if(c.y-ring-pad<pad) c.y+18f else c.y-ring-18f
        } else c.y-ring/2f
        val xSafe=x.coerceIn(pad,(hostW-ring-pad).coerceAtLeast(pad))
        val ySafe=desiredY.coerceIn(pad,(hostH-ring-pad).coerceAtLeast(pad))
        Box(Modifier.offset { IntOffset(xSafe.roundToInt(),ySafe.roundToInt()) }
            .size(54.dp).background(MaterialTheme.colorScheme.surface.copy(alpha=.95f),CircleShape),
            contentAlignment=Alignment.Center) {
            CircularProgressIndicator(progress={c.remaining}, modifier=Modifier.fillMaxSize().padding(5.dp))
            Text(String.format(java.util.Locale.ROOT,"%.1f",(c.remaining*c.total).coerceAtLeast(0f)),
                color=MaterialTheme.colorScheme.onSurface,style=MaterialTheme.typography.titleMedium)
        }
    }
    // Text uses Compose layout instead of bitmap painting so Arabic shaping remains correct.
    val fitDp = min(maxWidth.value / bitmap.width, maxHeight.value / bitmap.height)
    val pageWidthDp = (bitmap.width * fitDp).dp
    val pageHeightDp = (bitmap.height * fitDp).dp
    val pageLeftDp = (maxWidth - pageWidthDp) / 2
    val pageTopDp = (maxHeight - pageHeightDp) / 2
    strokes.filter { it.tool == "text" && it.text.isNotBlank() && it.points.isNotEmpty() }
        .forEach { stroke ->
            val pos = stroke.points.first()
            Text(stroke.text, color = Color(stroke.color.toInt()),
                fontSize = (16f + stroke.width * 350f).coerceIn(16f, 38f).sp,
                lineHeight = (24f + stroke.width * 420f).coerceIn(24f, 48f).sp,
                modifier = Modifier.offset(
                    x = pageLeftDp + pageWidthDp * pos.x,
                    y = pageTopDp + pageHeightDp * pos.y
                ).widthIn(max = 240.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = .15f)),
                style = TextStyle(textDirection = TextDirection.Content))
        }
    if (textPosition != null) AlertDialog(onDismissRequest = { textPosition = null },
        title = { Text("Текст на странице") },
        text = { OutlinedTextField(value = textEntry, onValueChange = { textEntry = it.take(500) },
            minLines = 2, maxLines = 6, label = { Text("Текст пометки") }) },
        confirmButton = { TextButton(enabled = textEntry.isNotBlank(), onClick = {
            val position = textPosition ?: return@TextButton
            strokes.add(InkStroke("text", inkColor, width.coerceAtLeast(.015f), listOf(position), textEntry))
            current.value()
            textPosition = null
        }) { Text("Добавить") } },
        dismissButton = { TextButton(onClick = { textPosition = null }) { Text("Отмена") } })
    }
}

/** A small overlay distinguishes the straightedge tool from a freehand marker. */
@Composable
private fun Q7HInkToolIcon(id: String, description: String, tint: Color) {
    // Keep the layer badge INSIDE the 42dp tool bounds. Never offset it outside
    // the parent: the old 30dp box clipped badges on narrow devices.
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        val icon = when(id) {
            "marker","marker_ruler","marker_single","marker_ruler_single","smart_marker" -> Icons.Default.FormatPaint
            "pen_ruler","pen" -> Icons.Default.Edit
            "cover" -> Icons.Default.BlurOn
            "text" -> Icons.Default.TextFields
            "box" -> Icons.Default.CropSquare
            "eraser" -> Icons.Default.AutoFixOff
            else -> Icons.Default.Edit
        }
        Icon(icon, description, tint=tint,
            modifier=Modifier.align(Alignment.BottomStart).padding(start=2.dp,bottom=2.dp).size(28.dp))
        if(id=="marker_single" || id=="marker_ruler_single") {
            Row(Modifier.align(Alignment.TopEnd)
                    .background(MaterialTheme.colorScheme.surface,RoundedCornerShape(3.dp))
                    .border(1.dp,MaterialTheme.colorScheme.outline,RoundedCornerShape(3.dp))
                    .padding(horizontal=1.dp),
                verticalAlignment=Alignment.CenterVertically) {
                Icon(Icons.Default.Layers,null,tint=tint,modifier=Modifier.size(12.dp))
                Text("1",color=tint,fontSize=11.sp,fontWeight=FontWeight.Bold,lineHeight=12.sp)
            }
        }
        if(id=="marker_ruler" || id=="marker_ruler_single" || id=="pen_ruler") {
            Icon(Icons.Default.Straighten,null,tint=tint,
                modifier=Modifier.align(Alignment.BottomEnd).size(13.dp)
                    .background(MaterialTheme.colorScheme.surface,RoundedCornerShape(2.dp)))
        }
    }
}

@Composable
private fun Q7HInkSwatch(c: InkPaletteColor, selected: Long, onColor: (Long) -> Unit) {
    val chosen = selected == c.argb
    Box(Modifier.padding(horizontal = 3.dp, vertical = 4.dp).size(25.dp)
        .background(Color(c.argb.toInt()), CircleShape)
        .border(if (chosen) 3.dp else 1.dp,
            if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            CircleShape)
        .clickable { onColor(c.argb) })
}

/** Compact palette: Tajweed colors first; then neutrals, purple and user colors. */
@Composable
private fun Q7HInkPalette(
    palette: List<InkPaletteColor>, pinned: Boolean,
    color: Long, onColor: (Long) -> Unit,
    onAdd: () -> Unit, onlyFirstRow: Boolean = false
) {
    val tajweed=palette.filter { it.tajweed }.sortedBy { it.sortOrder }
    val extras=listOf(
        InkPaletteColor(-101,0xFF202020L,"Чёрный",10000,false,false),
        InkPaletteColor(-102,0xFFFFFFFFL,"Белый",10001,false,false),
        InkPaletteColor(-103,0xFFD8C5A6L,"Бежевый",10002,false,false),
        InkPaletteColor(-104,0xFFAF72DCL,"Фиолетовый",10003,false,false)
    )
    val chosen=(extras + palette.filter { it.custom }.sortedBy { it.sortOrder })
        .distinctBy { it.argb }.filterNot { entry -> tajweed.any { it.argb==entry.argb } }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment=Alignment.CenterVertically) {
        tajweed.forEach { Q7HInkSwatch(it,color,onColor) }
        VerticalDivider(Modifier.height(30.dp).padding(horizontal=6.dp),
            thickness=1.dp,color=MaterialTheme.colorScheme.outlineVariant)
        chosen.forEach { Q7HInkSwatch(it,color,onColor) }
        IconButton(onClick=onAdd,modifier=Modifier.size(36.dp)) {
            Icon(Icons.Default.AddCircleOutline,"Добавить свой цвет через RGB")
        }
    }
}

/** Safe, movable reader palette. State and positions are owned/persisted by MainActivity. */
@Composable
internal fun InkToolbar(
    tool: String, onTool: (String) -> Unit,
    color: Long, onColor: (Long) -> Unit,
    thickness: Float, onThickness: (Float) -> Unit,
    palette: List<InkPaletteColor>, pinnedTajweed: Boolean,
    orderedTools: List<String>, smartPrefs: UserPreferences,
    onCreateColor: (Long, String) -> Unit,
    onSave: () -> Unit,
    onUndo: () -> Unit, onRedo: () -> Unit, onClear: () -> Unit,
    canUndo: Boolean, canRedo: Boolean, canClear: Boolean,
    onVerticalDrag: (Float) -> Unit,
    onHorizontalDrag: (Float) -> Unit,
    onDragFinished: () -> Unit,
    state: String,
    collapsedSlider: Boolean,
    collapsedTools: Boolean,
    collapsedTajweed: Boolean,
    doubleTapSquare: Boolean,
    longPressCompact: Boolean,
    onCollapse: () -> Unit,
    onMinimize: () -> Unit,
    maxPanelHeight: androidx.compose.ui.unit.Dp = 440.dp,
    modifier: Modifier = Modifier
) {
    var openInfo by remember { mutableStateOf(false) }
    var openSettings by remember { mutableStateOf(false) }
    var openRgb by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val onSurface = MaterialTheme.colorScheme.onSurface
    val dimmed = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .65f)
    // Listen at the SURFACE boundary, not on individual tool icons.
    // Compose cancels the parent gesture when an interactive child consumes
    // its pointer events. This leaves buttons, colours and the slider intact.
    val freeAreaTapGestures = if (doubleTapSquare || longPressCompact) Modifier.pointerInput(
        state, doubleTapSquare, longPressCompact
    ) {
        detectTapGestures(
            onDoubleTap = if (doubleTapSquare) ({ _ ->
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onMinimize()
            }) else null,
            onLongPress = if (longPressCompact) ({ _ ->
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onCollapse()
            }) else null
        )
    } else Modifier
    // Dragging on the handle and any free part of the surface never intercepts
    // a button press or a slider gesture; those child controls consume their input.
    val freeAreaDrag = Modifier.pointerInput(state) {
        detectDragGestures(
            onDragEnd = onDragFinished,
            onDragCancel = onDragFinished
        ) { change, drag ->
            change.consume()
            onVerticalDrag(with(density) { drag.y.toDp().value })
            onHorizontalDrag(with(density) { drag.x.toDp().value })
        }
    }
    // Two independent, tall grips prevent the top handle from becoming the
    // only way to retrieve a palette. Blank areas retain free-area dragging.
    val gripDrag = Modifier.pointerInput(state) {
        detectDragGestures(
            onDragEnd = onDragFinished,
            onDragCancel = onDragFinished
        ) { change, drag ->
            change.consume()
            onVerticalDrag(with(density) { drag.y.toDp().value })
            onHorizontalDrag(with(density) { drag.x.toDp().value })
        }
    }
    if(state=="square") {
        Surface(modifier=modifier.size(54.dp),shape=RoundedCornerShape(13.dp),
            color=MaterialTheme.colorScheme.surface,tonalElevation=8.dp,shadowElevation=4.dp) {
            Box(Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragCancel=onDragFinished,onDragEnd=onDragFinished
                    ) { change, drag ->
                        change.consume()
                        onVerticalDrag(with(density){ drag.y.toDp().value })
                        onHorizontalDrag(with(density){ drag.x.toDp().value })
                    }
                }
                .clickable(onClick=onMinimize),contentAlignment=Alignment.Center) {
                Icon(Icons.Default.OpenInFull,"Развернуть графическую панель")
            }
        }
        return
    }
    Surface(modifier=modifier.heightIn(max=maxPanelHeight)
            .then(freeAreaDrag).then(freeAreaTapGestures),
        shape=RoundedCornerShape(18.dp),
        color=MaterialTheme.colorScheme.surface,
        contentColor=onSurface, tonalElevation=8.dp, shadowElevation=4.dp
    ) {
        Column(Modifier.widthIn(max=340.dp).heightIn(max=maxPanelHeight)
                .padding(horizontal=6.dp,vertical=3.dp),
            verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth().height(30.dp),
                verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.SpaceBetween) {
                Box(Modifier.weight(1f).fillMaxHeight().then(gripDrag),
                    contentAlignment=Alignment.Center) {
                    Icon(Icons.Default.DragHandle,"Перетащить палитру (верхний захват)",
                        modifier=Modifier.size(30.dp),tint=onSurface)
                }
                IconButton(onClick=onMinimize, modifier=Modifier.size(30.dp)) {
                    Icon(Icons.Default.CloseFullscreen,"Свернуть до квадрата",Modifier.size(19.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.Center,
                verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick=onUndo,enabled=canUndo,modifier=Modifier.size(35.dp)) {
                    Icon(Icons.Default.Undo,"Отменить",tint=if(canUndo)onSurface else dimmed)
                }
                IconButton(onClick=onRedo,enabled=canRedo,modifier=Modifier.size(35.dp)) {
                    Icon(Icons.Default.Redo,"Повторить",tint=if(canRedo)onSurface else dimmed)
                }
                IconButton(onClick=onClear,enabled=canClear,modifier=Modifier.size(35.dp)) {
                    Icon(Icons.Default.DeleteSweep,"Удалить все пометки",tint=if(canClear)onSurface else dimmed)
                }
                IconButton(onClick=onSave,modifier=Modifier.size(35.dp)) {
                    Icon(Icons.Default.CheckCircle,"Сохранить изменения и завершить",
                        tint=MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick=onCollapse,modifier=Modifier.size(35.dp)) {
                    Icon(if(state=="collapsed") Icons.Default.UnfoldMore else Icons.Default.UnfoldLess,
                        if(state=="collapsed") "Развернуть палитру" else "Свернуть палитру")
                }
            }
            // Only the content between the two grips scrolls. Drag handles stay
            // in view even when a short phone or landscape viewport cannot fit
            // the entire set of tools, colors and thickness controls.
            Column(Modifier.fillMaxWidth().weight(1f, fill=false)
                .verticalScroll(rememberScrollState()),
                verticalArrangement=Arrangement.spacedBy(2.dp)) {
            if(state=="expanded" || collapsedTools) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.spacedBy(2.dp)) {
                    FilterChip(selected=tool=="smart_marker",onClick={onTool("smart_marker")},
                        label={Text("Умный выделитель аятов",fontSize=11.sp)},
                        leadingIcon={Icon(Icons.Default.AutoAwesome,null,Modifier.size(16.dp))})
                    Box {
                        IconButton(onClick={openInfo=true},modifier=Modifier.size(34.dp)) {
                            Icon(Icons.Outlined.Info,"Информация об умном выделителе",Modifier.size(19.dp))
                        }
                        DropdownMenu(expanded=openInfo,onDismissRequest={openInfo=false},
                            modifier=Modifier.widthIn(min=260.dp,max=320.dp)) {
                            Text("Дважды коснитесь аята, чтобы выделить его; повторное двойное " +
                                "касание снимает выделение. Долгое касание воспроизводит аят " +
                                "в общем плеере Тафсира. Два действия можно поменять местами " +
                                "в настройках. Дополнительные жесты с таймером необязательны.",
                                modifier=Modifier.padding(14.dp),style=MaterialTheme.typography.bodySmall)
                            TextButton(onClick={openInfo=false},modifier=Modifier.align(Alignment.End)) {
                                Text("Закрыть")
                            }
                        }
                    }
                    TextButton(onClick={openSettings=true},modifier=Modifier.height(36.dp),
                        contentPadding=PaddingValues(horizontal=4.dp)) {
                        Text("Настроить",fontSize=11.sp)
                    }
                }
            }
            if(state=="expanded" || collapsedTools) {
                // Tools are always horizontally scrollable, even with 11 IDs.
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment=Alignment.CenterVertically) {
                    orderedTools.filterNot { it == "smart_marker" }.forEach { id ->
                        IconToggleButton(checked=tool==id,onCheckedChange={onTool(id)},
                            modifier=Modifier.size(43.dp)) {
                            Q7HInkToolIcon(id,Q7HInkToolLabels.names[id]?:id,
                                if(tool==id)MaterialTheme.colorScheme.primary else onSurface)
                        }
                    }
                }
            }
            if(state=="expanded" || collapsedTajweed) {
                Q7HInkPalette(palette,pinnedTajweed,color,onColor,onAdd={openRgb=true})
            }
            if(state=="expanded" || collapsedSlider) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text("Толщина",style=MaterialTheme.typography.labelSmall)
                    Slider(value=thickness,onValueChange=onThickness,
                        valueRange=.002f..0.20f,
                        modifier=Modifier.weight(1f).padding(start=8.dp))
                }
            }
            } // end scrollable body; bottom handle is OUTSIDE the scroll area
            // Bottom grip remains reachable when the palette is tall or
            // the top row was previously placed near a screen edge.
            Box(Modifier.fillMaxWidth().height(30.dp).then(gripDrag),
                contentAlignment=Alignment.Center) {
                Icon(Icons.Default.DragHandle,
                    "Перетащить палитру за две полоски внизу",
                    modifier=Modifier.size(30.dp), tint=onSurface)
            }
        }
    }
    if(openSettings) AlertDialog(onDismissRequest={openSettings=false},
        title={Text("Умный выделитель аятов")},
        text={Column(Modifier.fillMaxWidth().heightIn(max=490.dp).verticalScroll(rememberScrollState())) {
            Q7HSmartMarkerSettings(smartPrefs)
        }},
        confirmButton={TextButton(onClick={openSettings=false}){Text("Готово")}})
    if(openRgb) Q7HInkColorEditor(initial=null,onDismiss={openRgb=false},
        onSave={value,meaning -> onCreateColor(value,meaning);openRgb=false})
}
