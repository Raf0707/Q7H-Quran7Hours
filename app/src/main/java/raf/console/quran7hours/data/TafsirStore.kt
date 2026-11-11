package raf.console.quran7hours.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import org.json.JSONTokener

class TafsirStore(private val context: Context) {
    // Short bounded LRU: repeated page navigation need not parse JSON again.
    private val cache = object : LinkedHashMap<Int, List<Verse>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<Verse>>?): Boolean = size > 14
    }

    data class Verse(val surah: Int, val ayah: Int, val arabic: String, val translation: String, val explanation: String)
    suspend fun page(number: Int): List<Verse> = withContext(Dispatchers.IO) {
        if (number !in 1..604) return@withContext emptyList()
        synchronized(cache) { cache[number] }?.let { return@withContext it }
        val file = "tafsir_azan_ru/${number.toString().padStart(3, '0')}.json"
        val parsed = runCatching {
            context.assets.open(file).bufferedReader(Charsets.UTF_8).use { reader ->
                parsePage(JSONTokener(reader.readText()).nextValue())
            }
        }.getOrDefault(emptyList())
        synchronized(cache) { cache[number] = parsed }
        parsed
    }

    /**
     * Find the physical mushaf page for a verse using the original per-page
     * tafsir_azan_ru JSON, rather than estimating from surah start pages.
     * The page may contain multiple surahs. This runs off the UI thread.
     */
    suspend fun findPageForAyah(surah: Int, ayah: Int): Int? = withContext(Dispatchers.IO) {
        if (!QuranCatalog.isValidAyah(surah, ayah)) return@withContext null
        val approxStart = QuranCatalog.surahPages[surah - 1]
        val approxEnd = QuranCatalog.surahPages.getOrNull(surah) ?: 604
        val start = (approxStart - 1).coerceIn(1, 604)
        val end = (approxEnd + 1).coerceIn(start, 604)
        // Usually 2–30 files, not 604. If the table differs from the user's
        // mushaf, scan the rest once; don't silently jump to a guessed page.
        val local = (start..end).firstOrNull { candidate ->
            page(candidate).any { it.surah == surah && it.ayah == ayah }
        }
        local ?: (1..604).asSequence().filter { it !in start..end }.firstOrNull { candidate ->
            page(candidate).any { it.surah == surah && it.ayah == ayah }
        }
    }

    private fun parsePage(node: Any?): List<Verse> {
        val root = node as? JSONObject
        if (node is JSONArray) return (0 until node.length()).mapNotNull { i ->
            verseFromObject(node.optJSONObject(i))
        }
        if (root == null) return emptyList()
        (root.opt("verses") as? JSONArray)?.let { return parsePage(it) }
        (root.opt("data") as? JSONArray)?.let { return parsePage(it) }
        (root.opt("data") as? JSONObject)?.let { return parsePage(it) }
        val res = mutableListOf<Verse>()
        // Original format: {"1": {"1": {"ar":...,"translation":...,"tafsir":...}}}
        root.keys().asSequence().sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }.forEach { s ->
            val surah = s.toIntOrNull() ?: return@forEach
            val verses = root.optJSONObject(s) ?: return@forEach
            verses.keys().asSequence().sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }.forEach { a ->
                val ayah = a.toIntOrNull() ?: return@forEach
                val v = verses.optJSONObject(a) ?: return@forEach
                verseFromObject(v, surah, ayah)?.let(res::add)
            }
        }
        return res
    }

    private fun verseFromObject(v: JSONObject?, defaultSurah: Int = 0, defaultAyah: Int = 0): Verse? {
        if (v == null) return null
        val surah = v.optInt("surah", v.optInt("surah_number", defaultSurah))
        val ayah = v.optInt("ayah", v.optInt("ayah_number", defaultAyah))
        if (surah !in 1..114 || ayah < 1) return null
        fun first(vararg keys: String): String = keys.firstNotNullOfOrNull { k ->
            v.optString(k).takeIf { it.isNotBlank() && it != "null" }
        }.orEmpty()
        return Verse(surah, ayah,
            first("ar", "arabic", "arabic_text"),
            first("translation", "translate", "translation_ru"),
            first("tafsir", "explanation", "text"))
    }

    /** Renderable paragraphs: quotation markers and lists retain their semantic grouping. */
    data class Paragraph(val text: String, val type: Type) {
        enum class Type { BODY, QUOTE, BULLET, HEADER }
    }
    fun format(raw: String): List<Paragraph> {
        val plain = raw
            .replace(Regex("<blockquote[^>]*>", RegexOption.IGNORE_CASE), "\n§QUOTE§ ")
            .replace(Regex("</blockquote\\s*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<h[1-6][^>]*>", RegexOption.IGNORE_CASE), "\n§HEAD§ ")
            .replace(Regex("</h[1-6]\\s*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<li[^>]*>", RegexOption.IGNORE_CASE), "\n• ")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</p\\s*>", RegexOption.IGNORE_CASE), "\n\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ").replace("&quot;", "\"")
            .replace("&laquo;", "«").replace("&raquo;", "»")
            .replace("&amp;", "&")
        return plain.split('\n').map(String::trim).filter(String::isNotBlank).map { text ->
            when {
                text.startsWith("§QUOTE§") -> Paragraph(text.removePrefix("§QUOTE§").trim(), Paragraph.Type.QUOTE)
                text.startsWith("§HEAD§") -> Paragraph(text.removePrefix("§HEAD§").trim(), Paragraph.Type.HEADER)
                text.startsWith("«") || text.startsWith("“") || text.startsWith("\"") -> Paragraph(text, Paragraph.Type.QUOTE)
                text.startsWith("-") || text.startsWith("•") -> Paragraph(text, Paragraph.Type.BULLET)
                text.endsWith(":") && text.length < 100 -> Paragraph(text, Paragraph.Type.HEADER)
                else -> Paragraph(text, Paragraph.Type.BODY)
            }
        }
    }
}
