package raf.console.quran7hours.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import raf.console.quran7hours.data.QuranCatalog
import raf.console.quran7hours.media.AyahEndMode

@Composable
private fun Q7HNumericRangeField(value:String,onChange:(String)->Unit,label:String) {
    OutlinedTextField(value=value,
        onValueChange={onChange(it.filter(Char::isDigit).take(4))},
        label={Text(label)},singleLine=true,modifier=Modifier.fillMaxWidth(),
        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Q7HHadrRangeDialog(
    title: String, total: Int, current: Int,
    onDismiss: ()->Unit,
    onStart: (Int,Int,String)->Unit
) {
    var from by remember(title) { mutableStateOf(current.coerceIn(1,total).toString()) }
    var until by remember(title) { mutableStateOf(current.coerceIn(1,total).toString()) }
    var mode by remember { mutableStateOf("STOP") }
    val a=from.toIntOrNull()
    val b=until.toIntOrNull()
    val valid=a!=null && b!=null && a in 1..total && b in 1..total && a<=b
    AlertDialog(onDismissRequest=onDismiss,title={Text("Отрезок · $title")},
        text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
            Q7HNumericRangeField(from,{from=it},"От (1–$total)")
            Q7HNumericRangeField(until,{until=it},"До (1–$total)")
            Text("Как проигрывать отрезок:",style=MaterialTheme.typography.labelMedium)
            Column {
                listOf("STOP" to "Один раз и стоп", "NEXT" to "После отрезка продолжить", "REPEAT" to "Зациклить",
                    "REPEAT_N" to "Повторить N раз").forEach { (id,label)->
                    Row(modifier=Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        RadioButton(selected=mode==id,onClick={mode=id})
                        TextButton(onClick={mode=id}){Text(label)}
                    }
                }
            }
        }},
        confirmButton={TextButton(enabled=valid,onClick={if(valid)onStart(a!!,b!!,mode)}){Text("Воспроизвести")}},
        dismissButton={TextButton(onClick=onDismiss){Text("Отмена")}})
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Q7HAyahRangeDialog(
    startSurah:Int, startAyah:Int,
    onDismiss:()->Unit,
    onStart:(Int,Int,Int,Int,AyahEndMode)->Unit
) {
    var fromS by remember { mutableStateOf(startSurah.toString()) }
    var fromA by remember { mutableStateOf(startAyah.toString()) }
    var toS by remember { mutableStateOf(startSurah.toString()) }
    var toA by remember { mutableStateOf(startAyah.toString()) }
    var mode by remember { mutableStateOf(AyahEndMode.STOP) }
    val s=fromS.toIntOrNull();val a=fromA.toIntOrNull()
    val t=toS.toIntOrNull();val b=toA.toIntOrNull()
    val valid=s!=null && a!=null && t!=null && b!=null &&
        QuranCatalog.isValidAyah(s,a) && QuranCatalog.isValidAyah(t,b) &&
        (s<t || s==t && a<=b)
    AlertDialog(onDismissRequest=onDismiss,title={Text("Отрезок аятов")},
        text={Column(Modifier.fillMaxWidth().heightIn(max=430.dp).verticalScroll(rememberScrollState()),
            verticalArrangement=Arrangement.spacedBy(6.dp)){
            Text("Первый аят",style=MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                Box(Modifier.weight(1f)){Q7HNumericRangeField(fromS,{fromS=it},"Сура")}
                Box(Modifier.weight(1f)){Q7HNumericRangeField(fromA,{fromA=it},"Аят")}
            }
            Text("Последний аят (включительно)",style=MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                Box(Modifier.weight(1f)){Q7HNumericRangeField(toS,{toS=it},"Сура")}
                Box(Modifier.weight(1f)){Q7HNumericRangeField(toA,{toA=it},"Аят")}
            }
            Text("Режим воспроизведения",style=MaterialTheme.typography.labelMedium)
            listOf(AyahEndMode.STOP,AyahEndMode.NEXT,AyahEndMode.REPEAT,AyahEndMode.REPEAT_N,
                AyahEndMode.ALL_RECITERS).forEach { option ->
                Row(horizontalArrangement=Arrangement.spacedBy(3.dp)){
                    RadioButton(selected=mode==option,onClick={mode=option})
                    TextButton(onClick={mode=option}){Text(
                        when(option){
                            AyahEndMode.STOP->"Один раз и стоп"
                            AyahEndMode.NEXT->"После отрезка продолжить"
                            AyahEndMode.REPEAT->"Повторять весь отрезок"
                            AyahEndMode.REPEAT_N->"Повторить весь отрезок N раз"
                            AyahEndMode.ALL_RECITERS->"Все чтецы: каждый читает весь отрезок"
                            else->option.title
                        })}
                }
            }
            Text("Для N и всех чтецов используются числа из настроек аудио.",
                style=MaterialTheme.typography.bodySmall)
        }},
        confirmButton={TextButton(enabled=valid,onClick={if(valid)onStart(s!!,a!!,t!!,b!!,mode)}){
            Text("Воспроизвести")
        }}, dismissButton={TextButton(onClick=onDismiss){Text("Отмена")}})
}
