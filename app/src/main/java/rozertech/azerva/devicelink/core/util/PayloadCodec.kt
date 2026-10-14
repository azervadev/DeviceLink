package rozertech.azerva.devicelink.core.util

/**
 * Provides stateless utilities for converting between hexadecimal strings
 * and byte arrays used in BLE communication.
 *
 * Validates hexadecimal input and restricts decoded payloads to 1–20 bytes.
 */
object PayloadCodec {
    fun hexToBytes(value: String): ByteArray {
        val normalized = value.filterNot(Char::isWhitespace)
        require(
            normalized.length in 2..40 &&
                    normalized.length % 2 == 0 &&
                    normalized.all { it in "0123456789abcdefABCDEF" }
        ) {
            "Enter 1–20 bytes of hexadecimal data (e.g. 01 FF)."
        }
        return normalized.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}