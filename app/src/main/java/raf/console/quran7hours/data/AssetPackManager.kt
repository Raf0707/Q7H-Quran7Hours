package raf.console.quran7hours.data

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.coroutineContext

/** Versioned GitHub Release archive installer with overlapping download and extraction pools. */
class AssetPackManager(context: Context) {
    private val app = context.applicationContext
    private val root = storageRoot(app)
    private val downloads = File(app.cacheDir, "q7h_asset_downloads")
    private val installMutex = Mutex()

    enum class Pack(val folder: String, val names: List<String>, val title: String) {
        CLASSIC_LIGHT("Quran_Classic_Light", listOf("Quran_Classic_Light.zip"), "Мусхаф · светлый"),
        CLASSIC_DARK("Quran_Classic_Dart", listOf("Quran_Classic_Dart.zip", "Quran_Classic_Dark.zip"), "Мусхаф · тёмный"),
        TAJWEED_LIGHT("Quran_Tajweed_Light", listOf("Quran_Tajweed_Light.zip"), "Таджвид · светлый"),
        TAJWEED_DARK("Quran_Tajweed_Dart", listOf("Quran_Tajweed_Dart.zip", "Quran_Tajweed_Dark.zip"), "Таджвид · тёмный"),
        TAFSIR("tafsir_azan_ru", listOf("tafsir_azan_ru.zip", "tafsir.zip"), "Тафсир Азан.ру"),
        AUDIO("quran", listOf("quran_7_hours.zip", "qyran_7_hours.zip", "quran.zip"), "Коран за 7 часов · MP3")
    }

    data class Progress(
        val state: String,
        val pack: Pack? = null,
        val index: Int = 0,
        val bytes: Long = 0,
        val expectedBytes: Long = 0,
        val description: String = "",
        val installed: Int = 0,
        val activeDownloads: Int = 0,
        val activeExtracts: Int = 0,
        val transferBytesPerSecond: Long = 0L
    )

    private data class Asset(val name: String, val url: String, val size: Long, val sha256: String?)

    companion object {
        const val RELEASE_TAG = "assets-v1" // Change only when publishing a new complete set.
        private const val REPO = "Raf0707/q7h_assets"
        private const val MIN_FREE = 64L * 1024 * 1024
        private const val MAX_ZIP = 2L * 1024 * 1024 * 1024
        private val IMAGE_EXT = setOf("webp", "png", "jpg", "jpeg")

        fun storageRoot(context: Context): File = File(context.filesDir, "q7h_resources/$RELEASE_TAG")
        fun pageDirectory(context: Context, folder: String): File = File(storageRoot(context), folder)
        fun tafsirDirectory(context: Context): File = File(storageRoot(context), Pack.TAFSIR.folder)
        fun audioFile(context: Context): File = File(storageRoot(context), "quran/quran_7_hours.mp3")
    }

    /** Fast offline check on cold start and on returning to the app. No network dependency. */
    fun ready(): Boolean = Pack.entries.all(::validPack)

    fun validPack(pack: Pack): Boolean {
        val dir = File(root, pack.folder)
        if (!dir.isDirectory || !File(dir, ".q7h-installed").isFile) return false
        return when (pack) {
            Pack.AUDIO -> File(dir, "quran_7_hours.mp3").let { it.isFile && it.length() > 10_000_000L }
            Pack.TAFSIR -> (1..604).all { p -> File(dir, "%03d.json".format(Locale.ROOT, p)).let { it.isFile && it.length() > 0L } }
            else -> (1..604).all { p -> IMAGE_EXT.any { ext ->
                File(dir, "%03d.$ext".format(Locale.ROOT, p)).let { it.isFile && it.length() > 100L }
            } }
        }
    }

