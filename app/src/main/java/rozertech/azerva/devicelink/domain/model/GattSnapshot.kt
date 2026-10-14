package rozertech.azerva.devicelink.domain.model


/**
 * Represents the current state of a GATT connection and its discovered characteristics.
 *
 * @property address Address of the BLE peripheral.
 * @property connected Indicates whether the peripheral is currently connected.
 * @property attributes List of discovered GATT characteristics.
 * @property error Error description, if an error has occurred.
 */
data class GattSnapshot(
    val address: String,
    val connected: Boolean,
    val attributes: List<GattAttribute> = emptyList(),
    val error: String? = null
)
