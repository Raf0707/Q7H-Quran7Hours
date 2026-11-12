package raf.console.quran7hours.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class UserPreferences(context: Context) {
    private val p = context.getSharedPreferences("q7h_compose", Context.MODE_PRIVATE)
    private val legacyPage = context.getSharedPreferences("LastPagePrefs", Context.MODE_PRIVATE)
    var lastPage: Int
        get() = p.getInt("page", legacyPage.getInt("last_page_key", 0) + 1).coerceIn(1, 604)
        set(value) { p.edit().putInt("page", value.coerceIn(1, 604)).apply(); legacyPage.edit().putInt("last_page_key", value - 1).apply() }
    var nightMode: String
        get() = p.getString("nightMode", "system") ?: "system"
        set(value) = p.edit().putString("nightMode", value).apply()
    var palette: Int
        get() = p.getInt("palette", 0)
        set(value) = p.edit().putInt("palette", value).apply()
    var contrastLevel: String
        get() = p.getString("contrastLevel", null) ?: if (p.getBoolean("contrast", false)) "high" else "normal"
        set(value) = p.edit().putString("contrastLevel", value).apply()
    var tajweed: Boolean
        get() = p.getBoolean("tajweed", legacyPage.getBoolean("tajweedSwitchState", true))
        set(value) {
            p.edit().putBoolean("tajweed", value).apply()
            legacyPage.edit().putBoolean("tajweedSwitchState", value).apply()
        }
    var autoFlip: Boolean
        get() = p.getBoolean("autoFlip", false)
        set(value) = p.edit().putBoolean("autoFlip", value).apply()
    var keepAwake: Boolean
        get() = p.getBoolean("keepAwake", false)
        set(value) = p.edit().putBoolean("keepAwake", value).apply()
    var hadrPositionMs: Long
        get() = p.getLong("hadrPositionMs", 0L).coerceAtLeast(0L)
        set(value) { p.edit().putLong("hadrPositionMs", value.coerceAtLeast(0L)).commit() }
    var lastSheetSection: String
        get() = p.getString("panel_section", "AUDIO") ?: "AUDIO"
        set(value) = p.edit().putString("panel_section", value).apply()
    var lastAudioSegment: String
        get() = p.getString("panel_audio_segment", "SURAH") ?: "SURAH"
        set(value) = p.edit().putString("panel_audio_segment", value).apply()
    fun panelViewport(id: String): Pair<Int, Int> {
        require(id.matches(Regex("[a-zA-Z0-9_]{1,50}")))
        return p.getInt("panel_${id}_index", 0).coerceAtLeast(0) to
            p.getInt("panel_${id}_offset", 0).coerceAtLeast(0)
    }
    fun savePanelViewport(id: String, index: Int, offset: Int) {
        require(id.matches(Regex("[a-zA-Z0-9_]{1,50}")))
        p.edit().putInt("panel_${id}_index", index.coerceAtLeast(0))
            .putInt("panel_${id}_offset", offset.coerceAtLeast(0)).apply()
    }
    var selectedReciter: String
        get() = p.getString("selectedReciter", "") ?: ""
        set(value) = p.edit().putString("selectedReciter", value).apply()
    /** Only a tap in the reciter chooser counts as a PERSONAL selection.
     * The first reciter automatically assigned by MainActivity does not count.
     */
    var manuallySelectedReciter: String
        get() = p.getString("ayah_user_chosen_reciter", "") ?: ""
        set(value) = p.edit().putString("ayah_user_chosen_reciter", value).apply()

    /** Temporary snapshot from just BEFORE ALL_RECITERS; not overwritten by Media3. */
    var reciterBeforeAllMode: String
        get() = p.getString("ayah_reciter_before_person", "") ?: ""
        set(value) = p.edit().putString("ayah_reciter_before_person", value).apply()

    /** If Person was enabled while paused, start at the intended voice, not old audio. */
    var personNeedsFirstPlay: Boolean
        get() = p.getBoolean("ayah_person_needs_first_play", false)
        set(value) = p.edit().putBoolean("ayah_person_needs_first_play", value).apply()

    var ayahEndMode: String
        get() = p.getString("ayahEndMode", "STOP") ?: "STOP"
        set(value) = p.edit().putString("ayahEndMode", value).apply()
    var inkVisible: Boolean
        get() = p.getBoolean("learning_ink_visible", true)
        set(value) = p.edit().putBoolean("learning_ink_visible", value).apply()
    var inkToolbarOffsetDp: Float
        get() = p.getFloat("learning_ink_toolbar_offset_dp", 0f)
        set(value) = p.edit().putFloat("learning_ink_toolbar_offset_dp", value).apply()
    var hadrSeekIntervalSeconds: Int
        get() = p.getInt("hadr_seek_interval_seconds", 10).coerceIn(1, 86400)
        set(value) = p.edit().putInt("hadr_seek_interval_seconds", value.coerceIn(1, 86400)).apply()

    /** Total plays per reciter in ALL_RECITERS, default 1. */
    var allRecitersRepeatCount: Int
        get() = p.getInt("ayah_all_reciters_repeat_count", 1).coerceIn(1, 1000)
        set(value) = p.edit().putInt("ayah_all_reciters_repeat_count", value.coerceIn(1, 1000)).apply()
    /** Total plays in tafsir N mode; default 3. */
    var ayahRepeatN: Int
        get() = p.getInt("ayah_repeat_n", 3).coerceIn(1, 1000)
        set(value) = p.edit().putInt("ayah_repeat_n", value.coerceIn(1, 1000)).apply()
    /** Total plays in 7-hours N mode; independently defaults to 3. */
    var hadrRepeatN: Int
        get() = p.getInt("hadr_repeat_n", 3).coerceIn(1, 1000)
        set(value) = p.edit().putInt("hadr_repeat_n", value.coerceIn(1, 1000)).apply()

    /** Reciters are already validated by ReciterRepository; no repeated network discovery in the service. */
    fun saveReciterOrder(reciters: List<Pair<String, String>>) {
        val array = JSONArray()
        reciters.distinctBy { it.first }.take(43).forEach { (repository, branch) ->
            array.put(JSONObject().put("repository", repository).put("branch", branch))
        }
        p.edit().putString("ayah_reciter_order", array.toString()).apply()
    }

    fun hadrMode(segment: String, number: Int): String =
        p.getString("hadr_mode_${segment}_${number}", "NEXT") ?: "NEXT"

    fun saveHadrMode(segment: String, number: Int, mode: String) {
        require(mode in setOf("NEXT", "REPEAT", "REPEAT_N", "STOP"))
        p.edit().putString("hadr_mode_${segment}_${number}", mode).commit()
    }
    fun setHadrActiveSegment(segment: String, number: Int) {
        p.edit().putString("hadr_active_segment", segment)
            .putInt("hadr_active_number", number).commit()
    }
}
