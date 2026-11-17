package raf.console.quran7hours.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import androidx.room.withTransaction

/** Local-only learning database. No assets or Qur'an image bytes are modified. */
data class BookmarkType(
    val id: Long,
    val name: String,
    val purpose: String,
    val color: Long,
    val icon: String,
    val custom: Boolean
)

data class LearningBookmark(val id: Long, val page: Int, val typeId: Long, val type: BookmarkType)

enum class NoteScope(val title: String) {
    NONE("Без привязки"), MANZIL("Манзиль"), JUZ("Джуз"), HIZB("Хизб"),
    SURAH("Сура"), PAGE("Страница"), AYAH("Аят"), AYAH_RANGE("Аяты")
}

data class LearningNote(
    val id: Long = 0,
    val title: String = "",
    val body: String = "",
    val scope: NoteScope = NoteScope.NONE,
    val number: Int = 0,
    val surah: Int = 0,
    val firstAyah: Int = 0,
    val lastAyah: Int = 0,
    val bookmarkId: Long? = null,
    val updatedAt: Long = 0
)

/** Saved in Room: name, editable color, position and a teacher-defined use. */
data class InkPaletteColor(
    val id: Long,
    val argb: Long,
    val purpose: String,
    val sortOrder: Int,
    val tajweed: Boolean,
    val custom: Boolean
)

data class InkPoint(val x: Float, val y: Float)

data class InkStroke(
    val tool: String,
    val color: Long,
    /** Normalized to image width, not screen pixels. */
    val width: Float,
    val points: List<InkPoint>,
    val text: String = ""
)

/**
 * The public facade preserves the existing Compose API while Room becomes the authoritative store.
 * The OLD q7h_learning.db is read once and kept untouched for safe rollback.
 * Room writes run on Dispatchers.IO, never in Room's main-thread query mode.
 */
class LearningDatabase(private val appContext: Context) {
    private val db = LearningRoomDatabase.open(appContext)
    private val dao = db.learningDao()
    private fun uuid() = java.util.UUID.randomUUID().toString()
    private fun <T> io(block: suspend () -> T): T = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { block() }

    private data class Backup(
        val types: List<BookmarkTypeEntity>, val bookmarks: List<BookmarkEntity>,
        val notes: List<NoteEntity>, val ink: List<PageAnnotationEntity>,
        val hafiz: List<HafizNoteEntity>
    )

