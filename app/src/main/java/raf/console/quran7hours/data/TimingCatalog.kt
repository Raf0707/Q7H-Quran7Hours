package raf.console.quran7hours.data

import android.content.Context
import org.json.JSONObject

/**
 * Edit: app/src/main/assets/hadr_timings.json
 * Keys: surahs (114), pages (604), juz (30), hizb (60), manzil (7).
 * Each { "number": N, "ms": millisecondOffsetOrNull, "status": "ИЗМЕНИТЬ" }.
 * A null time is UNAVAILABLE, never an invented estimate.
 *
 * The already-imported legacy_timings.json remains a fallback for surahs/pages,
 * so the editable blank template does NOT erase the user's original timestamps.
 */
class TimingCatalog(private val ctx: Context) {
    data class Mark(val number: Int, val positionMs: Long?, val status: String)

    private val user = readJson("hadr_timings.json")
    private val legacy = readJson("legacy_timings.json")
    val warnings = mutableListOf<String>()

    private fun readJson(name: String): JSONObject? = runCatching {
        ctx.assets.open(name).bufferedReader().use { JSONObject(it.readText()) }
    }.getOrNull()

    private fun key(segment: QuranCatalog.Segment) = when (segment) {
        QuranCatalog.Segment.SURAH -> "surahs"
        QuranCatalog.Segment.PAGE -> "pages"
        QuranCatalog.Segment.JUZ -> "juz"
        QuranCatalog.Segment.HIZB -> "hizb"
        QuranCatalog.Segment.MANZIL -> "manzil"
    }

    private fun readMarks(json: JSONObject?, field: String, count: Int): Map<Int, Mark> {
        val array = json?.optJSONArray(field) ?: return emptyMap()
        val result = mutableMapOf<Int, Mark>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val number = obj.optInt("number", -1)
            if (number !in 1..count) {
                warnings.add("$field: номер $number вне диапазона 1..$count")
                continue
            }
            if (result.containsKey(number)) {
                warnings.add("$field: дублируется элемент $number")
                continue
            }
            val time: Long? = if (!obj.has("ms") || obj.isNull("ms")) null else {
                val raw = obj.opt("ms")
                (raw as? Number)?.toLong()?.takeIf { it >= 0L }
                    ?: run { warnings.add("$field #$number: ожидается неотрицательное число ms или null"); null }
            }
            result[number] = Mark(number, time, obj.optString("status", "ИЗМЕНИТЬ"))
        }
        return result
    }

    private fun build(segment: QuranCatalog.Segment): List<Mark> {
        val field = key(segment)
        val count = QuranCatalog.startPages(segment).size
        val current = readMarks(user, field, count)
        val old = if (segment == QuranCatalog.Segment.SURAH || segment == QuranCatalog.Segment.PAGE)
            readMarks(legacy, field, count) else emptyMap()
        val joined = (1..count).map { index ->
            val edited = current[index]
            val historic = old[index]
            val position = edited?.positionMs ?: historic?.positionMs
            val status = when {
                edited?.positionMs != null -> edited.status
                historic?.positionMs != null -> historic.status
                else -> edited?.status ?: "ИЗМЕНИТЬ"
            }
            Mark(index, position, status)
        }
        var previous = -1L
        return joined.map { mark ->
            val ms = mark.positionMs
            if (ms != null && ms < previous) {
                warnings.add("$field #${mark.number}: время $ms меньше предыдущего $previous; переход отключён")
                mark.copy(positionMs = null, status = "ИЗМЕНИТЬ")
            } else {
                if (ms != null) previous = ms
                mark
            }
        }
    }

    private val timelines by lazy {
        QuranCatalog.Segment.entries.associateWith(::build)
    }

    val surahs: List<Mark> get() = marks(QuranCatalog.Segment.SURAH)
    val pages: List<Mark> get() = marks(QuranCatalog.Segment.PAGE)
    fun marks(segment: QuranCatalog.Segment): List<Mark> = timelines.getValue(segment)

    /** Last KNOWN marker not later than the current audio position. */
    fun at(segment: QuranCatalog.Segment, positionMs: Long): Int? =
        marks(segment).lastOrNull { it.positionMs != null && it.positionMs <= positionMs }?.number

    fun seekMs(segment: QuranCatalog.Segment, number: Int): Long? =
        marks(segment).getOrNull(number - 1)?.positionMs
}
