package eu.kanade.tachiyomi.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.sync.service.DemosCategoryControl
import eu.kanade.tachiyomi.data.sync.service.DemosRemoteCommands
import eu.kanade.tachiyomi.util.system.isOnline
import eu.kanade.tachiyomi.util.system.workManager
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

/**
 * Revocable, device-side opt-in to skip a SECOND phone confirmation for
 * category changes already reviewed/approved by the owner in ChatGPT.
 *
 * Never runs generic commands, installs/uninstalls extensions, modifies
 * progress, deletes files, manages backups, or bypasses Android system UI.
 * Exact previews are still checked against the current local DB revision.
 */
class DemosAutoCategoryJob(private val context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    private val prefs: SyncPreferences = Injekt.get()

    override suspend fun doWork(): Result {
        if (!isEnabled(prefs)) return Result.success()
        if (!context.isOnline()) return Result.retry()

        val command = try {
            DemosRemoteCommands.pending(prefs).firstOrNull { allowedAction(it.action) }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            return Result.retry()
        } ?: return Result.success()

        if (!isEnabled(prefs)) return Result.success()
        val success = try {
            // The existing transaction checks scope, expiry, exact preview and
            // revision, then durably journals the commit before any acknowledgement.
            DemosCategoryControl.execute(context, command)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            false
        }

        try {
            DemosRemoteCommands.report(prefs, command.command_id, if (success) "completed" else "failed")
        } catch (e: Exception) {
            // A successful category transaction is journaled and safe to replay
            // when result reporting fails. Never mark the action completed here.
            logcat(LogPriority.ERROR, e)
            return Result.retry()
        }
        return Result.success()
    }

    companion object {
        internal fun allowedAction(action: String): Boolean = action == "category_changes"

        private const val PERIODIC = "DemosAutoCategory:periodic"
        private const val CHECK = "DemosAutoCategory:check"

        private fun isEnabled(prefs: SyncPreferences): Boolean =
            prefs.unattendedCategoryActions().get() &&
                prefs.remoteScope("read_library").get() &&
                prefs.remoteScope("organize_library").get() &&
                prefs.syncService().get() == SyncManager.SyncService.SYNCYOMI.value

        fun configure(context: Context, enabled: Boolean) {
            if (!enabled) {
                context.workManager.cancelUniqueWork(PERIODIC)
                context.workManager.cancelUniqueWork(CHECK)
                return
            }
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            context.workManager.enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<DemosAutoCategoryJob>(15, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .build(),
            )
            checkNow(context)
        }

        fun checkNow(context: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            context.workManager.enqueueUniqueWork(
                CHECK,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<DemosAutoCategoryJob>()
                    .setConstraints(constraints)
                    .build(),
            )
        }
    }
}
