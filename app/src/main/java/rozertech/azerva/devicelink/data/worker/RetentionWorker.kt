package rozertech.azerva.devicelink.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import rozertech.azerva.devicelink.data.local.EventDao
import java.util.concurrent.TimeUnit


/**
 * Background worker responsible for removing expired event records.
 *
 * Uses WorkManager to delete events older than 30 days from the
 * local Room database, preventing indefinite log accumulation.
 *
 * Retries the operation if an exception occurs during cleanup.
 *
 * @property dao Data access object used to delete expired events.
 */
@HiltWorker
class RetentionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val dao: EventDao,
) : CoroutineWorker(appContext, params) {

    /**
     * Executes the event retention cleanup operation.
     *
     * Deletes events older than 30 days based on the current system time.
     *
     * @return [Result.success] if cleanup completes successfully,
     * or [Result.retry] if an exception occurs.
     */
    override suspend fun doWork(): Result = try {
        dao.deleteBefore(
            System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
        )
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }

    companion object {
        /**
         * Unique identifier used to schedule the retention worker.
         */
        const val NAME = "event-retention"
    }
}
