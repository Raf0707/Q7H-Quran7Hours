package raf.console.quran7hours.data

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * A Unicode Hafs font for the tafsir verses (NOT a QCF4/PUA word-glyph font).
 * The same Typeface is used in both modes: tajweed is a text-span concern.
 *
 * To bundle the font manually (no .bat), put UthmanicHafs1Ver18.ttf in
 * app/src/main/assets/quran/tafsir/UthmanicHafs1Ver18.ttf.
 * Alternatively it is downloaded once from the Quran Foundation font CDN.
 *
 * An accidental QCF4_Hafs_XX_W.ttf in that path is rejected by cmap checking.
 */
object TafsirHafsTypeface {
    private const val TAG = "Q7HTafsirFont"
    private const val FILE_NAME = "UthmanicHafs1Ver18.ttf"
    private const val ASSET_PATH = "quran/tafsir/$FILE_NAME"
    private const val FONT_URL =
        "https://verses.quran.foundation/fonts/quran/hafs/uthmanic_hafs/$FILE_NAME"
    private const val MAX_FONT_BYTES = 6 * 1024 * 1024L

    private val lock = Mutex()
    @Volatile private var loaded: Typeface? = null

    suspend fun get(context: Context): Typeface? = withContext(Dispatchers.IO) {
        loaded?.let { return@withContext it }
        lock.withLock {
            loaded?.let { return@withLock it }
            val app = context.applicationContext
            val fontDir = File(app.filesDir, "q7h_tafsir_fonts").apply { mkdirs() }
            val cached = File(fontDir, FILE_NAME)
            if (cached.exists() && !isUnicodeHafsFont(cached)) {
                Log.w(TAG, "Wrong/corrupt cached font (possibly QCF4); rejecting")
                cached.delete()
            }
            if (!cached.isFile) {
                // Use supplied assets first. The user's QCF4 range-01 font is not this font.
                try {
                    val tmp = File(fontDir, "$FILE_NAME.tmp")
                    app.assets.open(ASSET_PATH).use { src ->
                        tmp.outputStream().use { dst -> src.copyTo(dst) }
                    }
                    if (isUnicodeHafsFont(tmp)) {
                        install(tmp, cached)
                    } else {
                        Log.w(TAG, "Asset $ASSET_PATH is not a Unicode Arabic TTF")
                        tmp.delete()
                    }
                } catch (_: java.io.FileNotFoundException) {
                    // Font is not shipped; try the documented CDN below.
                } catch (e: Exception) {
                    Log.w(TAG, "Could not open bundled Unicode font", e)
                }
            }
            if (!cached.isFile) {
                var connection: HttpURLConnection? = null
                val tmp = File(fontDir, "$FILE_NAME.download")
                try {
                    connection = URL(FONT_URL).openConnection() as HttpURLConnection
                    connection.instanceFollowRedirects = true
                    connection.connectTimeout = 12_000
                    connection.readTimeout = 25_000
                    connection.setRequestProperty("User-Agent", "Quran7Hours/Tafsir")
                    require(connection.responseCode == HttpURLConnection.HTTP_OK)
                    var bytes = 0L
                    connection.inputStream.use { src ->
                        tmp.outputStream().use { dst ->
                            val buf = ByteArray(16 * 1024)
                            while (true) {
                                val n = src.read(buf)
                                if (n == -1) break
                                bytes += n
                                require(bytes <= MAX_FONT_BYTES) { "Font file is unexpectedly large" }
                                dst.write(buf, 0, n)
                            }
                        }
                    }
                    require(isUnicodeHafsFont(tmp)) { "CDN returned incompatible font" }
                    install(tmp, cached)
                } catch (e: Exception) {
                    Log.w(TAG, "Unicode Hafs TTF unavailable; using Android Arabic fallback", e)
                } finally {
                    tmp.delete()
                    connection?.disconnect()
                }
            }
            if (cached.isFile) {
                try {
                    Typeface.createFromFile(cached).also { loaded = it }
                } catch (e: Exception) {
                    Log.e(TAG, "Android failed to load Unicode Hafs font", e)
                    cached.delete()
                    null
                }
            } else null
        }
    }

