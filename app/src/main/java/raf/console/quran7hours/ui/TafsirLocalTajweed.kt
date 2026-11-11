
package raf.console.quran7hours.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import raf.console.quran7hours.data.UserPreferences
import java.util.concurrent.ConcurrentHashMap

private data class LocalAyah(
    val ar: String,
    val tw: String
)

private object LocalSurahRepository {
    private val cache =
        ConcurrentHashMap<Int, Map<Int, LocalAyah>>()

    fun get(
        context: Context,
        surah: Int,
        ayah: Int
    ): LocalAyah? {
        if (surah !in 1..114 || ayah < 1) return null

        val verses = cache.getOrPut(surah) {
            try {
                val filename =
                    "quran/data/surahs/%03d.json".format(surah)

                val jsonText = context.assets.open(filename)
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }

                val array = JSONObject(jsonText)
                    .optJSONArray("ayahs")
                    ?: return@getOrPut emptyMap()

                buildMap {
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        val number = item.optInt("a", 0)
                        val plain = item.optString("ar")
                        val marked = item.optString("tw")

                        if (number > 0 && plain.isNotBlank()) {
                            put(number, LocalAyah(plain, marked))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(
                    "Q7H-TafsirTajweed",
                    "Cannot read surah $surah",
                    e
                )
                emptyMap()
            }
        }

        return verses[ayah]
    }
}

private object TajweedMarkup {
    private val tagRegex = Regex(
        """</?tajweed\b[^>]*>""",
        RegexOption.IGNORE_CASE
    )

    private val classRegex = Regex(
        """class\s*=\s*['"]?([a-zA-Z0-9_-]+)""",
        RegexOption.IGNORE_CASE
    )

    private fun color(name: String, dark: Boolean): Int? {
        val lightColor = when (name.lowercase()) {
            "ham_wasl", "laam_shamsiyah", "slnt" ->
                0xFF888888

            "madda_normal" -> 0xFF2476CC
            "madda_permissible" -> 0xFF4268D5
            "madda_obligatory" -> 0xFF1848B8
            "madda_necessary" -> 0xFF9233AE

            "qlq", "qalqalah" -> 0xFFD22537

            "ghn", "ghunnah" -> 0xFFE07822

            "ikhf", "ikhf_shfw" -> 0xFF9B54A9

            "iqlb", "iqlab" -> 0xFFAB3683

            "idgh_ghn", "idgh_w_ghn",
            "idgh_mus", "idghm_shfw" -> 0xFF168052

            else -> null
        }

        if (!dark) return lightColor as Int?

        return when (name.lowercase()) {
            "ham_wasl", "laam_shamsiyah", "slnt" ->
                0xFFB0B0B0

            "madda_normal", "madda_permissible",
            "madda_obligatory" -> 0xFF86ACFF

            "madda_necessary" -> 0xFFDA94F0

            "qlq", "qalqalah" -> 0xFFFF7480

            "ghn", "ghunnah" -> 0xFFFFB46B

            "ikhf", "ikhf_shfw" -> 0xFFDCA3EC

            "iqlb", "iqlab" -> 0xFFEF91CE

            "idgh_ghn", "idgh_w_ghn",
            "idgh_mus", "idghm_shfw" -> 0xFF7EDCB0

            else -> lightColor
        } as Int?
    }

    fun render(
        plain: String,
        marked: String,
        enabled: Boolean,
        dark: Boolean
    ): CharSequence {
        if (!enabled || marked.isBlank()) return plain

        val result = SpannableStringBuilder()
        val activeClasses = mutableListOf<String>()
        var cursor = 0
        var invalid = false

        fun appendText(text: String) {
            if (text.isEmpty()) return

            val start = result.length
            result.append(text)

            activeClasses.lastOrNull()
                ?.let { color(it, dark) }
                ?.let { rgb ->
                    result.setSpan(
                        ForegroundColorSpan(rgb),
                        start,
                        result.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
        }

        for (match in tagRegex.findAll(marked)) {
            appendText(marked.substring(cursor, match.range.first))

            if (match.value.startsWith("</")) {
                if (activeClasses.isEmpty()) {
                    invalid = true
                } else {
                    activeClasses.removeAt(activeClasses.lastIndex)
                }
            } else {
                val rule = classRegex.find(match.value)
                    ?.groupValues?.getOrNull(1).orEmpty()

                activeClasses.add(rule)
            }

            cursor = match.range.last + 1
        }

        appendText(marked.substring(cursor))

        // Never change or truncate the original Quran text.
        if (invalid ||
            activeClasses.isNotEmpty() ||
            result.toString() != plain
        ) {
            return plain
        }

        return result
    }
}

private fun loadUthmanicFont(context: Context): Typeface {
    return try {
        Typeface.createFromAsset(
            context.assets,
            "quran/tafsir/UthmanicHafs1Ver18.ttf"
        )
    } catch (_: Exception) {
        // Safe system fallback; never use QCF4 PUA as Unicode.
        Typeface.create("serif", Typeface.NORMAL)
    }
}

@Composable
fun TafsirLocalTajweedText(
    surah: Int,
    ayah: Int,
    fallback: String,
    prefs: UserPreferences,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var tajweedEnabled by remember(prefs) {
        mutableStateOf(prefs.tajweed)
    }

    val shared = remember(context) {
        context.getSharedPreferences(
            "q7h_compose",
            Context.MODE_PRIVATE
        )
    }

    DisposableEffect(shared, prefs) {
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener {
                    _, key ->
                if (key == "tajweed") {
                    tajweedEnabled = prefs.tajweed
                }
            }

        shared.registerOnSharedPreferenceChangeListener(listener)

        onDispose {
            shared.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    val verse by produceState<LocalAyah?>(
        null,
        surah,
        ayah
    ) {
        value = withContext(Dispatchers.IO) {
            LocalSurahRepository.get(context, surah, ayah)
        }
    }

    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.45f
    val foreground = MaterialTheme.colorScheme.onSurface.toArgb()

    val displayed = remember(
        verse,
        fallback,
        tajweedEnabled,
        dark
    ) {
        val item = verse

        if (item == null) {
            fallback
        } else {
            TajweedMarkup.render(
                plain = item.ar,
                marked = item.tw,
                enabled = tajweedEnabled,
                dark = dark
            )
        }
    }

    val arabicFont = remember(context) {
        loadUthmanicFont(context)
    }

    AndroidView(
        factory = { ctx ->
            TextView(ctx).apply {
                gravity = Gravity.RIGHT
                textDirection = View.TEXT_DIRECTION_RTL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                includeFontPadding = true
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 27f)
                setLineSpacing(8f, 1.1f)
            }
        },
        modifier = modifier.fillMaxWidth(),
        update = { view ->
            view.typeface = arabicFont
            view.setTextColor(foreground)
            view.text = displayed
        }
    )
}
