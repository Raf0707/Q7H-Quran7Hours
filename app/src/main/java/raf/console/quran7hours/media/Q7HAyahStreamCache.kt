package raf.console.quran7hours.media

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import raf.console.quran7hours.data.QuranCatalog
import java.io.File

/**
 * Bounded playback and prefetch cache. PlaybackService and warmup MUST use the
 * same SimpleCache instance and the same unmodified MP3 URL as cache key.
 * Never place this in the Room/backup folder: it contains replaceable network data.
 */
@UnstableApi
object Q7HAyahStreamCache {
    private const val CAPACITY = 64L * 1024L * 1024L
    private const val WORKERS = 2
    @Volatile private var instance: SimpleCache? = null

    @Synchronized private fun cache(context: Context): SimpleCache {
        instance?.let { return it }
        return SimpleCache(
            File(context.applicationContext.cacheDir, "q7h_ayah_http_v2"),
            LeastRecentlyUsedCacheEvictor(CAPACITY),
            StandaloneDatabaseProvider(context.applicationContext)
        ).also { instance = it }
    }

    fun factory(context: Context): CacheDataSource.Factory {
        val app = context.applicationContext
        val upstream = DefaultDataSource.Factory(
            app,
            DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(5000)
                .setReadTimeoutMs(9000)
                .setAllowCrossProtocolRedirects(true)
        )
        return CacheDataSource.Factory()
            .setCache(cache(app))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** All ayahs in the open page FIRST, then the following and preceding pages. */
    fun orderedWindow(
        current: List<Pair<Int, Int>>,
        next: List<Pair<Int, Int>>,
        previous: List<Pair<Int, Int>>
    ): List<Pair<Int, Int>> {
        val unique = LinkedHashSet<Pair<Int, Int>>()
        for (page in listOf(current, next, previous)) {
            for (ref in page) if (QuranCatalog.isValidAyah(ref.first, ref.second)) unique.add(ref)
        }
        return unique.toList()
    }

    /**
     * Preloads whole opened page and its two neighbours, not just first N verses.
     * Two network workers make progress without saturating connections on mobile.
     * Cancel the caller's coroutine on page/reciter change to drop stale work.
     */
    suspend fun warmWindow(
        reciter: Reciter,
        current: List<Pair<Int, Int>>,
        next: List<Pair<Int, Int>>,
        previous: List<Pair<Int, Int>>,
        context: Context
    ) = coroutineScope {
        val ordered = orderedWindow(current, next, previous)
        (0 until minOf(WORKERS, ordered.size)).map { worker ->
            launch(Dispatchers.IO) {
                var index = worker
                var consecutiveFailures = 0
                while (index < ordered.size) {
                    currentCoroutineContext().ensureActive()
                    val (surah, ayah) = ordered[index]
                    consecutiveFailures = if (warmOne(reciter, surah, ayah, context)) {
                        0
                    } else {
                        consecutiveFailures + 1
                    }
                    // Offline / blocked GitHub must not trigger dozens of
                    // timeouts and drain the battery while the user reads.
                    if (consecutiveFailures >= 3) break
                    index += WORKERS
                }
            }
        }.joinAll()
    }

    /** High priority single ayah when the user taps a verse. No playback changes. */
    suspend fun warmSelected(reciter: Reciter, surah: Int, ayah: Int, context: Context) =
        withContext(Dispatchers.IO) { warmOne(reciter, surah, ayah, context) }

    private fun warmOne(reciter: Reciter, surah: Int, ayah: Int, context: Context): Boolean {
        if (!QuranCatalog.isValidAyah(surah, ayah)) return false
        return try {
            val source = factory(context).createDataSource()
            // CacheWriter reads the complete verse. If Media3 is already playing,
            // SimpleCache coordinates overlapping spans safely.
            val spec = DataSpec.Builder().setUri(reciter.audioUrl(surah, ayah)).build()
            CacheWriter(source, spec, ByteArray(32 * 1024), null).cache()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A missing/network-failing verse does NOT block other verses or Play.
            Log.d("Q7H-AyahWarmup", "Cannot prefetch $surah:$ayah", e)
            false
        }
    }
}
