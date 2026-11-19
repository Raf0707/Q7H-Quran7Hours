package raf.console.quran7hours.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
    var separateHafizPlayer: Boolean
        get() = p.getBoolean("separate_hafiz_player", false)
        set(value) = p.edit().putBoolean("separate_hafiz_player",value).apply()
    var hafizEndMode: String
        get() = p.getString("hafizEndMode", "STOP") ?: "STOP"
        set(value) = p.edit().putString("hafizEndMode",value).apply()

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

    /** All graphics preferences are in q7h_compose.xml and included in backup ZIPs. */
    companion object {
        // Tool IDs are stored as strings in Room annotations. New IDs are additive:
        // no schema change or destructive migration is needed.
        val SMART_GESTURES = setOf("single", "double", "long", "timed", "off")
        val INK_TOOLS = listOf(
            "marker", "marker_ruler", "marker_single", "marker_ruler_single",
            "smart_marker", "pen_ruler", "pen", "cover", "text", "box", "eraser"
        )
    }
    var inkDefaultTool: String
        get() = p.getString("ink_default_tool_v2", "marker")?.takeIf { it in INK_TOOLS } ?: "marker"
        set(value) { if (value in INK_TOOLS) p.edit().putString("ink_default_tool_v2",value).apply() }
    var inkToolOrder: List<String>
        get() {
            val saved=p.getString("ink_tool_order_v2", "")?.split(',') ?: emptyList()
            return if (saved.size==INK_TOOLS.size && saved.toSet()==INK_TOOLS.toSet()) saved
                else saved.filter { it in INK_TOOLS }.distinct() + INK_TOOLS.filterNot { it in saved }
        }
        set(value) {
            if(value.size==INK_TOOLS.size && value.toSet()==INK_TOOLS.toSet())
                p.edit().putString("ink_tool_order_v2",value.joinToString(",")).apply()
        }
    /** Persisted in q7h_compose and included in every user backup snapshot. */
    var inkCollapsedSlider: Boolean
        get() = p.getBoolean("ink_collapse_slider_v4", false)
        set(value) = p.edit().putBoolean("ink_collapse_slider_v4", value).apply()
    var inkCollapsedTools: Boolean
        get() = p.getBoolean("ink_collapse_tools_v4", false)
        set(value) = p.edit().putBoolean("ink_collapse_tools_v4", value).apply()
    var inkCollapsedTajweed: Boolean
        get() = p.getBoolean("ink_collapse_tajweed_v4", false)
        set(value) = p.edit().putBoolean("ink_collapse_tajweed_v4", value).apply()
    /** Preferences stay in q7h_compose.xml, included in every existing backup ZIP. */
    var inkSmartSettingsRevision by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    private fun touchSmartSettings() { inkSmartSettingsRevision++ }
    var inkSmartRadius: Float
        get() = p.getFloat("smart_marker_radius_v8", 1f).coerceIn(0f, 1f)
        set(value) { p.edit().putFloat("smart_marker_radius_v8",value.coerceIn(0f,1f)).apply(); touchSmartSettings() }
    var inkSmartBorderEnabled: Boolean
        get() = p.getBoolean("smart_marker_border_v8", false)
        set(value) { p.edit().putBoolean("smart_marker_border_v8",value).apply(); touchSmartSettings() }
    var inkSmartBorderWidth: Float
        get() = p.getFloat("smart_marker_border_width_v8", 1.3f).coerceIn(.4f,6f)
        set(value) { p.edit().putFloat("smart_marker_border_width_v8",value.coerceIn(.4f,6f)).apply(); touchSmartSettings() }
    /** Mutually exclusive gesture binding for add/remove. */
    var inkSmartMarkGesture: String
        get() = "double"
        set(value) { if (value in SMART_GESTURES) { p.edit().putString("smart_marker_add_gesture_v8",value).apply(); touchSmartSettings() } }
    var inkSmartClearGesture: String
        get() = "long"
        set(value) { if (value in SMART_GESTURES) { p.edit().putString("smart_marker_clear_gesture_v8",value).apply(); touchSmartSettings() } }
    var inkSmartHoldSeconds: Int
        get() = p.getInt("smart_marker_hold_seconds_v8", 3).coerceIn(1,10)
        set(value) { p.edit().putInt("smart_marker_hold_seconds_v8",value.coerceIn(1,10)).apply(); touchSmartSettings() }
    var inkSmartCountdownSide: String
        get() = p.getString("smart_marker_countdown_side_v8", "left")
            ?.takeIf { it in setOf("left","right","top") } ?: "left"
        set(value) { if (value in setOf("left","right","top")) { p.edit().putString("smart_marker_countdown_side_v8",value).apply(); touchSmartSettings() } }
    /** Long hold after the system long-press threshold enables ink-only eraser.
     * This setting lives in the existing SharedPreferences backup snapshot. */
    var inkHoldEraserEnabled: Boolean
        get() = p.getBoolean("ink_hold_eraser_v9", false)
        set(value) { p.edit().putBoolean("ink_hold_eraser_v9",value).apply(); touchSmartSettings() }

    /** Reader gestures have exactly two assignable actions. Single tap is NEVER reassigned. */
    var readerSwapAyahGestures: Boolean
        get() = p.getBoolean("reader_swap_ayah_gestures_v10", false)
        set(value) { p.edit().putBoolean("reader_swap_ayah_gestures_v10", value).apply(); touchSmartSettings() }
    /** Advanced timed gesture is disabled by default and independent of the two main actions. */
    var readerTimedGestureAction: String
        get() = p.getString("reader_timed_gesture_action_v10", "off")
            ?.takeIf { it in setOf("off", "eraser", "drawing", "controls", "navigation") } ?: "off"
        set(value) { if(value in setOf("off", "eraser", "drawing", "controls", "navigation")) {
            p.edit().putString("reader_timed_gesture_action_v10", value).apply(); touchSmartSettings()
        } }
    var readerTimedGestureMs: Long
        get() = p.getLong("reader_timed_gesture_ms_v10", 2500L).coerceIn(1500L, 10000L)
        set(value) { p.edit().putLong("reader_timed_gesture_ms_v10", value.coerceIn(1500L,10000L)).apply(); touchSmartSettings() }
    /** Full, one-row and square share a single playback channel with the Tafsir. */
    var readerPlayerDisplay: String
        get() = p.getString("reader_player_display_v10", "full")
            ?.takeIf { it in setOf("full", "row", "square") } ?: "full"
        set(value) { if(value in setOf("full", "row", "square"))
            p.edit().putString("reader_player_display_v10", value).apply() }
    var readerPlayerFullX: Float
        get() = p.getFloat("reader_player_full_x_v10", .5f).coerceIn(0f,1f)
        set(value) { p.edit().putFloat("reader_player_full_x_v10",value.coerceIn(0f,1f)).apply() }
    var readerPlayerFullY: Float
        get() = p.getFloat("reader_player_full_y_v10", .78f).coerceIn(0f,1f)
        set(value) { p.edit().putFloat("reader_player_full_y_v10",value.coerceIn(0f,1f)).apply() }
    var readerPlayerSquareX: Float
        get() = p.getFloat("reader_player_square_x_v10", .85f).coerceIn(0f,1f)
        set(value) { p.edit().putFloat("reader_player_square_x_v10",value.coerceIn(0f,1f)).apply() }
    var readerPlayerSquareY: Float
        get() = p.getFloat("reader_player_square_y_v10", .70f).coerceIn(0f,1f)
        set(value) { p.edit().putFloat("reader_player_square_y_v10",value.coerceIn(0f,1f)).apply() }
    var readerPlayerRowTop: Boolean
        get() = p.getBoolean("reader_player_row_top_v10", false)
        set(value) { p.edit().putBoolean("reader_player_row_top_v10",value).apply() }
    /** Normalized Y for the compact player: any height, fixed horizontal center.
     * Older installations had only top/bottom; migrate their choice on first read.
     * Stored in q7h_compose preferences and included in existing backups. */
    var readerPlayerRowY: Float
        get() = if (p.contains("reader_player_row_y_v13"))
            p.getFloat("reader_player_row_y_v13", 1f)
                .takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
        else if (readerPlayerRowTop) 0f else 1f
        set(value) { p.edit().putFloat("reader_player_row_y_v13",
            value.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f).apply() }

    /** Playback overlay never changes saved ink. SharedPreferences is included in Q7H backups. */
    var readerPlaybackHighlightMode: String
        get() = p.getString("reader_playback_highlight_v12", "replace")
            ?.takeIf { it in setOf("replace", "keep") } ?: "replace"
        set(value) { if (value in setOf("replace", "keep")) {
            p.edit().putString("reader_playback_highlight_v12", value).apply()
            touchSmartSettings()
        } }

    /** The transient playback marker is independent from the user's saved ink color.
     * Kept in q7h_compose.xml so existing ZIP exports/restores preserve it. */
    var readerPlaybackHighlightColor: Long
        get() = (p.getLong("reader_playback_highlight_color_v14", 0xFFD8C5A6L)
            and 0xFFFFFFFFL) or 0xFF000000L
        set(value) {
            p.edit().putLong("reader_playback_highlight_color_v14",
                (value and 0xFFFFFFL) or 0xFF000000L).apply()
            touchSmartSettings()
        }

    /** Retain the latest custom RGB so users can switch back to it without
     * re-entering channels. This preference is included in the backup too. */
    var readerPlaybackCustomColor: Long
        get() = (p.getLong("reader_playback_custom_rgb_color_v14", 0xFFD8C5A6L)
            and 0xFFFFFFFFL) or 0xFF000000L
        set(value) {
            p.edit().putLong("reader_playback_custom_rgb_color_v14",
                (value and 0xFFFFFFL) or 0xFF000000L).apply()
            touchSmartSettings()
        }

    var inkSmartTapCount: Int
        get() = p.getInt("ink_smart_taps_v4", 2).coerceIn(1,2)
        set(value) = p.edit().putInt("ink_smart_taps_v4", value.coerceIn(1,2)).apply()
    /** 0..1 normalized to the available viewport, independent of display size. */
    var inkPanelX: Float
        get() = p.getFloat("ink_panel_x_v4", .5f).coerceIn(0f,1f)
        set(value) = p.edit().putFloat("ink_panel_x_v4", value.coerceIn(0f,1f)).apply()
    var inkPanelY: Float
        get() = p.getFloat("ink_panel_y_v4", .77f).coerceIn(0f,1f)
        set(value) = p.edit().putFloat("ink_panel_y_v4", value.coerceIn(0f,1f)).apply()
    var inkSquareX: Float
        get() = p.getFloat("ink_square_x_v4", 1f).coerceIn(0f,1f)
        set(value) = p.edit().putFloat("ink_square_x_v4", value.coerceIn(0f,1f)).apply()
    var inkSquareY: Float
        get() = p.getFloat("ink_square_y_v4", 1f).coerceIn(0f,1f)
        set(value) = p.edit().putFloat("ink_square_y_v4", value.coerceIn(0f,1f)).apply()
    /** One-time recovery for palettes trapped by pre-v5 saved coordinates. */
    var inkPositionRecoveredV5: Boolean
        get() = p.getBoolean("ink_position_recovered_v5", false)
        set(value) = p.edit().putBoolean("ink_position_recovered_v5", value).apply()
    /** Double-tap on UNUSED palette background toggles the small floating square.
     * A button, tool, colour or slider must retain its native touch behavior.
     * SharedPreferences q7h_compose is included in the backup ZIP snapshot. */
    var inkDoubleTapSquare: Boolean
        get() = p.getBoolean("ink_double_tap_square_v5", true)
        set(value) = p.edit().putBoolean("ink_double_tap_square_v5", value).apply()
    /** Long press on an unused palette area switches between full and compact. */
    var inkLongPressCompact: Boolean
        get() = p.getBoolean("ink_long_press_compact_v5", true)
        set(value) = p.edit().putBoolean("ink_long_press_compact_v5", value).apply()
    var inkToolbarState: String
        get() = p.getString("ink_toolbar_state_v4", "expanded")
            ?.takeIf { it in setOf("expanded", "collapsed", "square") } ?: "expanded"
        set(value) { if(value in setOf("expanded", "collapsed", "square"))
            p.edit().putString("ink_toolbar_state_v4",value).apply() }
    var inkPinTajweedRow: Boolean
        get() = p.getBoolean("ink_pin_tajweed_row_v2", true)
        set(value) = p.edit().putBoolean("ink_pin_tajweed_row_v2",value).apply()
    var inkSelectedColor: Long
        get() = p.getLong("ink_last_color_v2",0xFFE30000L)
        set(value) = p.edit().putLong("ink_last_color_v2",value).apply()
    var inkStrokeSize: Float
        get() = p.getFloat("ink_stroke_size_v2",.04f).coerceIn(.002f,.20f)
        set(value) = p.edit().putFloat("ink_stroke_size_v2",value.coerceIn(.002f,.20f)).apply()
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
