package raf.console.quran7hours.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import raf.console.quran7hours.data.AssetPackManager
import java.util.Locale

/** Do not initialize the heavy reader, bitmap decode or media controllers until packs are valid. */
@Composable
fun AssetInstallGate(reader: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    val manager = remember(context) { AssetPackManager(context) }
    val owner = LocalLifecycleOwner.current
    var retry by remember { mutableIntStateOf(0) }
    var ready by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(true) }
    var progress by remember { mutableStateOf<AssetPackManager.Progress?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(retry) {
        checking = true
        error = null
        val alreadyInstalled = withContext(Dispatchers.IO) { manager.ready() }
        if (alreadyInstalled) {
            ready = true
            checking = false
            return@LaunchedEffect
        }
        ready = false
        checking = false
        // Conflate 12 worker notifications to one Main-thread Compose state writer.
        val updates = Channel<AssetPackManager.Progress>(Channel.CONFLATED)
        launch { for (event in updates) progress = event }
        try {
            manager.install { event -> updates.trySend(event) }
            ready = withContext(Dispatchers.IO) { manager.ready() }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = e.localizedMessage ?: "Ошибка установки ресурсов"
        } finally {
            updates.close()
        }
    }

    // A lightweight OFFLINE integrity check each time the app returns to the foreground.
    DisposableEffect(owner, manager) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && ready) {
                // Trigger a fresh background check without network if a user cleared app files.
                retry++
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    if (ready) { reader(); return }
    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().padding(22.dp), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(15.dp)
                ) {
                    Icon(Icons.Default.CloudDownload, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.height(52.dp))
                    Text("Ресурсы Quran7Hours", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Для чтения необходимы изображения мусхафа, тафсир и аудио. " +
                            "Они загружаются один раз, после чего приложение работает офлайн.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    val p = progress
                    if (checking) {
                        CircularProgressIndicator()
                        Text("Проверяем установленные файлы…")
                    } else if (error == null && p != null) {
                        Text("Установлено ${p.installed} из 6 пакетов",
                            style = MaterialTheme.typography.titleMedium)
                        val label = when (p.state) {
                            "DOWNLOAD" -> "Скачивание"
                            "VERIFY" -> "Проверка архива"
                            "EXTRACT" -> "Параллельная загрузка и распаковка"
                            "INSTALLED", "SKIP" -> "Готово"
                            else -> "Подготовка"
                        }
                        Text(label, style = MaterialTheme.typography.bodySmall)
                        if (p.expectedBytes > 0) {
                            LinearProgressIndicator(
                                progress = { (p.bytes.toFloat() / p.expectedBytes).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text("${megabytes(p.bytes)} / ${megabytes(p.expectedBytes)} МиБ скачано")
                            Text("Скачиваются: ${p.activeDownloads} · распаковываются: ${p.activeExtracts}" +
                                if (p.transferBytesPerSecond > 0) " · ~${megabytes(p.transferBytesPerSecond)} МиБ/с" else "",
                                style = MaterialTheme.typography.bodySmall)
                        } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        if (p.description.isNotBlank()) Text(p.description)
                    } else if (error == null) {
                        CircularProgressIndicator()
                        Text("Подключение к GitHub Releases…")
                    }
                    if (error != null) {
                        Text("Не удалось подготовить ресурсы", style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error)
                        Text(error.orEmpty(), style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center)
                        Button(onClick = { retry++ }) { Text("Повторить / продолжить") }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "До шести загрузок и шести распаковок одновременно; при недостатке места " +
                            "количество рабочих задач автоматически уменьшается. ZIP удаляются после проверки.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

private fun megabytes(size: Long): String = String.format(Locale.ROOT, "%.1f", size / 1048576.0)
