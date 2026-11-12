package raf.console.quran7hours.data

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Quran navigation metadata (Hafs, 604-page Madani mushaf).
 * Half-hizb boundaries are every TWO rub' al-hizb quarters (rub' numbers 1,3,...239).
 * Source: Tanzil quran-data.xml (CC BY 3.0), not an artificial division of 5 pages.
 * https://tanzil.net/docs/quran_metadata
 *
 * Quran7Hours uses its own Hanafi/Fatiha ayah division. The first boundary 1:1
 * means the FIRST numbered verse in Quran7Hours, without counting basmala.
 */
object QuranNavigationIndex {
    data class Ref(val surah: Int, val ayah: Int) {
        override fun toString() = "$surah:$ayah"
    }
    private val surahOffsets: IntArray = IntArray(115).apply {
        for (i in 1..114) this[i] = this[i - 1] + QuranCatalog.ayahCounts[i - 1]
    }
    // Exactly 120 half-hizbs, hence four quarters of each juz and two halves of each hizb.
    private val halfVersePairs = """
      1:1 2:44 2:75 2:106 2:142 2:177 2:203 2:233
      2:253 2:272 3:15 3:52 3:93 3:133 3:171 4:1
      4:24 4:58 4:88 4:114 4:148 5:1 5:27 5:51
      5:82 5:109 6:36 6:74 6:111 6:141 7:1 7:47
      7:88 7:142 7:171 8:1 8:41 9:1 9:34 9:60
      9:93 9:122 10:26 10:71 11:6 11:41 11:84 12:7
      12:53 12:101 13:19 14:10 15:1 16:1 16:51 16:90
      17:1 17:50 17:99 18:32 18:75 19:22 20:1 20:83
      21:1 21:51 22:1 22:38 23:1 23:75 24:21 24:53
      25:21 26:1 26:111 27:1 27:56 28:12 28:51 29:1
      29:46 30:31 31:22 33:1 33:31 33:60 34:24 35:15
      36:28 37:22 37:145 38:52 39:32 40:1 40:41 41:9
      41:47 42:27 43:24 44:17 46:1 47:10 48:18 49:14
      51:31 53:26 55:1 56:75 58:1 59:11 62:1 65:1
      67:1 69:1 72:1 75:1 78:1 82:1 87:1 94:1
    """.trimIndent().split(Regex("\\s+")).map {
        val pieces = it.split(':')
        Ref(pieces[0].toInt(), pieces[1].toInt())
    }
    val halfStarts: List<Ref> = halfVersePairs.also {
        require(it.size == 120) { "Expected 120 half-hizb boundaries, got ${it.size}" }
        require(it.zipWithNext().all { (a,b) -> absolute(a) < absolute(b) })
    }
    const val TOTAL_AYAHS = 6236
    fun absolute(ref: Ref): Int = surahOffsets[ref.surah - 1] + ref.ayah
    fun ref(global: Int): Ref {
        val v = global.coerceIn(1, TOTAL_AYAHS)
        val s = (1..114).first { v <= surahOffsets[it] }
        return Ref(s, v - surahOffsets[s - 1])
    }
    fun lastBefore(start: Ref): Ref = ref(absolute(start) - 1)
    fun halfFor(ref: Ref): Int {
        val absolute = absolute(ref)
        return (halfStarts.indexOfLast { absolute(it) <= absolute } + 1).coerceIn(1, 120)
    }
    data class Position(val juz: Int, val juzQuarter: Int, val hizb: Int, val hizbHalf: Int)
    fun position(ref: Ref): Position {
        val number = halfFor(ref) - 1
        return Position(number / 4 + 1, number % 4 + 1, number / 2 + 1, number % 2 + 1)
    }
    fun startOfHalf(number: Int): Ref = halfStarts[number.coerceIn(1,120)-1]
    fun startOfJuz(number: Int): Ref = startOfHalf((number.coerceIn(1,30)-1)*4+1)
    fun startOfHizb(number: Int): Ref = startOfHalf((number.coerceIn(1,60)-1)*2+1)
    fun endOfHalf(number: Int): Ref = if (number < 120) lastBefore(startOfHalf(number+1)) else Ref(114, 6)
    fun endOfJuz(number: Int): Ref = if (number < 30) lastBefore(startOfJuz(number+1)) else Ref(114,6)
    fun endOfHizb(number: Int): Ref = if (number < 60) lastBefore(startOfHizb(number+1)) else Ref(114,6)

