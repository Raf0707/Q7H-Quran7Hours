package raf.console.quran7hours.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.session.MediaController
import raf.console.quran7hours.data.MushafStore
import raf.console.quran7hours.data.QuranCatalog
import raf.console.quran7hours.data.TafsirStore
import raf.console.quran7hours.data.WordByWordStore
import raf.console.quran7hours.data.UserPreferences
import raf.console.quran7hours.data.AqidahVerseNotes
import raf.console.quran7hours.data.TimingCatalog
import raf.console.quran7hours.media.AyahEndMode
import raf.console.quran7hours.media.Reciter
import raf.console.quran7hours.media.PrefacePart
import kotlin.math.roundToLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

private fun timeLabel(ms: Long): String {
    val seconds = ms.coerceAtLeast(0L) / 1000L
    return if (seconds >= 3600L) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%02d:%02d".format(seconds / 60, seconds % 60)
}

/** Hadr-only chapter navigation with a single fixed-speed MP3 mini player. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioPanel(
    category: QuranCatalog.Segment,
    onCategory: (QuranCatalog.Segment) -> Unit,
    prefs: UserPreferences,
    playback: PlaybackSnapshot,
    controller: MediaController?,
    timings: TimingCatalog,
    activeSurah: Int?,
    currentPage: Int,
    onPlay: () -> Unit,
    onJump: (QuranCatalog.Segment, Int) -> Unit
) {
    // No auto-scroll on playback updates: never override the user's reading position.
    val marks = remember(category, timings) { timings.marks(category) }
    val viewportKey = remember(category) { "audio_${category.name.lowercase()}" }
    val listState = remember(category) {
        val (index, offset) = prefs.panelViewport(viewportKey)
        LazyListState(index.coerceIn(0, (marks.size - 1).coerceAtLeast(0)), offset)
    }
    PersistLazyViewport(listState, prefs, viewportKey)
    val timedActive = if (playback.mediaId == "hadr") timings.at(category, playback.positionMs) else null
    val active = timedActive ?: QuranCatalog.segmentForPage(category, currentPage)

    Column(modifier = Modifier.fillMaxSize()) {
        Text("Коран за 7 часов", style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 8.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            QuranCatalog.Segment.entries.forEachIndexed { index, value ->
                SegmentedButton(
                    selected = category == value,
                    onClick = { onCategory(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, 5),
                    modifier = Modifier.weight(1f), icon = {}
                ) { Text(value.title, fontSize = 10.sp, maxLines = 1, softWrap = false) }
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp),
            contentPadding = PaddingValues(bottom = 12.dp)
        ) {
            items(items = marks, key = { "${category.name}:${it.number}" }) { mark ->
                val number = mark.number
                val time = mark.positionMs
                val selected = active == number
                Surface(
                    onClick = { if (time != null) onJump(category, number) },
                    enabled = time != null,
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(number.toString(), modifier = Modifier.width(48.dp),
                            fontSize = 14.sp,
                            maxLines = 1, softWrap = false, textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                        Text(QuranCatalog.label(category, number), modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium)
                        if (time != null) {
                            Text(timeLabel(time), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary)
                        } else {
                            Text("—", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (selected && timedActive == number) Icon(Icons.Default.GraphicEq,
                            "Текущий раздел", modifier = Modifier.size(17.dp),
                            tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        HadrMiniPlayer(playback, controller, timings, activeSurah, currentPage, onPlay)
    }
}

@Composable
private fun HadrMiniPlayer(
    playback: PlaybackSnapshot,
    controller: MediaController?,
    timings: TimingCatalog,
    activeSurah: Int?,
    currentPage: Int,
    onPlay: () -> Unit
) {
    var dragging by remember(playback.mediaId) { mutableStateOf(false) }
    var proposed by remember(playback.mediaId) { mutableFloatStateOf(0f) }
    val duration = playback.durationMs
    val ready = playback.mediaId == "hadr" && duration > 0L && playback.seekable && playback.error == null
    val fraction = if (duration > 0L) (playback.positionMs.toDouble() / duration)
        .toFloat().coerceIn(0f, 1f) else 0f
    val listening = playback.mediaId == "hadr"
    fun current(segment: QuranCatalog.Segment): Int? =
        if (listening) timings.at(segment, playback.positionMs) else null

    val surah = current(QuranCatalog.Segment.SURAH) ?: activeSurah
    val page = current(QuranCatalog.Segment.PAGE) ?: currentPage
    val juz = current(QuranCatalog.Segment.JUZ)
    val hizb = current(QuranCatalog.Segment.HIZB)
    val manzil = current(QuranCatalog.Segment.MANZIL)

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        if (playback.error != null) {
            Text(playback.error, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error, maxLines = 3)
        }
        Text(
            "Сура ${surah ?: "—"} · Страница $page · Джуз ${juz ?: "—"} · Хизб ${hizb ?: "—"} · Манзиль ${manzil ?: "—"}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(timeLabel(if (dragging) raf.console.quran7hours.media.HadrSeekPolicy.fromFraction(proposed, duration)
                else playback.positionMs), style = MaterialTheme.typography.labelSmall)
            Slider(
                value = if (dragging) proposed else fraction,
                onValueChange = { proposed = it; dragging = true },
                onValueChangeFinished = {
                    if (ready) controller?.seekTo(
                        raf.console.quran7hours.media.HadrSeekPolicy.fromFraction(proposed, duration)
                    )
                    dragging = false
                },
                enabled = ready,
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp)
            )
            Text(timeLabel(duration), style = MaterialTheme.typography.labelSmall)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (ready) controller?.seekTo(0L) }, enabled = ready) {
                Icon(Icons.Default.FirstPage, "В начало записи")
            }
            IconButton(onClick = {
                if (ready) controller?.seekTo(
                    raf.console.quran7hours.media.HadrSeekPolicy.step(playback.positionMs, -10_000L, duration))
            }, enabled = ready) { Icon(Icons.Default.Replay10, "Назад на 10 секунд") }
            FilledIconButton(onClick = onPlay, enabled = controller != null) {
                Icon(if (playback.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (playback.playing) "Пауза" else "Воспроизвести")
            }
            IconButton(onClick = {
                if (ready) controller?.seekTo(
                    raf.console.quran7hours.media.HadrSeekPolicy.step(playback.positionMs, 10_000L, duration))
            }, enabled = ready) { Icon(Icons.Default.Forward10, "Вперёд на 10 секунд") }
            IconButton(onClick = { if (ready) controller?.seekTo(
                raf.console.quran7hours.media.HadrSeekPolicy.clampMs(duration, duration))
            }, enabled = ready) { Icon(Icons.Default.LastPage, "К концу записи") }
        }
        if (playback.error != null) {
            Text("Ошибка чтения MP3: ${playback.error}", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = onPlay) { Text("Повторить попытку") }
        }
    }
}

/** Azan.ru tafsir keeps the same rich text design, with independent inline Ayah audio. */
@Composable
fun TafsirPanel(
    prefs: UserPreferences,
    page: Int,
    store: TafsirStore,
    wordStore: WordByWordStore,
    playback: PlaybackSnapshot,
    controller: MediaController?,
    reciters: List<Reciter>,
    reciterError: String,
    selectedReciter: String,
    onReciter: (String) -> Unit,
    endMode: AyahEndMode,
    onEndMode: (AyahEndMode) -> Unit,
    onPlayVerse: (Int, Int) -> Unit,
    onPlayPreface: (PrefacePart) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    val verses by produceState<List<TafsirStore.Verse>?>(null, page) {
        value = store.page(page)
    }
    // No expensive per-word parsing on the main/UI thread. The reader caches per-surah JSON.
    val dictionaries by produceState<Map<Pair<Int, Int>, List<WordByWordStore.Word>>>(
        emptyMap(), page, verses
    ) {
        value = verses?.let { wordStore.forPage(page, it) }.orEmpty()
    }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        // Quran navigation: next page is to the LEFT, previous page to the RIGHT.
        // Keep buttons apart so they cannot be accidentally pressed together.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onNext, enabled = page < 604) {
                Icon(Icons.Default.ChevronLeft, "Следующая страница")
            }
            Text("Тафсир · страница $page", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            IconButton(onClick = onPrevious, enabled = page > 1) {
                Icon(Icons.Default.ChevronRight, "Предыдущая страница")
            }
        }
        val loadedVerses = verses
        if (loadedVerses == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
        // Critical: create the LazyList only AFTER the JSON has loaded. An empty
        // placeholder would otherwise clamp a restored index back to zero.
        val viewportKey = "tafsir_$page"
        val listState = remember(page) {
            val (index, offset) = prefs.panelViewport(viewportKey)
            LazyListState(index.coerceIn(0, (loadedVerses.size - 1 + if (page == 1) 1 else 0).coerceAtLeast(0)), offset)
        }
        PersistLazyViewport(listState, prefs, viewportKey)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            if (page == 1) {
                item(key = "istiaza-basmala") {
                    IstiazaBasmalaCard(
                        onPlay = onPlayPreface,
                        playing = PrefacePart.entries.firstOrNull { part ->
                            playback.playing && playback.mediaId == "preface:${part.name}"
                        }
                    )
                }
            }
            if (loadedVerses.isEmpty()) {
                item(key = "empty-tafsir") {
                    Text("Для страницы $page не найден JSON тафсира",
                        modifier = Modifier.padding(20.dp))
                }
            }
            items(loadedVerses, key = { "${it.surah}:${it.ayah}" }) { verse ->
                ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${verse.surah}:${verse.ayah}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f))
                            // Both controls are above the Arabic text, not in AudioPanel.
                            AyahAudioControls(
                                surah = verse.surah, ayah = verse.ayah,
                                playback = playback, controller = controller,
                                reciters = reciters, reciterError = reciterError,
                                selectedReciter = selectedReciter, onReciter = onReciter,
                                endMode = endMode, onEndMode = onEndMode,
                                onPlay = { onPlayVerse(verse.surah, verse.ayah) }
                            )
                        }
                        if (verse.arabic.isNotBlank()) {
                            Text(verse.arabic, modifier = Modifier.fillMaxWidth(),
                                fontSize = 25.sp, lineHeight = 43.sp, textAlign = TextAlign.Right,
                                style = TextStyle(textDirection = TextDirection.Rtl))
                        }
                        AyahWordDictionary(
                            verse.surah, verse.ayah,
                            dictionaries[verse.surah to verse.ayah].orEmpty()
                        )
                        if (verse.translation.isNotBlank()) {
                            HorizontalDivider()
                            Text(verse.translation, style = MaterialTheme.typography.bodyLarge)
                        }
                        // Expensive HTML/quote parsing is memoized across 450 ms player updates.
                        val formatted = remember(verse.explanation) { store.format(verse.explanation) }
                        formatted.forEach { paragraph ->
                            when (paragraph.type) {
                                TafsirStore.Paragraph.Type.QUOTE -> Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(12.dp)) {
                                    Text(paragraph.text, modifier = Modifier.fillMaxWidth().padding(13.dp),
                                        style = MaterialTheme.typography.bodyMedium)
                                }
                                TafsirStore.Paragraph.Type.HEADER -> Text(paragraph.text,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary)
                                TafsirStore.Paragraph.Type.BULLET -> Text(paragraph.text,
                                    modifier = Modifier.padding(start = 14.dp),
                                    style = MaterialTheme.typography.bodyMedium)
                                TafsirStore.Paragraph.Type.BODY -> Text(paragraph.text,
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
        }
}

