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
import top.nkbe.npatch.repo.KnotReleaseLoader
import java.util.concurrent.TimeUnit

/** NPatch 本体の [UpdateCheckWorker] と同じく、Knot の新しいリリースを定期的に確認して通知する。 */
class KnotUpdateCheckWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (!KnotUpdateChecker.backgroundChecks) return Result.success()
        // Knot が入っていなければ更新対象がないため、API を叩かない。
        val installed = KnotUpdateChecker.installedVersion(applicationContext)
            ?.let(KnotUpdatePolicy::normalizeVersion)
        if (installed == null) {
            KnotUpdateNotifications.dismiss(applicationContext)
            return Result.success()
        }
        val includePrereleases = KnotUpdateChecker.includePrereleases
        return try {
            val releases = KnotReleaseLoader.fetchRecentReleases()
            // Recheck the preferences after the network request, in case they changed meanwhile.
            if (KnotUpdateChecker.backgroundChecks && includePrereleases == KnotUpdateChecker.includePrereleases) {
                val update = KnotUpdatePolicy.findUpdate(releases, installed, includePrereleases)
                if (update != null) KnotUpdateNotifications.notify(applicationContext, update, installed)
                else KnotUpdateNotifications.dismiss(applicationContext)
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
        private const val WORK_NAME = "knot-release-check"

        fun schedule(context: Context) {
            KnotUpdateNotifications.dismissIfResolved(context)
            val manager = WorkManager.getInstance(context)
            if (!KnotUpdateChecker.backgroundChecks) {
                manager.cancelUniqueWork(WORK_NAME)
                KnotUpdateNotifications.dismiss(context)
                return
            }
            val request = PeriodicWorkRequestBuilder<KnotUpdateCheckWorker>(6, TimeUnit.HOURS)
                .setInitialDelay(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
