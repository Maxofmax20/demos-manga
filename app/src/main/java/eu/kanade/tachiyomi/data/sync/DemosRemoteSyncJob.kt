package eu.kanade.tachiyomi.data.sync

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.SyncStatus
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.sync.service.DemosRemoteCommands
import eu.kanade.tachiyomi.util.system.isOnline
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

/**
 * Android WorkManager polls only after the phone owner explicitly enables unattended sync.
 * It NEVER fetches or executes approval-required commands and cannot install extensions.
 * Typical polling interval is ~15 minutes and may be delayed by Android power management.
 */
class DemosRemoteSyncJob(private val context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    private val preferences: SyncPreferences = Injekt.get()
    private val syncStatus: SyncStatus = Injekt.get()

    override suspend fun doWork(): Result {
        if (!preferences.unattendedCloudSync().get() ||
            preferences.syncService().get() != SyncManager.SyncService.SYNCYOMI.value
        ) {
            return Result.success()
        }
        if (!context.isOnline() || SyncDataJob.isRunning(context) || syncStatus.isRunning.value) {
            return Result.retry()
        }
        val request = try {
            // A request is claimed only after checking local consent and connectivity.
            DemosRemoteCommands.nextRemoteSync(preferences)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            return Result.retry()
        } ?: return Result.success()

        syncStatus.start()
        var completed = false
        try {
            setForegroundSafely()
            if (!preferences.unattendedCloudSync().get()) {
                return Result.success()
            }
            SyncManager(context).syncData()
            DemosRemoteCommands.reportCloudInventory(preferences)
            completed = true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        } finally {
            syncStatus.stop()
            // The result is the status of both the standard sync and inventory upload.
            runCatching { DemosRemoteCommands.reportRemoteSync(preferences, request.request_id, completed) }
                .onFailure { logcat(LogPriority.ERROR, it) }
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(
            Notifications.ID_SYNC_PROGRESS,
            SyncNotifier(context).showSyncProgress().build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )

    companion object {
        private const val PERIODIC_NAME = "DemosRemoteSync:periodic"
        private const val ONE_TIME_NAME = "DemosRemoteSync:foreground"

        fun configure(context: Context, enabled: Boolean) {
            if (enabled) {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
                val periodic = PeriodicWorkRequestBuilder<DemosRemoteSyncJob>(15, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .build()
                context.workManager.enqueueUniquePeriodicWork(
                    PERIODIC_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    periodic,
                )
                checkNow(context)
            } else {
                context.workManager.cancelUniqueWork(PERIODIC_NAME)
                context.workManager.cancelUniqueWork(ONE_TIME_NAME)
            }
        }

        /** Called when the app launches, in addition to periodic background checks. */
        fun checkNow(context: Context) {
            val oneTime = OneTimeWorkRequestBuilder<DemosRemoteSyncJob>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            context.workManager.enqueueUniqueWork(ONE_TIME_NAME, ExistingWorkPolicy.KEEP, oneTime)
        }
    }
}
