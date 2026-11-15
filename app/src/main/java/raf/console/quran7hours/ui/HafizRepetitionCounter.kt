package raf.console.quran7hours.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Private, per-ayah, persisted mini-counter; goal notification uses local haptics. */
@Composable
internal fun HafizRepetitionCounter(surah: Int, ayah: Int) {
    val context = LocalContext.current.applicationContext
    val storage = remember(context) { context.getSharedPreferences("q7h_hafiz_repetitions",0) }
    val key = "${surah}_${ayah}"
    var count by remember(key) { mutableIntStateOf(storage.getInt("count_$key",0)) }
    var goalText by remember(key) { mutableStateOf(storage.getInt("goal_$key",10).toString()) }
    val goal = goalText.toIntOrNull()?.coerceIn(1,100000)
    val haptics = LocalHapticFeedback.current
    fun update(next: Int) {
        val old = count
        count = next.coerceIn(0,100000)
        storage.edit().putInt("count_$key",count).apply()
        if (goal != null && old < goal && count >= goal) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
    Column(verticalArrangement=Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Повторения: $count${goal?.let { " / $it" } ?: ""}",
                style=MaterialTheme.typography.labelLarge,modifier=Modifier.weight(1f))
            IconButton(onClick={ update(count-1) },enabled=count>0) {
                Icon(Icons.Default.Remove,"Уменьшить")
            }
            IconButton(onClick={ update(count+1) },enabled=count<100000) {
                Icon(Icons.Default.Add,"Повторение")
            }
            IconButton(onClick={ update(0) },enabled=count>0) {
                Icon(Icons.Default.Refresh,"Сбросить счётчик")
            }
        }
        OutlinedTextField(value=goalText,
            onValueChange={ text ->
                goalText=text.filter(Char::isDigit).take(6)
                goalText.toIntOrNull()?.takeIf { it in 1..100000 }?.let {
                    storage.edit().putInt("goal_$key",it).apply()
                }
            },label={Text("Цель повторений")},singleLine=true,
            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),
            modifier=Modifier.fillMaxWidth())
        if (goal != null) LinearProgressIndicator(
            progress = { (count.toFloat()/goal.toFloat()).coerceIn(0f,1f) },
            modifier=Modifier.fillMaxWidth()
        )
    }
}
