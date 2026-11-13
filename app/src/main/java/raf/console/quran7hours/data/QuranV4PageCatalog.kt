package raf.console.quran7hours.data

import android.content.Context
import org.json.JSONObject
import raf.console.quran7hours.data.QuranNavigationIndex.Ref

/** V4-only navigation: source of truth is assets/quran/qpc-v4/verse_page_index_q7h.json. */
class QuranV4PageCatalog(context: Context) {
    private val root: JSONObject = context.applicationContext.assets.open(
        "quran/qpc-v4/verse_page_index_q7h.json"
    ).bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
    private val surahCounts = root.getJSONArray("surah_counts")
    private val pageEntries = root.getJSONArray("page_ayahs")
    private val firstPages = root.getJSONArray("verse_first_page")
    private val offsets = IntArray(115).apply {
        for (surah in 1..114) this[surah] = this[surah-1] + surahCounts.getInt(surah-1)
    }
    private val pageVerseList: List<List<Ref>> = (0 until 604).map { n ->
        val entries = pageEntries.getJSONArray(n)
        (0 until entries.length()).map { i ->
            val verse = entries.getJSONArray(i)
            Ref(verse.getInt(0),verse.getInt(1))
        }
    }
    fun verses(page: Int): List<Ref> = pageVerseList.getOrNull(page - 1).orEmpty()
    fun first(page: Int): Ref? = verses(page).firstOrNull()
    fun last(page: Int): Ref? = verses(page).lastOrNull()
    fun pageOf(surah: Int, ayah: Int): Int? {
        if (surah !in 1..114 || ayah !in 1..surahCounts.getInt(surah-1)) return null
        return firstPages.getInt(offsets[surah-1]+ayah).takeIf { it in 1..604 }
    }
    private val manzilSurahs = intArrayOf(1, 5, 10, 17, 26, 37, 50)
    fun manzilStartRefs(): List<Ref> = manzilSurahs.map { Ref(it, 1) }
    fun startPages(segment: QuranCatalog.Segment): IntArray = when(segment) {
        QuranCatalog.Segment.SURAH -> (1..114).map { pageOf(it,1)!! }.toIntArray()
        QuranCatalog.Segment.PAGE -> IntArray(604) { it+1 }
        QuranCatalog.Segment.JUZ -> (1..30).map { n ->
            val r=QuranNavigationIndex.startOfJuz(n);pageOf(r.surah,r.ayah)!!
        }.toIntArray()
        QuranCatalog.Segment.HIZB -> (1..60).map { n ->
            val r=QuranNavigationIndex.startOfHizb(n);pageOf(r.surah,r.ayah)!!
        }.toIntArray()
        QuranCatalog.Segment.MANZIL -> manzilStartRefs().map { pageOf(it.surah,it.ayah)!! }.toIntArray()
    }
    fun segmentForPage(segment: QuranCatalog.Segment, page: Int): Int {
        if (segment == QuranCatalog.Segment.PAGE) return page.coerceIn(1,604)
        val r = first(page) ?: return 1
        return when(segment) {
            QuranCatalog.Segment.SURAH -> r.surah
            QuranCatalog.Segment.JUZ -> QuranNavigationIndex.position(r).juz
            QuranCatalog.Segment.HIZB -> QuranNavigationIndex.position(r).hizb
            QuranCatalog.Segment.MANZIL ->
                manzilSurahs.indexOfLast { it <= r.surah }.coerceAtLeast(0)+1
            QuranCatalog.Segment.PAGE -> page.coerceIn(1,604)
        }
    }
}
