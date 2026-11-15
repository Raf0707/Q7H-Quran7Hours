package raf.console.quran7hours.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Back up USER data, not the static Mushaf/fonts/reciter MP3 asset packs. */
internal object Q7HBackupManager {
    private const val TAG = "Q7HBackup"
    private const val VERSION = 1
    private const val MAX_SINGLE_ENTRY = 64L * 1024L * 1024L
    private const val MAX_TOTAL = 256L * 1024L * 1024L
    private const val PENDING = ".q7h_import_pending.zip"
    private const val ROLLBACK = ".q7h_backup_rollback"
    private fun root(context: Context) = File(context.applicationInfo.dataDir)
    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
    private fun sha(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
        FileInputStream(file).use { input ->
            val buffer=ByteArray(16*1024)
            while(true) { val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n) }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun allowed(name: String): Boolean {
        if(name.isBlank() || name.startsWith('/') || name.contains('\\') ||
            name.split('/').any { it==".." || it=="." || it.isEmpty() }) return false
        if(name.startsWith("shared_prefs/")) return name.endsWith(".xml")
        if(name.startsWith("databases/")) return !name.endsWith("-wal") && !name.endsWith("-shm") && !name.endsWith("-journal")
        if(name.startsWith("files/")) {
            val p=name.removePrefix("files/").lowercase(Locale.ROOT)
            if(p.startsWith(".q7h_") || p.startsWith("q7h_asset") || p.startsWith("quran/") ||
                p.startsWith("assets/") || p.startsWith("qpc-v4/") || p.startsWith("mushaf/") ||
                p.startsWith("quran_") || p.startsWith("tafsir_azan_ru/") ||
                p.startsWith("downloads/") || p.startsWith("release_") ||
                p.endsWith(".mp3") || p.endsWith(".webp") || p.endsWith(".zip")) return false
            return true
        }
        return false
    }
    private fun walk(dir: File): Sequence<File> = sequence {
        dir.listFiles()?.forEach { file ->
            if(file.isDirectory) yieldAll(walk(file)) else if(file.isFile) yield(file)
        }
    }
    private fun dbSnapshot(source:File,out:File) {
        if(!source.exists()) return
        out.parentFile?.mkdirs()
        // VACUUM INTO creates a transactionally consistent snapshot of SQLite + WAL.
        // Never create a raw DB copy while the application may still be writing.
        if(source.inputStream().use { stream ->
                val header=ByteArray(16);stream.read(header)==16 &&
                    String(header, Charsets.US_ASCII).startsWith("SQLite format 3")
            }) {
            out.delete()
            val sqlPath=out.absolutePath.replace("'","''")
            val db=SQLiteDatabase.openDatabase(source.absolutePath,null,SQLiteDatabase.OPEN_READONLY)
            try { db.execSQL("VACUUM INTO '$sqlPath'") } finally { db.close() }
        } else source.copyTo(out,overwrite=true)
    }
    private fun sourceFiles(context:Context, stage:File): List<Pair<String,File>> {
        val app=root(context)
        val collected=mutableListOf<Pair<String,File>>()
        for(category in listOf("shared_prefs","databases","files")) {
            val folder=File(app,category)
            for(file in walk(folder)) {
                val relative="$category/${file.relativeTo(folder).invariantSeparatorsPath}"
                if(!allowed(relative))continue
                if(file.length()>MAX_SINGLE_ENTRY) throw IllegalStateException("Слишком большой пользовательский файл: $relative")
                val snapshot=File(stage,relative)
                snapshot.parentFile?.mkdirs()
                if(category=="databases") dbSnapshot(file,snapshot)
                else file.copyTo(snapshot,overwrite=true)
                // Every ZIP entry uses its own frozen snapshot, never a live prefs XML.
                collected.add(relative to snapshot)
            }
        }
        return collected
    }
    fun export(context:Context): Uri {
        require(Build.VERSION.SDK_INT>=29) { "Экспорт в Загрузки требует Android 10 или новее" }
        val stage=File(context.cacheDir,"q7h_export_${System.nanoTime()}").apply{mkdirs()}
        try {
            val entries=sourceFiles(context,stage)
            if(entries.sumOf { it.second.length() }>MAX_TOTAL)
                error("Объём пользовательских данных превышает допустимый размер копии")
            val temp=File(stage,"backup.zip")
            val manifest=JSONObject().put("format","Quran7Hours-user-backup")
                .put("version",VERSION).put("created_at",System.currentTimeMillis())
            val files=JSONArray()
            ZipOutputStream(FileOutputStream(temp)).use { output ->
                for((path,file) in entries) {
                    val hash=sha(file)
                    files.put(JSONObject().put("path",path).put("bytes",file.length()).put("sha256",hash))
                    output.putNextEntry(ZipEntry(path))
                    FileInputStream(file).use { it.copyTo(output) }
                    output.closeEntry()
                }
                output.putNextEntry(ZipEntry("manifest.json"))
                output.write(manifest.put("files",files).toString(2).toByteArray(Charsets.UTF_8))
                output.closeEntry()
            }
            val stamp=SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).apply{timeZone=TimeZone.getDefault()}.format(Date())
            val name="Quran7Hours_Backup_${stamp}.zip"
            val values=ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME,name)
                put(MediaStore.Downloads.MIME_TYPE,"application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH,"${Environment.DIRECTORY_DOWNLOADS}/Quran7Hours")
                put(MediaStore.Downloads.IS_PENDING,1)
            }
            val resolver=context.contentResolver
            val uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)
                ?: error("Не удалось создать файл в Загрузках")
            try {
                resolver.openOutputStream(uri)?.use { out -> temp.inputStream().use { it.copyTo(out) } }
                    ?: error("Не удалось записать резервную копию")
                resolver.update(uri,ContentValues().apply{put(MediaStore.Downloads.IS_PENDING,0)},null,null)
            } catch(e:Exception) { resolver.delete(uri,null,null);throw e }
            return uri
        } finally { stage.deleteRecursively() }
    }
    fun share(context: Context, uri: Uri) {
        val intent=Intent(Intent.ACTION_SEND).apply {
            type="application/zip"
            putExtra(Intent.EXTRA_STREAM,uri)
            clipData=android.content.ClipData.newUri(context.contentResolver,"Quran7Hours Backup",uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent,"Сохранить резервную копию"))
    }
    /** Validate everything BEFORE scheduling a restore, without touching existing data. */
    private fun verify(zip:File): List<String> = ZipFile(zip).use { archive ->
        val manifest=archive.getEntry("manifest.json") ?: error("Нет manifest.json")
        val raw=archive.getInputStream(manifest).use{ it.readBytes() }
        val data=JSONObject(String(raw,Charsets.UTF_8))
        require(data.optString("format")=="Quran7Hours-user-backup" && data.optInt("version")==VERSION) {
            "Неподдерживаемый формат резервной копии"
        }
        val files=data.getJSONArray("files")
        val expected=linkedSetOf<String>()
        var total=0L
        for(i in 0 until files.length()) {
            val item=files.getJSONObject(i)
            val path=item.getString("path")
            require(allowed(path) && expected.add(path)) { "Недопустимый или повторяющийся файл: $path" }
            val entry=archive.getEntry(path) ?: error("Нет файла $path")
            require(!entry.isDirectory && entry.size in 0..MAX_SINGLE_ENTRY) { "Недопустимый размер: $path" }
            total+=entry.size
            require(total<=MAX_TOTAL) { "Архив слишком большой" }
            val digest=MessageDigest.getInstance("SHA-256")
            archive.getInputStream(entry).use { input ->
                val buffer=ByteArray(16384)
                while(true) {val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}
            }
            val hash=digest.digest().joinToString("") { "%02x".format(it) }
            require(hash.equals(item.getString("sha256"),true) && entry.size==item.getLong("bytes")) {
                "Не совпадает контрольная сумма: $path"
            }
        }
        val entryNames=archive.entries().asSequence().filter{!it.isDirectory}.map{it.name}.toList()
        require(entryNames.size == entryNames.toSet().size && entryNames.all {
            it=="manifest.json" || it in expected
        }) { "Архив содержит посторонние или повторяющиеся файлы" }
        expected.toList()
    }
    fun stageImport(context:Context,uri:Uri): Int {
        val target=File(context.filesDir,PENDING)
        val temp=File(context.filesDir,"${PENDING}.tmp")
        try {
            context.contentResolver.openInputStream(uri)?.use{input->
                FileOutputStream(temp).use{output->input.copyTo(output)}
            } ?: error("Невозможно прочитать выбранную копию")
            val names=verify(temp)
            if(target.exists())target.delete()
            require(temp.renameTo(target)) { "Не удалось подготовить импорт" }
            return names.size
        } finally {temp.delete()}
    }
    /** Call before setContent(), while none of the app databases or preferences are open. */
    fun applyPendingRestore(context:Context): Boolean {
        val pending=File(context.filesDir,PENDING)
        if(!pending.exists())return false
        val names=verify(pending)
        val app=root(context)
        val rollback=File(context.filesDir,ROLLBACK)
        if(rollback.exists()) rollback.deleteRecursively()
        rollback.mkdirs()
        val changed=mutableListOf<Pair<File,Boolean>>()
        try {
            ZipFile(pending).use { zip ->
                // First extract validated contents into a private staging area.
                val staged=File(context.filesDir,".q7h_restore_stage")
                staged.deleteRecursively();staged.mkdirs()
                try {
                    names.forEach{path->
                        val out=File(staged,path);out.parentFile?.mkdirs()
                        zip.getInputStream(zip.getEntry(path)).use { input ->
                            FileOutputStream(out).use{input.copyTo(it)}
                        }
                    }
                    names.forEach{path->
                        val dest=File(app,path)
                        val old=dest.exists()
                        if(old) {
                            val saved=File(rollback,path);saved.parentFile?.mkdirs()
                            dest.copyTo(saved,overwrite=true)
                        }
                        changed.add(dest to old)
                        dest.parentFile?.mkdirs()
                        // Preserve WAL/SHM during rollback as they can contain uncheckpointed notes.
                        if(path.startsWith("databases/")) {
                            listOf("-wal","-shm","-journal").forEach { suffix ->
                                val sidecar=File(dest.absolutePath+suffix)
                                if(sidecar.exists()) {
                                    val saveSidecar=File(rollback,path+suffix)
                                    saveSidecar.parentFile?.mkdirs()
                                    sidecar.copyTo(saveSidecar,overwrite=true)
                                }
                                sidecar.delete()
                            }
                        }
                        File(staged,path).copyTo(dest,overwrite=true)
                    }
                } finally { staged.deleteRecursively() }
            }
            pending.delete() // Remove only the temporary imported copy, not user's original ZIP.
            rollback.deleteRecursively()
            return true
        } catch(t:Throwable) {
            Log.e(TAG,"Restore failed; rolling back user data",t)
            changed.asReversed().forEach { (dest,old) ->
                try {
                    if(old) File(rollback,dest.relativeTo(app).invariantSeparatorsPath).copyTo(dest,overwrite=true)
                    else dest.delete()
                    if(dest.parentFile?.name=="databases") {
                        listOf("-wal","-shm","-journal").forEach { suffix ->
                            val sidecar=File(dest.absolutePath+suffix)
                            sidecar.delete()
                            val saved=File(rollback,dest.relativeTo(app).invariantSeparatorsPath+suffix)
                            if(saved.exists())saved.copyTo(sidecar,overwrite=true)
                        }
                    }
                } catch(e:Exception){Log.e(TAG,"Rollback failed for $dest",e)}
            }
            // Do not retry a corrupt or incompatible import on every app launch.
            pending.delete()
            throw t
        }
    }
}
