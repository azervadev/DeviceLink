package rozertech.azerva.devicelink.domain.repository

import kotlinx.coroutines.flow.Flow
import rozertech.azerva.devicelink.domain.model.MotionSample

interface SensorRepository {
    fun observe(): Flow<MotionSample>
}
