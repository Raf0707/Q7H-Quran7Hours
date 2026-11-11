package raf.console.quran7hours.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/**
 * Reads the user's original 604-page word corpus directly from APK assets:
 *   word_by_word/001.json .. word_by_word/604.json
 * A word carries its original 1:2:3 location; no roots, inference, or splitting
 * of the Arabic tafsir text is performed (word forms and repetitions stay intact).
 */
class WordByWordStore(private val context: Context) {
    data class Word(val position: Int, val arabic: String, val translationRu: String)
    private data class PageWords(val verses: Map<Pair<Int, Int>, List<Word>>)

    private val cache = object : LinkedHashMap<Int, PageWords>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PageWords>?) = size > 12
    }
    private val locationPattern = Regex("^(\\d{1,3}):(\\d{1,3}):(\\d{1,4})$")
    private val assetNames = listOf(
        "word_by_word", "word_by_word/word_by_word",
        "quran/data/word-by-word/pages"
    )

    suspend fun forPage(page: Int, verses: List<TafsirStore.Verse>):
        Map<Pair<Int, Int>, List<Word>> = withContext(Dispatchers.IO) {
        if (page !in 1..604 || verses.isEmpty()) return@withContext emptyMap()
        // Adjacent pages matter: a single verse may span two mushaf pages.
        val wanted = verses.map { it.surah to it.ayah }.toSet()
        val collected = wanted.associateWith { sortedMapOf<Int, Word>() }.toMutableMap()
        val conflicted = mutableSetOf<Pair<Int, Int>>()
        for (n in (page - 2).coerceAtLeast(1)..(page + 2).coerceAtMost(604)) {
            for ((key, words) in loadPage(n).verses) {
                val target = collected[key] ?: continue
                for (word in words) {
                    val old = target.putIfAbsent(word.position, word)
                    if (old != null && old != word) conflicted.add(key)
                }
            }
        }
        val result = wanted.associateWith { key ->
            val found = collected[key].orEmpty()
            if (key in conflicted || found.isEmpty() || found.keys.toList() != (1..found.size).toList())
                emptyList() else found.values.toList()
        }.toMutableMap()
        // Al-Fatihah can have different verse boundaries from the source
        // dictionary. Validate and resegment the complete Arabic word stream
        // rather than demanding that 1:1 always has the same text.
        val fatiha = verses.filter { it.surah == 1 }.sortedBy { it.ayah }
        if (fatiha.isNotEmpty()) {
            // The original 001.json is from Quran Academy: 1:1 is Basmala.
            // Load all seven original segments regardless of which Tafsir
            // verse keys are visible on the current page.
            val originalPage = loadPage(1).verses
            val source = (1..7).associateWith { n ->
                originalPage[1 to n].orEmpty().map { word ->
                    FatihahWordAlignment.Token(word.arabic, word.translationRu)
                }
            }
            val mapped = FatihahWordAlignment.align(
                fatiha.map { it.ayah to it.arabic }, source
            )
            if (mapped != null) {
                for ((ayah, tokens) in mapped) {
                    result[1 to ayah] = tokens.mapIndexed { index, token ->
                        Word(index + 1, token.arabic, token.translation)
                    }
                }
            } else {
                // If the whole surah cannot be safely aligned, keep only exact
                // individual verse matches. Never show a wrong shifted gloss.
                for (verse in fatiha) {
                    val key = 1 to verse.ayah
                    if (!sameArabic(result[key].orEmpty(), verse.arabic)) {
                        result[key] = emptyList()
                    }
                }
                Log.w("Q7H-WBW", "Al-Fatihah: invalid or unsupported verse boundaries; inspect 001.json")
            }
        }
        result
    }

    private fun sameArabic(words: List<Word>, ayahText: String): Boolean =
        words.isNotEmpty() && ayahText.isNotBlank() &&
            FatihahWordAlignment.normalized(words.joinToString("") { it.arabic }) ==
            FatihahWordAlignment.normalized(ayahText)

    private fun loadPage(page: Int): PageWords {
        synchronized(cache) { cache[page] }?.let { return it }
        val name = "%03d.json".format(Locale.ROOT, page)
        var raw: String? = null
        for (folder in assetNames) {
            raw = try {
                context.assets.open("$folder/$name").bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
            } catch (_: java.io.FileNotFoundException) { null }
              catch (error: Exception) {
                Log.w("Q7H-WBW", "Не удалось прочитать $folder/$name", error)
                null
            }
            if (raw != null) break
        }
        val parsed = runCatching { raw?.let { parse(page, JSONObject(it)) } ?: PageWords(emptyMap()) }
            .onFailure { Log.w("Q7H-WBW", "Ошибка JSON словаря, страница $page", it) }
            .getOrDefault(PageWords(emptyMap()))
        synchronized(cache) { cache[page] = parsed }
        return parsed
    }

    private fun parse(page: Int, json: JSONObject): PageWords {
        if (json.has("page") && json.optInt("page", -1) != page) return PageWords(emptyMap())
        val array = json.optJSONArray("words")
            ?: json.optJSONObject("data")?.optJSONArray("words")
            ?: return PageWords(emptyMap())
        val accumulator = mutableMapOf<Pair<Int, Int>, MutableMap<Int, Word>>()
        val invalid = mutableSetOf<Pair<Int, Int>>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val location = item.optString("location")
            val match = locationPattern.matchEntire(location) ?: continue
            val (surah, ayah, position) = match.destructured
            val s = surah.toIntOrNull() ?: continue
            val a = ayah.toIntOrNull() ?: continue
            val p = position.toIntOrNull() ?: continue
            if (!QuranCatalog.isValidAyah(s, a) || p < 1) continue
            fun first(vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
                item.optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }
            }.orEmpty()
            val arabic = first("arabic", "word", "ar", "text_ar")
            val russian = first("translation", "translation_ru", "ru", "word_translation", "meaning_ru")
            val key = s to a
            if (arabic.isBlank() || russian.isBlank() || russian == "—") {
                invalid.add(key); continue
            }
            val words = accumulator.getOrPut(key) { mutableMapOf() }
            if (words.putIfAbsent(p, Word(p, arabic, russian)) != null) invalid.add(key)
        }
        return PageWords(accumulator.mapNotNull { (key, indexed) ->
            if (key in invalid) null else key to indexed.toSortedMap().values.toList()
        }.toMap())
    }
}
