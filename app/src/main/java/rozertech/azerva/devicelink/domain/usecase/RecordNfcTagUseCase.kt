package rozertech.azerva.devicelink.domain.usecase

import rozertech.azerva.devicelink.domain.model.EventKind
import rozertech.azerva.devicelink.domain.repository.NfcRepository
import rozertech.azerva.devicelink.domain.repository.EventRepository
import javax.inject.Inject

/**
 * Records detected NFC tags and their associated events.
 *
 * Coordinates NFC state updates and local event persistence.
 *
 * @property nfc Repository responsible for NFC data.
 * @property history Repository responsible for event persistence.
 */
class RecordNfcTagUseCase @Inject constructor(
    private val nfc: NfcRepository,
    private val history: EventRepository,
) {

    /**
     * Processes a detected NFC tag.
     *
     * Updates the latest NFC record and stores an event
     * containing the tag identifier and optional text.
     *
     * @param uid Identifier reported by the NFC tag.
     * @param text Decoded NFC text, if available.
     */
    suspend operator fun invoke(
        uid: String,
        text: String?,
    ) {
        nfc.recordTag(uid, text)

        history.add(
            kind = EventKind.NFC,
            title = "NFC tag read",
            detail = "UID $uid; text ${text ?: "(none)"}"
        )
    }
}
