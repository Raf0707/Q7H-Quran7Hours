package raf.console.quran7hours.ui


import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
import raf.console.quran7hours.data.TafsirQuranScript
import raf.console.quran7hours.data.TafsirStore
import raf.console.quran7hours.data.WordByWordStore
import raf.console.quran7hours.data.UserPreferences
import raf.console.quran7hours.data.AqidahVerseNotes
import raf.console.quran7hours.data.Q7HAqidahDictionaryNotes
import raf.console.quran7hours.data.QuranV4PageCatalog
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

private fun seekLabel(seconds: Int): String = when {
    seconds % 3600 == 0 -> "${seconds / 3600}ч"
    seconds % 60 == 0 -> "${seconds / 60}м"
    else -> "${seconds}с"
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
    // Q7H_AUDIO_SCROLL_20261112: always open each category at its current mark.
    // Player snapshots arrive every ~450ms, but this effect only acts when the
    // ACTIVE NUMBER changes, not on every position update.
    val marks = remember(category, timings) { timings.marks(category) }
    val timedActive = if (playback.mediaId == "hadr") timings.at(category, playback.positionMs) else null
    val context = androidx.compose.ui.platform.LocalContext.current
    val v4Navigation = remember(context.applicationContext) { QuranV4PageCatalog(context.applicationContext) }
    val active = timedActive ?: v4Navigation.segmentForPage(category, currentPage)
    val initialIndex = marks.indexOfFirst { it.number == active }.coerceAtLeast(0)
    val listState = remember(category) { LazyListState(initialIndex, 0) }
    LaunchedEffect(category, active) {
        val targetIndex = marks.indexOfFirst { it.number == active }
        if (targetIndex >= 0 && (listState.firstVisibleItemIndex != targetIndex ||
                listState.firstVisibleItemScrollOffset != 0)) {
            listState.scrollToItem(targetIndex)
        }
    }
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
                        var endMode by remember(category, number) {
                            mutableStateOf(prefs.hadrMode(category.name, number))
                        }
                        // NEXT -> unlimited repeat -> N repeats -> STOP.
                        val canRepeatThisMark = time != null &&
                            (marks.getOrNull(number)?.positionMs != null || number == marks.lastOrNull()?.number)
                        IconButton(onClick = {
                            endMode = when (endMode) {
                                "NEXT" -> "REPEAT"
                                "REPEAT" -> "REPEAT_N"
                                "REPEAT_N" -> "STOP"
                                else -> "NEXT"
                            }
                            prefs.saveHadrMode(category.name, number, endMode)
                            prefs.setHadrActiveSegment(category.name, number)
                        }, enabled = canRepeatThisMark) {
                            val tint = if (!canRepeatThisMark) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.primary
                            if (endMode == "REPEAT_N") {
                                Text("N", fontWeight = FontWeight.Black, color = tint,
                                    fontSize = 19.sp)
                            } else {
                                Icon(when (endMode) {
                                    "REPEAT" -> Icons.Default.RepeatOne
                                    "STOP" -> Icons.Default.StopCircle
                                    else -> Icons.Default.PlaylistPlay
                                }, when (endMode) {
                                    "REPEAT" -> "Повторять выбранный раздел без ограничений"
                                    "STOP" -> "Остановиться после раздела"
                                    else -> "Продолжать по порядку"
                                }, tint = tint)
                            }
                        }
                        if (selected && timedActive == number) Icon(Icons.Default.GraphicEq,
                            "Текущий раздел", modifier = Modifier.size(17.dp),
                            tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        HadrMiniPlayer(playback, controller, timings, activeSurah, currentPage, onPlay, prefs)
    }
}
@Composable
private fun HadrMiniPlayer(
    playback: PlaybackSnapshot,
    controller: MediaController?,
    timings: TimingCatalog,
    activeSurah: Int?,
    currentPage: Int,
    onPlay: () -> Unit,
    prefs: UserPreferences
) {
    val seekSeconds = prefs.hadrSeekIntervalSeconds
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
                    raf.console.quran7hours.media.HadrSeekPolicy.step(playback.positionMs, -seekSeconds * 1000L, duration))
            }, enabled = ready) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (seekSeconds == 5) Icons.Default.Replay5
                        else if (seekSeconds == 10) Icons.Default.Replay10
                        else Icons.Default.FastRewind, "Назад на $seekSeconds секунд")
                    if (seekSeconds !in setOf(5, 10)) Text(seekLabel(seekSeconds), fontSize = 9.sp)
                }
            }
            FilledIconButton(onClick = onPlay, enabled = controller != null) {
                Icon(if (playback.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (playback.playing) "Пауза" else "Воспроизвести")
            }
            IconButton(onClick = {
                if (ready) controller?.seekTo(
                    raf.console.quran7hours.media.HadrSeekPolicy.step(playback.positionMs, seekSeconds * 1000L, duration))
            }, enabled = ready) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (seekSeconds == 5) Icons.Default.Forward5
                        else if (seekSeconds == 10) Icons.Default.Forward10
                        else Icons.Default.FastForward, "Вперёд на $seekSeconds секунд")
                    if (seekSeconds !in setOf(5, 10)) Text(seekLabel(seekSeconds), fontSize = 9.sp)
                }
            }
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
    quranScript: TafsirQuranScript,
    tajweed: Boolean,
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
    val readingPage by produceState<TafsirStore.ReadingPage?>(null, page) {
        value = store.readingPage(page)
    }
    val verses = readingPage?.verses
    val introductions = readingPage?.introductions.orEmpty()
    // No expensive per-word parsing on the main/UI thread. The reader caches per-surah JSON.
    val dictionaries by produceState<Map<Pair<Int, Int>, List<WordByWordStore.Word>>>(
        emptyMap(), page, verses
    ) {
        value = verses?.let { wordStore.forPage(page, it) }.orEmpty()
    }
    val quranVerses by produceState<Map<Pair<Int, Int>, TafsirQuranScript.Ayah>>(
        emptyMap(), page, verses
    ) {
        value = verses?.let { quranScript.forPage(it) }.orEmpty()
    }
    // Original QPC V4/V2 glyphs from the earlier Quran7Hours app.
    // These are page-specific fonts, not Unicode text colored with SpanStyle.
    val context = androidx.compose.ui.platform.LocalContext.current
    val displayPreferences = remember(context.applicationContext) {
        SharedTafsirDisplayOptions.get(context.applicationContext)
    }
    val display = displayPreferences.value
    val dictionaryOnly = display.showDictionary && !display.showArabic && !display.showTranslation
    val tafsirBodyStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = display.tafsirSp.sp,
        lineHeight = (display.tafsirSp * 1.5f).sp
    )
    val tafsirHeadingStyle = MaterialTheme.typography.titleSmall.copy(
        fontSize = (display.tafsirSp + 2f).sp,
        lineHeight = ((display.tafsirSp + 2f) * 1.45f).sp
    )
    // The audio service can continue the 43-reciter sequence after this sheet closes.
    // Store the verified current list once; never query GitHub on each transition.
    LaunchedEffect(reciters) {
        if (reciters.isNotEmpty()) {
            prefs.saveReciterOrder(reciters.map { it.repository to it.branch })
        }
    }
    val originalQpc = remember(context) { OriginalQpcTafsirRepository(context) }
    val qpcVerses by produceState<Map<Pair<Int, Int>, OriginalQpcAyah>>(
        emptyMap(), page, verses
    ) {
        value = verses?.let { originalQpc.forPage(page, it) }.orEmpty()
    }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        if (tajweed && !originalQpc.isPackComplete()) {
            Text(
                "Оригинальный QPC V4 не установлен. Для цветного таджвида запусти INSTALL_FROM_OLD.bat.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        val loadedVerses = verses
        if (loadedVerses == null) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
        // Q7H_TAFSIR_SCROLL_20261112:
        // The last SEEN page is stored independently of its reading viewport.
        // Reopening the same page restores the exact position. If the reader
        // has turned the Mushaf or tapped tafsir arrows, open the NEW page at
        // its beginning, even when it was previously read to the end.
        // No change to MainActivity.kt is needed.
        val viewportKey = "tafsir_$page"
        val lastViewedKey = "tafsir_last_opened_page_v2"
        val previousViewedPage = remember(page) { prefs.panelViewport(lastViewedKey).first }
        val pageChanged = previousViewedPage in 1..604 && previousViewedPage != page
        val listState = remember(page) {
            val (index, offset) = if (pageChanged) 0 to 0 else prefs.panelViewport(viewportKey)
            LazyListState(index.coerceIn(0, (loadedVerses.size + introductions.size + if (page == 1) 1 else 0).coerceAtLeast(0)), offset)
        }
        LaunchedEffect(page) {
            if (pageChanged) prefs.savePanelViewport(viewportKey, 0, 0)
            prefs.savePanelViewport(lastViewedKey, page, 0)
        }
        PersistLazyViewport(listState, prefs, viewportKey)
        LazyColumn(
            state = listState,
            // Keep reading independently scrollable above the permanent page controls.
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp)
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
                    Text("Тафсир страницы $page недоступен",
                        modifier = Modifier.padding(20.dp))
                }
            }
            loadedVerses.forEachIndexed { position, verse ->
                // Show the original zero-ayah record exactly when the new surah starts.
                // Never treat it as a numbered ayah or offer audio/dictionary for ayah 0.
                if (position == 0 || loadedVerses[position - 1].surah != verse.surah) {
                    introductions.firstOrNull { it.surah == verse.surah }?.let { intro ->
                        // Surah names separate chapters even in audio-only mode.
                        // Translation and explanation remain independently controlled
                        // inside the introduction card.
                        item(key = "surah_intro_${intro.surah}") {
                            Q7HSurahIntroductionCard(intro, store, display)
                        }
                    }
                }
                item(key = "${verse.surah}:${verse.ayah}") {
                // Separate link coordinates and return address for EACH ayah.
                val q7hFootnotes = remember(page, verse.surah, verse.ayah) { Q7HFootnoteNavigator() }
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
                                prefs = prefs,
                                endMode = endMode, onEndMode = onEndMode,
                                onPlay = { onPlayVerse(verse.surah, verse.ayah) }
                            )
                        }
                        if (display.showArabic &&
                            (verse.arabic.isNotBlank() || quranVerses[verse.surah to verse.ayah] != null)) {
                            val key = verse.surah to verse.ayah
                            val original = qpcVerses[key]
                            if (original != null) {
                                // Color is embedded in the real QPC V4 font;
                                // the matching V2 font is used with Tajweed off.
                                Q7HAdjustableOriginalQpcText(original, tajweed, display.arabicSp)
                            } else {
                                // No QPC pack or incomplete/mismatched ayah:
                                // keep the user's original Unicode font, without
                                // inventing a 'classic' Tajweed palette.
                                Q7HAdjustableUnicodeArabicText(
                                    arabicFromAzan = verse.arabic,
                                    verifiedArabic = quranVerses[key]?.arabic,
                                    fontSizeSp = display.arabicSp
                                )
                            }
                        }
                        if (display.showDictionary) {
                            AyahWordDictionary(
                                verse.surah, verse.ayah,
                                dictionaries[verse.surah to verse.ayah].orEmpty(),
                                fontSizeSp = display.dictionarySp,
                                initiallyExpanded = dictionaryOnly
                            )
                        }
                        if (display.showTranslation && verse.translation.isNotBlank()) {
                            HorizontalDivider()
                            Q7HFootnoteText(
                                text = verse.translation,
                                navigator = q7hFootnotes,
                                paragraphId = "translation",
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = display.translationSp.sp,
                                    lineHeight = (display.translationSp * 1.5f).sp
                                )
                            )
                        }
                        // Tafsir is only permitted together with the translation.
                        // Memorize formatted paragraphs across Media3 progress updates.
                        if (display.showTafsir && display.showTranslation) {
                            val formatted = remember(verse.explanation) { store.format(verse.explanation) }
                            formatted.forEachIndexed { paragraphIndex, paragraph ->
                                when (paragraph.type) {
                                TafsirStore.Paragraph.Type.QUOTE -> Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(12.dp)) {
                                    Q7HFootnoteText(paragraph.text, q7hFootnotes, "tafsir-$paragraphIndex",
                                        modifier = Modifier.fillMaxWidth().padding(13.dp), style = tafsirBodyStyle)
                                }
                                TafsirStore.Paragraph.Type.HEADER -> Q7HFootnoteText(
                                    paragraph.text, q7hFootnotes, "tafsir-$paragraphIndex",
                                    style = tafsirHeadingStyle, color = MaterialTheme.colorScheme.primary)
                                TafsirStore.Paragraph.Type.BULLET -> Q7HFootnoteText(
                                    paragraph.text, q7hFootnotes, "tafsir-$paragraphIndex",
                                    modifier = Modifier.padding(start = 14.dp), style = tafsirBodyStyle)
                                TafsirStore.Paragraph.Type.BODY -> Q7HFootnoteText(
                                    paragraph.text, q7hFootnotes, "tafsir-$paragraphIndex",
                                    style = tafsirBodyStyle)
                                }
                            }
                        } // only show tafsir when its accompanying translation is enabled
                    }
                }
                } // item(ayah)
            } // forEachIndexed
        }
    }
        // Fixed navigation bar: always accessible, even when the tafsir is very long.
        // Right-to-left Mushaf navigation: left = next page; right = previous page.
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNext, enabled = page < 604) {
                Icon(Icons.Default.ChevronLeft, contentDescription = "Следующая страница")
            }
            Text(
                text = page.toString(),
                modifier = Modifier.widthIn(min = 72.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleLarge
            )
            IconButton(onClick = onPrevious, enabled = page > 1) {
                Icon(Icons.Default.ChevronRight, contentDescription = "Предыдущая страница")
            }
        }
    }
}
/** A zero-ayah (surah introduction) from the *same* Azan.ru page JSON.
 *  It has no play button, ayah number, QPC renderer or dictionary.
 */
