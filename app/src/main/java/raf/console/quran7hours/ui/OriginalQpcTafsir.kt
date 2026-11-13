package raf.console.quran7hours.ui

import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.unit.sp
import android.util.TypedValue
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.Normalizer
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** One run of word-glyphs using the QPC font of the physical Mushaf page. */
/** Physical glyph offsets of rubʿ al-hizb stars (۞) in this font run. */
internal data class OriginalQpcPart(
    val page: Int,
    val glyphs: String,
    val rubStarOffsets: List<Int> = emptyList()
)
internal data class OriginalQpcAyah(val parts: List<OriginalQpcPart>)

private const val QPC_ROOT = "quran/qpc-v4"
private const val LOG_TAG = "QpcTafsir"

private fun verseId(surah: Int, ayah: Int): Long = (surah.toLong() shl 32) or (ayah.toLong() and 0xffffffffL)

private data class OriginalQpcWord(
    val surah: Int,
    val ayah: Int,
    val wordNumber: Int,
    val word: String,
    val glyphs: String,
    val page: Int,
    val rubStarAtStart: Boolean = false
)

/**
 * IMPORTANT: the previous implementation searched current page +/- 4, then required
 * identical normalized Arabic spelling. Many valid ayahs (e.g. 14:43) use a different
 * Uthmani spelling in the Azan.ru source, causing an unnecessary Unicode fallback.
 *
 * This repository indexes all 604 canonical QPC layouts ONCE on Dispatchers.IO.
 * The index is small (ayah key -> page numbers) and guarantees that words of an ayah
 * crossing any page boundary are collected in full. Then it verifies word positions
 * and uses a tolerant, *bounded* Quran-script orthography check, not an exact match.
 * It never uses a similar neighboring ayah or maps text by page order alone.
 */
internal class OriginalQpcTafsirRepository(context: Context) {
    private val assets = context.applicationContext.assets
    private val indexLock = Mutex()
    @Volatile private var versePages: Map<Long, List<Int>>? = null

