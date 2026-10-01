package top.nkbe.npatch.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import top.nkbe.npatch.BuildConfig
import java.util.concurrent.TimeUnit

/** Android may defer checks while idle; work remains scheduled after process death and reboot. */
class UpdateCheckWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (!AppUpdater.backgroundChecks) return Result.success()
        return try {
            val release = AppUpdater.fetchLatest()
            // Recheck the preference after the network request, in case it was disabled meanwhile.
            if (AppUpdater.backgroundChecks && UpdatePolicy.compare(release.version, BuildConfig.VERSION_NAME)?.let { it > 0 } == true) {
                UpdateNotifications.notify(applicationContext, release)
            }
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // A temporary connection or API failure must not permanently stop periodic checks.
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val WORK_NAME = "npatch-release-check"

        fun schedule(context: Context) {
            val manager = WorkManager.getInstance(context)
            if (!AppUpdater.backgroundChecks) {
                manager.cancelUniqueWork(WORK_NAME)
                UpdateNotifications.dismiss(context)
                return
            }
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(6, TimeUnit.HOURS)
                .setInitialDelay(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
