package raf.console.quran7hours.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import raf.console.quran7hours.media.Q7HPersonQueue
import raf.console.quran7hours.media.Reciter

/** One editor used by Settings and the compact PERSON control in every ayah player. */
@Composable
internal fun Q7HPersonReciterEditor(
    reciters: List<Reciter>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember(context.applicationContext) {
        Q7HPersonQueue.preferences(context.applicationContext)
    }
    val all = remember(reciters) { reciters.distinctBy { it.repository } }
    val selected = remember(reciters) {
        mutableStateListOf<String>().also { ids ->
            ids.addAll(Q7HPersonQueue.enabled(all, prefs).map { it.repository })
        }
    }
    val selectedSet = selected.toSet()
    val byId = all.associateBy { it.repository }
    val display = selected.mapNotNull { byId[it] } +
        all.filterNot { it.repository in selectedSet }

    fun persist() = Q7HPersonQueue.save(prefs, selected.toList())
    fun change(id: String, enabled: Boolean) {
        if (enabled && id !in selected) selected.add(id)
        else if (!enabled) selected.remove(id)
        persist()
    }
    fun swap(id: String, direction: Int) {
        val index = selected.indexOf(id)
        val next = index + direction
        if (index >= 0 && next in selected.indices) {
            val temp = selected[index]
            selected[index] = selected[next]
            selected[next] = temp
            persist()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Чтецы · режим PERSON") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Выбрано ${selected.size} из ${all.size}. Выбранные — вверху, " +
                    "стрелки изменяют порядок чтения только у них.",
                    style = MaterialTheme.typography.bodySmall)
                TextButton(
                    enabled = all.isNotEmpty(),
                    onClick = {
                        val allAreSelected = all.all { it.repository in selectedSet }
                        selected.clear()
                        if (!allAreSelected) selected.addAll(all.map { it.repository })
                        persist()
                    }
                ) {
                    Text(if (all.isNotEmpty() && all.all { it.repository in selectedSet })
                        "Снять выбор со всех" else "Выбрать всех")
                }
                if (selected.isEmpty()) Text("Не выбран ни один чтец. Режим PERSON не " +
                    "запускается, пока не выбран хотя бы один.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
                if (all.size < 40) Text("Каталог пока неполный: дождитесь " +
                    "фоновой загрузки списка чтецов.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                LazyColumn(Modifier.fillMaxWidth().heightIn(max=340.dp),
                    verticalArrangement=Arrangement.spacedBy(2.dp)) {
                    items(display,key={it.repository}) { voice ->
                        val isEnabled = voice.repository in selectedSet
                        val position = selected.indexOf(voice.repository)
                        Surface(
                            color = if (isEnabled) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(9.dp),
                            border = if (isEnabled) BorderStroke(0.5.dp,
                                MaterialTheme.colorScheme.outlineVariant) else null
                        ) {
                            Row(Modifier.fillMaxWidth().heightIn(min=45.dp),
                                verticalAlignment=Alignment.CenterVertically) {
                                Checkbox(checked=isEnabled,
                                    onCheckedChange={ change(voice.repository,it) },
                                    modifier=Modifier.size(38.dp))
                                Text(voice.title, modifier=Modifier.weight(1f)
                                    .clickable { change(voice.repository,!isEnabled) },
                                    style=MaterialTheme.typography.bodySmall)
                                if (isEnabled) {
                                    IconButton(onClick={swap(voice.repository,-1)},
                                        enabled=position>0,modifier=Modifier.size(36.dp)) {
                                        Icon(Icons.Default.ArrowUpward,"Раньше",Modifier.size(19.dp))
                                    }
                                    IconButton(onClick={swap(voice.repository,1)},
                                        enabled=position in 0 until selected.lastIndex,
                                        modifier=Modifier.size(36.dp)) {
                                        Icon(Icons.Default.ArrowDownward,"Позже",Modifier.size(19.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick=onDismiss) { Text("Готово") } }
    )
}

@Composable
internal fun Q7HPersonSettingsButton(
    reciters: List<Reciter>
) {
    val context = LocalContext.current
    val shared = remember(context.applicationContext) {
        Q7HPersonQueue.preferences(context.applicationContext)
    }
    var editing by remember { mutableStateOf(false) }
    // Forces the subtitle to reflect edits performed in the editor when it closes.
    var version by remember { mutableIntStateOf(0) }
    val count = remember(version,reciters) { Q7HPersonQueue.enabled(reciters, shared).size }
    Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("PERSON · порядок и выбор чтецов",
                style=MaterialTheme.typography.titleMedium)
            Text("Участвуют $count из ${reciters.size} чтецов. " +
                "По умолчанию выбраны все.", style=MaterialTheme.typography.bodySmall)
        }
        FilledTonalIconButton(onClick={editing=true}) {
            Icon(Icons.Default.Edit, "Настроить PERSON")
        }
    }
    if (editing) Q7HPersonReciterEditor(reciters) { editing=false;version++ }
}
