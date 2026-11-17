package raf.console.quran7hours.data

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Settings live in SharedPreferences and are therefore included in user backup snapshots. */
internal object Q7HBackupSchedule {
    const val SETTINGS = "q7h_automatic_backup_v2"
    private const val PERIODIC = "q7h_periodic_user_backup_v2"
    private const val INITIAL = "q7h_initial_user_backup_v2"
    private val validIntervals = setOf(1L, 3L, 6L, 12L, 24L, 72L, 168L, 720L)

    private fun p(context:Context) =
        context.applicationContext.getSharedPreferences(SETTINGS,Context.MODE_PRIVATE)

    fun enabled(context:Context):Boolean = p(context).getBoolean("enabled",false)
    fun intervalHours(context:Context):Long = p(context).getLong("interval_hours",24L)
        .takeIf { it in validIntervals } ?: 24L
    fun keepCount(context:Context):Int = p(context).getInt("keep_count",5).coerceIn(1,20)
    fun lastSuccess(context:Context):Long = p(context).getLong("last_success",0L)
    fun lastError(context:Context):String = p(context).getString("last_error","").orEmpty()

    fun setEnabled(context:Context, active:Boolean) {
        p(context).edit().putBoolean("enabled",active).apply()
        refresh(context, makeFirstCopy=active)
    }
    fun setIntervalHours(context:Context, hours:Long) {
        require(hours in validIntervals)
        p(context).edit().putLong("interval_hours",hours).apply()
        refresh(context)
    }
    fun setKeepCount(context:Context,count:Int) {
        require(count in 1..20)
        p(context).edit().putInt("keep_count",count).apply()
    }

    /** Called on Activity startup AFTER any pending restore was applied. */
    fun refresh(context:Context, makeFirstCopy:Boolean=false) {
        val work=WorkManager.getInstance(context.applicationContext)
        if (!enabled(context)) {
            work.cancelUniqueWork(PERIODIC)
            work.cancelUniqueWork(INITIAL)
            return
        }
        val constraints=Constraints.Builder().setRequiresStorageNotLow(true).build()
        val periodic=PeriodicWorkRequestBuilder<Q7HBackupWorker>(intervalHours(context),TimeUnit.HOURS)
            .setConstraints(constraints).build()
        work.enqueueUniquePeriodicWork(PERIODIC,ExistingPeriodicWorkPolicy.UPDATE,periodic)
        if (makeFirstCopy) {
            work.enqueueUniqueWork(INITIAL,ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<Q7HBackupWorker>()
                    .setConstraints(constraints).build())
        }
    }

    internal fun reportSuccess(context:Context) {
        p(context).edit().putLong("last_success",System.currentTimeMillis())
            .remove("last_error").apply()
    }
    internal fun reportError(context:Context, message:String) {
        p(context).edit().putString("last_error",message.take(200)).apply()
    }
}

/** Deferrable periodic local snapshots; no Internet access and no notification permission. */
internal class Q7HBackupWorker(context:Context, parameters:WorkerParameters)
    : CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result = withContext(Dispatchers.IO) {
        val app=applicationContext
        if (!Q7HBackupSchedule.enabled(app) || Q7HBackupManager.hasPendingImport(app)) {
            return@withContext Result.success()
        }
        try {
            // A successful export is published to Downloads only after ZIP completion.
            Q7HBackupManager.export(app,automatic=true,keepAutomatic=Q7HBackupSchedule.keepCount(app))
            Q7HBackupSchedule.reportSuccess(app)
            Result.success()
        } catch(error:Exception) {
            Log.e("Q7HBackup","Automatic backup failed",error)
            Q7HBackupSchedule.reportError(app,error.localizedMessage ?: "Не удалось сохранить копию")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
