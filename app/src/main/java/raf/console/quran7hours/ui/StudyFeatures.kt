package raf.console.quran7hours.ui

import android.graphics.Bitmap
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
    val art = when (icon) {
        "headphones" -> Icons.Default.Headphones
        "school" -> Icons.Default.School
        "menu_book" -> Icons.Default.MenuBook
        "psychology" -> Icons.Default.Psychology
        "repeat" -> Icons.Default.Repeat
        else -> Icons.Default.Bookmark
    }
    Icon(art, contentDescription = null, tint = tint, modifier = modifier)
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
    onRemove: () -> Unit
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
                        "Закладки · страница $page",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                    )
                    // The delete action remains first, before the color choices.
                    // Do not show it when the current page has no bookmarks.
                    if (bookmarks.any { it.page == page }) {
                        DropdownMenuItem(
                            text = { Text("Удалить закладку") },
                            leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null) },
                            onClick = onRemove
                        )
                        HorizontalDivider()
                    }
                    types.forEachIndexed { index, type ->
                        if (index == 8) {
                            HorizontalDivider()
                            Text(
                                "Назначения",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        val selected = bookmarks.any { it.page == page && it.typeId == type.id }
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
                        text = { Text("Свой цвет и закладка") },
                        leadingIcon = { Icon(Icons.Default.AddCircleOutline, contentDescription = null) },
                        onClick = onCreate
                    )
                }
            }
        }
    }
}

@Composable
internal fun BookmarkTypeEditor(
    initial: BookmarkType?,
    onDismiss: () -> Unit,
    onSave: (String, String, Long) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name ?: "") }
    var purpose by remember(initial?.id) { mutableStateOf(initial?.purpose ?: "") }
    val raw = initial?.color ?: 0xFF467FDEL
    var r by remember(initial?.id) { mutableFloatStateOf(((raw shr 16) and 255).toFloat()) }
    var g by remember(initial?.id) { mutableFloatStateOf(((raw shr 8) and 255).toFloat()) }
    var b by remember(initial?.id) { mutableFloatStateOf((raw and 255).toFloat()) }
    val color = 0xFF000000L or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
    AlertDialog(onDismissRequest = onDismiss, title = {
        Text(if (initial == null) "Новая закладка" else "Назначение закладки")
    }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(value = name, onValueChange = { name = it.take(60) },
                label = { Text("Название") }, singleLine = true)
            OutlinedTextField(value = purpose, onValueChange = { purpose = it.take(280) },
                label = { Text("Для чего использую") }, minLines = 2, maxLines = 4,
                placeholder = { Text("Например, ежедневный вирд") })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).background(Color(color.toInt()), CircleShape))
                Spacer(Modifier.width(12.dp))
                Text("#%06X".format(color and 0xFFFFFFL))
            }
            listOf(Triple("Красный", r, 0), Triple("Зелёный", g, 1), Triple("Синий", b, 2))
                .forEach { (label, value, channel) ->
                    Text(label, style = MaterialTheme.typography.labelSmall)
                    Slider(value = value, onValueChange = {
                        when (channel) { 0 -> r = it; 1 -> g = it; else -> b = it }
                    }, valueRange = 0f..255f)
                }
            if (onDelete != null) TextButton(onClick = onDelete) { Text("Удалить свою категорию") }
        }
    }, confirmButton = {
        TextButton(onClick = { onSave(name, purpose, color) }, enabled = name.isNotBlank()) {
            Text("Сохранить")
        }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
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
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    val verses by produceState<List<TafsirStore.Verse>?>(null, page) { value = tafsir.page(page) }
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
                HafizVerseCard(verse, db)
            }
            }
        }
    }
}

