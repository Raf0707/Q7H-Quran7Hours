package raf.console.quran7hours.data

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Accurate page/ayah captions are read from the user's tafsir_azan_ru page files.
 * Optional catalog_details.json overrides are used verbatim for original Java captions.
 * A page number alone cannot establish an exact juz/hizb boundary inside that page.
 */
class CatalogDetails(private val context: Context) {
    private val original: JSONObject? by lazy {
        runCatching {
            context.assets.open("catalog_details.json").bufferedReader().use { JSONObject(it.readText()) }
        }.getOrNull()
    }
    private val tafsir = TafsirStore(context)
    private val versesCache = ConcurrentHashMap<Int, List<VerseReference>>()

    data class VerseReference(val surah: Int, val ayah: Int)

    private fun originalText(segment: QuranCatalog.Segment, number: Int): String? {
        val key = when (segment) {
            QuranCatalog.Segment.SURAH -> "surahs"
            QuranCatalog.Segment.PAGE -> "pages"
            QuranCatalog.Segment.JUZ -> "juz"
            QuranCatalog.Segment.HIZB -> "hizb"
            QuranCatalog.Segment.MANZIL -> "manzil"
        }
        return original?.optJSONArray(key)?.optString(number - 1)?.takeIf(String::isNotBlank)
    }

    private suspend fun pageVerses(page: Int): List<VerseReference> {
        if (page !in 1..604) return emptyList()
        versesCache[page]?.let { return it }
        val read = tafsir.page(page).map { VerseReference(it.surah, it.ayah) }
            .filter { QuranCatalog.isValidAyah(it.surah, it.ayah) }
            .distinct()
        versesCache.putIfAbsent(page, read)
        return versesCache[page].orEmpty()
    }

    suspend fun pageDescription(page: Int): String {
        if (page !in 1..604) return "Введите страницу от 1 до 604"
        originalText(QuranCatalog.Segment.PAGE, page)?.let { return it }
        val verses = pageVerses(page)
        if (verses.isEmpty()) return "Для страницы $page нет доступной разметки аятов"
        return verses.groupBy(VerseReference::surah).entries.joinToString(" · ") { (surah, items) ->
            val name = QuranCatalog.surahNames.getOrElse(surah - 1) { "Сура $surah" }
            val ayahs = items.map(VerseReference::ayah)
            if (ayahs.first() == ayahs.last()) "$name, аят ${ayahs.first()}"
            else "$name, аяты ${ayahs.first()}–${ayahs.last()}"
        }
    }

    suspend fun description(segment: QuranCatalog.Segment, number: Int): String {
        originalText(segment, number)?.let { return it }
        if (number !in 1..QuranCatalog.startPages(segment).size) return ""
        if (segment == QuranCatalog.Segment.PAGE) return pageDescription(number)
        val starts = QuranCatalog.startPages(segment)
        val firstPage = starts[number - 1]
        if (segment == QuranCatalog.Segment.SURAH) {
            val juz = QuranCatalog.segmentForPage(QuranCatalog.Segment.JUZ, firstPage)
            val hizb = QuranCatalog.segmentForPage(QuranCatalog.Segment.HIZB, firstPage)
            return "${QuranCatalog.ayahCounts[number - 1]} аятов · Джуз $juz · Хизб $hizb · стр. $firstPage"
        }
        val next = starts.getOrNull(number)
        val finalPage = (next?.minus(1) ?: 604).coerceAtLeast(firstPage)
        val firstRef = pageVerses(firstPage).firstOrNull()
        val lastRef = pageVerses(finalPage).lastOrNull()
        val approximateFrom = firstRef?.let { "${it.surah}:${it.ayah}" } ?: "?"
        val approximateTo = lastRef?.let { "${it.surah}:${it.ayah}" } ?: "?"
        // The supplied images/tafsir give page spans, NOT precise subdivision verses.
        return "Стр. $firstPage–$finalPage · на крайних страницах: $approximateFrom — $approximateTo"
    }
}