@Composable
private fun Q7HSurahIntroductionCard(
    intro: TafsirStore.SurahIntroduction,
    store: TafsirStore,
    display: TafsirDisplayOptions
) {
    // Ayah 0 has its OWN link space, independent of all numbered verses.
    val q7hFootnotes = remember(intro.surah) { Q7HFootnoteNavigator() }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // The JSON's "ar" is a Russian surah name, not Quran Arabic.
            if (intro.heading.isNotBlank()) Q7HFootnoteText(
                intro.heading, q7hFootnotes, "intro-heading",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )
            // Only the name is immutable. Hide the translated title if the
            // user disabled translations, including in audio-only mode.
            if (display.showTranslation && intro.translation.isNotBlank()) Q7HFootnoteText(
                intro.translation, q7hFootnotes, "intro-translation",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (display.showTafsir && display.showTranslation && intro.explanation.isNotBlank()) {
                HorizontalDivider()
                val paragraphs = remember(intro.explanation) { store.format(intro.explanation) }
                val bodyStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = display.tafsirSp.sp,
                    lineHeight = (display.tafsirSp * 1.5f).sp
                )
                val headingStyle = MaterialTheme.typography.titleSmall.copy(
                    fontSize = (display.tafsirSp + 2f).sp,
                    lineHeight = ((display.tafsirSp + 2f) * 1.45f).sp
                )
                paragraphs.forEachIndexed { paragraphIndex, paragraph ->
                    when (paragraph.type) {
                        TafsirStore.Paragraph.Type.QUOTE -> Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Q7HFootnoteText(paragraph.text, q7hFootnotes, "intro-tafsir-$paragraphIndex",
                                modifier = Modifier.fillMaxWidth().padding(13.dp), style = bodyStyle)
                        }
                        TafsirStore.Paragraph.Type.HEADER -> Q7HFootnoteText(
                            paragraph.text, q7hFootnotes, "intro-tafsir-$paragraphIndex",
                            style = headingStyle, color = MaterialTheme.colorScheme.primary
                        )
                        TafsirStore.Paragraph.Type.BULLET -> Q7HFootnoteText(
                            paragraph.text, q7hFootnotes, "intro-tafsir-$paragraphIndex",
                            modifier = Modifier.padding(start = 14.dp), style = bodyStyle
                        )
                        TafsirStore.Paragraph.Type.BODY -> Q7HFootnoteText(
                            paragraph.text, q7hFootnotes, "intro-tafsir-$paragraphIndex",
                            style = bodyStyle
                        )
                    }
                }
            }
        }
    }
}

