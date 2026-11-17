package raf.console.quran7hours.data

import android.app.ActivityManager
import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.WeakHashMap
import java.util.Collections
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max

/** Loads original Mushaf bitmaps. A failed lookup never gets cached. */
class MushafStore(private val context: Context) {
    private val lowMemory = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
    private val memoryBudgetBytes = if (lowMemory) 20 * 1024 * 1024 else
        (Runtime.getRuntime().maxMemory() / 6L).coerceIn(48L*1024*1024, 80L*1024*1024).toInt()
    private val decodeLocks = ConcurrentHashMap<String, Mutex>()
    /** Exact mapping from original WebP pixels to the cropped Bitmap displayed by Compose.
     * Weak keys prevent metadata retaining bitmaps after the decode cache evicts them. */
    data class ImageFrame(
        val sourceWidth: Int, val sourceHeight: Int,
        val decodedWidth: Int, val decodedHeight: Int,
        val cropLeft: Int, val cropTop: Int,
        val visibleWidth: Int, val visibleHeight: Int,
        val sourceSha256: ByteArray?,
    )
    private val imageFrames = Collections.synchronizedMap(WeakHashMap<Bitmap, ImageFrame>())
    fun imageFrame(bitmap: Bitmap): ImageFrame? = imageFrames[bitmap]

