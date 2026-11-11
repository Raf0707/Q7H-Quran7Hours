package raf.console.quran7hours.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/** Public GitHub repositories are discovered live; audio itself is NEVER downloaded to app files. */
data class Reciter(val repository: String, val branch: String) {
    val title: String get() = ReciterNames.displayName(repository)
    fun audioUrl(surah: Int, ayah: Int): String {
        require(surah in 1..114 && ayah in 1..286)
        val prefix = surah.toString().padStart(3, '0')
        // Аль-Фатиха: в тафсире басмала вынесена отдельно, а в аудиофайлах
        // она учитывается как 001001. Поэтому 1:1 -> 001002, ...,
        // 1:6 и 1:7 -> 001007 (в одном MP3 прочитаны оба аята).
        // Для всех остальных сур номера аудиофайлов не меняются.
        val audioAyah = if (surah == 1 && ayah in 1..7) (ayah + 1).coerceAtMost(7) else ayah
        val verse = audioAyah.toString().padStart(3, '0')
        return "https://raw.githubusercontent.com/Raf0707/$repository/$branch/$prefix/$prefix$verse.mp3"
    }
}

class ReciterRepository {
    suspend fun list(): Result<List<Reciter>> = withContext(Dispatchers.IO) {
        runCatching {
            val results = mutableListOf<Reciter>()
            val unrecognized = mutableListOf<String>()
            (1..4).forEach { page ->
                val url = URL("https://api.github.com/users/Raf0707/repos?per_page=100&page=$page&type=owner")
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12000
                    readTimeout = 15000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "Quran7Hours-Compose")
                }
                try {
                    if (connection.responseCode != 200) throw IllegalStateException("GitHub: HTTP ${connection.responseCode}")
                    val list = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
                    if (list.length() == 0) return@forEach
                    repeat(list.length()) { i ->
                        val obj = list.getJSONObject(i)
                        val name = obj.optString("name")
                        val isCandidate = name.startsWith("q7h_") &&
                            name !in setOf("q7h_nabil_rifai", "q7h_assets", "q7h_old_java_version") &&
                            !name.startsWith("q7h_assets_") &&
                            obj.optInt("size", 0) > 0 && !obj.optBoolean("private", false)
                        if (isCandidate) {
                            if (ReciterNames.knownName(name) != null) {
                                results += Reciter(name, obj.optString("default_branch", "main"))
                            } else {
                                unrecognized += name
                            }
                        }
                    }
                    if (list.length() < 100) {
                        if (unrecognized.isNotEmpty()) Log.w("Q7H-Reciters", "Не сопоставлены русские имена: $unrecognized")
                        return@withContext Result.success(results.distinctBy { it.repository }.sortedBy { it.title.lowercase() })
                    }
                } finally { connection.disconnect() }
            }
            if (unrecognized.isNotEmpty()) Log.w("Q7H-Reciters", "Не сопоставлены русские имена: $unrecognized")
            results.distinctBy { it.repository }.sortedBy { it.title.lowercase() }
        }
    }
}
