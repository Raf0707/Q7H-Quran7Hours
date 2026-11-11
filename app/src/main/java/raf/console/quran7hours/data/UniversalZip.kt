package raf.console.quran7hours.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipFile

/**
 * Pure-JVM ZIP normalization and secure extraction, independently testable without Android.
 * Any number of parent directories (including zero) is supported.
 */
object UniversalZip {
    enum class Kind { IMAGES, TAFSIR, AUDIO }
    private const val MAX_IMAGE = 80L * 1024 * 1024
    private const val MAX_JSON = 30L * 1024 * 1024
    private const val MAX_AUDIO = 2L * 1024 * 1024 * 1024
    private const val MAX_TOTAL = 6L * 1024 * 1024 * 1024

    /** Leaf-only normalized target filename, or null for irrelevant files. */
    fun destinationName(kind: Kind, entryPath: String): String? {
        val path = entryPath.replace('\\', '/')
        if (path.isEmpty() || path.startsWith('/') || path.indexOf('\u0000') >= 0 ||
            path.split('/').any { it == ".." }) throw IOException("Опасный путь внутри ZIP: $path")
        val leaf = path.substringAfterLast('/')
        return when (kind) {
            Kind.AUDIO -> if (leaf.equals("quran_7_hours.mp3", true) || leaf.equals("qyran_7_hours.mp3", true))
                "quran_7_hours.mp3" else null
            Kind.TAFSIR -> {
                val result = Regex("(?i)(?:page[_-]?)?(\\d{1,3})\\.json").matchEntire(leaf)
                val page = result?.groupValues?.get(1)?.toIntOrNull()
                if (page != null && page in 1..604) "%03d.json".format(Locale.ROOT, page) else null
            }
            Kind.IMAGES -> {
                val result = Regex("(?i)(\\d{1,3})\\.(webp|png|jpe?g)").matchEntire(leaf)
                val page = result?.groupValues?.get(1)?.toIntOrNull()
                if (page != null && page in 1..604)
                    "%03d.%s".format(Locale.ROOT, page, result.groupValues[2].lowercase(Locale.ROOT))
                else null
            }
        }
    }

    /** Get expanded size before writing any file; names checked for duplicates. */
    fun expectedExpandedSize(zipFile: File, kind: Kind): Long = ZipFile(zipFile).use { zip ->
        val seen = HashSet<String>()
        var sum = 0L
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val e = entries.nextElement()
            val name = destinationName(kind, e.name) ?: continue
            if (e.isDirectory) continue
            if (!seen.add(name.lowercase(Locale.ROOT))) throw IOException("ZIP содержит дубликат: $name")
            if (e.size < 0) throw IOException("Не указан размер: $name")
            val max = when (kind) { Kind.AUDIO -> MAX_AUDIO; Kind.TAFSIR -> MAX_JSON; Kind.IMAGES -> MAX_IMAGE }
            if (e.size > max) throw IOException("Слишком большой файл: $name")
            sum += e.size
            if (sum > MAX_TOTAL) throw IOException("Слишком большой распакованный пакет")
        }
        sum
    }

    /** Stream copy with CRC verification, duplicate defense, bounded expansion and cancellation. */
    fun extract(zipFile: File, targetRoot: File, kind: Kind, checkCancelled: () -> Unit = {}) {
        val canonicalRoot = targetRoot.canonicalFile
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries()
            val seen = HashSet<String>()
            var expanded = 0L
            while (entries.hasMoreElements()) {
                checkCancelled()
                val e = entries.nextElement()
                val normalized = destinationName(kind, e.name) ?: continue
                if (e.isDirectory) continue
                if (!seen.add(normalized.lowercase(Locale.ROOT))) throw IOException("Дубликат файла в ZIP: $normalized")
                val max = when (kind) { Kind.AUDIO -> MAX_AUDIO; Kind.TAFSIR -> MAX_JSON; Kind.IMAGES -> MAX_IMAGE }
                if (e.size > max) throw IOException("Слишком большой файл: $normalized")
                val output = File(canonicalRoot, normalized)
                if (output.canonicalFile.parentFile != canonicalRoot) throw IOException("Выход из каталога ресурсов")
                var count = 0L
                val crc = CRC32()
                zip.getInputStream(e).use { stream ->
                    FileOutputStream(output).buffered().use { out ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            checkCancelled()
                            val n = stream.read(buffer)
                            if (n < 0) break
                            count += n
                            expanded += n
                            if (count > max || expanded > MAX_TOTAL) throw IOException("ZIP слишком большой")
                            crc.update(buffer, 0, n)
                            out.write(buffer, 0, n)
                        }
                    }
                }
                if (count == 0L || (e.size >= 0 && count != e.size) || (e.crc >= 0 && crc.value != e.crc)) {
                    throw IOException("Повреждён ZIP: $normalized (CRC/размер)")
                }
            }
        }
    }
}
