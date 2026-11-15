package raf.console.quran7hours.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import java.util.WeakHashMap

internal data class Q7HVisibleSections(val audio:Boolean=true,val tafsir:Boolean=true,val hafiz:Boolean=true) {
    fun includes(section: SheetSection):Boolean = when(section) {
        SheetSection.AUDIO -> audio
        SheetSection.TAFSIR -> tafsir
        SheetSection.HAFIZ -> hafiz
        SheetSection.SETTINGS -> true
    }
}

internal class Q7HSheetVisibilityPreferences(context: Context) {
    private val p=context.applicationContext.getSharedPreferences("q7h_reader_sections",Context.MODE_PRIVATE)
    var value by mutableStateOf(Q7HVisibleSections(
        audio=p.getBoolean("audio",true),tafsir=p.getBoolean("tafsir",true),
        hafiz=p.getBoolean("hafiz",true)))
        private set
    fun set(section: SheetSection, visible:Boolean) {
        value=when(section) {
            SheetSection.AUDIO -> value.copy(audio=visible)
            SheetSection.TAFSIR -> value.copy(tafsir=visible)
            SheetSection.HAFIZ -> value.copy(hafiz=visible)
            SheetSection.SETTINGS -> value
        }
        p.edit().putBoolean("audio",value.audio).putBoolean("tafsir",value.tafsir)
            .putBoolean("hafiz",value.hafiz).apply()
    }
}

internal object SharedQ7HSheetVisibility {
    private val instances=WeakHashMap<Context,Q7HSheetVisibilityPreferences>()
    fun get(context:Context):Q7HSheetVisibilityPreferences=synchronized(instances){
        val app=context.applicationContext
        instances.getOrPut(app){Q7HSheetVisibilityPreferences(app)}
    }
}

@Composable
internal fun Q7HSheetVisibilitySettings() {
    val context=androidx.compose.ui.platform.LocalContext.current
    val state=remember(context.applicationContext){SharedQ7HSheetVisibility.get(context.applicationContext)}
    val v=state.value
    Text("Разделы выдвижной панели",style=MaterialTheme.typography.titleMedium)
    listOf(Triple(SheetSection.AUDIO,"Аудио",v.audio),
        Triple(SheetSection.TAFSIR,"Тафсир",v.tafsir),
        Triple(SheetSection.HAFIZ,"Дневник хафиза",v.hafiz)).forEach { (section,title,visible)->
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            Checkbox(checked=visible,onCheckedChange={state.set(section,it)})
            Text(title,modifier=Modifier.weight(1f))
        }
    }
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
        Checkbox(checked=true,enabled=false,onCheckedChange=null)
        Text("Настройки · всегда доступны",modifier=Modifier.weight(1f))
    }
}
