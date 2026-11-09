package com.keeper.vault.duress.intruder

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.keeper.vault.duress.AndroidDuressWiring
import com.keeper.vault.duress.DuressFeature
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Daily retention purge.
 *
 * The worker only runs the tested [IntruderRetention] path; it holds no key material. A failed
 * run retries with WorkManager's backoff. The app also purges on module start, so a device that
 * never runs jobs still expires old captures at the next launch.
 */
class IntruderPurgeWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        // Construct the wiring directly: calling DuressFeature.component() would start the
        // component and re-enqueue this same periodic work from inside the worker.
        val component = try {
            DuressFeature.componentOrNull() ?: AndroidDuressWiring.create(applicationContext)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return Result.retry()
        }
        return try {
            component.purgeExpired()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

/**
 * WorkManager scheduling adapter.
 *
 * A unique periodic work keeps repeated module starts from stacking purges. `KEEP` preserves the
 * existing schedule when the app is reopened.
 */
object IntruderPurgeScheduler {
    const val UNIQUE_WORK_NAME: String = "keeper-intruder-purge"
    private const val REPEAT_INTERVAL_DAYS: Long = 1L

    fun buildRequest(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<IntruderPurgeWorker>(
            REPEAT_INTERVAL_DAYS,
            TimeUnit.DAYS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()

    fun schedule(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            buildRequest(),
        )
    }
}
