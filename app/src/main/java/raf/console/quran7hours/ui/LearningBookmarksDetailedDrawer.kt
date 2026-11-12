package raf.console.quran7hours.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import raf.console.quran7hours.data.LearningBookmark
import raf.console.quran7hours.data.QuranNavigationPageSource

/**
 * Rich bookmarks list. We intentionally use exactly the same page metadata
 * provider as the Contents and Go To navigation, rather than maintaining
 * separate page, surah, ayah, juz or hizb tables.
 *
 * This is display-only: LearningDatabase, the bookmark IDs, page navigation,
 * colors and note/delete actions remain unchanged.
 */
@Composable
internal fun LearningBookmarksDetailedDrawer(
    bookmarks: List<LearningBookmark>,
    onOpen: (Int) -> Unit,
    onRemove: (Long) -> Unit,
    onNote: (LearningBookmark) -> Unit
) {
    val context = LocalContext.current.applicationContext
    val pageSource = remember(context) { QuranNavigationPageSource(context) }
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Мои закладки (${bookmarks.size})",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp)
        )
        if (bookmarks.isEmpty()) {
            Text(
                text = "Нажмите значок закладки на странице Корана, чтобы сохранить её.",
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(bookmarks, key = { it.id }) { mark ->
                val details by produceState(
                    initialValue = "Загрузка данных о сурах и аятах…",
                    key1 = mark.page,
                    key2 = pageSource
                ) {
                    value = withContext(Dispatchers.IO) {
                        pageSource.summary(mark.page)
                    }
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f).clickable { onOpen(mark.page) },
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LearningBookmarkIcon(
                                    icon = mark.type.icon,
                                    tint = Color(mark.type.color.toInt()),
                                    modifier = Modifier.size(25.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Страница ${mark.page}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = mark.type.name,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Color(mark.type.color.toInt())
                                    )
                                }
                            }
                            // QuranNavigationPageSource.summary() includes the names and
                            // ayah ranges of ALL surahs that occur on this page, the juz
                            // quarter, hizb half, manzil and possible sajdah-tilawah.
                            Text(
                                text = details,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (mark.type.purpose.isNotBlank()) {
                                Text(
                                    text = mark.type.purpose,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Открыть страницу",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(onClick = { onNote(mark) }) {
                                Icon(Icons.Default.NoteAdd, contentDescription = "Заметка для страницы ${mark.page}")
                            }
                            IconButton(onClick = { onRemove(mark.id) }) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Удалить закладку страницы ${mark.page}")
                            }
                        }
                    }
                }
            }
        }
    }
}
