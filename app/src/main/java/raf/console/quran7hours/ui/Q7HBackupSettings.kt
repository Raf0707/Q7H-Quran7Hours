package raf.console.quran7hours.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import raf.console.quran7hours.data.Q7HBackupManager

@Composable
internal fun Q7HBackupSettings() {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var busy by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf("")}
    var confirm by remember {mutableStateOf(false)}
    var pendingUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) {pendingUri=uri;confirm=true}
    }
    HorizontalDivider()
    Text("Резервная копия",style=MaterialTheme.typography.titleLarge)
    Text("Сохраняются настройки, закладки, заметки, рисунки и прогресс. Скачанные аудио, шрифты и изображения Корана в копию не включаются.",
        style=MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled=!busy,onClick={
            busy=true;message="Экспорт данных…"
            scope.launch {
                try {
                    val uri=withContext(Dispatchers.IO){Q7HBackupManager.export(context)}
                    message="Копия сохранена в Загрузки/Quran7Hours"
                    Q7HBackupManager.share(context,uri)
                } catch(e:Exception){message="Ошибка экспорта: ${e.localizedMessage}"}
                finally {busy=false}
            }
        },modifier=Modifier.weight(1f)) {
            Icon(Icons.Default.Upload,null);Spacer(Modifier.width(4.dp));Text("Экспорт")
        }
        OutlinedButton(enabled=!busy,onClick={importer.launch(arrayOf("application/zip","application/octet-stream","*/*"))},
            modifier=Modifier.weight(1f)) {
            Icon(Icons.Default.Download,null);Spacer(Modifier.width(4.dp));Text("Импорт")
        }
    }
    if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if(message.isNotBlank()) Text(message,style=MaterialTheme.typography.bodySmall)
    if(confirm) AlertDialog(onDismissRequest={confirm=false;pendingUri=null},
        title={Text("Импорт резервной копии")},
        text={Text("Настройки и пользовательские данные из архива заменят соответствующие данные этого устройства. Перед продолжением экспортируйте текущую копию. Импорт вступит в силу после полного перезапуска приложения.")},
        confirmButton={TextButton(onClick={
            val uri=pendingUri
            confirm=false;pendingUri=null
            if(uri!=null) {
                busy=true;message="Проверка резервной копии…"
                scope.launch {
                    try {
                        val count=withContext(Dispatchers.IO){Q7HBackupManager.stageImport(context,uri)}
                        message="Проверено $count файлов. Полностью закройте приложение и откройте снова: данные восстановятся до запуска интерфейса."
                    } catch(e:Exception){message="Импорт отклонён: ${e.localizedMessage}"}
                    finally{busy=false}
                }
            }
        }){Text("Проверить и подготовить")}},
        dismissButton={TextButton(onClick={confirm=false;pendingUri=null}){Text("Отмена")}}
    )
}
