package raf.console.quran7hours.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import raf.console.quran7hours.data.QuranCatalog
import raf.console.quran7hours.data.QuranNavigationIndex
import raf.console.quran7hours.data.QuranNavigationPageSource
import raf.console.quran7hours.data.Q7HSurahTitles
import raf.console.quran7hours.data.LearningDatabase
import androidx.compose.ui.graphics.Color

/** Persistent, independent ayah bookmarks. Existing page bookmarks are not modified. */
internal object Q7HAyahBookmarks {
    fun contains(context: Context,surah:Int,ayah:Int) = Q7HAyahCategoryStore.contains(context,surah,ayah)
    fun list(context: Context) = Q7HAyahCategoryStore.list(context)
}

internal enum class Q7HVerseAction(val title: String) {
    BOOKMARK("Закладка"), NOTE("Заметка"), COPY("Скопировать аят"), SHARE("Поделиться аятом"),
    COPY_PART("Скопировать часть"), SHARE_PART("Поделиться частью")
}
internal object Q7HQuickAction {
    private const val KEY = "ayah_quick_action"
    fun get(context: Context): Q7HVerseAction {
        val name = context.getSharedPreferences("q7h_actions_settings",Context.MODE_PRIVATE).getString(KEY,null)
        return Q7HVerseAction.entries.firstOrNull { it.name == name } ?: Q7HVerseAction.BOOKMARK
    }
    fun set(context: Context, action: Q7HVerseAction) {
        context.getSharedPreferences("q7h_actions_settings",Context.MODE_PRIVATE)
            .edit().putString(KEY,action.name).apply()
    }
}

/** A single source for both the full and partial copy/share actions. */
internal data class Q7HVerseContent(
    val surah: Int, val ayah: Int,
    val arabic: String, val translation: String, val tafsir: String,
    val dictionary: String,
    val showArabic: Boolean, val showTranslation: Boolean,
    val showTafsir: Boolean, val showDictionary: Boolean,
    val order: List<String> = listOf("dictionary", "translation", "tafsir")
) {
    fun orderedIds(): List<String> = (listOf("arabic") + order + blocks().map {it.first}).distinct()
        .filter { id -> blocks().any {it.first == id} }
    fun blocks(): List<Pair<String,String>> = listOf(
        "arabic" to arabic, "dictionary" to dictionary,
        "translation" to translation, "tafsir" to tafsir
    ).filter { it.second.isNotBlank() }
    fun visible(): Set<String> = buildSet {
        if (showArabic) add("arabic")
        if (showDictionary) add("dictionary")
        if (showTranslation) add("translation")
        if (showTafsir) add("tafsir")
    }
    fun render(order: List<String>): String = buildString {
        append("Сура $surah, аят $ayah")
        for (id in order) {
            val value = blocks().firstOrNull { it.first == id }?.second.orEmpty()
            if (value.isNotBlank()) append("\n\n").append(value)
        }
    }
}
private fun Context.q7hCopy(text: String) {
    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
        ClipData.newPlainText("Коран",text))
}
private fun Context.q7hShare(text: String) {
    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT,text)
    },"Поделиться аятом"))
}