    /** Hanafi tilawah-sajdah positions (14); 22:77 is not counted as a Hanafi sajdah. */
    val sajdaTilawah: Set<Ref> = setOf(
        Ref(7,206), Ref(13,15), Ref(16,50), Ref(17,109), Ref(19,58),
        Ref(22,18), Ref(25,60), Ref(27,26), Ref(32,15), Ref(38,24),
        Ref(41,38), Ref(53,62), Ref(84,21), Ref(96,19)
    )
}

/** Per-page Quran details come from the same Azan.ru files as the Tafsir, not stale Java switches. */
class QuranNavigationPageSource(context: Context) {
    private val tafsir = TafsirStore(context.applicationContext)
    private val cache = ConcurrentHashMap<Int, List<QuranNavigationIndex.Ref>>()
    private val ayahPageCache = ConcurrentHashMap<QuranNavigationIndex.Ref,Int>()
    suspend fun verses(page: Int): List<QuranNavigationIndex.Ref> {
        if (page !in 1..604) return emptyList()
        cache[page]?.let { return it }
        val value = tafsir.page(page).asSequence().map { QuranNavigationIndex.Ref(it.surah, it.ayah) }
            .filter { QuranCatalog.isValidAyah(it.surah,it.ayah) }.distinct().toList()
        cache.putIfAbsent(page, value)
        return cache[page].orEmpty()
    }
    suspend fun pageOf(ref: QuranNavigationIndex.Ref): Int? {
        ayahPageCache[ref]?.let { return it }
        val resolved = tafsir.findPageForAyah(ref.surah, ref.ayah)
        if (resolved != null) ayahPageCache.putIfAbsent(ref,resolved)
        return resolved
    }
    suspend fun first(page: Int) = verses(page).firstOrNull()
    suspend fun last(page: Int) = verses(page).lastOrNull()
    suspend fun pageDescription(page: Int): String {
        if (page !in 1..604) return "Введите страницу от 1 до 604"
        val list = verses(page)
        if (list.isEmpty()) return "Нет сведений об аятах этой страницы"
        return list.groupBy { it.surah }.entries.joinToString("\n") { (s, refs) ->
            val a = refs.map { it.ayah }
            "Сура $s. ${QuranCatalog.surahNames[s-1]} · ${if (a.first() == a.last()) "аят ${a.first()}" else "аяты ${a.first()}–${a.last()}"}"
        }
    }
    suspend fun pageSajdas(page: Int): List<QuranNavigationIndex.Ref> = verses(page)
        .filter { it in QuranNavigationIndex.sajdaTilawah }
    suspend fun summary(page: Int): String {
        if (page !in 1..604) return ""
        val first = first(page) ?: return "Нет сведений об аятах"
        val sections = verses(page).map(QuranNavigationIndex::position).distinct()
        val manzil = QuranCatalog.segmentForPage(QuranCatalog.Segment.MANZIL, page)
        val main = pageDescription(page)
        val sajda = pageSajdas(page)
        return buildString {
            append(main)
            sections.forEachIndexed { i, p ->
                append(if (i == 0) "\n" else "\nТакже на странице: ")
                append("Джуз ${p.juz}, ${p.juzQuarter}/4 · Хизб ${p.hizb}, ${p.hizbHalf}/2")
            }
            append(" · Манзиль $manzil")
            if (sajda.isNotEmpty()) append("\nСаджда-тилява: ").append(sajda.joinToString { "после ${it.surah}:${it.ayah}" })
        }
    }
}
