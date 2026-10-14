package rozertech.azerva.devicelink.data.repository_imp

import jakarta.inject.Inject
import jakarta.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rozertech.azerva.devicelink.domain.model.NfcRecord
import rozertech.azerva.devicelink.domain.repository.NfcRepository


/**
 * Android implementation of [NfcRepository].
 *
 * Maintains the most recently detected NFC tag information in memory
 * and exposes it through a read-only StateFlow.
 *
 * Limits stored tag identifiers and text payloads to prevent
 * excessively large records.
 */
@Singleton
class NfcRepositoryImp @Inject constructor() : NfcRepository {

    private val state = MutableStateFlow<NfcRecord?>(null)

    /**
     * Observes the most recently recorded NFC tag.
     *
     * Emits null until the first tag is recorded.
     */
    override val latest: StateFlow<NfcRecord?> = state.asStateFlow()

    /**
     * Records information from a detected NFC tag.
     *
     * Limits the tag identifier to 64 characters and the text
     * content to 200 characters before updating the current state.
     *
     * @param uid Identifier reported by the NFC tag.
     * @param text Decoded text content, if available.
     */
    override fun recordTag(uid: String, text: String?) {
        state.value = NfcRecord(
            uid.take(64),
            text?.take(200),
            System.currentTimeMillis()
        )
    }
}
