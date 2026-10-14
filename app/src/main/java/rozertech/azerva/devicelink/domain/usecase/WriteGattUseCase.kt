package rozertech.azerva.devicelink.domain.usecase

import rozertech.azerva.devicelink.core.util.PayloadCodec
import rozertech.azerva.devicelink.domain.model.EventKind
import rozertech.azerva.devicelink.domain.model.GattAttribute
import rozertech.azerva.devicelink.domain.repository.BleRepository
import rozertech.azerva.devicelink.domain.repository.EventRepository
import javax.inject.Inject

/**
 * Validates and writes hexadecimal data to a GATT characteristic.
 *
 * Converts hexadecimal input into bytes, performs the BLE write
 * operation and records successful transmissions.
 *
 * @property ble Repository responsible for BLE operations.
 * @property history Repository responsible for event persistence.
 */
class WriteGattUseCase @Inject constructor(
    private val ble: BleRepository,
    private val history: EventRepository
) {

    /**
     * Writes hexadecimal data to the specified GATT characteristic.
     *
     * Validates and converts the input before executing the operation.
     * Records the transmitted payload only when writing succeeds.
     *
     * @param attribute GATT characteristic to write to.
     * @param text Hexadecimal payload containing 1–20 bytes.
     * @return A Result indicating success or failure.
     */
    suspend operator fun invoke(
        attribute: GattAttribute,
        text: String
    ): Result<Unit> {

        val bytes = try {
            PayloadCodec.hexToBytes(text)
        } catch (error: IllegalArgumentException) {
            return Result.failure(error)
        }

        val result = ble.write(attribute, bytes)

        if (result.isSuccess) {
            history.add(
                kind = EventKind.BLE,
                title = "Characteristic written",
                detail = "${attribute.characteristicUuid}: " +
                        PayloadCodec.bytesToHex(bytes)
            )
        }

        return result
    }
}
