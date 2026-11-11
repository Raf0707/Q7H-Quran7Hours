package raf.console.quran7hours.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** Room is the authoritative store. The previous q7h_learning.db is retained for rollback. */
@Entity(tableName = "bookmark_types", indices = [Index(value = ["uuid"], unique = true)])
data class BookmarkTypeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String, val purpose: String = "", val color: Long,
    val icon: String = "bookmark",
    @ColumnInfo(name = "is_custom") val isCustom: Int = 0,
    val uuid: String
)

@Entity(
    tableName = "bookmarks",
    foreignKeys = [ForeignKey(entity = BookmarkTypeEntity::class, parentColumns = ["id"], childColumns = ["type_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["page", "type_id"], unique = true), Index(value = ["type_id"]), Index(value = ["uuid"], unique = true)]
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val page: Int,
    @ColumnInfo(name = "type_id") val typeId: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val uuid: String
)

@Entity(
    tableName = "notes",
    foreignKeys = [ForeignKey(entity = BookmarkEntity::class, parentColumns = ["id"], childColumns = ["bookmark_id"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index(value = ["bookmark_id"]), Index(value = ["uuid"], unique = true)]
)
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = "", val body: String = "",
    val scope: String = "NONE",
    @ColumnInfo(name = "target_number") val targetNumber: Int = 0,
    val surah: Int = 0,
    @ColumnInfo(name = "ayah_first") val ayahFirst: Int = 0,
    @ColumnInfo(name = "ayah_last") val ayahLast: Int = 0,
    @ColumnInfo(name = "bookmark_id") val bookmarkId: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val uuid: String
)

@Entity(tableName = "page_annotations", indices = [Index(value = ["uuid"], unique = true)])
data class PageAnnotationEntity(
    @PrimaryKey val page: Int,
    @ColumnInfo(name = "strokes_json") val strokesJson: String = "[]",
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val uuid: String
)

@Entity(tableName = "hafiz_notes", primaryKeys = ["surah", "ayah"], indices = [Index(value = ["uuid"], unique = true)])
data class HafizNoteEntity(
    val surah: Int, val ayah: Int,
    val body: String = "",
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val uuid: String
)

@Entity(tableName = "learning_meta")
data class LearningMetaEntity(@PrimaryKey val key: String, val value: String)

@Dao
interface LearningDao {
    @Query("SELECT * FROM bookmark_types ORDER BY id") suspend fun types(): List<BookmarkTypeEntity>
    @Query("SELECT * FROM bookmark_types ORDER BY id") fun observeTypes(): Flow<List<BookmarkTypeEntity>>
    @Query("SELECT * FROM bookmark_types WHERE id = :id LIMIT 1") suspend fun typeById(id: Long): BookmarkTypeEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertType(row: BookmarkTypeEntity): Long
    @Update suspend fun updateType(row: BookmarkTypeEntity)
    @Query("DELETE FROM bookmark_types WHERE id=:id AND is_custom=1") suspend fun deleteCustomType(id: Long)

    @Query("SELECT * FROM bookmarks ORDER BY page,type_id") suspend fun bookmarks(): List<BookmarkEntity>
    @Query("SELECT * FROM bookmarks ORDER BY page,type_id") fun observeBookmarks(): Flow<List<BookmarkEntity>>
    @Query("SELECT * FROM bookmarks WHERE page=:page AND type_id=:typeId LIMIT 1") suspend fun bookmarkAt(page: Int, typeId: Long): BookmarkEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertBookmark(row: BookmarkEntity): Long
    @Query("DELETE FROM bookmarks WHERE id=:id") suspend fun removeBookmark(id: Long)

    @Query("SELECT * FROM notes ORDER BY updated_at DESC") suspend fun notes(): List<NoteEntity>
    @Query("SELECT * FROM notes ORDER BY updated_at DESC") fun observeNotes(): Flow<List<NoteEntity>>
    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1") suspend fun noteById(id: Long): NoteEntity?
    @Insert suspend fun insertNote(row: NoteEntity): Long
    @Update suspend fun updateNote(row: NoteEntity)
    @Query("DELETE FROM notes WHERE id=:id") suspend fun deleteNote(id: Long)

    @Query("SELECT * FROM page_annotations WHERE page=:page LIMIT 1") suspend fun annotation(page: Int): PageAnnotationEntity?
    @Query("SELECT EXISTS(SELECT 1 FROM page_annotations WHERE page=:page AND strokes_json!='[]')") suspend fun hasInk(page: Int): Boolean
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAnnotation(row: PageAnnotationEntity)

    @Query("SELECT * FROM hafiz_notes WHERE surah=:surah AND ayah=:ayah LIMIT 1") suspend fun hafizNote(surah: Int, ayah: Int): HafizNoteEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putHafizNote(row: HafizNoteEntity)

    @Query("SELECT value FROM learning_meta WHERE `key`=:key LIMIT 1") suspend fun meta(key: String): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMeta(row: LearningMetaEntity)
}

@Database(
    entities = [BookmarkTypeEntity::class, BookmarkEntity::class, NoteEntity::class,
        PageAnnotationEntity::class, HafizNoteEntity::class, LearningMetaEntity::class],
    version = 1,
    exportSchema = true
)
abstract class LearningRoomDatabase : RoomDatabase() {
    abstract fun learningDao(): LearningDao

    companion object {
        @Volatile private var instance: LearningRoomDatabase? = null
        fun open(context: Context): LearningRoomDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LearningRoomDatabase::class.java,
                "q7h_learning_room.db")
                // No destructive fallback: missing migrations must fail rather than erase notes.
                .build().also { instance = it }
        }
    }
}