    private data class Trimmed(val bitmap: Bitmap, val left: Int, val top: Int)
    // A downloaded image may be unexpectedly large. Keep the extra trust check
    // bounded; an oversized file still decodes normally but uses the v7 fallback.
    private fun tryReadSmallSource(source: Source): ByteArray? = runCatching {
        source.open(context).use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (out.size() + count > 4 * 1024 * 1024) return@use null
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
    }.getOrNull()
    private val cache = object : LruCache<String,Bitmap>(memoryBudgetBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    enum class Variant(val title: String) {
        LIGHT_TAJWEED("Светлая · таджвид"), LIGHT_PLAIN("Светлая · без таджвида"),
        DARK_TAJWEED("Тёмная · таджвид"), DARK_PLAIN("Тёмная · без таджвида")
    }
    fun variant(dark: Boolean, tajweed: Boolean): Variant = when {
        dark && tajweed -> Variant.DARK_TAJWEED
        dark -> Variant.DARK_PLAIN
        tajweed -> Variant.LIGHT_TAJWEED
        else -> Variant.LIGHT_PLAIN
    }
    fun folderName(kind: Variant): String = when(kind) {
        Variant.LIGHT_TAJWEED -> "Quran_Tajweed_Light"
        Variant.LIGHT_PLAIN -> "Quran_Classic_Light"
        Variant.DARK_TAJWEED -> "Quran_Tajweed_Dart"
        Variant.DARK_PLAIN -> "Quran_Classic_Dart"
    }
    private fun folderAliases(kind: Variant): List<String> = when(kind) {
        Variant.LIGHT_TAJWEED -> listOf("Quran_Tajweed_Light")
        Variant.LIGHT_PLAIN -> listOf("Quran_Classic_Light")
        Variant.DARK_TAJWEED -> listOf("Quran_Tajweed_Dart", "Quran_Tajweed_Dark")
        Variant.DARK_PLAIN -> listOf("Quran_Classic_Dart", "Quran_Classic_Dark")
    }
    private sealed class Source(val label: String) {
        class Packaged(val path: String) : Source("assets/$path")
        class Disk(val file: File) : Source(file.absolutePath)
        fun open(context: Context): InputStream = when(this) {
            is Packaged -> context.assets.open(path, AssetManager.ACCESS_STREAMING)
            is Disk -> file.inputStream()
        }
    }
    // AssetManager cannot see files downloaded AFTER installing the APK. Search both
    // packaged assets and private extraction destinations; never assume a ZIP's root
    // folder was stripped by the installer.
    private fun candidates(kind: Variant, number: String): Sequence<Source> = sequence {
        val roots = listOfNotNull(context.filesDir, context.noBackupFilesDir,
            context.getExternalFilesDir(null))
        val prefixes = listOf("", "assets/", "mushaf/", "images/", "quran/", "q7h_assets/")
        for (folder in folderAliases(kind)) {
            val folderNames = listOf(folder, "$folder/$folder")
            for (prefix in prefixes) for (name in folderNames) for (ext in listOf("webp","png","jpg","jpeg")) {
                val rel = "$prefix$name/$number.$ext"
                for (root in roots) {
                    val f = File(root, rel)
                    if (f.isFile && f.length() > 0) yield(Source.Disk(f))
                }
                // Do not list and cache a directory: an extraction may still be ongoing.
                yield(Source.Packaged(rel))
            }
        }
    }
    suspend fun load(page: Int, kind: Variant, targetWidth: Int = 1400, targetHeight: Int = 2300): Bitmap? =
        withContext(Dispatchers.IO) {
            if (page !in 1..604) return@withContext null
            val key = "${kind.name}:$page:$targetWidth:$targetHeight"
            synchronized(cache) { cache.get(key) }?.let { return@withContext it }
            val lock = decodeLocks.getOrPut(key) { Mutex() }
            lock.withLock {
                synchronized(cache) { cache.get(key) }?.let { return@withLock it }
                val number = page.toString().padStart(3, '0')
                var found = false
                var reason: String? = null
                for (source in candidates(kind,number)) {
                    // Stage 11: verify the immutable original bytes for ALL four variants.
                    // Decode and hash the same bytes to avoid races with extraction.
                    // Modified / oversized images still load but safely use v7.
                    val trustedBytes = tryReadSmallSource(source)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    try {
                        if (trustedBytes != null)
                            BitmapFactory.decodeByteArray(trustedBytes,0,trustedBytes.size,bounds)
                        else source.open(context).use { BitmapFactory.decodeStream(it, null, bounds) }
                    } catch (_: java.io.FileNotFoundException) {
                        continue
                    } catch (e: Exception) {
                        reason = "${source.label}: ${e.message}"
                        continue
                    }
                    found = true
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                        reason = "${source.label}: image header could not be decoded"
                        continue
                    }
                    var sample = 1
                    while (bounds.outWidth/(sample*2) >= targetWidth &&
                        bounds.outHeight/(sample*2) >= targetHeight) sample *= 2
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = if (lowMemory) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
                    }
                    val decoded = try {
                        if (trustedBytes != null)
                            BitmapFactory.decodeByteArray(trustedBytes,0,trustedBytes.size,options)
                        else source.open(context).use { BitmapFactory.decodeStream(it, null, options) }
                    } catch (e: Exception) {
                        reason = "${source.label}: ${e.message}"
                        null
                    }
                    if (decoded != null) {
                        val trimmed = trimFlatMargins(decoded)
                        val output = trimmed.bitmap
                        // Source SHA is checked against the appropriate variant/page in Q7S2.
                        // Any modified/recompressed image uses the v7 selector.
                        val rawDigest = trustedBytes?.let { bytes ->
                            MessageDigest.getInstance("SHA-256").digest(bytes)
                        }
                        imageFrames[output] = ImageFrame(
                            bounds.outWidth, bounds.outHeight, decoded.width, decoded.height,
                            trimmed.left, trimmed.top, output.width, output.height, rawDigest
                        )
                        synchronized(cache) { cache.put(key, output) }
                        Log.d("Q7HMushaf", "Page $number ${kind.name} from ${source.label}")
                        return@withLock output
                    }
                    reason = "${source.label}: bitmap decode returned null"
                }
                if (found) Log.e("Q7HMushaf", "Page $number ${kind.name}: found but cannot decode; $reason")
                else Log.e("Q7HMushaf", "Page $number ${kind.name}: NOT FOUND in packaged assets or app-private folders. Check ZIP nesting/extraction and variant name.")
                null // do not cache missing pages; retry when the installer finishes
            }
        }
    fun backdropColor(bitmap: Bitmap): Int {
        val w = bitmap.width.coerceAtLeast(1); val h = bitmap.height.coerceAtLeast(1)
        val x = listOf(0,(w*0.01f).toInt(),w-1-(w*0.01f).toInt(),w-1)
        val y = listOf(0,(h*0.01f).toInt(),h-1-(h*0.01f).toInt(),h-1)
        val samples = x.flatMap { px -> listOf(y.first(),y.last()).map { bitmap.getPixel(px,it) } } +
            y.flatMap { py -> listOf(x.first(),x.last()).map { bitmap.getPixel(it,py) } }
        fun median(shift: Int) = samples.map { (it shr shift) and 255 }.sorted()[samples.size/2]
        return android.graphics.Color.rgb(median(16),median(8),median(0))
    }
    /** Preserve the former page cropping policy. Never remove Quran ink near edges. */
    private fun trimFlatMargins(bitmap: Bitmap): Trimmed {
        val width=bitmap.width; val height=bitmap.height
        if(width<250 || height<350) return Trimmed(bitmap,0,0)
        val background=bitmap.getPixel(1,1)
        fun different(c:Int):Boolean {
            val dr=abs(((c shr 16) and 255)-((background shr 16) and 255))
            val dg=abs(((c shr 8) and 255)-((background shr 8) and 255))
            val db=abs((c and 255)-(background and 255))
            return max(dr,max(dg,db))>31
        }
        fun column(x:Int):Boolean {
            var hits=0; val stride=max(1,height/150)
            for(y in 0 until height step stride) if(different(bitmap.getPixel(x,y)) && ++hits>=2) return true
            return false
        }
        fun row(y:Int):Boolean {
            var hits=0; val stride=max(1,width/110)
            for(x in 0 until width step stride) if(different(bitmap.getPixel(x,y)) && ++hits>=2) return true
            return false
        }
        val maxX=(width*0.09f).toInt(); val maxY=(height*0.06f).toInt()
        var left=0;var right=width-1;var top=0;var bottom=height-1
        while(left<maxX && !column(left))left++
        while(right>width-maxX && !column(right))right--
        while(top<maxY && !row(top))top++
        while(bottom>height-maxY && !row(bottom))bottom--
        left=(left-5).coerceAtLeast(0);right=(right+5).coerceAtMost(width-1)
        top=(top-4).coerceAtLeast(0);bottom=(bottom+4).coerceAtMost(height-1)
        if(left==0 && right==width-1 && top==0 && bottom==height-1)return Trimmed(bitmap,0,0)
        return Trimmed(Bitmap.createBitmap(bitmap,left,top,right-left+1,bottom-top+1),left,top)
    }
}
