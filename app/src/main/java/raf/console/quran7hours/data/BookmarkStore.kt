package raf.console.quran7hours.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Only explicit user actions create bookmarks. Bundled assets are NOT saved user state. */
data class PageBookmark(val page: Int, val color: Long)

object BookmarkColors {
    val defaults: List<Pair<String, Long>> = listOf(
        "Красный" to 0xFFE05252L, "Оранжевый" to 0xFFF19C48L,
        "Жёлтый" to 0xFFE9C84BL, "Зелёный" to 0xFF49A876L,
        "Бирюзовый" to 0xFF3BA9A6L, "Синий" to 0xFF5387DCL,
        "Фиолетовый" to 0xFF9476D2L, "Розовый" to 0xFFD773AAL
    )
    fun parse(value: Any?): Long? {
        if (value is Number) return value.toLong() and 0xFFFFFFFFL
        val raw = value?.toString()?.trim() ?: return null
        val hex = raw.removePrefix("#").removePrefix("0x").removePrefix("0X")
        if (!hex.matches(Regex("[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))) return null
        val parsed = hex.toLongOrNull(16) ?: return null
        return if (hex.length == 6) parsed or 0xFF000000L else parsed
    }
}

class BookmarkStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("q7h_compose", Context.MODE_PRIVATE)
    private val dataKey = "colored_bookmarks_v2"

    /** The original asset may specify palette colors, but never assigned pages. */
    val palette: List<Pair<String, Long>> by lazy {
        val json = context.assets.list("Bookmarks").orEmpty()
            .filter { it.endsWith(".json", ignoreCase = true) }.sorted()
            .asSequence().mapNotNull { filename ->
                runCatching {
                    context.assets.open("Bookmarks/$filename").bufferedReader().use { JSONTokener(it.readText()).nextValue() }
                }.getOrNull() as? JSONObject
            }.firstOrNull { it.has("colors") || it.has("palette") }
        val colors = json?.optJSONArray("colors") ?: json?.optJSONArray("palette")
        val parsed = (0 until (colors?.length() ?: 0)).mapNotNull { i ->
            val value = colors?.opt(i)
            val label = (value as? JSONObject)?.optString("name")?.takeIf { it.isNotBlank() } ?: "Цвет ${i + 1}"
            val raw = if (value is JSONObject) value.opt("hex") ?: value.opt("color") else value
            BookmarkColors.parse(raw)?.let { label to it }
        }
        parsed.ifEmpty { BookmarkColors.defaults }
    }

    fun read(): List<PageBookmark> {
        val saved = prefs.getString(dataKey, null)
        if (saved != null) {
            val bookmarks = parse(saved).distinctBy(PageBookmark::page).sortedBy(PageBookmark::page)
            if (looksLikeAutoCreatedAllPages(bookmarks)) {
                // Previous builds accidentally imported every page from an asset JSON.
                // Only the unmistakable 604-pages/same-default-color pattern is cleared.
                clearAutoCreatedBookmarks()
                return emptyList()
            }
            return bookmarks
        }
        // Migrate only bookmarks saved through the previous Compose UI. Assets are never
        // imported here: a packaged JSON must not mark pages for a new installation.
        val previous = prefs.getStringSet("bookmarks", emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }
            .filter { it in 1..604 }.distinct().sorted()
        if (previous.size == 604) {
            clearAutoCreatedBookmarks()
            return emptyList()
        }
        val migrated = previous.map { PageBookmark(it, palette.first().second) }
        if (migrated.isNotEmpty()) save(migrated)
        return migrated
    }

    fun put(page: Int, color: Long): List<PageBookmark> {
        if (page !in 1..604) return read()
        return (read().filterNot { it.page == page } + PageBookmark(page, color and 0xFFFFFFFFL))
            .sortedBy(PageBookmark::page).also(::save)
    }

    fun remove(page: Int): List<PageBookmark> = read().filterNot { it.page == page }.also(::save)

    private fun clearAutoCreatedBookmarks() {
        prefs.edit().remove(dataKey).remove("bookmarks").putString(dataKey, "[]")
            .putBoolean("auto_seed_bookmarks_cleaned_v4", true).apply()
    }

    private fun looksLikeAutoCreatedAllPages(items: List<PageBookmark>): Boolean {
        if (items.size != 604 || items.any { it.page !in 1..604 }) return false
        if (items.map { it.page }.toSet().size != 604) return false
        val colors = items.map(PageBookmark::color).toSet()
        return colors.size == 1
    }

    private fun save(items: List<PageBookmark>) {
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("page", it.page).put("color", "#%08X".format(it.color))) }
        prefs.edit().putString(dataKey, array.toString())
            .putStringSet("bookmarks", items.map { it.page.toString() }.toSet()).apply()
    }

    private fun parse(raw: String): List<PageBookmark> = runCatching {
        val node = JSONTokener(raw).nextValue() as? JSONArray ?: return@runCatching emptyList()
        (0 until node.length()).mapNotNull { i ->
            val entry = node.optJSONObject(i) ?: return@mapNotNull null
            val page = entry.optInt("page", -1)
            if (page !in 1..604) return@mapNotNull null
            PageBookmark(page, BookmarkColors.parse(entry.opt("color")) ?: BookmarkColors.defaults.first().second)
        }
    }.getOrDefault(emptyList())
}