    /** Opens the former SQLiteOpenHelper db READ-ONLY. No schema modifications, no deletes. */
    private fun previousDatabase(): Backup? {
        val file = appContext.getDatabasePath("q7h_learning.db")
        if (!file.isFile) return null
        val legacy = android.database.sqlite.SQLiteDatabase.openDatabase(
            file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
        return try {
            val names = buildSet {
                legacy.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
                    while (c.moveToNext()) add(c.getString(0))
                }
            }
            val required = setOf("bookmark_types", "bookmarks", "notes", "page_annotations", "hafiz_notes")
            require(names.containsAll(required)) { "Старая база содержит не все таблицы: ${required - names}" }
            fun <T> read(sql: String, transform: (android.database.Cursor) -> T): List<T> =
                legacy.rawQuery(sql, null).use { c -> buildList { while (c.moveToNext()) add(transform(c)) } }
            Backup(
                types = read("SELECT id,name,purpose,color,icon,is_custom FROM bookmark_types") { c ->
                    BookmarkTypeEntity(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3),
                        c.getString(4), c.getInt(5), uuid())
                },
                bookmarks = read("SELECT id,page,type_id,created_at FROM bookmarks") { c ->
                    BookmarkEntity(c.getLong(0), c.getInt(1), c.getLong(2), c.getLong(3), uuid())
                },
                notes = read("SELECT id,title,body,scope,target_number,surah,ayah_first,ayah_last,bookmark_id,created_at,updated_at FROM notes") { c ->
                    NoteEntity(c.getLong(0), c.getString(1), c.getString(2), c.getString(3),
                        c.getInt(4), c.getInt(5), c.getInt(6), c.getInt(7),
                        if (c.isNull(8)) null else c.getLong(8), c.getLong(9), c.getLong(10), uuid())
                },
                ink = read("SELECT page,strokes_json,updated_at FROM page_annotations") { c ->
                    PageAnnotationEntity(c.getInt(0), c.getString(1), c.getLong(2), uuid())
                },
                hafiz = read("SELECT surah,ayah,body,updated_at FROM hafiz_notes") { c ->
                    HafizNoteEntity(c.getInt(0), c.getInt(1), c.getString(2), c.getLong(3), uuid())
                }
            )
        } finally { legacy.close() }
    }

    private val presets = listOf(
        Triple("Слушаю", "headphones", 0xFF507CC4L),
        Triple("Заучиваю", "school", 0xFFAD72C6L),
        Triple("Читаю", "menu_book", 0xFF45A17CL),
        Triple("Размышляю", "psychology", 0xFFF3A34CL),
        Triple("Читаю регулярно", "repeat", 0xFFCF7485L)
    )

    private val initialInkColors = listOf(
        0xFF999999L to "Не произносится",
        0xFFDED537L to "Мадд — удлинение (2 хараката)",
        0xFFFE7D03L to "Мадд мунфасыль",
        0xFFFF4D8EL to "Мадд муттасыль",
        0xFFE30000L to "Мадд лазим",
        0xFF26B55DL to "Гунна и ихфа",
        0xFF00DEFFL to "Калькаля",
        0xFF3C84D5L to "Тафхим",
        0xFFE64F4FL to "Красный мягкий",
        0xFFFFC848L to "Жёлтый",
        0xFF4EA6DAL to "Голубой",
        0xFF4DAD74L to "Зелёный",
        0xFFAF72DCL to "Фиолетовый",
        0xFF202020L to "Чёрный",
        0xFFFFFFFFL to "Белый",
        0xFF795548L to "Коричневый",
        0xFFD8C5A6L to "Бежевый",
        0xFFF2A444L to "Оранжевый",
        0xFF1F5CAAL to "Синий",
        0xFFED9DBFL to "Розовый",
        0xFF00B8A9L to "Бирюзовый",
        0xFF8C9A3AL to "Оливковый",
        0xFF666666L to "Тёмно-серый",
        0xFF9AE6B4L to "Мятный",
        0xFFA3A1EEL to "Лавандовый"
    )

    /** Exactly-once import marker is committed IN THE SAME Room transaction as all rows. */
    init {
        io {
            if (dao.meta("legacy_import_v1") != "complete") {
                val snapshot = previousDatabase()
                db.withTransaction {
                    if (dao.meta("legacy_import_v1") != "complete") {
                        if (snapshot != null) {
                            snapshot.types.forEach { dao.insertType(it) }
                            snapshot.bookmarks.forEach { dao.insertBookmark(it) }
                            snapshot.notes.forEach { dao.insertNote(it) }
                            snapshot.ink.forEach { dao.putAnnotation(it) }
                            snapshot.hafiz.forEach { dao.putHafizNote(it) }
                        } else {
                            BookmarkColors.defaults.forEach { (name, color) ->
                                dao.insertType(BookmarkTypeEntity(name = name, color = color, uuid = uuid()))
                            }
                            presets.forEach { (name, icon, color) ->
                                dao.insertType(BookmarkTypeEntity(name = name, color = color, icon = icon, uuid = uuid()))
                            }
                        }
                        dao.putMeta(LearningMetaEntity("legacy_import_v1", "complete"))
                    }
                }
            }
        }
        // First-launch seed after Room's 1 -> 2 additive migration; never re-seed user deletions.
        io {
            db.withTransaction {
                if (dao.meta("ink_palette_seed_v1") != "complete") {
                    if (dao.inkColors().isEmpty()) {
                        initialInkColors.forEachIndexed { index, (color, meaning) ->
                            dao.putInkColor(InkColorEntity(
                                argb = color, purpose = meaning, sortOrder = index,
                                isTajweed = if (index < 8) 1 else 0))
                        }
                    }
                    dao.putMeta(LearningMetaEntity("ink_palette_seed_v1", "complete"))
                }
            }
        }
        // Add the five new colors to existing Room installations exactly once.
        // Do not recreate deleted/renamed user categories on every launch.
        io {
            db.withTransaction {
                if (dao.meta("palette_neutral_v1") != "complete") {
                    val existing = dao.types().map { it.name }.toSet()
                    BookmarkColors.defaults.takeLast(5).forEach { (name, color) ->
                        if (name !in existing) dao.insertType(
                            BookmarkTypeEntity(name = name, color = color, icon = "bookmark", uuid = uuid())
                        )
                    }
                    dao.putMeta(LearningMetaEntity("palette_neutral_v1", "complete"))
                }
            }
        }
    }

    /** The original pre-learning color bookmarks are imported only once, without duplication. */
    fun migrateLegacyBookmarks() = io {
        if (dao.meta("color_bookmark_import_v1") == "complete") return@io
        val legacyMarks = BookmarkStore(appContext).read()
        db.withTransaction {
            if (dao.meta("color_bookmark_import_v1") == "complete") return@withTransaction
            val ts = dao.types().toMutableList()
            legacyMarks.forEach { mark ->
                var typeId = ts.firstOrNull { it.color == mark.color }?.id
                if (typeId == null) {
                    typeId = dao.insertType(BookmarkTypeEntity(name = "Мой цвет", purpose = "",
                        color = mark.color, icon = "bookmark", isCustom = 1, uuid = uuid()))
                    ts.add(dao.typeById(typeId!!)!!)
                }
                dao.insertBookmark(BookmarkEntity(page = mark.page, typeId = typeId!!,
                    createdAt = System.currentTimeMillis(), uuid = uuid()))
            }
            dao.putMeta(LearningMetaEntity("color_bookmark_import_v1", "complete"))
        }
    }

    private fun BookmarkTypeEntity.domain() = BookmarkType(id, name, purpose, color, icon, isCustom != 0)
    fun types(): List<BookmarkType> = io { dao.types().map { it.domain() } }
    fun saveType(id: Long?, name: String, purpose: String, color: Long, icon: String = "bookmark"): Long = io {
        require(name.isNotBlank())
        if (id == null) dao.insertType(BookmarkTypeEntity(name = name.trim(), purpose = purpose.trim(),
            color = color and 0xFFFFFFFFL, icon = icon, isCustom = 1, uuid = uuid()))
        else {
            val old = requireNotNull(dao.typeById(id))
            dao.updateType(old.copy(name = name.trim(), purpose = purpose.trim(), color = color and 0xFFFFFFFFL, icon = icon))
            id
        }
    }
    fun deleteCustomType(id: Long) = io { dao.deleteCustomType(id); Unit }
    fun addBookmark(page: Int, typeId: Long) = io {
        require(page in 1..604)
        dao.insertBookmark(BookmarkEntity(page = page, typeId = typeId,
            createdAt = System.currentTimeMillis(), uuid = uuid()))
        Unit
    }
    fun removeBookmark(id: Long) = io { dao.removeBookmark(id); Unit }
    fun bookmarks(): List<LearningBookmark> = io {
        val types = dao.types().associateBy { it.id }
        dao.bookmarks().mapNotNull { row ->
            types[row.typeId]?.let { LearningBookmark(row.id, row.page, row.typeId, it.domain()) }
        }
    }
    private fun NoteEntity.domain() = LearningNote(id, title, body,
        runCatching { NoteScope.valueOf(scope) }.getOrDefault(NoteScope.NONE),
        targetNumber, surah, ayahFirst, ayahLast, bookmarkId, updatedAt)
    fun notes(): List<LearningNote> = io { dao.notes().map { it.domain() } }
    fun saveNote(note: LearningNote): Long = io {
        require(note.title.isNotBlank() || note.body.isNotBlank())
        val now = System.currentTimeMillis()
        val old = if (note.id > 0) dao.noteById(note.id) else null
        val row = NoteEntity(id = old?.id ?: 0, title = note.title.trim(), body = note.body,
            scope = note.scope.name, targetNumber = note.number, surah = note.surah,
            ayahFirst = note.firstAyah, ayahLast = note.lastAyah,
            bookmarkId = note.bookmarkId, createdAt = old?.createdAt ?: now,
            updatedAt = now, uuid = old?.uuid ?: uuid())
        if (old == null) dao.insertNote(row) else { dao.updateNote(row); row.id }
    }
    fun deleteNote(id: Long) = io { dao.deleteNote(id); Unit }
    fun hafizNote(surah: Int, ayah: Int): String = io { dao.hafizNote(surah, ayah)?.body.orEmpty() }
    fun saveHafizNote(surah: Int, ayah: Int, body: String) = io {
        require(QuranCatalog.isValidAyah(surah, ayah))
        val old = dao.hafizNote(surah, ayah)
        dao.putHafizNote(HafizNoteEntity(surah, ayah, body, System.currentTimeMillis(), old?.uuid ?: uuid()))
        Unit
    }
    fun hasInk(page: Int): Boolean = io { dao.hasInk(page) }
    fun loadInk(page: Int): List<InkStroke> = io {
        val raw = dao.annotation(page)?.strokesJson ?: "[]"
        runCatching {
            val entries = JSONArray(raw)
            buildList {
                for (i in 0 until entries.length()) {
                    val o = entries.optJSONObject(i) ?: continue
                    val pts = o.optJSONArray("points") ?: continue
                    val points = buildList {
                        for (j in 0 until pts.length()) {
                            val xy = pts.optJSONArray(j) ?: continue
                            add(InkPoint(xy.optDouble(0).toFloat().coerceIn(0f, 1f),
                                xy.optDouble(1).toFloat().coerceIn(0f, 1f)))
                        }
                    }
                    if (points.isNotEmpty()) add(InkStroke(o.optString("tool", "pen"),
                        o.optLong("color", 0xFFFF4A48L), o.optDouble("width", .01).toFloat(), points,
                        o.optString("text", "")))
                }
            }
        }.getOrDefault(emptyList())
    }
    fun saveInk(page: Int, strokes: List<InkStroke>) = io {
        require(page in 1..604)
        val arr = JSONArray().apply {
            strokes.forEach { stroke ->
                put(JSONObject().apply {
                    put("tool", stroke.tool); put("color", stroke.color); put("width", stroke.width)
                    // Stage12 smart:surah:ayah:page tags are required to clear
                    // only a chosen verse after an app restart/backup restore.
                    if (stroke.text.isNotEmpty()) put("text", stroke.text)
                    put("points", JSONArray().apply {
                        stroke.points.forEach { p -> put(JSONArray().put(p.x.toDouble()).put(p.y.toDouble())) }
                    })
                })
            }
        }
        val old = dao.annotation(page)
        dao.putAnnotation(PageAnnotationEntity(page, arr.toString(), System.currentTimeMillis(), old?.uuid ?: uuid()))
        Unit
    }
    /** Palette is authoritative in Room, and automatically part of the DB ZIP snapshot. */
    fun inkColors(): List<InkPaletteColor> = io {
        dao.inkColors().map { row -> InkPaletteColor(row.id,row.argb,row.purpose,row.sortOrder,
            row.isTajweed == 1,row.isCustom == 1) }
    }
    fun addInkColor(color: Long, purpose: String) = io {
        val rows=dao.inkColors()
        dao.putInkColor(InkColorEntity(argb=color and 0xFFFFFFFFL,
            purpose=purpose.take(120), sortOrder=(rows.maxOfOrNull { it.sortOrder } ?: -1)+1,
            isCustom=1))
        Unit
    }
    fun updateInkPurpose(id: Long, purpose: String) = io {
        dao.inkColors().firstOrNull { it.id==id }?.let {
            dao.updateInkColor(it.copy(purpose=purpose.take(160)))
        }
    }
    fun updateInkColor(id: Long, color: Long) = io {
        dao.inkColors().firstOrNull { it.id==id }?.let {
            dao.updateInkColor(it.copy(argb=color and 0xFFFFFFFFL))
        }
    }
    fun moveInkColor(id: Long, delta: Int) = io {
        db.withTransaction {
            val rows=dao.inkColors()
            val index=rows.indexOfFirst { it.id==id }
            val dest=index+delta
            if(index in rows.indices && dest in rows.indices) {
                // Reindex in-memory to repair legacy duplicates before swapping.
                rows.toMutableList().apply {
                    val target=removeAt(index)
                    add(dest,target)
                }.forEachIndexed { order, row ->
                    if(row.sortOrder!=order) dao.updateInkColor(row.copy(sortOrder=order))
                }
            }
        }
    }
    fun deleteInkColor(id: Long) = io { dao.deleteCustomInkColor(id) }

}