/** Word-by-word gloss, separate from the full translation and the Azan.ru tafsir. */
@Composable
private fun AyahWordDictionary(
    surah: Int,
    ayah: Int,
    words: List<WordByWordStore.Word>,
    fontSizeSp: Float,
    initiallyExpanded: Boolean
) {
    var expanded by remember(surah, ayah, initiallyExpanded) { mutableStateOf(initiallyExpanded) }
    val dictionaryTranslationSp = (fontSizeSp - 4f).coerceAtLeast(10f)
    val note = remember(surah, ayah) { Q7HAqidahDictionaryNotes.note(surah, ayah) ?: AqidahVerseNotes.note(surah, ayah) }
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
                    Text("Словарь аята", modifier = Modifier.weight(1f), textAlign = TextAlign.Start,
                        fontSize = dictionaryTranslationSp.sp)
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
                            "Пословный перевод пока недоступен для этого аята.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = dictionaryTranslationSp.sp,
                            lineHeight = (dictionaryTranslationSp * 1.4f).sp
                        )
                    } else {
                        // Every original word, every repetition; no Arabic roots or morphology.
                        words.forEach { word ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(word.arabic, fontSize = fontSizeSp.sp,
                                    style = TextStyle(textDirection = TextDirection.Rtl))
                                Text("— ${word.translationRu}", modifier = Modifier.weight(1f),
                                    fontSize = dictionaryTranslationSp.sp,
                                    lineHeight = (dictionaryTranslationSp * 1.45f).sp)
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
                                    fontSize = dictionaryTranslationSp.sp,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer)
                                Spacer(Modifier.height(5.dp))
                                Text(note, fontSize = dictionaryTranslationSp.sp,
                                    lineHeight = (dictionaryTranslationSp * 1.4f).sp,
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
    prefs: UserPreferences,
    endMode: AyahEndMode,
    onEndMode: (AyahEndMode) -> Unit,
    onPlay: () -> Unit
) {
    val idParts = playback.mediaId.split(':')
    val isCurrent = idParts.size >= 3 && idParts[0] == "ayah" &&
        idParts[1] == surah.toString() && idParts[2] == ayah.toString()
    // In ALL_RECITERS, the actual voice changes inside PlaybackService.
    // Outside Person mode show the user's selected reciter, not the last voice
    // that happened to be playing in the previous 43-reciter series.
    val playingReciterRepository = if (endMode == AyahEndMode.ALL_RECITERS &&
        isCurrent && idParts.size == 5) idParts[3] else selectedReciter
    val currentReciter = reciters.firstOrNull { it.repository == playingReciterRepository }
    val reciterFocusManager = LocalFocusManager.current
    val reciterKeyboard = LocalSoftwareKeyboardController.current
    var reciterMenu by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf("") }
    var dragging by remember { mutableStateOf(false) }
    var fraction by remember { mutableFloatStateOf(0f) }
    val duration = playback.durationMs
    val position = playback.positionMs
    val shownFraction = if (dragging) fraction else if (isCurrent && duration > 0)
        (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    // The recorder's current mediaId is NEVER the user's saved reciter preference.
    // GitHub repo aliases are recognized without depending on their position in the list.
    fun findReciter(id: String): Reciter? = reciters.firstOrNull { it.repository == id }
    val suwaid = reciters.firstOrNull {
        it.repository.removePrefix("q7h_").lowercase() in setOf("aymansowaid", "aymansuwaid")
    }
    fun preferredForPerson(): Reciter? {
        findReciter(prefs.manuallySelectedReciter)?.let { return it }
        // Migration: older builds only stored selectedReciter. The first entry
        // was picked automatically; other stored entries are treated as manual.
        val oldChosen = selectedReciter.takeIf { it.isNotBlank() &&
            it != reciters.firstOrNull()?.repository }
        findReciter(oldChosen.orEmpty())?.let {
            prefs.manuallySelectedReciter = it.repository
            return it
        }
        return suwaid ?: reciters.firstOrNull()
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        IconButton(onClick = {
            // When the service has advanced to another reciter, pausing must NOT
            // unexpectedly restart the first voice chosen in MainActivity.
            if (isCurrent && endMode == AyahEndMode.ALL_RECITERS && !playback.ended) {
                if (playback.playing) controller?.pause()
                else if (prefs.personNeedsFirstPlay) {
                    prefs.personNeedsFirstPlay = false
                    val wanted = prefs.reciterBeforeAllMode
                    if (idParts.getOrNull(3) == wanted) {
                        controller?.seekTo(0L)
                        controller?.play()
                    } else onPlay() // MainActivity uses selectedReciter, not the temporary service voice.
                } else controller?.play() // Resume the CURRENT voice in the 43-reciter series.
            } else onPlay()
        }, enabled = reciters.isNotEmpty()) {
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
                AyahEndMode.NEXT -> AyahEndMode.ALL_RECITERS
                AyahEndMode.ALL_RECITERS -> AyahEndMode.REPEAT_N
                AyahEndMode.REPEAT_N -> AyahEndMode.STOP
            }
            IconButton(onClick = {
                when {
                    // Entering Person mode never changes the personal choice. Ayman
                    // Rushdi Suwaid is the default ONLY if no choice was ever made.
                    nextMode == AyahEndMode.ALL_RECITERS -> {
                        val preferred = preferredForPerson()
                        if (preferred != null) {
                            prefs.reciterBeforeAllMode = preferred.repository
                            prefs.personNeedsFirstPlay = !playback.playing
                            if (selectedReciter != preferred.repository ||
                                (playback.playing && idParts.getOrNull(3) != preferred.repository)) {
                                onReciter(preferred.repository)
                            }
                        }
                        onEndMode(nextMode)
                    }
                    // LEAVING Person mode restores user preference AND Media3 audio.
                    endMode == AyahEndMode.ALL_RECITERS -> {
                        val original = findReciter(prefs.reciterBeforeAllMode)
                            ?: preferredForPerson()
                        onEndMode(nextMode) // Set the new mode BEFORE restarting its audio.
                        prefs.personNeedsFirstPlay = false
                        if (original != null) {
                            // This callback also resets the current Media3 track if playing.
                            onReciter(original.repository)
                        }
                        prefs.reciterBeforeAllMode = ""
                    }
                    else -> onEndMode(nextMode)
                }
            }, modifier = Modifier.size(40.dp)) {
                if (endMode == AyahEndMode.REPEAT_N) {
                    // N is intentionally typographic, distinct from repeat-one.
                    Text("N", fontSize = 20.sp, fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.primary)
                } else {
                    Icon(when (endMode) {
                        AyahEndMode.STOP -> Icons.Default.StopCircle
                        AyahEndMode.REPEAT -> Icons.Default.RepeatOne
                        AyahEndMode.NEXT -> Icons.Default.SkipNext
                        AyahEndMode.ALL_RECITERS -> Icons.Default.Person // Person WITHOUT the circle.
                        AyahEndMode.REPEAT_N -> Icons.Default.RepeatOne
                    }, endMode.title, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Box {
            IconButton(onClick = { reciterMenu = true }) {
                Icon(
                    Icons.Default.AccountCircle,
                    "Профиль чтеца: ${currentReciter?.title ?: "не выбран"}",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            DropdownMenu(expanded = reciterMenu, onDismissRequest = {
                reciterMenu = false
                reciterFocusManager.clearFocus()
                reciterKeyboard?.hide()
            },
                modifier = Modifier.heightIn(max = 370.dp)) {
                Text("Выберите чтеца", modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleSmall)
                if (reciters.isEmpty()) Text(reciterError.ifBlank { "Загрузка 43 чтецов…" },
                    modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
                else {
                    OutlinedTextField(value = searching, onValueChange = { searching = it },
                        modifier = Modifier.width(250.dp).padding(horizontal = 8.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            reciterFocusManager.clearFocus()
                            reciterKeyboard?.hide()
                        }),
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
                            onClick = {
                                prefs.manuallySelectedReciter = reciter.repository
                                if (endMode == AyahEndMode.ALL_RECITERS) {
                                    prefs.reciterBeforeAllMode = reciter.repository
                                    prefs.personNeedsFirstPlay = false
                                }
                                onReciter(reciter.repository)
                                reciterMenu = false
                                reciterFocusManager.clearFocus()
                                reciterKeyboard?.hide()
                            })
                    }
                }
            }
        }
    }
}
/** Positions of all editable fields, measured in window coordinates. */
private class Q7HSettingsFocusTracker {
    var focusedId by mutableStateOf<String?>(null)
    val rects = mutableStateMapOf<String, Rect>()

    fun fieldModifier(id: String): Modifier = Modifier
        .onFocusChanged { state ->
            if (state.isFocused) focusedId = id
            else if (focusedId == id) focusedId = null
        }
        .onGloballyPositioned { coordinates -> rects[id] = coordinates.boundsInWindow() }
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
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val textFocus = remember { Q7HSettingsFocusTracker() }
    var settingsCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // Listening at the Initial pass does NOT consume taps or disrupt sliders/buttons.
    // Check the active field's actual bounds to allow caret placement INSIDE it.
    val clearOnOutsideTap = Modifier.onGloballyPositioned { settingsCoordinates = it }
        .pointerInput(textFocus, focusManager) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false,
                    pass = PointerEventPass.Initial)
                val active = textFocus.focusedId
                val rect = active?.let { textFocus.rects[it] }
                val tap = settingsCoordinates?.localToWindow(down.position)
                if (rect != null && tap != null && !rect.contains(tap)) {
                    focusManager.clearFocus()
                    keyboard?.hide()
                }
            }
        }
    Column(Modifier.fillMaxSize().then(clearOnOutsideTap).verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)) {
        // Q7H_TAJWEED_LEGEND: permanent, read-only QPC V4 reference before theme.
        Q7HTajweedColorLegend(
            dark = mode == "dark" || (mode == "system" && isSystemInDarkTheme())
        )
        HorizontalDivider()
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
        Text("Шаг перемотки аудио", style = MaterialTheme.typography.titleMedium)
        var intervalSeconds by remember { mutableIntStateOf(prefs.hadrSeekIntervalSeconds) }
        var customSeconds by remember { mutableStateOf(intervalSeconds.toString()) }
        var intervalUnit by remember { mutableStateOf("с") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5 to "5 секунд", 10 to "10 секунд").forEach { (seconds, label) ->
                FilterChip(selected = intervalSeconds == seconds, onClick = {
                    intervalSeconds = seconds
                    prefs.hadrSeekIntervalSeconds = seconds
                }, label = { Text(label) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = customSeconds,
                onValueChange = { customSeconds = it.filter(Char::isDigit).take(5) },
                label = { Text("Свой шаг") }, singleLine = true,
                modifier = Modifier.weight(1f).then(textFocus.fieldModifier("seek_step")),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    focusManager.clearFocus(); keyboard?.hide()
                }))
            var unitMenu by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { unitMenu = true }) { Text(when (intervalUnit) {
                    "ч" -> "Часы"; "м" -> "Минуты"; else -> "Секунды"
                }) }
                DropdownMenu(expanded = unitMenu, onDismissRequest = { unitMenu = false }) {
                    listOf("с" to "Секунды", "м" to "Минуты", "ч" to "Часы").forEach { (u, name) ->
                        DropdownMenuItem(text = { Text(name) }, onClick = {
                            intervalUnit = u; unitMenu = false
                        })
                    }
                }
            }
            val factor = when (intervalUnit) { "ч" -> 3600; "м" -> 60; else -> 1 }
            val newSeconds = (customSeconds.toLongOrNull() ?: 0L) * factor
            TextButton(onClick = {
                intervalSeconds = newSeconds.toInt()
                prefs.hadrSeekIntervalSeconds = intervalSeconds
            }, enabled = newSeconds in 1L..86400L) { Text("OK") }
        }
        // Separate stored limits. They do not reset text/tajweed preferences.
        HorizontalDivider()
        Text("Повторения при прослушивании", style = MaterialTheme.typography.titleMedium)
        Q7HRepeatCountSetting(
            title = "Каждый из 43 чтецов — повторов одного аята",
            value = prefs.allRecitersRepeatCount,
            default = 1,
            onSave = { prefs.allRecitersRepeatCount = it },
            focusTracker = textFocus, fieldId = "reciters_n",
            onDoneEditing = { focusManager.clearFocus(); keyboard?.hide() }
        )
        Q7HRepeatCountSetting(
            title = "Тафсир: режим N (число чтений аята)",
            value = prefs.ayahRepeatN,
            default = 3,
            onSave = { prefs.ayahRepeatN = it },
            focusTracker = textFocus, fieldId = "ayah_n",
            onDoneEditing = { focusManager.clearFocus(); keyboard?.hide() }
        )
        Q7HRepeatCountSetting(
            title = "Коран за 7 часов: режим N (сура, страница, джуз, хизб, манзиль)",
            value = prefs.hadrRepeatN,
            default = 3,
            onSave = { prefs.hadrRepeatN = it },
            focusTracker = textFocus, fieldId = "hadr_n",
            onDoneEditing = { focusManager.clearFocus(); keyboard?.hide() }
        )
        Text("Число от 1 до 1000 — общее количество воспроизведений, включая первое. " +
            "После N чтений выбранный аят или раздел останавливается. " +
            "Для разделов быстрого чтения нужны тайминги начала и конца; " +
            "для ещё не размеченных разделов повтор недоступен.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Tafsir display settings remain immediately below audio settings.
        HorizontalDivider()
        val context = androidx.compose.ui.platform.LocalContext.current
        val tafsirDisplay = remember(context.applicationContext) {
            SharedTafsirDisplayOptions.get(context.applicationContext)
        }
        TafsirDisplaySettingsSection(tafsirDisplay)
        Spacer(Modifier.height(20.dp))
    }
}
/**
 * Read-only legend of the EIGHT official QPC/QCF V4 colour groups.
 * This UI does not modify the font palette, tajweed detection, or user settings.
 * Colours are taken from the QFC4 tajweed palette reference.
 */
