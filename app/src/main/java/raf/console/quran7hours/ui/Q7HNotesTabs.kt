package raf.console.quran7hours.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import raf.console.quran7hours.data.LearningDatabase

/** Pages containing persisted Paint strokes, queried from the existing LearningDatabase. */
@Composable
internal fun Q7HGraphicNotesDrawer(db: LearningDatabase, onOpenGraphic: (Int) -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
            val graphicPages by produceState<List<Pair<Int,Int>>?>(null,db,refresh) {
                value = withContext(Dispatchers.IO) {
                    (1..604).mapNotNull { page ->
                        val count = runCatching { db.loadInk(page).size }.getOrDefault(0)
                        if (count>0) page to count else null
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically){
                Text("Страницы с пометками: ${graphicPages?.size ?: "…"}",
                    modifier=Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
                IconButton(onClick={refresh++}){
                    Icon(Icons.Default.Refresh,"Обновить список")
                }
            }
            if (graphicPages == null) {
                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment=Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (graphicPages!!.isEmpty()) {
                Text("Графических пометок пока нет.",modifier=Modifier.padding(16.dp),
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
            } else LazyColumn(contentPadding=PaddingValues(12.dp),
                verticalArrangement=Arrangement.spacedBy(7.dp)) {
                items(graphicPages!!,key={it.first}) { (page,count) ->
                    Surface(onClick={onOpenGraphic(page)},shape=MaterialTheme.shapes.medium,
                        color=MaterialTheme.colorScheme.surfaceVariant,modifier=Modifier.fillMaxWidth()){
                        Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically){
                            Icon(Icons.Default.Brush,"Графические пометки")
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)){
                                Text("Страница $page",style=MaterialTheme.typography.titleSmall)
                                Text("Штрихов: $count",style=MaterialTheme.typography.labelSmall)
                            }
                            Icon(Icons.Default.ChevronRight,"Открыть")
                        }
                    }
                }
            }
    }
}
