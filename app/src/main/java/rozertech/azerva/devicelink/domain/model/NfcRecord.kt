package rozertech.azerva.devicelink.domain.model

/**
 * Represents an NFC tag record detected by the device.
 *
 * @property uid Unique identifier reported by the NFC tag.
 * @property text Decoded text content, if available.
 * @property seenAt Detection timestamp in milliseconds.
 */
data class NfcRecord(
    val uid: String,
    val text: String?,
    val seenAt: Long
)