    private fun install(source: File, target: File) {
        if (!source.renameTo(target)) {
            source.copyTo(target, overwrite = true)
            source.delete()
        }
    }

    /**
     * Look inside the real TTF cmap instead of trusting the file name. QCF4
     * fonts do NOT map Arabic Unicode codepoints, only private-use glyphs.
     */
    private fun isUnicodeHafsFont(file: File): Boolean {
        if (!file.isFile || file.length() !in 10_000L..MAX_FONT_BYTES) return false
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val sfnt = raf.readInt()
                if (sfnt != 0x00010000 && sfnt != 0x4F54544F) return false
                val count = raf.readUnsignedShort()
                raf.skipBytes(6)
                var cmapOffset = -1L
                repeat(count) {
                    val tag = raf.readInt()
                    raf.skipBytes(4)
                    val offset = raf.readInt().toLong() and 0xffffffffL
                    raf.skipBytes(4)
                    if (tag == 0x636D6170) cmapOffset = offset // "cmap"
                }
                if (cmapOffset < 0 || cmapOffset + 4 >= file.length()) return false
                raf.seek(cmapOffset)
                raf.readUnsignedShort() // version
                val subtables = raf.readUnsignedShort()
                val positions = ArrayList<Long>()
                repeat(subtables) {
                    val platform = raf.readUnsignedShort()
                    val encoding = raf.readUnsignedShort()
                    val subOffset = raf.readInt().toLong() and 0xffffffffL
                    if (platform == 0 || (platform == 3 && (encoding == 1 || encoding == 10))) {
                        positions += cmapOffset + subOffset
                    }
                }
                // Fundamental Arabic letters + a Quranic diacritic. In particular
                // a QCF4_Hafs_01_W file cannot satisfy even the first two.
                val required = intArrayOf(0x0627, 0x0644, 0x0645, 0x0646, 0x064A, 0x0652)
                positions.any { pos ->
                    if (pos < 0 || pos + 16 >= raf.length()) false
                    else required.all { unicodeGlyphPresent(raf, pos, it) }
                }
            }
        } catch (_: Exception) { false }
    }

    private fun unicodeGlyphPresent(raf: RandomAccessFile, pos: Long, cp: Int): Boolean {
        raf.seek(pos)
        return when (raf.readUnsignedShort()) {
            4 -> {
                raf.seek(pos + 6)
                val segments = raf.readUnsignedShort() / 2
                if (segments !in 1..10_000) return false
                val ends = pos + 14
                val starts = ends + 2L * segments + 2
                val deltas = starts + 2L * segments
                val ranges = deltas + 2L * segments
                for (i in 0 until segments) {
                    raf.seek(ends + 2L * i)
                    val end = raf.readUnsignedShort()
                    raf.seek(starts + 2L * i)
                    val start = raf.readUnsignedShort()
                    if (cp in start..end) {
                        raf.seek(deltas + 2L * i)
                        val delta = raf.readShort().toInt()
                        raf.seek(ranges + 2L * i)
                        val shift = raf.readUnsignedShort()
                        val glyph = if (shift == 0) (cp + delta) and 0xffff
                        else {
                            val glyphPos = ranges + 2L * i + shift + 2L * (cp - start)
                            if (glyphPos + 2 > raf.length()) return false
                            raf.seek(glyphPos)
                            val raw = raf.readUnsignedShort()
                            if (raw == 0) 0 else (raw + delta) and 0xffff
                        }
                        return glyph != 0
                    }
                }
                false
            }
            12 -> {
                raf.seek(pos + 12)
                val n = raf.readInt()
                if (n !in 1..100_000) return false
                for (i in 0 until n) {
                    raf.seek(pos + 16L + 12L * i)
                    val start = raf.readInt().toLong() and 0xffffffffL
                    val end = raf.readInt().toLong() and 0xffffffffL
                    val glyph = raf.readInt().toLong() and 0xffffffffL
                    if (cp.toLong() in start..end) return glyph + cp - start != 0L
                }
                false
            }
            else -> false
        }
    }
}
