package raf.console.quran7hours.media

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Public GitHub reciter repositories; the application stores only a validated catalog. */
data class Reciter(val repository: String, val branch: String) {
    val title: String get() = ReciterNames.displayName(repository)
    fun audioUrl(surah: Int, ayah: Int): String {
        require(surah in 1..114 && ayah in 1..286)
        val prefix = surah.toString().padStart(3, '0')
        // Fatiha follows the app's Hanafi verse numbering: Basmala is separate.
        val fileAyah = if (surah == 1 && ayah in 1..7) (ayah + 1).coerceAtMost(7) else ayah
        return "https://raw.githubusercontent.com/Raf0707/$repository/$branch/$prefix/$prefix${fileAyah.toString().padStart(3, '0')}.mp3"
    }
}

/**
 * Stale-while-revalidate catalog. A network failure must never erase a previously
 * usable list. The same ayah_reciter_order key is used by PERSON mode in Media3.
 */
class ReciterRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("q7h_compose", Context.MODE_PRIVATE)
    private val namePattern = Regex("q7h_[A-Za-z0-9_-]+")
    private val branchPattern = Regex("[A-Za-z0-9._/-]+")

    private fun isValid(item: Reciter) =
        item.repository.matches(namePattern) && item.branch.matches(branchPattern) &&
            ReciterNames.knownName(item.repository) != null &&
            item.repository != "q7h_nabil_rifai"

    private fun parse(raw: String): List<Reciter> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val item = Reciter(obj.optString("repository"), obj.optString("branch", "main"))
                if (isValid(item)) add(item)
            }
        }.distinctBy { it.repository }.take(43).sortedBy { it.title.lowercase() }
    }.getOrDefault(emptyList())

    /** No I/O, immediately available on the very first Compose frame. */
    fun cached(): List<Reciter> {
        val current = parse(prefs.getString("ayah_reciter_order", "[]") ?: "[]")
        if (current.isNotEmpty()) return current
        val old = parse(prefs.getString("q7h_reciters_cached", "[]") ?: "[]")
        if (old.isNotEmpty()) return old
        // First-install bootstrap only: its real branch is corrected by discovery.
        val selected = prefs.getString("selectedReciter", "").orEmpty()
        val candidate = if (selected.matches(namePattern) &&
            ReciterNames.knownName(selected) != null && selected != "q7h_nabil_rifai") {
            selected
        } else "q7h_shatri"
        return listOf(Reciter(candidate, "main"))
    }

    /** The last successful discovery time; do not hammer GitHub on every launch. */
    fun refreshNeeded(): Boolean {
        val last = prefs.getLong("q7h_reciter_catalog_refreshed", 0L)
        return (System.currentTimeMillis() - last) > 24L * 60L * 60L * 1000L || cached().size < 40
    }

    suspend fun list(): Result<List<Reciter>> = withContext(Dispatchers.IO) {
        val old = cached()
        runCatching {
            val found = ArrayList<Reciter>(43)
            // Most accounts fit within the first page; avoid four serial timeouts.
            for (page in 1..4) {
                val url = URL("https://api.github.com/users/Raf0707/repos?per_page=100&page=$page&type=owner")
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4500
                    readTimeout = 6000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "Quran7Hours-Compose")
                }
                val count: Int
                try {
                    val status = connection.responseCode
                    if (status != 200) error("GitHub: HTTP $status")
                    val list = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
                    count = list.length()
                    repeat(count) { index ->
                        val obj = list.optJSONObject(index) ?: return@repeat
                        val id = obj.optString("name")
                        val candidate = Reciter(id, obj.optString("default_branch", "main"))
                        if (!obj.optBoolean("private", false) && obj.optInt("size", 0) > 0 &&
                            isValid(candidate) && !id.startsWith("q7h_assets")) found.add(candidate)
                    }
                } finally { connection.disconnect() }
                if (count < 100 || found.distinctBy { it.repository }.size >= 43) break
            }
            val validated = found.distinctBy { it.repository }.take(43).sortedBy { it.title.lowercase() }
            if (validated.isEmpty()) error("GitHub: пустой каталог")
            // Never overwrite a full cached list with a partial, rate-limited reply.
            val best = if (validated.size >= old.size) validated else old
            val json = JSONArray().apply {
                best.forEach { put(JSONObject().put("repository", it.repository).put("branch", it.branch)) }
            }.toString()
            prefs.edit()
                .putString("ayah_reciter_order", json)
                .putString("q7h_reciters_cached", json)
                .putLong("q7h_reciter_catalog_refreshed", System.currentTimeMillis())
                .apply()
            best
        }.recoverCatching { error ->
            Log.w("Q7H-Reciters", "Using offline reciter catalog; discovery failed", error)
            if (old.isNotEmpty()) old else throw error
        }
    }
}
