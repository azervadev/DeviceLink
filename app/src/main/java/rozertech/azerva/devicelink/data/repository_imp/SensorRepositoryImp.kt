package rozertech.azerva.devicelink.data.repository_imp

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import jakarta.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import rozertech.azerva.devicelink.domain.model.MotionSample
import rozertech.azerva.devicelink.domain.repository.SensorRepository
import kotlin.jvm.java


/**
 * Android implementation of [SensorRepository].
 *
 * Observes accelerometer and optional ambient light sensor data
 * using the Android SensorManager.
 *
 * Emits motion samples through a Flow and automatically unregisters
 * sensor listeners when observation is cancelled.
 *
 * @param context Application context used to access system sensors.
 */
class SensorRepositoryImp @Inject constructor(
    @ApplicationContext context: Context
) : SensorRepository {

    private val sensors = context.getSystemService(SensorManager::class.java)

    /**
     * Observes accelerometer and ambient light measurements.
     *
     * Emits a [MotionSample] whenever a new accelerometer reading
     * is received, including the latest available ambient light value.
     *
     * Closes the Flow with an exception if the accelerometer is
     * unavailable or its listener cannot be registered.
     *
     * @return A Flow emitting sensor measurements in real time.
     */
    override fun observe(): Flow<MotionSample> = callbackFlow {
        val acceleration = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        if (acceleration == null) {
            close(IllegalStateException("Accelerometer unavailable on this device"))
            return@callbackFlow
        }

        val ambient = sensors.getDefaultSensor(Sensor.TYPE_LIGHT)
        var light: Float? = null

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_LIGHT -> light = event.values[0]

                    Sensor.TYPE_ACCELEROMETER -> trySend(
                        MotionSample(
                            event.values[0],
                            event.values[1],
                            event.values[2],
                            light,
                            System.currentTimeMillis()
                        )
                    )
                }
            }

            override fun onAccuracyChanged(
                sensor: Sensor?,
                accuracy: Int
            ) = Unit
        }

        if (!sensors.registerListener(
                listener,
                acceleration,
                SensorManager.SENSOR_DELAY_UI
            )
        ) {
            close(IllegalStateException("Cannot register the accelerometer"))
            return@callbackFlow
        }

        if (ambient != null) {
            sensors.registerListener(
                listener,
                ambient,
                SensorManager.SENSOR_DELAY_UI
            )
        }

        awaitClose {
            sensors.unregisterListener(listener)
        }
    }
}
