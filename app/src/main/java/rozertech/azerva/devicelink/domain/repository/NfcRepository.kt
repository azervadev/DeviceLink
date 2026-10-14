package rozertech.azerva.devicelink.domain.repository

import kotlinx.coroutines.flow.StateFlow
import rozertech.azerva.devicelink.domain.model.NfcRecord


/**
 * Defines the contract for managing Near Field Communication (NFC) data.
 *
 * Provides observable access to the latest detected NFC record
 * and supports recording newly discovered tags.
 */
interface NfcRepository {

    /**
     * Observes the most recently recorded NFC tag.
     *
     * Emits null when no NFC record is available.
     */
    val latest: StateFlow<NfcRecord?>

    /**
     * Records information from a detected NFC tag.
     *
     * @param uid Identifier reported by the NFC tag.
     * @param text Decoded text content, if available.
     */
    fun recordTag(uid: String, text: String?)
}
