package raf.console.quran7hours.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.app.ActivityManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import android.util.LruCache
import android.content.res.AssetManager
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

/** Always renders the user's own page bitmaps: never reflows Arabic glyphs. */
class MushafStore(private val context: Context) {
    // Keep a few recent pages in memory while swiping, but bound decoded bitmap RAM.
    private val lowMemory = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
    private val memoryBudgetBytes = if (lowMemory) 20 * 1024 * 1024
        else (Runtime.getRuntime().maxMemory() / 6L).coerceIn(48L * 1024 * 1024, 80L * 1024 * 1024).toInt()
    private val decodeLocks = ConcurrentHashMap<String, Mutex>()
    // AssetManager.list is performed only once per folder, not on every page swipe.
    private val indexedFiles = ConcurrentHashMap<String, Map<String, String>>()
    private val cache = object : LruCache<String, Bitmap>(memoryBudgetBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    enum class Variant(val title: String) {
        LIGHT_TAJWEED("Светлая · таджвид"),
        LIGHT_PLAIN("Светлая · без таджвида"),
        DARK_TAJWEED("Тёмная · таджвид"),
        DARK_PLAIN("Тёмная · без таджвида")
    }

    fun variant(dark: Boolean, tajweed: Boolean): Variant = when {
        dark && tajweed -> Variant.DARK_TAJWEED
        dark -> Variant.DARK_PLAIN
        tajweed -> Variant.LIGHT_TAJWEED
        else -> Variant.LIGHT_PLAIN
    }

    fun folderName(kind: Variant): String = when (kind) {
        Variant.LIGHT_TAJWEED -> "Quran_Tajweed_Light"
        Variant.LIGHT_PLAIN -> "Quran_Classic_Light"
        Variant.DARK_TAJWEED -> "Quran_Tajweed_Dart"
        Variant.DARK_PLAIN -> "Quran_Classic_Dart"
    }


    /**
     * Sample the *actual* page background. Dark assets are not assumed to have
     * the same RGB as the Material theme; the letterbox must be invisible.
     * Take a median of edge pixels to reject decorative pixels/noise.
     */
    fun backdropColor(bitmap: Bitmap): Int {
        val width = bitmap.width.coerceAtLeast(1)
        val height = bitmap.height.coerceAtLeast(1)
        val x = listOf(0, (width * 0.01f).toInt(), width - 1 - (width * 0.01f).toInt(), width - 1)
        val y = listOf(0, (height * 0.01f).toInt(), height - 1 - (height * 0.01f).toInt(), height - 1)
        val samples = (x.flatMap { px -> listOf(y.first(), y.last()).map { py -> bitmap.getPixel(px, py) } } +
            y.flatMap { py -> listOf(x.first(), x.last()).map { px -> bitmap.getPixel(px, py) } })
        fun median(shift: Int): Int = samples.map { (it shr shift) and 255 }.sorted()[samples.size / 2]
        return android.graphics.Color.rgb(median(16), median(8), median(0))
    }

    private fun assetFolders(kind: Variant): List<String> = when (kind) {
        Variant.LIGHT_TAJWEED -> listOf("Quran_Tajweed_Light")
        Variant.LIGHT_PLAIN -> listOf("Quran_Classic_Light")
        Variant.DARK_TAJWEED -> listOf("Quran_Tajweed_Dart", "Quran_Tajweed_Dark")
        Variant.DARK_PLAIN -> listOf("Quran_Classic_Dart", "Quran_Classic_Dark")
    }

    suspend fun load(page: Int, kind: Variant, targetWidth: Int = 1400, targetHeight: Int = 2300): Bitmap? =
        withContext(Dispatchers.IO) {
            if (page !in 1..604) return@withContext null
            val cacheKey = "${kind.name}:$page:$targetWidth:$targetHeight"
            synchronized(cache) { cache.get(cacheKey) }?.let { return@withContext it }
            val lock = decodeLocks.getOrPut(cacheKey) { Mutex() }
            lock.withLock {
                synchronized(cache) { cache.get(cacheKey) }?.let { return@withLock it }
            val number = page.toString().padStart(3, '0')
            val source = assetFolders(kind).asSequence().flatMap { folder ->
                sequenceOf("webp", "png", "jpg", "jpeg").map { ext ->
                    folder to "$number.$ext"
                }
            }.mapNotNull { (folder, filename) ->
                val names = indexedFiles.getOrPut(folder) {
                    context.assets.list(folder).orEmpty().associateBy { it.lowercase(Locale.ROOT) }
                }
                names[filename]?.let { folder to it }
            }.firstOrNull() ?: return@withLock null
            val assetPath = "${source.first}/${source.second}"
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching {
                context.assets.open(assetPath, AssetManager.ACCESS_STREAMING).use { input ->
                    BitmapFactory.decodeStream(input, null, bounds)
                }
            }.getOrNull()
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withLock null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetWidth &&
                bounds.outHeight / (sample * 2) >= targetHeight) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                // RGB_565 clips subtle tajweed colours. Use full colour on standard devices.
                inPreferredConfig = if (lowMemory) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
            }
            val decoded = runCatching {
                context.assets.open(assetPath, AssetManager.ACCESS_STREAMING).use { input ->
                    BitmapFactory.decodeStream(input, null, opts)
                }
            }.getOrNull() ?: return@withLock null
            val output = trimFlatMargins(decoded)
            synchronized(cache) { cache.put(cacheKey, output) }
            output
            }
        }

    /**
     * Trim only empty margins and never zoom/crop verse text. At most 9% sideways and 6%
     * vertically. Scan the entire outer edge (sampled) to preserve colored ornaments and
     * page numbers. If uncertain, retain the original image.
     */
    private fun trimFlatMargins(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 250 || height < 350) return bitmap
        val background = bitmap.getPixel(1, 1)
        fun pixelDifferent(color: Int): Boolean {
            val dr = abs(((color shr 16) and 255) - ((background shr 16) and 255))
            val dg = abs(((color shr 8) and 255) - ((background shr 8) and 255))
            val db = abs((color and 255) - (background and 255))
            return max(dr, max(dg, db)) > 31
        }
        fun significantColumn(x: Int): Boolean {
            var hits = 0
            val stride = max(1, height / 150)
            for (y in 0 until height step stride) {
                if (pixelDifferent(bitmap.getPixel(x, y)) && ++hits >= 2) return true
            }
            return false
        }
        fun significantRow(y: Int): Boolean {
            var hits = 0
            val stride = max(1, width / 110)
            for (x in 0 until width step stride) {
                if (pixelDifferent(bitmap.getPixel(x, y)) && ++hits >= 2) return true
            }
            return false
        }
        val maxX = (width * 0.09f).toInt()
        val maxY = (height * 0.06f).toInt()
        var left = 0
        var right = width - 1
        var top = 0
        var bottom = height - 1
        while (left < maxX && !significantColumn(left)) left++
        while (right > width - maxX && !significantColumn(right)) right--
        while (top < maxY && !significantRow(top)) top++
        while (bottom > height - maxY && !significantRow(bottom)) bottom--
        // Keep a protective buffer to avoid cutting antialiasing or edge ornaments.
        left = (left - 5).coerceAtLeast(0)
        right = (right + 5).coerceAtMost(width - 1)
        top = (top - 4).coerceAtLeast(0)
        bottom = (bottom + 4).coerceAtMost(height - 1)
        if (left == 0 && right == width - 1 && top == 0 && bottom == height - 1) return bitmap
        val cropped = Bitmap.createBitmap(bitmap, left, top, right - left + 1, bottom - top + 1)
        // createBitmap may return the same bitmap in some cases; do not recycle shared backing.
        return cropped
    }
}
