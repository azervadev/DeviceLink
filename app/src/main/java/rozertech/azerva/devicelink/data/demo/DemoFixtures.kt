package rozertech.azerva.devicelink.data.demo

import rozertech.azerva.devicelink.domain.model.BlePeripheral
import rozertech.azerva.devicelink.domain.model.GattAttribute


/**
 * Provides predefined BLE data for application demonstration purposes.
 *
 * Contains simulated peripherals and GATT characteristics that allow
 * the application to demonstrate its functionality without physical
 * Bluetooth devices.
 *
 * These fixtures are static test data and must not be represented
 * as real device discoveries or live hardware measurements.
 */
object DemoFixtures {

    /**
     * List of simulated BLE peripherals.
     *
     * Contains predefined device names, identifiers and signal
     * strength values used to populate demonstration screens.
     */
    val peripherals = listOf(
        BlePeripheral(
            "DE:MO:00:00:00:01",
            "Demo Environment Sensor",
            -48
        ),
        BlePeripheral(
            "DE:MO:00:00:00:02",
            "Demo Smart Light",
            -66
        )
    )

    /**
     * List of simulated GATT characteristics.
     *
     * Defines predefined service and characteristic UUIDs
     * together with their supported read, write and notification
     * properties for demonstration purposes.
     */
    val attributes = listOf(
        GattAttribute(
            "0000180F-0000-1000-8000-00805F9B34FB",
            "00002A19-0000-1000-8000-00805F9B34FB",
            true,
            false,
            true
        ),
        GattAttribute(
            "0000FFF0-0000-1000-8000-00805F9B34FB",
            "0000FFF1-0000-1000-8000-00805F9B34FB",
            true,
            true,
            false
        )
    )
}
