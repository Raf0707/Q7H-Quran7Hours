package raf.console.quran7hours.ui

import android.content.Context
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import raf.console.quran7hours.data.TafsirHafsTypeface
import java.util.LinkedHashMap
import java.util.Locale

/**
 * Offline tafsir Quran source. These are the ORIGINAL surah JSON files from
 * Quran7HoursGrantUpd: assets/quran/data/surahs/001.json ... 114.json.
 *
 * - ayahs[].a: verse number (the Hanafi numbering of al-Fatiha is preserved)
 * - ayahs[].ar: Unicode Arabic without colored rules
 * - ayahs[].tw: SAME Unicode Arabic with <tajweed class=...> tags
 *
 * Only the lightweight ar/tw fields are cached, not the large tafsir bodies.
 * No network, no HTML WebView, no PUA/QCF4 font substitutions.
 */
private data class OfflineTafsirAyah(val arabic: String, val tagged: String)

private object OfflineSurahTajweed {
    private const val CACHE_LIMIT = 8
    private val cacheLock = Any()
    private val cache = object : LinkedHashMap<Int, Map<Int, OfflineTafsirAyah>>(
        16, 0.75f, true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Int, Map<Int, OfflineTafsirAyah>>?
        ): Boolean = size > CACHE_LIMIT
    }

    suspend fun get(context: Context, surah: Int, ayah: Int): OfflineTafsirAyah? =
        withContext(Dispatchers.IO) {
            if (surah !in 1..114 || ayah !in 1..286) return@withContext null
            val saved = synchronized(cacheLock) { cache[surah] }
            val verses = saved ?: load(context.applicationContext, surah).also { loaded ->
                synchronized(cacheLock) { cache[surah] = loaded }
            }
            verses[ayah]
        }

    private fun load(context: Context, surah: Int): Map<Int, OfflineTafsirAyah> {
        val path = "quran/data/surahs/%03d.json".format(Locale.ROOT, surah)
        return try {
            val json = context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(json)
            require(root.optInt("id") == surah) { "Wrong surah id in $path" }
            val array = root.getJSONArray("ayahs")
            val result = LinkedHashMap<Int, OfflineTafsirAyah>(array.length())
            for (i in 0 until array.length()) {
                val row = array.getJSONObject(i)
                val number = row.optInt("a", 0)
                val arabic = row.optString("ar")
                val tagged = row.optString("tw")
                if (number <= 0 || arabic.isBlank()) continue
                result[number] = OfflineTafsirAyah(arabic, tagged)
            }
            result
        } catch (e: Exception) {
            Log.e("Q7HTafsirLocal", "Cannot read $path", e)
            emptyMap()
        }
    }
}

/**
 * Parse only the two known inline elements in `tw`:
 * <tajweed class=rule>...content...</tajweed> and
 * <span class=end>verse number</span>. The latter is NOT Quran text.
 *
 * Fail closed: if stripping markup does not reproduce `ar` exactly, show `ar`
 * WITHOUT coloring, never a damaged or silently altered Quran verse.
 */
internal object LocalTajweedMarkup {
    private val tag = Regex("""<(/?)([A-Za-z]+)([^>]*)>""", RegexOption.IGNORE_CASE)
    private val clazz = Regex(
        """\bclass\s*=\s*(?:\"([^\"]+)\"|'([^']+)'|([a-zA-Z0-9_-]+))""",
        RegexOption.IGNORE_CASE
    )

    private fun ruleColor(rule: String, dark: Boolean): Int? {
        val color = if (dark) when (rule.lowercase(Locale.ROOT)) {
            "ham_wasl", "slnt", "laam_shamsiyah", "idgh_mus" -> 0xFFBDBDBDL
            "madda_normal" -> 0xFF93B7FFL
            "madda_permissible" -> 0xFF9F9BFFL
            "madda_necessary" -> 0xFF9DADFFL
            "madda_obligatory", "madda_pbligatory" -> 0xFF80A0FFL
            "qlq" -> 0xFFFF7377L
            "ikhf", "ikhf_shfw" -> 0xFFE6A0F3L
            "idgh_ghn", "idgh_w_ghn" -> 0xFF67E1A8L
            "idghm_shfw" -> 0xFFA1DF6BL
            "iqlb" -> 0xFF70DDFCL
            "ghn" -> 0xFFFFAD6FL
            else -> return null
        } else when (rule.lowercase(Locale.ROOT)) {
            "ham_wasl", "slnt", "laam_shamsiyah", "idgh_mus" -> 0xFF858585L
            "madda_normal" -> 0xFF537FFFL
            "madda_permissible" -> 0xFF4050FFL
            "madda_necessary" -> 0xFF000EBCL
            "madda_obligatory", "madda_pbligatory" -> 0xFF2144C1L
            "qlq" -> 0xFFDD0008L
            "ikhf", "ikhf_shfw" -> 0xFF9400A8L
            "idgh_ghn", "idgh_w_ghn" -> 0xFF169777L
            "idghm_shfw" -> 0xFF58B800L
            "iqlb" -> 0xFF137DB0L
            "ghn" -> 0xFFC35D07L
            else -> return null
        }
        return color.toInt()
    }