    /**
     * Six network slots and six extraction slots on devices with enough free space.
     * Use separate dispatchers so a slow SD card never blocks network reads.
     * Smaller storage budgets switch automatically to fewer simultaneous archives.
     * Never hold whole archives or decoded images in heap memory.
     */
    suspend fun install(report: (Progress) -> Unit): Unit = installMutex.withLock {
        withContext(Dispatchers.IO) {
            // Recover if Android killed the process between backup and atomic folder rename.
            recoverInterruptedMoves()
            if (ready()) { report(Progress("READY", description = "Ресурсы установлены", installed = 6)); return@withContext }
            val assets = fetchRelease()
            val todo = Pack.entries.filterNot(::validPack)
            todo.forEach { p ->
                if (assets[p] == null) throw IOException("В Release '$RELEASE_TAG' нет ZIP для ${p.title}: ${p.names.joinToString()}")
            }
            root.mkdirs()
            downloads.mkdirs()
            val leftBytes = todo.sumOf { assets.getValue(it).size }
            val free = StatFs(root.absolutePath).availableBytes
            // 6 simultaneous ZIP downloads can temporarily double disk use.
            // Conservative estimate; exact free-space is checked again before each extraction.
            val parallel = TransferTuning.parallelJobs(free, leftBytes)
            val downloadId = AtomicInteger(0)
            val extractId = AtomicInteger(0)
            val downloadPool = Executors.newFixedThreadPool(parallel) { work ->
                Thread(work, "q7h-download-${downloadId.incrementAndGet()}").apply { isDaemon = true }
            }.asCoroutineDispatcher()
            val extractPool = Executors.newFixedThreadPool(parallel) { work ->
                Thread(work, "q7h-unpack-${extractId.incrementAndGet()}").apply { isDaemon = true }
            }.asCoroutineDispatcher()
            val bytes = AtomicLongArray(Pack.entries.size)
            val states = AtomicIntegerArray(Pack.entries.size)
            val lastUpdate = AtomicLong(0L)
            val previous = AtomicLong(0L)
            val previousAt = AtomicLong(System.currentTimeMillis())
            Pack.entries.forEachIndexed { i, p ->
                if (validPack(p)) {
                    states.set(i, 4)
                    bytes.set(i, assets[p]?.size ?: 0L)
                }
            }
            fun emit(force: Boolean = false) {
                val now = System.currentTimeMillis()
                val old = lastUpdate.get()
                if (!force && now - old < 300L) return
                if (!force && !lastUpdate.compareAndSet(old, now)) return
                if (force) lastUpdate.set(now)
                val downloaded = Pack.entries.sumOf { i -> bytes.get(i.ordinal) }
                val expected = Pack.entries.sumOf { p -> assets[p]?.size ?: 0L }
                val prevAt = previousAt.getAndSet(now)
                val prevBytes = previous.getAndSet(downloaded)
                val rate = if (now > prevAt) ((downloaded - prevBytes).coerceAtLeast(0L) * 1000L) / (now - prevAt) else 0L
                val done = (0 until states.length()).count { states.get(it) == 4 }
                val extracting = (0 until states.length()).count { states.get(it) == 3 }
                val loading = (0 until states.length()).count { states.get(it) in 1..2 }
                report(Progress(
                    state = when { done == 6 -> "READY"; extracting > 0 -> "EXTRACT"; else -> "DOWNLOAD" },
                    index = done, bytes = downloaded, expectedBytes = expected,
                    description = "Готово $done/6 · загрузок $loading · распаковок $extracting",
                    installed = done, activeDownloads = loading, activeExtracts = extracting,
                    transferBytesPerSecond = rate
                ))
            }
            try {
                coroutineScope {
                    todo.map { pack ->
                        async(downloadPool) {
                            val i = pack.ordinal
                            val remote = assets.getValue(pack)
                            val partial = File(downloads, "${pack.name}.zip.part")
                            states.set(i, 1)
                            bytes.set(i, partial.length().coerceAtMost(remote.size))
                            emit(true)
                            try {
                                // A complete partial file must still pass SHA/CRC checks.
                                var digest: String
                                var attempt = 0
                                while (true) {
                                    attempt++
                                    downloadWithRetries(remote, partial) { n ->
                                        bytes.set(i, n)
                                        emit()
                                    }
                                    states.set(i, 2)
                                    emit(true)
                                    digest = sha256(partial)
                                    if (remote.sha256 == null || digest.equals(remote.sha256, ignoreCase = true)) break
                                    partial.delete()
                                    bytes.set(i, 0)
                                    if (attempt >= 2) throw IOException("Не совпадает SHA-256: ${remote.name}")
                                    states.set(i, 1)
                                }
                                withContext(extractPool) {
                                    states.set(i, 3)
                                    emit(true)
                                    installVerifiedArchive(remote, partial, digest, pack)
                                }
                                states.set(i, 4)
                                bytes.set(i, remote.size)
                                partial.delete() // only after atomic, verified installation
                                emit(true)
                            } catch (t: Throwable) {
                                states.set(i, -1)
                                emit(true)
                                throw t
                            }
                        }
                    }.awaitAll()
                }
            } finally {
                downloadPool.close()
                extractPool.close()
            }
            if (!ready()) throw IOException("После установки отсутствуют обязательные файлы")
            // Only after complete, validated installation, reclaim obsolete version storage.
            root.parentFile?.listFiles()?.filter {
                it.isDirectory && it != root && Regex("assets-v[0-9]+").matches(it.name)
            }?.forEach { it.deleteRecursively() }
            Pack.entries.filter(::validPack).forEach { pack ->
                File(downloads, "${pack.name}.zip.part").delete()
            }
            emit(true)
            report(Progress("READY", description = "Все шесть пакетов установлены", installed = 6))
        }
    }

