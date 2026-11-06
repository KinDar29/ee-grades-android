package ph.edu.mmsu.ee.grades.notify

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import ph.edu.mmsu.ee.grades.ServiceLocator
import java.util.concurrent.TimeUnit

/**
 * Runs every couple of hours while the phone has a connection. If a student
 * is signed in, it asks the portal for their scores and posts a notification
 * for anything new. Admins and signed-out phones are skipped.
 *
 * Every failure is quiet: the next run simply tries again.
 */
class ScoreCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        ServiceLocator.init(applicationContext)
        val repo = ServiceLocator.repo

        val profile = repo.cachedProfile()
        if (!repo.hasSession() || profile == null || profile.isAdmin) return Result.success()

        return try {
            val fresh = repo.findNewScores()
            ScoreNotifier.show(applicationContext, fresh)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline, signed out on the server, or must change password: try next time.
            Result.success()
        }
    }

    companion object {
        private const val WORK_NAME = "new-score-check"
        private const val EVERY_HOURS = 2L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ScoreCheckWorker>(EVERY_HOURS, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
