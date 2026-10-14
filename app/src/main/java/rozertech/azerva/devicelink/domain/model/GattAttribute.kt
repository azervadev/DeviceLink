package rozertech.azerva.devicelink.domain.model

/**
 * Represents a GATT characteristic and its supported operations.
 *
 * @property serviceUuid UUID of the service containing the characteristic.
 * @property characteristicUuid UUID of the characteristic.
 * @property readable Indicates whether the characteristic supports reading.
 * @property writable Indicates whether the characteristic supports writing.
 * @property notifiable Indicates whether the characteristic supports notifications.
 */
data class GattAttribute(
    val serviceUuid: String,
    val characteristicUuid: String,
    val readable: Boolean,
    val writable: Boolean,
    val notifiable: Boolean
)