/** Quick action + popup of the other five. The Play button is hosted by the caller. */
@Composable
internal fun Q7HAyahActions(
    content: Q7HVerseContent,
    onNote: (Int,Int) -> Unit,
    database: LearningDatabase,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var bookmarkPopup by remember { mutableStateOf(false) }
    var composition by remember { mutableStateOf<Q7HVerseAction?>(null) }
    var quick by remember { mutableStateOf(Q7HQuickAction.get(context)) }
    val saved = Q7HAyahBookmarks.contains(context,content.surah,content.ayah)
    val bookmarkTypes = remember(database) { database.types() }
    val markIds = Q7HAyahCategoryStore.typeIds(context,content.surah,content.ayah)
    val primaryMark = bookmarkTypes.firstOrNull { it.id in markIds }
    fun execute(action: Q7HVerseAction) {
        expanded = false
        when(action) {
            Q7HVerseAction.BOOKMARK -> bookmarkPopup = true
            Q7HVerseAction.NOTE -> onNote(content.surah,content.ayah)
            Q7HVerseAction.COPY -> {
                val selected=content.orderedIds().filter {it in content.visible()}
                if(selected.isEmpty()) composition=Q7HVerseAction.COPY_PART
                else context.q7hCopy(content.render(selected))
            }
            Q7HVerseAction.SHARE -> {
                val selected=content.orderedIds().filter {it in content.visible()}
                if(selected.isEmpty()) composition=Q7HVerseAction.SHARE_PART
                else context.q7hShare(content.render(selected))
            }
            Q7HVerseAction.COPY_PART, Q7HVerseAction.SHARE_PART -> composition = action
        }
    }
    Row(modifier,verticalAlignment=Alignment.CenterVertically) {
        IconButton(onClick={execute(quick)}) {
            if(quick==Q7HVerseAction.BOOKMARK && primaryMark!=null) {
                LearningBookmarkIcon(primaryMark.icon,Color(primaryMark.color.toInt()))
            } else {
                Icon(when(quick) {
                    Q7HVerseAction.BOOKMARK -> if(saved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder
                    Q7HVerseAction.NOTE -> Icons.Default.NoteAdd
                    Q7HVerseAction.COPY, Q7HVerseAction.COPY_PART -> Icons.Default.ContentCopy
                    else -> Icons.Default.Share
                },contentDescription=quick.title)
            }
        }
        Box {
            IconButton(onClick={expanded=true}) { Icon(Icons.Default.MoreVert,"Действия с аятом") }
            DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
                Q7HVerseAction.entries.filter { it != quick }.forEach { action ->
                    DropdownMenuItem(text={Text(action.title)},onClick={execute(action)})
                }
            }
        }
    }
    Q7HAyahCategoryPopup(content.surah,content.ayah,bookmarkPopup,
        onDismiss={bookmarkPopup=false},database=database)
    if(composition!=null) Q7HVerseComposer(content,composition!!,onClose={composition=null})
}

@Composable
private fun Q7HVerseComposer(content: Q7HVerseContent, action: Q7HVerseAction, onClose:()->Unit) {
    val context = LocalContext.current
    val original = remember(content.surah, content.ayah, content.order) { content.blocks() }
    val order = remember(content.surah, content.ayah, content.order) {
        mutableStateListOf<String>().also { list -> list.addAll(content.orderedIds()) }
    }
    val selected = remember(content.surah,content.ayah) { mutableStateMapOf<String,Boolean>().also { map ->
        original.forEach { map[it.first] = it.first in content.visible() }
    } }
    val labels = mapOf("arabic" to "Арабский текст", "dictionary" to "Словарь",
        "translation" to "Перевод", "tafsir" to "Тафсир")
    val count = order.count { selected[it] == true }
    AlertDialog(
        onDismissRequest=onClose,
        title={Text("Выбрать элементы аята")},
        text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text("Сура ${content.surah}, аят ${content.ayah} — всегда включены.",style=MaterialTheme.typography.bodySmall)
            Text("Отметьте элементы и перетащите за шесть точек, чтобы изменить порядок.",style=MaterialTheme.typography.labelSmall)
            order.forEach { id ->
                var accumulated by remember(id) { mutableFloatStateOf(0f) }
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(checked=selected[id]==true,onCheckedChange={selected[id]=it})
                    Text(labels[id] ?: id, modifier=Modifier.weight(1f),fontSize=13.sp)
                    Icon(Icons.Default.DragIndicator,"Перетащить",
                        Modifier.size(35.dp).pointerInput(id) {
                            detectDragGesturesAfterLongPress(
                                onDragEnd={accumulated=0f},onDragCancel={accumulated=0f}
                            ) { change, dragAmount ->
                                change.consume(); accumulated += dragAmount.y
                                if(kotlin.math.abs(accumulated) > 35.dp.toPx()) {
                                    val from = order.indexOf(id)
                                    val to = (from + if(accumulated>0) 1 else -1).coerceIn(0,order.lastIndex)
                                    if(from>=0 && from!=to) { order.removeAt(from);order.add(to,id) }
                                    accumulated=0f
                                }
                            }
                        })
                }
            }
            if(count==0) Text("Выберите минимум один элемент помимо номера аята.",color=MaterialTheme.colorScheme.error)
        }},
        confirmButton={TextButton(enabled=count>0,onClick={
            val text=content.render(order.filter {selected[it]==true})
            if(action==Q7HVerseAction.COPY_PART) context.q7hCopy(text) else context.q7hShare(text)
            onClose()
        }){Text(if(action==Q7HVerseAction.COPY_PART)"Копировать" else "Поделиться")}},
        dismissButton={TextButton(onClick=onClose){Text("Отмена")}}
    )
}