@Composable
private fun HafizVerseCard(verse: TafsirStore.Verse, db: LearningDatabase) {
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
            Text(verse.arabic, fontSize = 25.sp, lineHeight = 40.sp,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Right,
                style = TextStyle(textDirection = TextDirection.Rtl))
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

/** Brush strokes remain in image-relative coordinates, never baked into Qur'an WebP. */
@Composable
internal fun InkPageLayer(
    bitmap: Bitmap,
    strokes: SnapshotStateList<InkStroke>,
    tool: String,
    inkColor: Long,
    width: Float,
    onChanged: () -> Unit,
    editable: Boolean,
    coverColor: Color,
    modifier: Modifier = Modifier
) {
    val active = remember(strokes) { mutableStateListOf<InkPoint>() }
    var inProgress by remember(strokes) { mutableStateOf(false) }
    var textPosition by remember(strokes) { mutableStateOf<InkPoint?>(null) }
    var textEntry by remember(strokes) { mutableStateOf("") }
    val current = rememberUpdatedState(onChanged)
    val drawInput = if (editable) Modifier.pointerInput(bitmap, strokes, tool, inkColor, width) {
        fun point(offset: Offset): InkPoint? {
            val fit = min(size.width / bitmap.width.toFloat(), size.height / bitmap.height.toFloat())
            val w = bitmap.width * fit; val h = bitmap.height * fit
            val left = (size.width - w) / 2f; val top = (size.height - h) / 2f
            if (offset.x < left || offset.x > left + w || offset.y < top || offset.y > top + h) return null
            return InkPoint(((offset.x - left) / w).coerceIn(0f, 1f),
                ((offset.y - top) / h).coerceIn(0f, 1f))
        }
        fun erase(point: InkPoint) {
            val candidate = strokes.indexOfLast { stroke ->
                stroke.points.any { p ->
                    hypot((p.x - point.x).toDouble(), (p.y - point.y).toDouble()) <
                        (stroke.width.coerceAtLeast(.012f) * 1.3f).toDouble()
                }
            }
            if (candidate >= 0) { strokes.removeAt(candidate); current.value() }
        }
        detectDragGestures(
            onDragStart = { offset ->
                active.clear()
                point(offset)?.let { p ->
                    if (tool == "eraser") erase(p)
                    else if (tool != "text") active.add(p)
                }
                inProgress = true
            },
            onDrag = { change, _ ->
                change.consume()
                point(change.position)?.let { p ->
                    if (tool == "eraser") erase(p)
                    else if (inProgress && tool != "text") active.add(p)
                }
            },
            onDragCancel = { active.clear(); inProgress = false },
            onDragEnd = {
                if (tool != "eraser" && tool != "text" && active.isNotEmpty()) {
                    strokes.add(InkStroke(tool, inkColor, width, active.toList()))
                    current.value()
                }
                active.clear(); inProgress = false
            }
        )
    } else Modifier
    val tapInput = if (editable) Modifier.pointerInput(bitmap, strokes, tool, inkColor, width) {
        detectTapGestures { offset ->
            val fit = min(size.width / bitmap.width.toFloat(), size.height / bitmap.height.toFloat())
            val w = bitmap.width * fit; val h = bitmap.height * fit
            val left = (size.width - w) / 2f; val top = (size.height - h) / 2f
            if (offset.x in left..(left+w) && offset.y in top..(top+h)) {
                val point = InkPoint((offset.x-left)/w, (offset.y-top)/h)
                if (tool == "text") {
                    textEntry = ""
                    textPosition = point
                } else if (tool == "eraser") {
                    val target = strokes.indexOfLast { item -> item.points.any { p ->
                        hypot((p.x-point.x).toDouble(), (p.y-point.y).toDouble()) <
                            (item.width.coerceAtLeast(.02f)*1.4f).toDouble()
                    } }
                    if (target >= 0) { strokes.removeAt(target); current.value() }
                } else {
                    strokes.add(InkStroke(tool, inkColor, width, listOf(point)))
                    current.value()
                }
            }
        }
    } else Modifier
    BoxWithConstraints(modifier.fillMaxSize()) {
    Canvas(Modifier.fillMaxSize().then(drawInput).then(tapInput)) {
        val fit = min(size.width / bitmap.width, size.height / bitmap.height)
        val w = bitmap.width * fit; val h = bitmap.height * fit
        val left = (size.width - w) / 2f; val top = (size.height - h) / 2f
        fun render(stroke: InkStroke) {
            val pts = stroke.points
            if (pts.isEmpty() || stroke.tool == "text") return
            val col = (if (stroke.tool == "cover") coverColor else Color(stroke.color.toInt())).copy(alpha = when (stroke.tool) {
                "marker" -> .32f
                "cover" -> .97f
                else -> 1f
            })
            val sw = (stroke.width * w).coerceAtLeast(2f)
            if (stroke.tool == "box" && pts.size >= 2) {
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
                drawPath(path, col, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        strokes.forEach(::render)
        if (inProgress && active.isNotEmpty()) render(InkStroke(tool, inkColor, width, active.toList()))
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

@Composable
internal fun InkToolbar(
    tool: String, onTool: (String) -> Unit,
    color: Long, onColor: (Long) -> Unit,
    thickness: Float, onThickness: (Float) -> Unit,
    onUndo: () -> Unit, onRedo: () -> Unit, onClear: () -> Unit,
    canUndo: Boolean, canRedo: Boolean, canClear: Boolean,
    onVerticalDrag: (Float) -> Unit,
    onDragFinished: () -> Unit
) {
    val density = LocalDensity.current
    val onSurface = MaterialTheme.colorScheme.onSurface
    val dimmed = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .65f)
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
        contentColor = onSurface, tonalElevation = 8.dp, shadowElevation = 4.dp) {
        Column(Modifier.widthIn(max = 380.dp).padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.fillMaxWidth().height(28.dp)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, drag ->
                            change.consume()
                            onVerticalDrag(with(density) { drag.toDp().value })
                        },
                        onDragEnd = onDragFinished,
                        onDragCancel = onDragFinished
                    )
                }, contentAlignment = Alignment.Center) {
                Icon(Icons.Default.DragHandle, "Переместить панель вверх или вниз", tint = onSurface)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                listOf(Triple("pen", "Ручка", Icons.Default.Edit),
                    Triple("marker", "Маркер", Icons.Default.BorderColor),
                    Triple("cover", "Скрыть", Icons.Default.BlurOn),
                    Triple("text", "Текст", Icons.Default.TextFields),
                    Triple("box", "Рамка", Icons.Default.CropSquare),
                    Triple("eraser", "Ластик", Icons.Default.AutoFixOff)).forEach { (key, label, icon) ->
                    IconToggleButton(checked = tool == key, onCheckedChange = { onTool(key) }) {
                        Icon(icon, label,
                            tint = if (tool == key) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface)
                    }
                }
                IconButton(onClick = onUndo, enabled = canUndo) {
                    Icon(Icons.Default.Undo, "Отменить", tint = if (canUndo) onSurface else dimmed)
                }
                IconButton(onClick = onRedo, enabled = canRedo) {
                    Icon(Icons.Default.Redo, "Повторить", tint = if (canRedo) onSurface else dimmed)
                }
                IconButton(onClick = onClear, enabled = canClear) {
                    Icon(Icons.Default.DeleteSweep, "Удалить все пометки", tint = if (canClear) onSurface else dimmed)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                listOf(0xFFE64F4FL,0xFFFFC848L,0xFF4EA6DAL,0xFF4DAD74L,0xFFAF72DCL,
                    0xFF202020L,0xFFFFFFFFL).forEach { c ->
                    Box(Modifier.padding(horizontal = 3.dp).size(22.dp)
                        .background(Color(c.toInt()), CircleShape)
                        .then(if (color == c) Modifier.border(2.dp,
                            MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
                        .pointerInput(c) {
                            detectTapGestures(onTap = { onColor(c) })
                        })
                }
                Slider(value = thickness, onValueChange = onThickness,
                    valueRange = .002f..0.20f, modifier = Modifier.weight(1f).padding(start = 8.dp))
            }
        }
    }
}