private data class Q7HTajweedLegendRule(
    val color: Color,
    val title: String
)

private val q7hTajweedLegendRules = listOf(
    Q7HTajweedLegendRule(Color(0xFF999999), "Не произносится"),
    Q7HTajweedLegendRule(Color(0xFFDED537), "Мадд — удлинение (2 хараката)"),
    Q7HTajweedLegendRule(Color(0xFFFE7D03), "Мадд мунфасыль — раздельное удлинение (2, 4 или 6 харакатов)"),
    Q7HTajweedLegendRule(Color(0xFFFF4D8E), "Мадд муттасыль — слитное удлинение (4–5 харакатов)"),
    Q7HTajweedLegendRule(Color(0xFFE30000), "Мадд лазим — обязательное удлинение (6 харакатов)"),
    Q7HTajweedLegendRule(Color(0xFF26B55D), "Гунна и ихфа — носовое звучание (удлинение на 2 хараката)"),
    Q7HTajweedLegendRule(Color(0xFF00DEFF), "Калькаля — отрывистый отзвук"),
    Q7HTajweedLegendRule(Color(0xFF3C84D5), "Тафхим — твёрдое произношение"),
)

@Composable
private fun Q7HTajweedColorLegend(dark: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Неизменяемые настройки", style = MaterialTheme.typography.headlineSmall)
        Text("Цвета таджвида · QPC V4", style = MaterialTheme.typography.titleMedium)
        Text(
            "Памятка по цветам оригинального таджвида. Эти цвета встроены в шрифт " +
                "и не меняются в настройках приложения.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                q7hTajweedLegendRules.forEach { rule ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            modifier = Modifier.size(22.dp),
                            color = rule.color,
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(
                                1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                            )
                        ) {}
                        Text(
                            rule.title,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
        Text(
            "Харакат — мера длительности звука. Памятка отображает закреплённые обозначения правил; " +
                "оригинальный QPC V4 использует встроенную палитру для каждой темы.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Numeric editor; commits only validated 1..1000 values. */
@Composable
private fun Q7HRepeatCountSetting(
    title: String, value: Int, default: Int, onSave: (Int) -> Unit,
    focusTracker: Q7HSettingsFocusTracker, fieldId: String,
    onDoneEditing: () -> Unit
) {
    var saved by remember { mutableIntStateOf(value.coerceIn(1, 1000)) }
    var input by remember { mutableStateOf(saved.toString()) }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.filter(Char::isDigit).take(4) },
                singleLine = true,
                label = { Text("1–1000") },
                isError = (input.toIntOrNull() ?: 0) !in 1..1000,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDoneEditing() }),
                modifier = Modifier.weight(1f).then(focusTracker.fieldModifier(fieldId))
            )
            val candidate = input.toIntOrNull()
            TextButton(onClick = {
                val n = candidate ?: return@TextButton
                onSave(n)
                saved = n
            }, enabled = candidate != null && candidate in 1..1000 && candidate != saved) {
                Text("OK")
            }
            IconButton(onClick = {
                saved = default
                input = default.toString()
                onSave(default)
            }) { Icon(Icons.Default.Refresh, "Сбросить значение до $default") }
        }
    }
}

@Composable
private fun SettingsSwitch(label: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = enabled, onCheckedChange = onChange)
    }
}
/** Persist a reader viewport. Audio uses its own active-item positioning. */
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

