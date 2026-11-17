package raf.console.quran7hours.ui

import android.content.Context
import android.content.res.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import raf.console.quran7hours.data.MushafStore
import raf.console.quran7hours.data.UserPreferences

/**
 * Optional, entirely offline calibration using the ACTUAL Mushaf images installed
 * on the user's device. It processes ALL 604 pages, in bounded WorkManager jobs.
 * No user strokes are changed. Geometry is retained in app files and included
 * in the existing Q7H backup; it may always be regenerated from source images.
 * "Calibrated" means image/layout projections were computed, not that an OCR
 * classifier was trained or that verse boundaries were human-certified.
 */
internal object Q7HSmartAyahCalibration {
    private const val JOB = "q7h_smart_ayah_calibration_v7"
    private const val PREFS = "q7h_smart_ayah_calibration_v7"
    private const val CHUNK = 24
    internal const val TOTAL = 604
    internal data class Status(
        val processed: Int,
        val success: Int,
        val failed: Int,
        val running: Boolean,
        val variant: String
    )

    internal fun selectedVariant(context: Context, prefs: UserPreferences): MushafStore.Variant {
        val systemDark = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val dark = when (prefs.nightMode) { "dark" -> true; "light" -> false; else -> systemDark }
        return MushafStore(context).variant(dark, prefs.tajweed)
    }

    internal fun status(context: Context): Status {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Status(p.getInt("processed", 0).coerceIn(0,TOTAL),
            p.getInt("success",0).coerceIn(0,TOTAL),
            p.getInt("failed",0).coerceIn(0,TOTAL),
            p.getBoolean("running",false), p.getString("variant", "").orEmpty())
    }

    internal fun start(context: Context, variant: MushafStore.Variant) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("processed", 0).putInt("success",0).putInt("failed",0)
            .putBoolean("running", true).putBoolean("cancelled", false)
            .putString("variant",variant.name).apply()
        val requests = (0 until TOTAL step CHUNK).map { start ->
            OneTimeWorkRequestBuilder<Q7HSmartAyahCalibrationWorker>()
                .setInputData(workDataOf("from" to (start + 1),
                    "to" to (start + CHUNK).coerceAtMost(TOTAL),
                    "variant" to variant.name))
                .build()
        }
        val workManager = WorkManager.getInstance(app)
        var chain = workManager.beginUniqueWork(JOB, ExistingWorkPolicy.REPLACE, requests.first())
        requests.drop(1).forEach { chain = chain.then(it) }
        chain.enqueue()
    }

    internal fun stop(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(JOB)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("running", false).putBoolean("cancelled", true).apply()
    }

    internal fun report(context: Context, processed: Int, success: Int, failed: Int) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putInt("processed",processed).putInt("success",success)
            .putInt("failed",failed)
            .putBoolean("running",processed<TOTAL && !p.getBoolean("cancelled",false)).apply()
    }
}

internal class Q7HSmartAyahCalibrationWorker(
    appContext: Context, parameters: WorkerParameters
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val first = inputData.getInt("from", 1).coerceIn(1,604)
        val last = inputData.getInt("to", first).coerceIn(first,604)
        val kind = runCatching {
            MushafStore.Variant.valueOf(inputData.getString("variant") ?: "")
        }.getOrNull() ?: return@withContext Result.failure()
        val mushaf = MushafStore(applicationContext)
        var success = Q7HSmartAyahCalibration.status(applicationContext).success
        var failed = Q7HSmartAyahCalibration.status(applicationContext).failed
        val auditDir = java.io.File(applicationContext.filesDir,"q7h_smart_ayah_v7")
            .apply { mkdirs() }
        val audit = java.io.File(auditDir,"audit_${kind.name.lowercase()}.csv")
        if (first == 1) audit.writeText("page,status,reason\n",Charsets.UTF_8)
        for (page in first..last) {
            if (isStopped) {
                Q7HSmartAyahCalibration.report(applicationContext,page-1,success,failed)
                return@withContext Result.success()
            }
            val result = runCatching {
                val image = mushaf.load(page, kind, targetWidth=1400, targetHeight=2300)
                if (image == null) Q7HSmartAyahMarker.Calibration(false,"image_unavailable")
                else Q7HSmartAyahMarker.calibrate(applicationContext,image,page)
            }.getOrElse { Q7HSmartAyahMarker.Calibration(false,"processing_error") }
            if (result.ok) success++ else failed++
            val reason = result.reason.replace(',', ';').replace('\n', ' ')
            audit.appendText("$page,${if(result.ok) "ok" else "failed"},$reason\n",Charsets.UTF_8)
            Q7HSmartAyahCalibration.report(applicationContext,page,success,failed)
        }
        Result.success()
    }
}