    private fun recoverInterruptedMoves() {
        Pack.entries.forEach { pack ->
            val installed = File(root, pack.folder)
            val backup = File(root, ".${pack.folder}.old")
            if (!installed.exists() && backup.exists()) backup.renameTo(installed)
            // If the new package is already active, an abandoned backup is safe to delete.
            if (installed.exists() && backup.exists() && validPack(pack)) backup.deleteRecursively()
        }
    }

    private suspend fun installVerifiedArchive(remote: Asset, partial: File, digest: String, pack: Pack) {
        val temp = File(root.parentFile, ".staging_${RELEASE_TAG}_${pack.name}")
        if (temp.exists()) temp.deleteRecursively()
        if (!temp.mkdirs()) throw IOException("Не удалось создать временный каталог ${pack.folder}")
        try {
            unpackUniversal(partial, temp, pack)
            if (!verifyContents(temp, pack)) throw IOException("ZIP ${remote.name} не содержит полный набор ресурсов")
            File(temp, ".nomedia").writeText("")
            File(temp, ".q7h-installed").writeText("$RELEASE_TAG\n${remote.name}\n$digest\n")
            root.mkdirs()
            val installed = File(root, pack.folder)
            val backup = File(root, ".${pack.folder}.old")
            if (backup.exists()) backup.deleteRecursively()
            if (installed.exists() && !installed.renameTo(backup)) throw IOException("Не удалось заменить ${pack.folder}")
            if (!temp.renameTo(installed)) {
                if (backup.exists()) backup.renameTo(installed)
                throw IOException("Не удалось активировать ${pack.folder}")
            }
            backup.deleteRecursively()
        } finally {
            if (temp.exists()) temp.deleteRecursively()
        }
    }

    private fun verifyContents(dir: File, pack: Pack): Boolean = when (pack) {
        Pack.AUDIO -> File(dir, "quran_7_hours.mp3").let { it.isFile && it.length() > 10_000_000L }
        Pack.TAFSIR -> (1..604).all { n -> File(dir, "%03d.json".format(Locale.ROOT, n)).let { it.isFile && it.length() > 0L } }
        else -> (1..604).all { n -> IMAGE_EXT.any { ext -> File(dir, "%03d.$ext".format(Locale.ROOT, n)).let { it.isFile && it.length() > 100L } } }
    }

