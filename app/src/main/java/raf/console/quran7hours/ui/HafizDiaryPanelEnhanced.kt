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
import kotlinx.coroutines.flow.collectLatest
import raf.console.quran7hours.data.LearningDatabase
import raf.console.quran7hours.data.TafsirStore
import raf.console.quran7hours.data.UserPreferences

/** Hafiz diary with the exact same QPC light/dark renderer as Tafsir.
 *  Existing per-ayah notes and scroll snapshots use the same persisted keys.
 */
@Composable
internal fun HafizDiaryPanelEnhanced(
    page: Int,
    tafsir: TafsirStore,
    prefs: UserPreferences,
    db: LearningDatabase,
    tajweed: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    val context = LocalContext.current
    val prepared by produceState<QpcPreparedPage?>(null, page, tafsir) {
        value = QpcPreparedPages.page(context, tafsir, page)
    }
    val displayPreferences = remember(context.applicationContext) {
        SharedTafsirDisplayOptions.get(context.applicationContext)
    }
    val display = displayPreferences.value
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val tapSource = remember { MutableInteractionSource() }
    Column(
        Modifier.fillMaxSize().clickable(interactionSource = tapSource, indication = null) {
            focus.clearFocus(); keyboard?.hide()
        }.padding(horizontal = 12.dp)
    ) {
        val ready = prepared
        if (ready == null) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (ready.verses.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("Для этой страницы не найдены аяты в локальном тафсире.")
            }
        } else {
            val verses = ready.verses
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
                    val qpc = ready.qpc[verse.surah to verse.ayah]
                    var note by remember(verse.surah, verse.ayah) {
                        mutableStateOf(db.hafizNote(verse.surah, verse.ayah))
                    }
                    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                        Column(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("${verse.surah}:${verse.ayah}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary)
                            if (qpc != null) {
                                // Applies real QPC V2 or V4 font, including the dark V4 CPAL palette.
                                Q7HAdjustableOriginalQpcText(qpc, tajweed, display.arabicSp)
                            } else {
                                Q7HAdjustableUnicodeArabicText(verse.arabic, null, display.arabicSp)
                            }
                            if (verse.translation.isNotBlank()) Text(
                                verse.translation,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            OutlinedTextField(
                                value = note,
                                onValueChange = { value ->
                                    note = value
                                    db.saveHafizNote(verse.surah, verse.ayah, value)
                                },
                                label = { Text("Мои ошибки, подсказки и замечания") },
                                minLines = 2,
                                maxLines = 5,
                                modifier = Modifier.fillMaxWidth(),
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
        // Fixed bottom navigation, just as in the Tafsir panel.
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
