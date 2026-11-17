package raf.console.quran7hours.ui

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.session.MediaController
import raf.console.quran7hours.data.BookmarkType
import raf.console.quran7hours.data.LearningDatabase
import raf.console.quran7hours.data.UserPreferences
import raf.console.quran7hours.media.Q7HRangeState
import raf.console.quran7hours.media.AudioChannel
import kotlin.math.abs

/** Q7H_PATCH_20261115: reading preferences kept outside the Room schema. */
internal object Q7HReaderOptions {
    private const val NAME = "q7h_reader_addons_20261115"
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    var bookmarkTarget by mutableStateOf(SheetSection.TAFSIR)
        private set
    var topClose by mutableStateOf(true)
        private set
    var bottomClose by mutableStateOf(true)
        private set
    private var loaded = false
    fun load(ctx: Context) {
        if (loaded) return
        val prefs = p(ctx)
        bookmarkTarget = if (prefs.getString("bookmark_destination", "TAFSIR") == "HAFIZ") SheetSection.HAFIZ else SheetSection.TAFSIR
        topClose = prefs.getBoolean("top_close", true)
        bottomClose = prefs.getBoolean("bottom_close", true)
        if (!topClose && !bottomClose) bottomClose = true
        loaded = true
    }
    fun setBookmarkTarget(ctx: Context, value: SheetSection) {
        bookmarkTarget = if (value == SheetSection.HAFIZ) SheetSection.HAFIZ else SheetSection.TAFSIR
        p(ctx).edit().putString("bookmark_destination", bookmarkTarget.name).apply()
    }
    fun setTopClose(ctx: Context, visible: Boolean) {
        topClose = visible
        if (!visible) bottomClose = true
        p(ctx).edit().putBoolean("top_close", topClose).putBoolean("bottom_close", bottomClose).apply()
    }
    fun setBottomClose(ctx: Context, visible: Boolean) {
        bottomClose = visible
        if (!visible) topClose = true
        p(ctx).edit().putBoolean("top_close", topClose).putBoolean("bottom_close", bottomClose).apply()
    }
}

/** Scroll request stays alive while a new page/QPC font is loading. */
data class Q7HAyahJump(val surah: Int, val ayah: Int, val section: SheetSection, val revision: Int)
internal object Q7HAyahJumpBus {
    var pending by mutableStateOf<Q7HAyahJump?>(null)
        private set
    private var serial = 0
    fun open(surah: Int, ayah: Int, section: SheetSection) {
        serial++
        pending = Q7HAyahJump(surah, ayah, section, serial)
    }
    fun finish(request: Q7HAyahJump) {
        if (pending == request) pending = null
    }
}

/** Legacy keys are never deleted during v2 upgrade; category IDs live in a sidecar. */
internal object Q7HAyahCategoryStore {
    private const val OLD = "q7h_ayah_bookmarks_v1"
    private const val NEW = "q7h_ayah_bookmarks_categories_v2"
    // Each ayah, like a Mushaf page, can have several independent categories.
    private val entries = mutableStateMapOf<String, Set<Long>>()
    private var loaded = false
    private fun key(s: Int, a: Int) = "$s:$a"
    @Synchronized private fun load(ctx: Context) {
        if (loaded) return
        val old = ctx.applicationContext.getSharedPreferences(OLD, Context.MODE_PRIVATE)
            .getStringSet("keys", emptySet()).orEmpty()
        val props = ctx.applicationContext.getSharedPreferences(NEW, Context.MODE_PRIVATE)
        val all = old + props.all.keys
        for (item in all) {
            val s = item.substringBefore(':').toIntOrNull()
            val a = item.substringAfter(':').toIntOrNull()
            if (s == null || a == null || !raf.console.quran7hours.data.QuranCatalog.isValidAyah(s,a)) continue
            val raw = props.all[item]
            val ids: Set<Long> = when (raw) {
                is Long -> setOf(raw).filter { it > 0L }.toSet()
                is String -> raw.split(',').mapNotNull { it.toLongOrNull() }.filter { it > 0L }.toSet()
                else -> emptySet() // Old bookmarks remain present until given a color.
            }
            entries[item] = ids
        }
        loaded = true
    }
    fun contains(ctx: Context, s: Int, a: Int): Boolean { load(ctx); return entries.containsKey(key(s,a)) }
    fun typeIds(ctx: Context, s: Int, a: Int): Set<Long> { load(ctx); return entries[key(s,a)].orEmpty() }
    fun list(ctx: Context): List<Pair<Int,Int>> {
        load(ctx)
        return entries.keys.mapNotNull { k ->
            val s = k.substringBefore(':').toIntOrNull()
            val a = k.substringAfter(':').toIntOrNull()
            if (s != null && a != null) s to a else null
        }.sortedWith(compareBy({ it.first }, { it.second }))
    }
    private fun persist(ctx: Context) {
        ctx.applicationContext.getSharedPreferences(OLD,Context.MODE_PRIVATE).edit()
            .putStringSet("keys",entries.keys.toSet()).apply()
        val editor = ctx.applicationContext.getSharedPreferences(NEW,Context.MODE_PRIVATE).edit().clear()
        entries.forEach { (name, ids) -> editor.putString(name,ids.sorted().joinToString(",")) }
        editor.apply()
    }
    fun assign(ctx: Context, s: Int, a: Int, typeId: Long) {
        load(ctx)
        require(typeId > 0L)
        val k = key(s,a)
        val updated = entries[k].orEmpty().toMutableSet()
        if (!updated.add(typeId)) updated.remove(typeId)
        if (updated.isEmpty()) entries.remove(k) else entries[k] = updated.toSet()
        persist(ctx)
    }
    fun remove(ctx: Context, s: Int, a: Int) {
        load(ctx)
        entries.remove(key(s,a))
        persist(ctx)
    }
    fun removeType(ctx: Context, id: Long) {
        load(ctx)
        entries.keys.toList().forEach { k ->
            val updated = entries[k].orEmpty() - id
            if (updated.isEmpty()) entries.remove(k) else entries[k] = updated
        }
        persist(ctx)
    }
}

