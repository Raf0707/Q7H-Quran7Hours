package raf.console.quran7hours.data

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.Normalizer

/** Offline Hafs Unicode text + precomputed tajweed spans. Never guesses tajweed rules. */
class TafsirQuranScript(private val context: Context) {
    @Immutable
    data class Mark(val start: Int, val end: Int, val rule: String)
    @Immutable
    data class Ayah(val arabic: String, val marks: List<Mark>)

    private val cache = object : LinkedHashMap<Int, Map<Int, Ayah>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Map<Int, Ayah>>?): Boolean = size > 12
    }

    suspend fun forPage(verses: List<TafsirStore.Verse>): Map<Pair<Int, Int>, Ayah> =
        withContext(Dispatchers.IO) {
            val result = HashMap<Pair<Int, Int>, Ayah>(verses.size)
            for (surah in verses.map { it.surah }.distinct()) {
                val items = load(surah)
                for (v in verses) {
                    if (v.surah != surah) continue
                    val ayah = items[v.ayah] ?: continue
                    // Check the Quran text from Azan against the extra Hafs dataset.
                    // If an edition or numbering differs, DO NOT substitute the ayah.
                    if (v.arabic.isNotBlank() && !sameLetters(v.arabic, ayah.arabic)) {
                        Log.w("Q7HTafsir", "Different Arabic text at ${v.surah}:${v.ayah}; keeping original")
                        continue
                    }
                    result[v.surah to v.ayah] = ayah
                }
            }
            result
        }

    private fun load(surah: Int): Map<Int, Ayah> {
        synchronized(cache) { cache[surah]?.let { return it } }
        val file = "tafsir_quran/${surah.toString().padStart(3, '0')}.json"
        val result: Map<Int, Ayah> = try {
            val content = context.assets.open(file).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(content)
            require(root.getInt("surah") == surah)
            val verses = root.getJSONArray("ayahs")
            val parsed = LinkedHashMap<Int, Ayah>()
            for (i in 0 until verses.length()) {
                val node = verses.getJSONObject(i)
                val number = node.getInt("ayah")
                val arabic = node.getString("ar")
                require(number > 0 && arabic.isNotBlank() && '\uFFFD' !in arabic)
                val spans = node.getJSONArray("spans")
                val marks = ArrayList<Mark>(spans.length())
                for (j in 0 until spans.length()) {
                    val span = spans.getJSONArray(j)
                    val start = span.getInt(0)
                    val end = span.getInt(1)
                    val rule = span.getString(2)
                    require(start >= 0 && end <= arabic.length && end > start)
                    marks.add(Mark(start, end, rule))
                }
                require(parsed.put(number, Ayah(arabic, marks)) == null)
            }
            parsed
        } catch (e: Exception) {
            Log.w("Q7HTafsir", "No verified Hafs data for surah $surah ($file); using Azan Arabic", e)
            emptyMap()
        }
        synchronized(cache) { cache[surah] = result }
        return result
    }

    private fun sameLetters(a: String, b: String): Boolean = normalizeLetters(a) == normalizeLetters(b)

    private fun normalizeLetters(value: String): String = buildString {
        // Compare actual Arabic letters; ignore just vowels, Uthmani stops and ornament numbers.
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
}
