package rozertech.azerva.devicelink.domain.model


/**
 * Represents a BLE peripheral discovered during a scan.
 *
 * @property address Temporary device identifier provided by Android.
 * @property name Advertised name of the peripheral.
 * @property rssi Received signal strength indicator, expressed in dBm.
 */
data class BlePeripheral(
    val address: String,
    val name: String,
    val rssi: Int
)