/** Same category records and the SAME editor as page bookmarks; no duplicate palette. */
@Composable
internal fun Q7HAyahCategoryPopup(
    surah: Int, ayah: Int, expanded: Boolean, onDismiss: () -> Unit,
    database: LearningDatabase
) {
    val context = LocalContext.current
    var types by remember(database, expanded) {
        mutableStateOf(if(expanded) database.types() else emptyList())
    }
    var editing by remember { mutableStateOf<BookmarkType?>(null) }
    var creating by remember { mutableStateOf(false) }
    val selected = Q7HAyahCategoryStore.typeIds(context,surah,ayah)
    LearningBookmarkMenu(
        expanded=expanded, page=0, types=types, bookmarks=emptyList(),
        selectedTypeIds=selected, showDeleteOverride=Q7HAyahCategoryStore.contains(context,surah,ayah),
        titleOverride="Закладки · аят $surah:$ayah",
        onDismiss=onDismiss,
        onToggle={ type -> Q7HAyahCategoryStore.assign(context,surah,ayah,type.id);onDismiss() },
        onEdit={ editing=it;onDismiss() },
        onCreate={ creating=true;onDismiss() },
        onRemove={ Q7HAyahCategoryStore.remove(context,surah,ayah);onDismiss() }
    )
    if (creating || editing != null) {
        val old = editing
        BookmarkTypeEditor(
            initial=old, onDismiss={ creating=false; editing=null },
            onSave={name,purpose,color, icon->
                val id=database.saveType(old?.id,name,purpose,color,icon)
                types=database.types()
                if(old==null) Q7HAyahCategoryStore.assign(context,surah,ayah,id)
                creating=false;editing=null
            },
            onDelete=old?.takeIf{it.custom}?.let{item-> ({
                Q7HAyahCategoryStore.removeType(context,item.id)
                database.deleteCustomType(item.id)
                types=database.types()
                editing=null
            })}
        )
    }
}

@Composable
internal fun Q7HAdditionalReaderSettings() {
    val ctx = LocalContext.current
    Q7HReaderOptions.load(ctx)
    HorizontalDivider()
    Text("Навигация и закладки", style = MaterialTheme.typography.titleMedium)
    Text("Открывать закладки по аятам в:")
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        FilterChip(selected=Q7HReaderOptions.bookmarkTarget==SheetSection.TAFSIR,
            onClick={Q7HReaderOptions.setBookmarkTarget(ctx,SheetSection.TAFSIR)},label={Text("Тафсире")})
        FilterChip(selected=Q7HReaderOptions.bookmarkTarget==SheetSection.HAFIZ,
            onClick={Q7HReaderOptions.setBookmarkTarget(ctx,SheetSection.HAFIZ)},label={Text("Дневнике хафиза")})
    }
    Q7HBottomNavigationSettings()
    if (!Q7HBottomNavSettings.enabled) {
    Text("Кнопки закрытия панели", style=MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
        Text("Верхний крестик",Modifier.weight(1f))
        Switch(checked=Q7HReaderOptions.topClose,onCheckedChange={Q7HReaderOptions.setTopClose(ctx,it)})
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
        Text("Нижняя стрелка",Modifier.weight(1f))
        Switch(checked=Q7HReaderOptions.bottomClose,onCheckedChange={Q7HReaderOptions.setBottomClose(ctx,it)})
    }
    Text("Одну кнопку можно скрыть; одновременно скрыть обе нельзя.",style=MaterialTheme.typography.bodySmall)
    }
}

