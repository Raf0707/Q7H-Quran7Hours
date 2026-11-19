package raf.console.quran7hours.media

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * A PERSON queue is a selected, ordered SUBSET of the complete reciter catalog.
 * The latter remains in ayah_reciter_order, which is updated by discovery.
 * Storing a separate list is crucial: a catalog refresh must not overwrite the
 * user's selection/order. Missing key = all reciters (first-install default).
 * An explicitly saved [] means that the user selected nobody.
 */
internal object Q7HPersonQueue {
    private const val KEY = "ayah_person_selected_order_v2"

    fun preferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences("q7h_compose", Context.MODE_PRIVATE)

    fun configured(prefs: SharedPreferences): Boolean = prefs.contains(KEY)

    fun selectedIds(prefs: SharedPreferences): List<String>? {
        if (!configured(prefs)) return null
        return runCatching {
            val entries = JSONArray(prefs.getString(KEY, "[]") ?: "[]")
            buildList {
                for (i in 0 until entries.length()) {
                    val id = entries.optString(i)
                    if (id.matches(Regex("q7h_[A-Za-z0-9_-]+"))) add(id)
                }
            }.distinct()
        }.getOrNull()
    }

    fun enabled(all: List<Reciter>, prefs: SharedPreferences): List<Reciter> {
        val distinct = all.distinctBy { it.repository }
        val ids = selectedIds(prefs) ?: return distinct
        val byId = distinct.associateBy { it.repository }
        return ids.mapNotNull { byId[it] }
    }

    fun editorOrder(all: List<Reciter>, prefs: SharedPreferences): List<Reciter> {
        val distinct = all.distinctBy { it.repository }
        if (selectedIds(prefs) == null) return distinct
        val enabled = enabled(distinct,prefs)
        val chosen = enabled.map { it.repository }.toSet()
        return enabled + distinct.filterNot { it.repository in chosen }
    }

    fun save(prefs: SharedPreferences, selected: List<String>) {
        val array = JSONArray()
        selected.distinct().filter { it.matches(Regex("q7h_[A-Za-z0-9_-]+")) }
            .take(43).forEach { array.put(it) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }
}
