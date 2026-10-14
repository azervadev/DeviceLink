
package rozertech.azerva.devicelink.domain.usecase

import rozertech.azerva.devicelink.domain.model.EventKind
import rozertech.azerva.devicelink.domain.repository.BleRepository
import rozertech.azerva.devicelink.domain.repository.EventRepository
import javax.inject.Inject

/**
 * Establishes a BLE connection and records successful connections.
 *
 * Coordinates Bluetooth connectivity and event persistence
 * without depending on Android platform implementations.
 *
 * @property ble Repository responsible for BLE operations.
 * @property history Repository responsible for event persistence.
 */
class ConnectBleUseCase @Inject constructor(
    private val ble: BleRepository,
    private val history: EventRepository
) {

    /**
     * Connects to the specified BLE peripheral.
     *
     * Records an event only when the connection succeeds.
     *
     * @param address Bluetooth address of the target peripheral.
     * @return A Result indicating connection success or failure.
     */
    suspend operator fun invoke(address: String): Result<Unit> {
        val result = ble.connect(address)

        if (result.isSuccess) {
            history.add(
                kind = EventKind.BLE,
                title = "Device connected",
                detail = address
            )
        }

        return result
    }
}