/** Word-by-word gloss, separate from the full translation and the Azan.ru tafsir. */
@Composable
private fun AyahWordDictionary(surah: Int, ayah: Int, words: List<WordByWordStore.Word>) {
    var expanded by remember(surah, ayah) { mutableStateOf(false) }
    val note = remember(surah, ayah) { AqidahVerseNotes.note(surah, ayah) }
    OutlinedCard(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.MenuBook, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Словарь аята", modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                    Text(if (words.isNotEmpty()) russianWordCount(words.size) else "—",
                        style = MaterialTheme.typography.labelMedium)
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Свернуть словарь" else "Раскрыть словарь")
                }
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    if (words.isEmpty()) {
                        Text(
                            "Для этого аята нет проверенного соответствия в assets/word_by_word. " +
                                "Проверьте JSON страницы и нумерацию аятов (особенно Аль-Фатиху).",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        // Every original word, every repetition; no Arabic roots or morphology.
                        words.forEach { word ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(word.arabic, fontSize = 19.sp,
                                    style = TextStyle(textDirection = TextDirection.Rtl))
                                Text("— ${word.translationRu}", modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    if (note != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Text("Примечание по вероубеждению",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer)
                                Spacer(Modifier.height(5.dp))
                                Text(note, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AyahAudioControls(
    surah: Int,
    ayah: Int,
    playback: PlaybackSnapshot,
    controller: MediaController?,
    reciters: List<Reciter>,
    reciterError: String,
    selectedReciter: String,
    onReciter: (String) -> Unit,
    endMode: AyahEndMode,
    onEndMode: (AyahEndMode) -> Unit,
    onPlay: () -> Unit
) {
    val idParts = playback.mediaId.split(':')
    val isCurrent = idParts.size >= 3 && idParts[0] == "ayah" &&
        idParts[1] == surah.toString() && idParts[2] == ayah.toString()
    val currentReciter = reciters.firstOrNull { it.repository == selectedReciter }
    var reciterMenu by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf("") }
    var dragging by remember { mutableStateOf(false) }
    var fraction by remember { mutableFloatStateOf(0f) }
    val duration = playback.durationMs
    val position = playback.positionMs
    val shownFraction = if (dragging) fraction else if (isCurrent && duration > 0)
        (position.toFloat() / duration).coerceIn(0f, 1f) else 0f

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        IconButton(onClick = onPlay, enabled = reciters.isNotEmpty()) {
            Icon(if (isCurrent && playback.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                if (isCurrent && playback.playing) "Пауза аята $surah:$ayah" else "Слушать аят $surah:$ayah",
                tint = MaterialTheme.colorScheme.primary)
        }
        if (isCurrent) {
            Slider(value = shownFraction, onValueChange = { fraction = it; dragging = true },
                onValueChangeFinished = {
                    if (duration > 0) controller?.seekTo((fraction * duration).roundToLong())
                    dragging = false
                }, enabled = duration > 0,
                modifier = Modifier.width(86.dp))
            val nextMode = when (endMode) {
                AyahEndMode.STOP -> AyahEndMode.REPEAT
                AyahEndMode.REPEAT -> AyahEndMode.NEXT
                AyahEndMode.NEXT -> AyahEndMode.STOP
            }
            IconButton(onClick = { onEndMode(nextMode) }, modifier = Modifier.size(40.dp)) {
                Icon(when (endMode) {
                    AyahEndMode.STOP -> Icons.Default.StopCircle
                    AyahEndMode.REPEAT -> Icons.Default.RepeatOne
                    AyahEndMode.NEXT -> Icons.Default.SkipNext
                }, endMode.title, tint = MaterialTheme.colorScheme.primary)
            }
        }
        Box {
            IconButton(onClick = { reciterMenu = true }) {
                Icon(Icons.Default.AccountCircle, "Профиль чтеца: ${currentReciter?.title ?: "не выбран"}")
            }
            DropdownMenu(expanded = reciterMenu, onDismissRequest = { reciterMenu = false },
                modifier = Modifier.heightIn(max = 370.dp)) {
                Text("Выберите чтеца", modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleSmall)
                if (reciters.isEmpty()) Text(reciterError.ifBlank { "Загрузка 43 чтецов…" },
                    modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
                else {
                    OutlinedTextField(value = searching, onValueChange = { searching = it },
                        modifier = Modifier.width(250.dp).padding(horizontal = 8.dp),
                        singleLine = true, placeholder = { Text("Поиск") })
                    val filtered = remember(reciters, searching) {
                        reciters.filter { searching.isBlank() || it.title.contains(searching, true) ||
                            it.repository.contains(searching, true) }
                    }
                    filtered.forEach { reciter ->
                        DropdownMenuItem(text = { Text(reciter.title) },
                            leadingIcon = {
                                if (reciter.repository == selectedReciter) Icon(Icons.Default.Check, null)
                                else Spacer(Modifier.size(20.dp))
                            },
                            onClick = { onReciter(reciter.repository); reciterMenu = false })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPanel(
    prefs: UserPreferences,
    mode: String, setMode: (String) -> Unit,
    palette: Int, setPalette: (Int) -> Unit,
    contrast: String, setContrast: (String) -> Unit,
    tajweed: Boolean, setTajweed: (Boolean) -> Unit,
    autoFlip: Boolean, setAutoFlip: (Boolean) -> Unit,
    keepAwake: Boolean, setKeepAwake: (Boolean) -> Unit,
    mushaf: MushafStore
) {
    val scroll = rememberScrollState(initial = prefs.panelViewport("settings").second)
    PersistSettingsViewport(scroll, prefs)
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Text("Оформление и чтение", style = MaterialTheme.typography.headlineSmall)
        Text("Тема", style = MaterialTheme.typography.titleMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("system" to "Системная", "light" to "Светлая", "dark" to "Тёмная")
                .forEachIndexed { index, (key, title) ->
                    SegmentedButton(selected = mode == key, onClick = { setMode(key) },
                        shape = SegmentedButtonDefaults.itemShape(index, 3)) { Text(title, fontSize = 12.sp) }
                }
        }
        Text("Контраст", style = MaterialTheme.typography.titleMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("normal" to "Обычный", "medium" to "Средний", "high" to "Высокий")
                .forEachIndexed { index, (key, title) ->
                    SegmentedButton(selected = contrast == key, onClick = { setContrast(key) },
                        shape = SegmentedButtonDefaults.itemShape(index, 3)) { Text(title, fontSize = 12.sp) }
                }
        }
        Text("Акцентный цвет", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AccentPalettes.all.forEachIndexed { index, color ->
                Surface(onClick = { setPalette(index) }, modifier = Modifier.size(39.dp)
                    .then(if (palette == index) Modifier.border(2.dp,
                        MaterialTheme.colorScheme.onSurface, RoundedCornerShape(50)) else Modifier),
                    color = color, shape = RoundedCornerShape(50),
                    border = if (palette == index) BorderStroke(2.dp, MaterialTheme.colorScheme.surface) else null) {
                    if (palette == index) Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Check, "Выбранный акцент", tint = Color.White)
                    }
                }
            }
        }
        HorizontalDivider()
        SettingsSwitch("Таджвид", tajweed, setTajweed)
        SettingsSwitch("Автоматическое перелистывание по проверенным таймингам", autoFlip, setAutoFlip)
        SettingsSwitch("Не выключать экран", keepAwake, setKeepAwake)
        HorizontalDivider()
        Text("Мусхаф", style = MaterialTheme.typography.titleMedium)
        Text("Мусхаф, тафсир, словарь и MP3 находятся в локальных assets. " +
            "Интернет требуется только для онлайн-аудио чтецов.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        MushafStore.Variant.entries.forEach { variant ->
            Text("${variant.title}: ${mushaf.folderName(variant)}",
                style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SettingsSwitch(label: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = enabled, onCheckedChange = onChange)
    }
}

/** No animation and no scroll-to-current on re-opening a sheet. */
@Composable
private fun PersistLazyViewport(state: LazyListState, prefs: UserPreferences, key: String) {
    LaunchedEffect(state, key) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collectLatest { (index, offset) ->
                delay(180) // avoid writing SharedPreferences on every drag frame
                prefs.savePanelViewport(key, index, offset)
            }
    }
    DisposableEffect(state, key) {
        onDispose {
            prefs.savePanelViewport(key, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
        }
    }
}

@Composable
private fun PersistSettingsViewport(state: androidx.compose.foundation.ScrollState, prefs: UserPreferences) {
    LaunchedEffect(state) {
        snapshotFlow { state.value }
            .distinctUntilChanged()
            .collectLatest { offset ->
                delay(180)
                prefs.savePanelViewport("settings", 0, offset)
            }
    }
    DisposableEffect(state) {
        onDispose { prefs.savePanelViewport("settings", 0, state.value) }
    }
}

/** Russian 1 слово, 2 слова, 5 слов, 11 слов, 21 слово, 22 слова. */
internal fun russianWordCount(count: Int): String {
    val lastTwo = count % 100
    val suffix = when {
        lastTwo in 11..14 -> "слов"
        count % 10 == 1 -> "слово"
        count % 10 in 2..4 -> "слова"
        else -> "слов"
    }
    return "$count $suffix"
}
