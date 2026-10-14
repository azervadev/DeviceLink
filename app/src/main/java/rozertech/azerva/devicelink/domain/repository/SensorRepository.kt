package rozertech.azerva.devicelink.domain.repository

import kotlinx.coroutines.flow.Flow
import rozertech.azerva.devicelink.domain.model.MotionSample


/**
 * Defines the contract for observing device sensor measurements.
 *
 * Abstracts sensor data collection from platform-specific
 * implementations.
 */
interface SensorRepository {

    /**
     * Observes motion and environmental sensor measurements.
     *
     * @return A Flow emitting motion samples collected from device sensors.
     */
    fun observe(): Flow<MotionSample>
}