/** Page navigation remains available above the mini player / fingerprint dock.
 *  Long press is intentionally REMOVED from the page number: it is reserved for
 *  the fingerprint button, which is present in all four sections.
 */
@Composable
internal fun Q7HPanelFooter(
    page: Int,
    section: SheetSection,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onNavigate: () -> Unit,
    onClose: () -> Unit,
    onSection: (SheetSection) -> Unit
) {
    val context=LocalContext.current
    Q7HBottomNavSettings.load(context)
    Q7HReaderOptions.load(context)
    Box(Modifier.fillMaxWidth().height(50.dp)) {
        Row(Modifier.align(Alignment.Center), verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onNext,enabled=page<604) {
                Icon(Icons.Default.ChevronLeft,"Следующая страница")
            }
            Text(page.toString(),
                modifier=Modifier.widthIn(min=70.dp)
                    .clickable { onNavigate() }
                    .padding(horizontal=15.dp,vertical=9.dp),
                textAlign=TextAlign.Center,
                style=MaterialTheme.typography.titleLarge)
            IconButton(onClick=onPrevious,enabled=page>1) {
                Icon(Icons.Default.ChevronRight,"Предыдущая страница")
            }
        }
        if(!Q7HBottomNavSettings.enabled && Q7HReaderOptions.bottomClose) {
            IconButton(onClick=onClose,modifier=Modifier.align(Alignment.CenterEnd)) {
                Icon(Icons.Default.KeyboardArrowDown,"Свернуть панель")
            }
        }
    }
}

/** Only the section where the range was started owns its secondary slider. */
internal object Q7HRangeOverlay {
    var owner by mutableStateOf<SheetSection?>(null)
    var hidden by mutableStateOf(false)
    fun start(section: SheetSection) { owner=section;hidden=false }
    fun close(context:Context, channel:AudioChannel, controller:MediaController?) {
        Q7HRangeState.clearAyah(context,channel)
        controller?.stop()
        hidden=true;owner=null
    }
}

@Composable
internal fun Q7HRangeBelowPlayer(section: SheetSection, playback: PlaybackSnapshot, controller: MediaController?) {
    val context=LocalContext.current
    val channel=if(section==SheetSection.HAFIZ && UserPreferences(context).separateHafizPlayer)
        AudioChannel.HAFIZ else AudioChannel.AYAH
    if (Q7HRangeOverlay.hidden || Q7HRangeOverlay.owner != section ||
        Q7HRangeState.ayahRange(context,channel)==null || !playback.mediaId.startsWith("ayah:")) return
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { Q7HAyahSecondaryRangeSlider(playback,channel) }
        IconButton(onClick={Q7HRangeOverlay.close(context,channel,controller)},enabled=controller!=null) {
            Icon(Icons.Default.Close,"Закрыть отрезок и остановить аудио")
        }
    }
}

/** Selection lives in Android's native text-selection popup; this action can quote the copied selection. */
@Composable
internal fun Q7HQuoteAction(surah: Int, ayah: Int, text: String, onQuote: (Int,Int,String)->Unit) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick={open=true}) { Icon(Icons.Default.FormatQuote,null,Modifier.size(17.dp));Text("Цитировать",fontSize=11.sp) }
        DropdownMenu(expanded=open,onDismissRequest={open=false}) {
            DropdownMenuItem(text={Text("Этот фрагмент целиком")},onClick={open=false;onQuote(surah,ayah,text)})
            DropdownMenuItem(text={Text("Выделенное и скопированное")},onClick={
                open=false
                val clipboard=ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val selected=clipboard.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                onQuote(surah,ayah,selected.takeIf { it.isNotBlank() && text.contains(it) } ?: text)
            })
        }
    }
}
