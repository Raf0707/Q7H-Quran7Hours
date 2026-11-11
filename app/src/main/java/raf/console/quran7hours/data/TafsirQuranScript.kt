package raf.console.quran7hours.data

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer

/**
 * Quran Tajweed Engine (MIT, https://github.com/TheAbubakrAbu/Quran-Tajweed-Engine).
 * Offline assets take precedence. If the previous installer was never run, download only
 * missing surahs on first use and retain the *validated* data in app-private storage.
 * Never guesses color spans or changes Azan's Arabic on an unverified mismatch.
 */
class TafsirQuranScript(private val context: Context) {
    @Immutable data class Mark(val start: Int, val end: Int, val rule: String)
    @Immutable data class Ayah(val arabic: String, val marks: List<Mark>)

    private val cache = object : LinkedHashMap<Int, Map<Int, Ayah>>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Int, Map<Int, Ayah>>?
        ): Boolean = size > 12
    }

    suspend fun forPage(verses: List<TafsirStore.Verse>): Map<Pair<Int, Int>, Ayah> =
        withContext(Dispatchers.IO) {
            val result = HashMap<Pair<Int, Int>, Ayah>(verses.size)
            for (surah in verses.map { it.surah }.distinct()) {
                val items = load(surah)
                // Some tafsir editions number the basmalah separately from the Hafs corpus.
                // Resolve *only* when Arabic consonant text matches, never by offset alone.
                for (v in verses) {
                    if (v.surah != surah) continue
                    val direct = items[v.ayah]
                    val match = if (v.arabic.isBlank()) {
                        direct
                    } else if (direct != null && sameLetters(v.arabic, direct.arabic)) {
                        direct
                    } else {
                        items.values.singleOrNull { sameLetters(v.arabic, it.arabic) }
                    }
                    if (match == null) {
                        if (items.isNotEmpty()) Log.w(
                            TAG, "No safe tajweed text match at ${v.surah}:${v.ayah}; keeping original"
                        )
                        continue
                    }
                    result[v.surah to v.ayah] = match
                }
            }
            result
        }

    private fun load(surah: Int): Map<Int, Ayah> {
        if (surah !in 1..114) return emptyMap()
        synchronized(cache) { cache[surah]?.let { return it } }
        val fileName = "${surah.toString().padStart(3, '0')}.json"
        val assetPath = "tafsir_quran/$fileName"
        val stored = File(File(context.filesDir, "tafsir_quran"), fileName)

        val packaged = try {
            context.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { parsePacked(it.readText(), surah) }
        } catch (_: java.io.FileNotFoundException) {
            null
        } catch (e: Exception) {
            Log.w(TAG, "Bundled $assetPath invalid; trying cached data", e)
            null
        }
        if (packaged != null) return rememberSuccess(surah, packaged)

        if (stored.isFile) {
            try {
                val fromDisk = parsePacked(stored.readText(Charsets.UTF_8), surah)
                return rememberSuccess(surah, fromDisk)
            } catch (e: Exception) {
                Log.w(TAG, "Corrupt cached tajweed for $surah; fetching again", e)
                stored.delete()
            }
        }

        try {
            val chapter = JSONObject(download("surahs/$fileName"))
            val annotations = JSONArray(download("tajweed/$fileName"))
            val packed = packVerified(surah, chapter, annotations)
            val parsed = parsePacked(packed.toString(), surah)
            stored.parentFile?.mkdirs()
            val tmp = File(stored.parentFile, "$fileName.tmp")
            tmp.writeText(packed.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(stored)) {
                stored.writeText(packed.toString(), Charsets.UTF_8)
                tmp.delete()
            }
            Log.i(TAG, "Cached verified offline tajweed for surah $surah: ${parsed.size} ayahs")
            return rememberSuccess(surah, parsed)
        } catch (e: Exception) {
            // Do NOT cache a failed download; the 'Retry' button can try again.
            Log.w(TAG, "Tajweed unavailable for surah $surah (offline or source unreachable)", e)
            return emptyMap()
        }
    }

    private fun rememberSuccess(surah: Int, value: Map<Int, Ayah>): Map<Int, Ayah> {
        synchronized(cache) { cache[surah] = value }
        return value
    }

    private fun parsePacked(json: String, surah: Int): Map<Int, Ayah> {
        val root = JSONObject(json)
        require(root.getInt("surah") == surah)
        val array = root.getJSONArray("ayahs")
        require(array.length() in 1..286)
        val result = LinkedHashMap<Int, Ayah>()
        for (i in 0 until array.length()) {
            val node = array.getJSONObject(i)
            val number = node.getInt("ayah")
            val arabic = node.getString("ar")
            require(number in 1..286 && arabic.isNotBlank() && '\uFFFD' !in arabic)
            val spans = node.getJSONArray("spans")
            val marks = ArrayList<Mark>(spans.length())
            var previousEnd = 0
            for (j in 0 until spans.length()) {
                val span = spans.getJSONArray(j)
                val start = span.getInt(0)
                val end = span.getInt(1)
                val rule = span.getString(2)
                require(start >= previousEnd && end <= arabic.length && end > start)
                require(rule in RULES)
                marks.add(Mark(start, end, rule))
                previousEnd = end
            }
            require(result.put(number, Ayah(arabic, marks)) == null)
        }
        require(result.size == array.length() && result.keys == (1..array.length()).toSet())
        return result
    }

    private fun packVerified(surah: Int, chapter: JSONObject, annotations: JSONArray): JSONObject {
        require(chapter.getInt("id") == surah)
        val chapters = chapter.getJSONArray("ayahs")
        val expected = chapter.getInt("numberOfAyahs")
        require(chapters.length() == expected && expected in 1..286)
        val byVerse = HashMap<Int, JSONArray>()
        for (i in 0 until annotations.length()) {
            val a = annotations.getJSONObject(i)
            require(a.getInt("surah") == surah)
            val number = a.getInt("ayah")
            require(number in 1..expected && byVerse.put(number, a.getJSONArray("annotations")) == null)
        }
        require(byVerse.size == expected)
        val result = JSONArray()
        val seen = HashSet<Int>()
        for (i in 0 until chapters.length()) {
            val v = chapters.getJSONObject(i)
            val number = v.getInt("id")
            require(number in 1..expected && seen.add(number))
            val arabic = v.getString("textArabic")
            require(arabic.isNotBlank() && '\uFFFD' !in arabic)
            val rawSpans = byVerse.getValue(number)
            val ordered = ArrayList<Mark>()
            for (j in 0 until rawSpans.length()) {
                val m = rawSpans.getJSONObject(j)
                val start = m.getInt("start")
                val end = m.getInt("end")
                val rule = m.getString("rule")
                require(start in 0 until end && end <= arabic.length)
                require(rule in RULES)
                ordered.add(Mark(start, end, rule))
            }
            ordered.sortWith(compareBy({ it.start }, { it.end }))
            require(ordered.zipWithNext().all { (a, b) -> a.end <= b.start })
            val spans = JSONArray()
            for (m in ordered) spans.put(JSONArray().put(m.start).put(m.end).put(m.rule))
            result.put(JSONObject().put("ayah", number).put("ar", arabic).put("spans", spans))
        }
        // Source JSON should already be ascending, but preserve correct ayah order regardless.
        val sorted = JSONArray()
        val pages = (0 until result.length()).map { result.getJSONObject(it) }.sortedBy { it.getInt("ayah") }
        for (p in pages) sorted.put(p)
        return JSONObject().put("surah", surah).put("ayahs", sorted)
    }

    private fun download(relative: String): String {
        val bases = listOf(
            "https://raw.githubusercontent.com/TheAbubakrAbu/Quran-Tajweed-Engine/main/data/",
            "https://cdn.jsdelivr.net/gh/TheAbubakrAbu/Quran-Tajweed-Engine@main/data/"
        )
        var last: Exception? = null
        for (base in bases) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(base + relative).openConnection() as HttpURLConnection)
                conn.connectTimeout = 8_000
                conn.readTimeout = 12_000
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("User-Agent", "Quran7Hours/tafseer-tajweed")
                require(conn.responseCode == HttpURLConnection.HTTP_OK)
                val body = conn.inputStream.use { stream ->
                    val buffer = ByteArray(16 * 1024)
                    val bytes = ByteArrayOutputStream()
                    var size = 0
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        size += count
                        require(size <= MAX_DOWNLOAD) { "Data exceeds maximum size" }
                        bytes.write(buffer, 0, count)
                    }
                    bytes.toString("UTF-8")
                }
                require(body.isNotBlank())
                return body
            } catch (e: Exception) {
                last = e
            } finally {
                conn?.disconnect()
            }
        }
        throw IllegalStateException("Cannot download $relative", last)
    }

    private fun sameLetters(a: String, b: String): Boolean =
        normalizeLetters(a) == normalizeLetters(b)

    private fun normalizeLetters(value: String): String = buildString {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
        for (ch in normalized) {
            if (ch.isWhitespace() || ch == '\u0640' || ch == '\u200C' || ch == '\u200D') continue
            val type = Character.getType(ch)
            if (type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt()) continue
            if (ch in '\u06D6'..'\u06ED' || ch in '\u0660'..'\u0669' || ch in '\u06F0'..'\u06F9') continue
            append(when (ch) {
                '\u0671', '\u0623', '\u0625', '\u0622', '\u0672', '\u0673' -> '\u0627'
                '\u0649' -> '\u064A'
                '\u0624' -> '\u0648'
                '\u0626' -> '\u064A'
                else -> ch
            })
        }
    }

    companion object {
        private const val TAG = "Q7HTafsir"
        private const val MAX_DOWNLOAD = 6 * 1024 * 1024
        private val RULES = setOf(
            "lamShamsiyah", "droppedLetter", "hamzatWaslSilent", "idghamBilaGhunnah",
            "idghamGhunnah", "generalGhunnah", "ikhfaaLight", "ikhfaaHeavy", "iqlaab",
            "qalqalah", "tafkhim", "maddNatural", "maddNaturalMiniature", "maddSukoon",
            "maddSeparated", "maddConnected", "maddNecessary"
        )
    }
}