    fun build(arabic: String, markup: String, enabled: Boolean, dark: Boolean): CharSequence {
        if (!enabled || markup.isBlank()) return arabic
        val output = SpannableStringBuilder()
        val rules = ArrayList<String>()
        var skipMarker = false
        var malformed = false
        var cursor = 0

        fun addText(fragment: String) {
            if (fragment.isEmpty() || skipMarker) return
            val start = output.length
            output.append(fragment)
            rules.lastOrNull()?.let { rule ->
                ruleColor(rule, dark)?.let { color ->
                    output.setSpan(
                        ForegroundColorSpan(color),
                        start,
                        output.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
        }

        for (match in tag.findAll(markup)) {
            addText(markup.substring(cursor, match.range.first))
            val closing = match.groupValues[1] == "/"
            val name = match.groupValues[2].lowercase(Locale.ROOT)
            val attr = match.groupValues[3]
            when (name) {
                "tajweed" -> {
                    if (skipMarker) {
                        malformed = true
                    } else if (closing) {
                        if (rules.isEmpty()) malformed = true
                        else rules.removeAt(rules.lastIndex)
                    } else {
                        val c = clazz.find(attr)
                        val rule = c?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
                        if (rule.isNullOrBlank()) malformed = true
                        else rules.add(rule)
                    }
                }
                "span" -> {
                    if (closing) {
                        if (!skipMarker) malformed = true
                        skipMarker = false
                    } else {
                        val c = clazz.find(attr)
                        val css = c?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
                        if (skipMarker || css != "end") malformed = true
                        else skipMarker = true
                    }
                }
                else -> malformed = true
            }
            cursor = match.range.last + 1
        }
        addText(markup.substring(cursor))
        if (skipMarker || rules.isNotEmpty() || malformed ||
            output.toString() != arabic || output.toString().contains('<')
        ) {
            // Do not emit partially colored, truncated or malformed Arabic.
            return SpannableString(arabic)
        }
        return output
    }
}

/** Render a single verse. This is deliberately independent of remote TafsirQuranScript. */
@Composable
fun TafsirLocalTajweedText(
    surah: Int,
    ayah: Int,
    fallbackArabic: String,
    tajweed: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val verse by produceState<OfflineTafsirAyah?>(null, surah, ayah) {
        value = OfflineSurahTajweed.get(context, surah, ayah)
    }
    val typeface by produceState<Typeface?>(null, context.applicationContext) {
        value = TafsirHafsTypeface.get(context.applicationContext)
    }

    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.45f
    val defaultTextColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val content = remember(verse, fallbackArabic, tajweed, dark) {
        val source = verse
        if (source == null) fallbackArabic else LocalTajweedMarkup.build(
            arabic = source.arabic,
            markup = source.tagged,
            enabled = tajweed,
            dark = dark
        )
    }

    AndroidView(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        factory = { ctx ->
            TextView(ctx).apply {
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                textDirection = View.TEXT_DIRECTION_RTL
                gravity = Gravity.RIGHT
                textLocale = Locale.forLanguageTag("ar")
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 29f)
                setLineSpacing(8f * resources.displayMetrics.density, 1.23f)
                includeFontPadding = true
                breakStrategy = Layout.BREAK_STRATEGY_HIGH_QUALITY
                hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
        update = { view ->
            view.typeface = typeface ?: Typeface.create("serif", Typeface.NORMAL)
            view.setTextColor(defaultTextColor)
            view.text = content
        }
    )
}