    private val pageCache = object : LinkedHashMap<Int, List<OriginalQpcWord>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<OriginalQpcWord>>?): Boolean = size > 20
    }

    // Android AssetManager.list() returns only filenames, not recursive descendants.
    private val v4Files: Set<String> by lazy {
        runCatching { assets.list("$QPC_ROOT/fonts/v4")?.toSet().orEmpty() }.getOrDefault(emptySet())
    }
    private val v2Files: Set<String> by lazy {
        runCatching { assets.list("$QPC_ROOT/fonts/v2")?.toSet().orEmpty() }.getOrDefault(emptySet())
    }
    private val layoutFiles: Set<String> by lazy {
        runCatching { assets.list("$QPC_ROOT/layout")?.toSet().orEmpty() }.getOrDefault(emptySet())
    }

    private val packComplete: Boolean by lazy {
        (1..604).all { p ->
            "p$p.ttf" in v4Files && "p$p.ttf" in v2Files &&
                "page-${p.toString().padStart(3, '0')}.json" in layoutFiles
        }
    }

    fun isPackComplete(): Boolean = packComplete

    suspend fun forPage(
        page: Int,
        verses: List<raf.console.quran7hours.data.TafsirStore.Verse>
    ): Map<Pair<Int, Int>, OriginalQpcAyah> = withContext(Dispatchers.IO) {
        if (page !in 1..604 || verses.isEmpty()) return@withContext emptyMap()
        if (!packComplete) {
            Log.w(LOG_TAG, "QPC V2/V4/layout pack incomplete; run tools/audit_qpc_tafsir_pack.py")
            return@withContext emptyMap()
        }

        val index = getVersePageIndex()
        val result = LinkedHashMap<Pair<Int, Int>, OriginalQpcAyah>()
        for (verse in verses) {
            // The Azan.ru Hanafi numbering of al-Fatiha is preserved. The QPC
            // source treats basmala as 1:1 and combines Hanafi 1:6+1:7 in QPC 1:7.
            val key = verse.surah to verse.ayah
            val qpcAyah = when {
                verse.surah == 1 && verse.ayah in 1..5 -> verse.ayah + 1
                verse.surah == 1 && verse.ayah in 6..7 -> 7
                else -> verse.ayah
            }
            // All QPC layout JSON files now match the physical QCF V2/V4
            // page fonts. Resolve the source words by their canonical page;
            // never apply an ayah-specific font/page override.
            val pages = index[verseId(verse.surah, qpcAyah)].orEmpty()
            if (pages.isEmpty()) {
                Log.w(LOG_TAG, "No verified QPC layout for ${verse.surah}:${verse.ayah} (QPC $qpcAyah)")
                continue
            }
            val canonicalWords = ArrayList<OriginalQpcWord>()
            for (physicalPage in pages) {
                canonicalWords.addAll(wordsForPage(physicalPage).filter {
                    it.surah == verse.surah && it.ayah == qpcAyah
                })
            }
            canonicalWords.sortWith(compareBy<OriginalQpcWord> { it.wordNumber }.thenBy { it.page })
            if (canonicalWords.isEmpty() || canonicalWords[0].wordNumber != 1 ||
                canonicalWords.indices.any { canonicalWords[it].wordNumber != it + 1 }) {
                Log.w(LOG_TAG, "Incomplete QPC words for ${verse.surah}:$qpcAyah")
                continue
            }
            val words: List<OriginalQpcWord> = if (verse.surah == 1 && verse.ayah in 6..7) {
                // Explicit Hanafi 1:6 = four words, 1:7 = five words.
                if (canonicalWords.size != 9 ||
                    !qpcSkeleton(canonicalWords[0].word).startsWith("صر") ||
                    !qpcSkeleton(canonicalWords[4].word).startsWith("غير")) {
                    Log.w(LOG_TAG, "Unexpected QPC Fatiha 1:7 word sequence; Unicode fallback")
                    continue
                }
                if (verse.ayah == 6) canonicalWords.subList(0, 4)
                else canonicalWords.subList(4, 9)
            } else canonicalWords
            val qpcArabic = words.joinToString(" ") { it.word }
            if (!likelySameVerse(verse.arabic, qpcArabic)) {
                Log.w(LOG_TAG, "Different Quran text for ${verse.surah}:${verse.ayah}; safe Unicode fallback")
                continue
            }
            val parts = ArrayList<OriginalQpcPart>()
            for (word in words) {
                if (word.glyphs.isBlank()) continue
                // The glyph and its actual physical QPC page always agree.
                val fontPage = word.page
                // In the source layout, the star is identified by the readable
                // word text "۞ ...", NOT by a fixed QPC character code. Every
                // page reuses U+FC41, so relying on its code would also pad
                // ordinary words on hundreds of unrelated pages.
                val starInWord = if (word.rubStarAtStart)
                    word.glyphs.indexOfFirst { !it.isWhitespace() } else -1
                val previous = parts.lastOrNull()
                if (previous != null && previous.page == fontPage) {
                    val starOffset = if (starInWord >= 0)
                        previous.glyphs.length + 1 + starInWord else -1
                    parts[parts.lastIndex] = previous.copy(
                        glyphs = previous.glyphs + " " + word.glyphs,
                        rubStarOffsets = if (starOffset >= 0)
                            previous.rubStarOffsets + starOffset
                        else previous.rubStarOffsets
                    )
                } else {
                    parts.add(OriginalQpcPart(
                        fontPage, word.glyphs,
                        if (starInWord >= 0) listOf(starInWord) else emptyList()
                    ))
                }
            }
            if (parts.isNotEmpty()) {
                result[key] = OriginalQpcAyah(parts)
            }
        }
        result
    }

    /** Cache the physical QPC page(s) for each canonical ayah. */
    private suspend fun getVersePageIndex(): Map<Long, List<Int>> {
        versePages?.let { return it }
        return indexLock.withLock {
            versePages?.let { return@withLock it }
            // 604 small JSON files are read once in a background dispatcher;
            // index is cached for the lifetime of the Tafsir panel.
            val map = HashMap<Long, MutableSet<Int>>(6500)
            for (p in 1..604) {
                for (word in wordsForPage(p)) {
                    map.getOrPut(verseId(word.surah, word.ayah)) { linkedSetOf() }.add(p)
                }
            }
            val immutable = map.mapValues { (_, pages) -> pages.sorted() }
            versePages = immutable
            Log.i(LOG_TAG, "QPC index: ${immutable.size} ayah keys found across 604 pages")
            immutable
        }
    }

    private fun wordsForPage(page: Int): List<OriginalQpcWord> {
        synchronized(pageCache) { pageCache[page]?.let { return it } }
        val path = "$QPC_ROOT/layout/page-${page.toString().padStart(3, '0')}.json"
        val words = try {
            assets.open(path).bufferedReader(Charsets.UTF_8).use { stream ->
                val json = JSONObject(stream.readText())
                require(json.getInt("page") == page) { "Mismatched QPC page number in $path" }
                val lines = json.getJSONArray("lines")
                buildList {
                    for (i in 0 until lines.length()) {
                        val line = lines.getJSONObject(i)
                        if (line.optString("type") != "text") continue
                        val entries = line.optJSONArray("words") ?: continue
                        for (j in 0 until entries.length()) {
                            val entry = entries.getJSONObject(j)
                            val loc = entry.optString("location").split(':')
                            if (loc.size != 3) continue
                            val s = loc[0].toIntOrNull() ?: continue
                            val a = loc[1].toIntOrNull() ?: continue
                            val w = loc[2].toIntOrNull() ?: continue
                            val glyphs = entry.optString("qpcV2")
                            if (s !in 1..114 || a < 1 || w < 1 || glyphs.isBlank()) continue
                            val readableWord = entry.optString("word")
                            // Quranic rubʿ al-hizb ornament is a separate glyph
                            // before the word. The Unicode marker occurs only in
                            // layout metadata; the actual font glyph is PUA.
                            val hasRubStar = readableWord.trimStart().startsWith("۞")
                            add(OriginalQpcWord(
                                s, a, w, readableWord, glyphs, page, hasRubStar
                            ))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Cannot read QPC layout $path", e)
            emptyList()
        }
        synchronized(pageCache) { pageCache[page] = words }
        return words
    }
}

/**
 * Use the consonant skeleton only for a SAFETY check, never to construct Quran text.
 * Normalize waqf, tashkeel, Uthmani hamza variants (رءوس/رؤوس), tatweel,
 * alif/alif maqsura, while ensuring a clearly different ayah cannot be substituted.
 */
private fun qpcSkeleton(input: String): String = buildString(input.length) {
    val decomposed = Normalizer.normalize(input, Normalizer.Form.NFKD)
    var at = 0
    while (at < decomposed.length) {
        val cp = Character.codePointAt(decomposed, at)
        at += Character.charCount(cp)
        if (cp == 0x0640 || cp == 0x0621) continue // tatweel or standalone hamza
        when (Character.getType(cp)) {
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt() -> continue
        }
        if (!Character.isLetter(cp) || Character.UnicodeScript.of(cp) != Character.UnicodeScript.ARABIC) continue
        val normalized = when (cp) {
            0x0671, 0x0622, 0x0623, 0x0625 -> 0x0627
            0x0649, 0x0626 -> 0x064A
            0x0624 -> 0x0648
            else -> cp
        }
        appendCodePoint(normalized)
    }
}

private fun levenshteinForSmallVerse(a: String, b: String): Int =
    boundedDistance(a, b, max(a.length, b.length))

private fun likelySameVerse(azanText: String, qpcText: String): Boolean {
    val azan = qpcSkeleton(azanText)
    val qpc = qpcSkeleton(qpcText)
    if (azan.isEmpty() || qpc.isEmpty()) return false
    if (azan == qpc) return true
    val longer = max(azan.length, qpc.length)
    // Allowed edits cover normal orthographic differences but not a distinct ayah.
    val allowed = max(1, (longer * if (longer < 12) 0.25 else 0.32).toInt())
    return boundedDistance(azan, qpc, allowed) <= allowed
}

/** Efficient Ukkonen band: O(length * allowed), early-exit on substantial mismatches. */
private fun boundedDistance(a: String, b: String, allowed: Int): Int {
    if (abs(a.length - b.length) > allowed) return allowed + 1
    if (a == b) return 0
    val inf = allowed + 1
    var previous = IntArray(b.length + 1) { if (it <= allowed) it else inf }
    var current = IntArray(b.length + 1) { inf }
    for (i in 1..a.length) {
        java.util.Arrays.fill(current, inf)
        if (i <= allowed) current[0] = i
        val from = max(1, i - allowed)
        val to = min(b.length, i + allowed)
        var best = inf
        for (j in from..to) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            val v = min(min(previous[j] + 1, current[j - 1] + 1), previous[j - 1] + cost)
            current[j] = v
            if (v < best) best = v
        }
        if (best > allowed) return inf
        val old = previous
        previous = current
        current = old
    }
    return previous[b.length]
}

/** Font is assigned to each PAGE run; no shaping or diacritic position is recomputed. */
private class QpcFontSpan(private val typeface: Typeface) : MetricAffectingSpan() {
    override fun updateMeasureState(p: TextPaint) { p.typeface = typeface }
    override fun updateDrawState(p: TextPaint) { p.typeface = typeface }
}

/**
 * QPC fonts expose very generous vertical metrics. A fixed 1.6 multiplier plus
 * includeFontPadding made visual baselines ~200px apart in the reported screenshot.
 *
 * Measure ACTUAL word-glyph ink bounds with each appropriate page Typeface,
 * then use one tight line box for all lines of the ayah. Add a safety margin
 * so the upper/lower Quran marks remain visible and do not overlap.
 */
private class QpcInkLineHeight(private val ascentPx: Int, private val descentPx: Int) : LineHeightSpan {
    override fun chooseHeight(
        text: CharSequence, start: Int, end: Int, spanstartv: Int, v: Int,
        fm: Paint.FontMetricsInt
    ) {
        fm.ascent = -ascentPx
        fm.top = -ascentPx
        fm.descent = descentPx
        fm.bottom = descentPx
    }
}

private class QpcFontCache(private val context: Context) {
    private val cache = object : LinkedHashMap<String, Typeface>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Typeface>?): Boolean = size > 24
    }
    fun get(page: Int, tajweed: Boolean): Typeface? {
        val path = "$QPC_ROOT/fonts/${if (tajweed) "v4" else "v2"}/p$page.ttf"
        synchronized(cache) { cache[path]?.let { return it } }
        val font = runCatching { Typeface.createFromAsset(context.assets, path) }
            .onFailure { Log.e(LOG_TAG, "Cannot load font $path", it) }
            .getOrNull() ?: return null
        synchronized(cache) { cache[path] = font }
        return font
    }
}

