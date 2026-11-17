package raf.console.quran7hours.ui

import android.content.Context
import java.io.IOException

/** Thread-safe Q7M1 asset loader used by the stage-10 integrated selector. */
internal object Q7HCompactModelAssets {
    private const val PATH = "q7h_smart_ayah/compact_model_v1/model.q7m"
    @Volatile private var cached: Q7HCompactModelV1? = null

    @Synchronized
    fun load(context: Context): Q7HCompactModelV1? {
        cached?.let { return it }
        return try {
            context.applicationContext.assets.open(PATH).use { stream ->
                Q7HCompactModelV1.fromStream(stream).also { cached = it }
            }
        } catch (_: IOException) {
            // A corrupt/missing model must not break reading Quran; v7 remains active.
            null
        }
    }
}
