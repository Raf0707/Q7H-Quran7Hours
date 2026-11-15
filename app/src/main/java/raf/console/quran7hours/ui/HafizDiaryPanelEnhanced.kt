package raf.console.quran7hours.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.session.MediaController
import kotlinx.coroutines.flow.collectLatest
import raf.console.quran7hours.data.LearningDatabase
import raf.console.quran7hours.data.TafsirStore
import raf.console.quran7hours.data.UserPreferences
import raf.console.quran7hours.data.WordByWordStore
import raf.console.quran7hours.media.AyahEndMode
import raf.console.quran7hours.media.Reciter

/** Same atomic font preparation as Tafsir; independent diary display settings. */
@Composable
internal fun HafizDiaryPanelEnhanced(
    page: Int,
    tafsir: TafsirStore,
    prefs: UserPreferences,
    db: LearningDatabase,
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
    onNoteAyah: (Int, Int) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    val context = LocalContext.current
    val ready by produceState<QpcPreparedPage?>(null, page, tafsir) {
        value = QpcPreparedPages.page(context.applicationContext, tafsir, page)
    }
    val displayPreferences = remember(context.applicationContext) {
        SharedHafizDisplayOptions.get(context.applicationContext)
    }
    val display = displayPreferences.value
    val dictionaries by produceState<Map<Pair<Int, Int>, List<WordByWordStore.Word>>>(
        emptyMap(), page, ready, display.showDictionary
    ) {
        // Load also when hidden: copy/share-part must be able to include this block.
        value = ready?.verses?.let { wordStore.forPage(page,it) }.orEmpty()
    }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val tapSource = remember { MutableInteractionSource() }
    Column(
        Modifier.fillMaxSize().clickable(interactionSource = tapSource, indication = null) {
            focus.clearFocus(); keyboard?.hide()
        }.padding(horizontal = 12.dp)
    ) {
        val prepared = ready
        if (prepared == null) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (prepared.verses.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("Для этой страницы не найдены аяты в локальном тафсире.")
            }
        } else {
            val verses = prepared.verses
            val listState = remember(page) {
                val (index, offset) = prefs.panelViewport("hafiz_$page")
                LazyListState(index.coerceIn(0, verses.lastIndex), offset)
            }
            LaunchedEffect(listState, page) {
                snapshotFlow {
                    listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
                }.collectLatest { (index, offset) ->
                    prefs.savePanelViewport("hafiz_$page", index, offset)
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(top = 14.dp, bottom = 22.dp)
            ) {
                items(verses, key = { "${it.surah}:${it.ayah}" }) { verse ->
                    val qpc = prepared.qpc[verse.surah to verse.ayah]
                    val q7hFootnotes = remember(verse.surah, verse.ayah) { Q7HFootnoteNavigator() }
                    var note by remember(verse.surah, verse.ayah) {
                        mutableStateOf(db.hafizNote(verse.surah, verse.ayah))
                    }
                    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                        Column(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                Text("${verse.surah}:${verse.ayah}",
                                    modifier=Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary)
                                if (display.showAudio) {
                                    AyahAudioControls(
                                        surah=verse.surah,ayah=verse.ayah,
                                        playback=playback,controller=controller,
                                        reciters=reciters,reciterError=reciterError,
                                        selectedReciter=selectedReciter,onReciter=onReciter,
                                        prefs=prefs,endMode=endMode,onEndMode=onEndMode,
                                        onPlay={onPlayVerse(verse.surah,verse.ayah)},
                                        playOnly = true
                                    )
                                }
                                val words = dictionaries[verse.surah to verse.ayah].orEmpty()
                                Q7HAyahActions(
                                    content = Q7HVerseContent(
                                        surah = verse.surah, ayah = verse.ayah,
                                        arabic = verse.arabic, translation = verse.translation,
                                        tafsir = verse.explanation,
                                        dictionary = words.joinToString("\n") { "${it.arabic} — ${it.translationRu}" },
                                        showArabic = true, showDictionary = display.showDictionary,
                                        showTranslation = true, showTafsir = display.showTafsir,
                                        order = display.blockOrder
                                    ), onNote = onNoteAyah
                                )
                            }
                            if (qpc != null) {
                                Q7HAdjustableOriginalQpcText(qpc, tajweed, display.arabicSp)
                            } else {
                                Q7HAdjustableUnicodeArabicText(verse.arabic, null, display.arabicSp)
                            }
                            // Only Arabic & audio have fixed positions. All other
                            // blocks follow the user's persisted order.
                            display.blockOrder.forEach { block ->
                                when (block) {
                                    "dictionary" -> if (display.showDictionary) {
                                        AyahWordDictionary(verse.surah,verse.ayah,
                                            dictionaries[verse.surah to verse.ayah].orEmpty(),
                                            display.dictionarySp, false)
                                    }
                                    "translation" -> if (verse.translation.isNotBlank()) {
                                        Q7HFootnoteText(verse.translation,q7hFootnotes,"translation",
                                            style=MaterialTheme.typography.bodyMedium.copy(
                                                fontSize=display.translationSp.sp,
                                                lineHeight=(display.translationSp*1.45f).sp))
                                    }
                                    "tafsir" -> if (display.showTafsir && verse.explanation.isNotBlank()) {
                                        val formatted=remember(verse.explanation){tafsir.format(verse.explanation)}
                                        formatted.forEachIndexed { index, paragraph ->
                                            when (paragraph.type) {
                                                TafsirStore.Paragraph.Type.QUOTE -> Surface(
                                                    color=MaterialTheme.colorScheme.primaryContainer,
                                                    shape=RoundedCornerShape(12.dp)) {
                                                    Q7HFootnoteText(paragraph.text,q7hFootnotes,"tafsir-$index",
                                                        modifier=Modifier.fillMaxWidth().padding(12.dp),
                                                        style=MaterialTheme.typography.bodyMedium.copy(
                                                            fontSize=display.tafsirSp.sp))
                                                }
                                                else -> Q7HFootnoteText(paragraph.text,q7hFootnotes,"tafsir-$index",
                                                    style=MaterialTheme.typography.bodyMedium.copy(
                                                        fontSize=display.tafsirSp.sp,
                                                        lineHeight=(display.tafsirSp*1.45f).sp))
                                            }
                                        }
                                    }
                                    "counter" -> HafizRepetitionCounter(verse.surah,verse.ayah)
                                    "notes" -> OutlinedTextField(
                                        value = note,
                                        onValueChange = { value ->
                                            note = value
                                            db.saveHafizNote(verse.surah, verse.ayah, value)
                                        },
                                        label = { Text("Мои ошибки, подсказки и замечания") },
                                        minLines = 2,maxLines=5,modifier=Modifier.fillMaxWidth(),
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                        keyboardActions = KeyboardActions(onDone = {
                                            focus.clearFocus(); keyboard?.hide()
                                        })
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Q7HAyahSecondaryRangeSlider(playback)
        if (display.showAudio) {
            val target = q7hAyahMiniTarget(ready?.verses.orEmpty(), playback)
            if (target != null) {
                Q7HAyahMiniPlayer(target, playback, controller, prefs,
                    reciters, reciterError, selectedReciter, onReciter,
                    endMode, onEndMode, onPlayVerse)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNext, enabled = page < 604) {
                Icon(Icons.Default.ChevronLeft, contentDescription = "Следующая страница")
            }
            Text(page.toString(), modifier = Modifier.widthIn(min = 72.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = onPrevious, enabled = page > 1) {
                Icon(Icons.Default.ChevronRight, contentDescription = "Предыдущая страница")
            }
        }
    }
}
