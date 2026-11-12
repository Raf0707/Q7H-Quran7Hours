package raf.console.quran7hours.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

class TafsirStore(private val context: Context) {
    private val cache = object : LinkedHashMap<Int, List<Verse>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<Verse>>?): Boolean = size > 14
    }

    data class Verse(val surah: Int, val ayah: Int, val arabic: String, val translation: String, val explanation: String)
    /** Zero-ayah record from Azan.ru. It is metadata, never a playable Quran ayah. */
    data class SurahIntroduction(
        val surah: Int,
        val heading: String,       // original JSON "ar": usually "Сура N. ..."
        val translation: String,   // original JSON "translation": name translation
        val explanation: String    // complete original JSON "tafsir", footnotes retained
    )

    data class ReadingPage(
        val verses: List<Verse>,
        val introductions: List<SurahIntroduction>
    )

    private val introCache = object : LinkedHashMap<Int, List<SurahIntroduction>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<SurahIntroduction>>?): Boolean = size > 14
    }

    /** Read ayah 0 only for presentation; do NOT include it in page() or ayah navigation. */
    suspend fun introductions(number: Int): List<SurahIntroduction> = withContext(Dispatchers.IO) {
        if (number !in 1..604) return@withContext emptyList()
        synchronized(introCache) { introCache[number] }?.let { return@withContext it }
        val path = "tafsir_azan_ru/${number.toString().padStart(3, '0')}.json"
        val result = try {
            val raw = context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = try { JSONTokener(raw).nextValue() as JSONObject }
                catch (first: Exception) {
                    JSONTokener(repairLegacyJsonSyntax(raw)).nextValue() as JSONObject
                }
            val resolved = root.optJSONObject("data") ?: root
            resolved.keys().asSequence().mapNotNull { key ->
                val surah = key.toIntOrNull()?.takeIf { it in 1..114 } ?: return@mapNotNull null
                val zero = resolved.optJSONObject(key)?.optJSONObject("0") ?: return@mapNotNull null
                val heading = zero.optString("ar").takeIf { it != "null" }.orEmpty()
                val titleTranslation = zero.optString("translation").takeIf { it != "null" }.orEmpty()
                val commentary = zero.optString("tafsir").takeIf { it != "null" }.orEmpty()
                if (heading.isBlank() && titleTranslation.isBlank() && commentary.isBlank()) null
                else SurahIntroduction(surah, heading, titleTranslation, commentary)
            }.sortedBy { it.surah }.toList()
        } catch (error: Exception) {
            Log.e("TafsirStore", "Failed to load surah introductions from $path", error)
            return@withContext emptyList() // failure is NOT cached
        }
        synchronized(introCache) { introCache[number] = result }
        result
    }

    /** Load both sources before making a page visible; avoids list jumps. */
    suspend fun readingPage(number: Int): ReadingPage = withContext(Dispatchers.IO) {
        ReadingPage(page(number), introductions(number))
    }


    suspend fun page(number: Int): List<Verse> = withContext(Dispatchers.IO) {
        if (number !in 1..604) return@withContext emptyList()
        synchronized(cache) { cache[number] }?.let { return@withContext it }
        val file = "tafsir_azan_ru/${number.toString().padStart(3, '0')}.json"
        val parsed = try {
            val text = context.assets.open(file).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val result = try {
                parsePage(JSONTokener(text).nextValue())
            } catch (first: Exception) {
                // Some legacy Azan.ru exports contain literal line breaks inside
                // quoted Russian tafsir/translation text and trailing commas.
                // Repair JSON delimiters only: never rewrite Arabic or tafsir words.
                val repaired = repairLegacyJsonSyntax(text)
                if (repaired == text) throw first
                val recovered = parsePage(JSONTokener(repaired).nextValue())
                Log.w("TafsirStore", "Recovered legacy JSON formatting in $file", first)
                recovered
            }
            if (result.isEmpty()) Log.e("TafsirStore", "$file parsed, but no verses recognized")
            result
        } catch (e: Exception) {
            // Do not hide a missing/malformed page as if tafsir did not exist.
            Log.e("TafsirStore", "Failed to open/parse $file. Check APK assets and JSON syntax", e)
            emptyList()
        }
        // An empty result could mean an absent ZIP asset or a broken file.
        // Do NOT cache that state forever: let a future visit retry.
        if (parsed.isNotEmpty()) synchronized(cache) { cache[number] = parsed }
        parsed
    }

    /** Escape only raw line breaks INSIDE JSON strings and remove stray trailing
     * commas. Existing backslash escapes remain untouched; all text is preserved. */
    private fun repairLegacyJsonSyntax(source: String): String {
        val out = StringBuilder(source.length + 32)
        var quoted = false
        var escaped = false
        var cursor = 0
        while (cursor < source.length) {
            val ch = source[cursor]
            if (quoted) {
                when {
                    escaped -> { out.append(ch); escaped = false }
                    ch == '\\' -> { out.append(ch); escaped = true }
                    ch == '"' -> { out.append(ch); quoted = false }
                    ch == '\n' -> out.append("\\n")
                    ch == '\r' -> out.append("\\r")
                    ch == '\t' -> out.append("\\t")
                    ch.code < 0x20 -> out.append("\\u%04x".format(ch.code))
                    else -> out.append(ch)
                }
            } else {
                when (ch) {
                    '"' -> { quoted = true; out.append(ch) }
                    ',' -> {
                        var next = cursor + 1
                        while (next < source.length && source[next].isWhitespace()) next++
                        if (next >= source.length || source[next] !in charArrayOf('}', ']')) out.append(ch)
                    }
                    else -> out.append(ch)
                }
            }
            cursor++
        }
        return out.toString()
    }

    suspend fun findPageForAyah(surah: Int, ayah: Int): Int? = withContext(Dispatchers.IO) {
        if (!QuranCatalog.isValidAyah(surah, ayah)) return@withContext null
        val approxStart = QuranCatalog.surahPages[surah - 1]
        val approxEnd = QuranCatalog.surahPages.getOrNull(surah) ?: 604
        val start = (approxStart - 1).coerceIn(1, 604)
        val end = (approxEnd + 1).coerceIn(start, 604)
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