private object QpcSharedFonts {
    private val caches = WeakHashMap<Context, QpcFontCache>()
    fun forContext(context: Context): QpcFontCache = synchronized(caches) {
        val app = context.applicationContext
        caches.getOrPut(app) { QpcFontCache(app) }
    }
}

/**
 * Unified QPC renderer shared by Hafiz Diary and Tafsir.
 * The adjustable renderer loads the actual page-specific font as the TextView
 * base, and selects the built-in dark V4 palette while preserving color tajweed
 * and decorative verse-number ornaments. Exact physical page selection
 * now comes directly from the corrected QPC layout assets.
 */
@Composable
internal fun OriginalQpcTafsirText(
    ayah: OriginalQpcAyah,
    tajweed: Boolean,
    fontSizeSp: Float = 29f
) {
    Q7HAdjustableOriginalQpcText(ayah, tajweed, fontSizeSp)
}

/** Preload the EXACT typefaces used by the visible ayahs before any TextView is displayed.
 * Font loading only happens on Dispatchers.IO via QpcPreparedPages.
 */
internal fun warmOriginalQpcFonts(context: Context, ayahs: Collection<OriginalQpcAyah>) {
    val fonts = QpcSharedFonts.forContext(context)
    ayahs.asSequence().flatMap { it.parts.asSequence() }.map { it.page }.distinct().forEach { page ->
        fonts.get(page, true)
        fonts.get(page, false)
    }
}
