
package rozertech.azerva.devicelink.domain.usecase

import rozertech.azerva.devicelink.domain.model.EventKind
import rozertech.azerva.devicelink.domain.model.GattAttribute
import rozertech.azerva.devicelink.domain.repository.BleRepository
import rozertech.azerva.devicelink.domain.repository.EventRepository
import javax.inject.Inject

/**
 * Reads a GATT characteristic and records successful read operations.
 *
 * Coordinates characteristic reading and event persistence
 * through domain repository contracts.
 *
 * @property ble Repository responsible for BLE operations.
 * @property history Repository responsible for event persistence.
 */
class ReadGattUseCase @Inject constructor(
    private val ble: BleRepository,
    private val history: EventRepository
) {

    /**
     * Reads the specified GATT characteristic.
     *
     * Records the characteristic UUID and returned value
     * when the operation succeeds.
     *
     * @param attribute GATT characteristic to read.
     * @return A Result containing the hexadecimal value or an error.
     */
    suspend operator fun invoke(
        attribute: GattAttribute
    ): Result<String> {

        val result = ble.read(attribute)

        result.getOrNull()?.let { value ->
            history.add(
                kind = EventKind.BLE,
                title = "Characteristic read",
                detail = "${attribute.characteristicUuid}: $value"
            )
        }

        return result
    }
}
