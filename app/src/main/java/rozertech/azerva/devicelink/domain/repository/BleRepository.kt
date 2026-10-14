package rozertech.azerva.devicelink.domain.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import rozertech.azerva.devicelink.domain.model.BlePeripheral
import rozertech.azerva.devicelink.domain.model.GattAttribute
import rozertech.azerva.devicelink.domain.model.GattSnapshot


/**
 * Defines the contract for Bluetooth Low Energy (BLE) operations.
 *
 * Abstracts device discovery, GATT connections, characteristic
 * operations and notifications from platform-specific implementations.
 */
interface BleRepository {

    /**
     * Scans for nearby BLE peripherals.
     *
     * @return A Flow emitting discovered BLE peripherals.
     */
    fun scan(): Flow<BlePeripheral>

    /**
     * Observes the current GATT connection state.
     *
     * Emits null when no connection snapshot is available.
     */
    val connection: StateFlow<GattSnapshot?>

    /**
     * Observes data received through BLE characteristic notifications.
     *
     * @return A Flow emitting received notification payloads as strings.
     */
    val notifications: Flow<String>

    /**
     * Establishes a GATT connection with a BLE peripheral.
     *
     * @param address Address of the peripheral to connect to.
     * @return A Result indicating success or failure.
     */
    suspend fun connect(address: String): Result<Unit>

    /**
     * Reads the value of a GATT characteristic.
     *
     * @param attribute GATT characteristic to read.
     * @return A Result containing the decoded value or an error.
     */
    suspend fun read(attribute: GattAttribute): Result<String>

    /**
     * Writes a byte array to a GATT characteristic.
     *
     * @param attribute GATT characteristic to write to.
     * @param bytes Data to transmit.
     * @return A Result indicating success or failure.
     */
    suspend fun write(
        attribute: GattAttribute,
        bytes: ByteArray
    ): Result<Unit>

    /**
     * Subscribes to notifications from a GATT characteristic.
     *
     * @param attribute GATT characteristic to subscribe to.
     * @return A Result indicating success or failure.
     */
    suspend fun subscribe(attribute: GattAttribute): Result<Unit>

    /**
     * Requests disconnection from the currently connected BLE peripheral.
     */
    fun disconnect()
}
