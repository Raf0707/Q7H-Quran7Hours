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
import raf.console.quran7hours.data.Q7HBackupSchedule

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Q7HBackupSettings() {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var busy by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf("")}
    var confirm by remember {mutableStateOf(false)}
    var pendingUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var autoEnabled by remember(context) { mutableStateOf(Q7HBackupSchedule.enabled(context)) }
    var selectedHours by remember(context) { mutableLongStateOf(Q7HBackupSchedule.intervalHours(context)) }
    var retention by remember(context) { mutableIntStateOf(Q7HBackupSchedule.keepCount(context)) }
    var lastSuccess by remember(context) { mutableLongStateOf(Q7HBackupSchedule.lastSuccess(context)) }
    var lastFailure by remember(context) { mutableStateOf(Q7HBackupSchedule.lastError(context)) }
    DisposableEffect(context) {
        val preferences=context.getSharedPreferences(Q7HBackupSchedule.SETTINGS,android.content.Context.MODE_PRIVATE)
        val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if(key=="last_success" || key=="last_error") {
                lastSuccess=Q7HBackupSchedule.lastSuccess(context)
                lastFailure=Q7HBackupSchedule.lastError(context)
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val intervals = listOf(1L to "1 час", 3L to "3 часа", 6L to "6 часов",
        12L to "12 часов", 24L to "Каждый день", 72L to "Каждые 3 дня",
        168L to "Раз в неделю", 720L to "Каждые 30 дней")
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) {pendingUri=uri;confirm=true}
    }
    HorizontalDivider()
    Text("Резервная копия",style=MaterialTheme.typography.titleLarge)
    Text("Сохраняются настройки, закладки, заметки, рисунки и прогресс. Скачанные аудио, шрифты и изображения Корана в копию не включаются.",
        style=MaterialTheme.typography.bodySmall)
    Text("Включены цветные и функциональные закладки (названия, иконки и назначения), закладки аятов, " +
        "заметки, пометки, настройки Тафсира/Дневника хафиза, аудиоплееров, навигации и расписания копий.",
        style=MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth(), verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Автоматическое резервное копирование", style=MaterialTheme.typography.titleMedium)
            Text("Копии сохраняются локально в Загрузки/Quran7Hours; Интернет не нужен.",
                style=MaterialTheme.typography.bodySmall)
        }
        Switch(checked=autoEnabled, onCheckedChange={ checked ->
            try {
                Q7HBackupSchedule.setEnabled(context,checked)
                autoEnabled=checked
                message=if(checked) "Автокопирование включено; первая копия запланирована" else "Автокопирование отключено"
            } catch(e:Exception) { message="Не удалось обновить расписание: ${e.localizedMessage}" }
        })
    }
    Text("Интервал создания копии", style=MaterialTheme.typography.labelLarge)
    var intervalMenu by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded=intervalMenu,onExpandedChange={ intervalMenu=it }) {
        OutlinedTextField(
            value=intervals.first { it.first==selectedHours }.second,
            onValueChange={},readOnly=true,label={Text("Период")},
            trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded=intervalMenu)},
            modifier=Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded=intervalMenu,onDismissRequest={intervalMenu=false}) {
            intervals.forEach { (hours,title) ->
                DropdownMenuItem(text={Text(title)},onClick={
                    intervalMenu=false
                    try {
                        Q7HBackupSchedule.setIntervalHours(context,hours)
                        selectedHours=hours
                    } catch(e:Exception) { message="Не удалось изменить интервал: ${e.localizedMessage}" }
                })
            }
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
        Text("Хранить последние автокопии",Modifier.weight(1f),
            style=MaterialTheme.typography.bodyMedium)
        IconButton(onClick={
            if(retention>1) {retention--;Q7HBackupSchedule.setKeepCount(context,retention)}
        },enabled=retention>1){Text("−",style=MaterialTheme.typography.titleLarge)}
        Text("$retention",style=MaterialTheme.typography.titleMedium)
        IconButton(onClick={
            if(retention<20) {retention++;Q7HBackupSchedule.setKeepCount(context,retention)}
        },enabled=retention<20){Text("+",style=MaterialTheme.typography.titleLarge)}
    }
    if (lastSuccess > 0) {
        val date=remember(lastSuccess) { java.text.SimpleDateFormat("dd.MM.yyyy HH:mm",java.util.Locale.getDefault())
            .format(java.util.Date(lastSuccess)) }
        Text("Последняя автоматическая копия: $date",style=MaterialTheme.typography.bodySmall)
    } else Text("Автоматических копий пока нет",style=MaterialTheme.typography.bodySmall)
    if(lastFailure.isNotBlank()) Text("Последняя ошибка: $lastFailure",
        color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
    Text("Android может отложить фоновую задачу при экономии батареи. " +
        "На Android 8–9 копии хранятся в папке документов самого приложения; " +
        "перед удалением приложения перенесите их вручную.",style=MaterialTheme.typography.bodySmall)
    HorizontalDivider()
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
