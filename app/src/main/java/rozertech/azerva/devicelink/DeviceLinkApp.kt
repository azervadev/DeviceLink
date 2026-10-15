package rozertech.azerva.devicelink

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.Configuration.Provider
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.HiltAndroidApp
import rozertech.azerva.devicelink.data.worker.RetentionWorker
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Application entry point and WorkManager configuration provider.
 *
 * Hilt creates [HiltWorkerFactory] so [RetentionWorker] can obtain its DAO.
 * The periodic job removes local event records older than 30 days; Android
 * decides the actual execution time (the interval is not an exact alarm).
 *
 * IMPORTANT INTEGRATION REQUIREMENTS OUTSIDE THIS FILE:
 * - Add `androidx.hilt:hilt-work` and KSP `androidx.hilt:hilt-compiler`.
 * - Remove the WorkManager App Startup initializer in AndroidManifest.xml
 *   so this [Configuration.Provider] is actually used.
 * - Preserve `android:name=".DeviceLinkApp"` in the application manifest.
 */
@HiltAndroidApp
class DeviceLinkApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    /** Supplies the Hilt-aware WorkerFactory to WorkManager. */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    /** Schedules at most one persistent periodic retention job. */
    override fun onCreate() {
        super.onCreate()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            RetentionWorker.NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RetentionWorker>(1, TimeUnit.DAYS).build()
        )
    }
}