@Composable
internal fun Q7HQuickActionSettings() {
    val context = LocalContext.current
    var quick by remember { mutableStateOf(Q7HQuickAction.get(context)) }
    var expanded by remember { mutableStateOf(false) }
    Text("Быстрое действие с аятом",style=MaterialTheme.typography.titleMedium)
    Box {
        OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth()) {
            Text(quick.title,modifier=Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown,null)
        }
        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
            Q7HVerseAction.entries.forEach { action -> DropdownMenuItem(
                text={Text(action.title)},onClick={quick=action;Q7HQuickAction.set(context,action);expanded=false}
            ) }
        }
    }
}

@Composable
internal fun Q7HAyahBookmarksDrawer(
    onOpen:(Int,Int)->Unit,
    onNote:(Int,Int)->Unit,
    database: LearningDatabase
) {
    val context = LocalContext.current
    val entries = Q7HAyahBookmarks.list(context)
    val types = remember(database) { database.types() }
    val pages = remember(context.applicationContext) {
        QuranNavigationPageSource(context.applicationContext)
    }
    Column(Modifier.fillMaxSize()) {
        Text("Закладки по аятам (${entries.size})",Modifier.padding(14.dp),style=MaterialTheme.typography.titleMedium)
        if(entries.isEmpty()) Text("Закладок по аятам пока нет.",Modifier.padding(14.dp))
        LazyColumn(modifier=Modifier.weight(1f),contentPadding=PaddingValues(10.dp)) {
            items(entries,key={"${it.first}:${it.second}"}) { (surah,ayah) ->
                val page by produceState<Int?>(null, surah, ayah) {
                    value=pages.pageOf(QuranNavigationIndex.Ref(surah,ayah))
                }
                val pos=remember(surah,ayah) {
                    QuranNavigationIndex.position(QuranNavigationIndex.Ref(surah,ayah))
                }
                ElevatedCard(Modifier.fillMaxWidth().padding(vertical=4.dp)) {
                    Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically) {
                        TextButton(onClick={onOpen(surah,ayah)},modifier=Modifier.weight(1f)) {
                            Column {
                                Text("Сура $surah. ${QuranCatalog.surahNames.getOrNull(surah-1).orEmpty()}")
                                Text(Q7HSurahTitles.translation(surah),style=MaterialTheme.typography.labelSmall)
                                Text("Аят $ayah · стр. ${page ?: "…"}",style=MaterialTheme.typography.labelSmall)
                                Text("Джуз ${pos.juz}, ${pos.juzQuarter}/4 · Хизб ${pos.hizb}, ${pos.hizbHalf}/2",
                                    style=MaterialTheme.typography.labelSmall)
                                page?.let { Text("Манзиль ${pages.v4.segmentForPage(QuranCatalog.Segment.MANZIL,it)}",
                                    style=MaterialTheme.typography.labelSmall) }
                            }
                        }
                        val selectedTypes = Q7HAyahCategoryStore.typeIds(context,surah,ayah)
                        types.filter { it.id in selectedTypes }.take(3).forEach { mark ->
                            LearningBookmarkIcon(mark.icon,Color(mark.color.toInt()),Modifier.size(21.dp))
                        }
                        IconButton(onClick={onNote(surah,ayah)}){Icon(Icons.Default.NoteAdd,"Заметка")}
                        IconButton(onClick={Q7HAyahCategoryStore.remove(context,surah,ayah)}) {
                            Icon(Icons.Default.DeleteOutline,"Удалить закладку, сохранить заметку")
                        }
                    }
                }
            }
        }
    }
}
