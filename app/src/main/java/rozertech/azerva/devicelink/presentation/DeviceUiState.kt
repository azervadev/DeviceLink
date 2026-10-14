package rozertech.azerva.devicelink.presentation

import rozertech.azerva.devicelink.domain.model.BlePeripheral
import rozertech.azerva.devicelink.domain.model.EventLog
import rozertech.azerva.devicelink.domain.model.GattSnapshot
import rozertech.azerva.devicelink.domain.model.MotionSample
import rozertech.azerva.devicelink.domain.model.NfcRecord

/**
 * Represents the complete UI state of the DeviceLink screen.
 *
 * Holds hardware information, demonstration mode settings,
 * connection status and locally recorded events.
 *
 * @property tab Currently selected navigation tab.
 * @property isDemoEnabled Indicates whether demonstration mode is active.
 * @property peripherals Discovered BLE peripherals.
 * @property isScanning Indicates whether BLE scanning is active.
 * @property isConnecting Indicates whether a BLE connection is in progress.
 * @property connection Current GATT connection snapshot.
 * @property lastValue Most recently read GATT value.
 * @property lastNotification Most recently received GATT notification.
 * @property nfc Most recently detected NFC record.
 * @property sensor Latest available sensor sample.
 * @property history List of recorded application events.
 */
data class DeviceUiState(
    val tab: DeviceTab = DeviceTab.HOME,
    val isDemoEnabled: Boolean = false,

    val peripherals: List<BlePeripheral> = emptyList(),
    val isScanning: Boolean = false,
    val isConnecting: Boolean = false,
    val connection: GattSnapshot? = null,
    val lastValue: String = "No value read",
    val lastNotification: String = "No notifications",

    val nfc: NfcRecord? = null,
    val sensor: MotionSample? = null,

    val history: List<EventLog> = emptyList()
)
