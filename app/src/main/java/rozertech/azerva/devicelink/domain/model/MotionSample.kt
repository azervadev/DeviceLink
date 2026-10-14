package rozertech.azerva.devicelink.domain.model

/**
 * Represents a sample of motion and environmental sensor data.
 *
 * @property x Motion sensor reading along the X axis.
 * @property y Motion sensor reading along the Y axis.
 * @property z Motion sensor reading along the Z axis.
 * @property lightLux Ambient light level in lux, if available.
 * @property collectedAt Timestamp when the sample was collected, in milliseconds.
 */
data class MotionSample(
    val x: Float,
    val y: Float,
    val z: Float,
    val lightLux: Float?,
    val collectedAt: Long
)
