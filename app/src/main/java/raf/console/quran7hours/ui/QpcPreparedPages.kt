package raf.console.quran7hours.ui

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import raf.console.quran7hours.data.TafsirStore

/**
 * A page is exposed atomically: Azan.ru verses + verified QPC glyphs + loaded font faces.
 * This prevents Unicode verses appearing and then changing to QPC a second later.
 */
internal data class QpcPreparedPage(
    val verses: List<TafsirStore.Verse>,
    val qpc: Map<Pair<Int, Int>, OriginalQpcAyah>
)

internal object QpcPreparedPages {
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var repo: OriginalQpcTafsirRepository? = null
    private var owner: Context? = null
    private val ready = object : LinkedHashMap<Int, QpcPreparedPage>(6, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, QpcPreparedPage>?): Boolean = size > 5
    }

    suspend fun page(context: Context, store: TafsirStore, number: Int): QpcPreparedPage =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            // Serialize preparation so the 604-page index is only constructed once.
            lock.lock()
            try {
                if (owner !== app) {
                    owner = app
                    repo = OriginalQpcTafsirRepository(app)
                    ready.clear()
                }
                ready[number]?.let { return@withContext it }
                val verses = store.page(number)
                val qpc = repo!!.forPage(number, verses)
                // Both variants are ready before this object becomes visible to Compose.
                warmOriginalQpcFonts(app, qpc.values)
                QpcPreparedPage(verses, qpc).also { ready[number] = it }
            } finally {
                lock.unlock()
            }
        }

    /** Called immediately on activity creation, without waiting for panel opening. */
    fun start(context: Context, store: TafsirStore, currentPage: Int) {
        if (currentPage !in 1..604) return
        scope.launch {
            try {
                page(context, store, currentPage)
                // Small neighborhood only. Preloading all 1208 TTFs would be wasteful.
                if (currentPage < 604) page(context, store, currentPage + 1)
                if (currentPage > 1) page(context, store, currentPage - 1)
            } catch (e: Exception) {
                Log.w("QpcWarmup", "QPC preloading failed on page $currentPage", e)
            }
        }
    }
}