    private fun fetchRelease(): Map<Pack, Asset> {
        val conn = (URL("https://api.github.com/repos/$REPO/releases/tags/$RELEASE_TAG").openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 45_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Quran7Hours-Assets-Android")
        }
        try {
            if (conn.responseCode !in 200..299) throw IOException(
                "GitHub Release '$RELEASE_TAG' недоступен (HTTP ${conn.responseCode}). Убедитесь, что репозиторий и Release опубликованы."
            )
            val array = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).getJSONArray("assets")
            val result = mutableMapOf<Pack, Asset>()
            for (i in 0 until array.length()) {
                val a = array.getJSONObject(i)
                val name = a.optString("name")
                val pack = Pack.entries.firstOrNull { p -> p.names.any { it.equals(name, ignoreCase = true) } } ?: continue
                if (result.containsKey(pack)) throw IOException("Несколько архивов для ${pack.title}; оставьте один")
                val url = a.optString("browser_download_url")
                if (!url.startsWith("https://github.com/$REPO/releases/download/")) throw IOException("Недопустимый URL: $name")
                val size = a.optLong("size")
                if (size <= 0 || size >= MAX_ZIP) throw IOException("ZIP $name должен быть меньше 2 ГиБ")
                val rawHash = a.optString("digest").removePrefix("sha256:")
                val hash = rawHash.takeIf { it.length == 64 && it.all { c -> c in "0123456789abcdefABCDEF" } }
                result[pack] = Asset(name, url, size, hash)
            }
            return result
        } finally { conn.disconnect() }
    }

    private suspend fun downloadWithRetries(asset: Asset, file: File, report: (Long) -> Unit) {
        var last: IOException? = null
        repeat(3) { attempt ->
            coroutineContext.ensureActive()
            try { download(asset, file, report); return }
            catch (e: IOException) { last = e; if (attempt < 2) delay((attempt + 1) * 1_200L) }
        }
        throw last ?: IOException("Не удалось скачать ${asset.name}")
    }

    private suspend fun download(asset: Asset, file: File, report: (Long) -> Unit) {
        file.parentFile?.mkdirs()
        if (file.length() > asset.size) file.delete()
        var offset = file.takeIf { it.isFile }?.length() ?: 0L
        if (offset == asset.size) { report(offset); return }
        if (StatFs(file.parentFile!!.absolutePath).availableBytes < asset.size - offset + MIN_FREE)
            throw IOException("Недостаточно места для ${asset.name}. Освободите память телефона.")
        val connection = (URL(asset.url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "Quran7Hours-Assets-Android")
            setRequestProperty("Accept-Encoding", "identity") // Range must address raw ZIP bytes.
            if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
        }
        try {
            val status = connection.responseCode
            if (status !in listOf(200, 206)) throw IOException("HTTP $status при скачивании ${asset.name}")
            val append = status == 206 && offset > 0L
            if (append && !connection.getHeaderField("Content-Range").orEmpty().startsWith("bytes $offset-"))
                throw IOException("Сервер вернул неверную часть архива ${asset.name}")
            if (!append) offset = 0L // Server ignored Range; restart, don't append duplicate bytes.
            var written = offset
            connection.inputStream.use { input ->
                FileOutputStream(file, append).buffered().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var lastUpdate = System.currentTimeMillis()
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (written > asset.size) throw IOException("ZIP длиннее объявленного размера")
                        if (System.currentTimeMillis() - lastUpdate >= 200L) {
                            report(written); lastUpdate = System.currentTimeMillis()
                        }
                    }
                }
            }
            report(written)
            if (written != asset.size) throw IOException("Неполный ZIP: $written/${asset.size} байт")
        } finally { connection.disconnect() }
    }

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Resolve files by basename rather than assuming any fixed ZIP directory depth. */
    private suspend fun unpackUniversal(file: File, destination: File, pack: Pack) {
        val kind = when (pack) {
            Pack.AUDIO -> UniversalZip.Kind.AUDIO
            Pack.TAFSIR -> UniversalZip.Kind.TAFSIR
            else -> UniversalZip.Kind.IMAGES
        }
        val requiredBytes = UniversalZip.expectedExpandedSize(file, kind)
        val available = StatFs(destination.absolutePath).availableBytes
        if (requiredBytes + MIN_FREE > available) {
            throw IOException("Недостаточно памяти для распаковки ${pack.title}. Нужно ещё ${requiredBytes / 1048576} МиБ плюс резерв.")
        }
        val job = coroutineContext
        UniversalZip.extract(file, destination, kind) { job.ensureActive() }
    }
}
