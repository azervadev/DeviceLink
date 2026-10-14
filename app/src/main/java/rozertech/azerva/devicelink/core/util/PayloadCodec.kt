package rozertech.azerva.devicelink.core.util


/**
 * Provides stateless utilities for converting between hexadecimal strings
 * and byte arrays used in BLE communication.
 *
 * Validates hexadecimal input and restricts decoded payloads to 1–20 bytes.
 */
object PayloadCodec {

    /**
     * Converts a hexadecimal string into a byte array.
     *
     * Ignores whitespace and validates that the input contains
     * between 1 and 20 bytes of valid hexadecimal data.
     *
     * @param value Hexadecimal string to convert (e.g. "01 FF").
     * @return Byte array containing the decoded hexadecimal values.
     * @throws IllegalArgumentException If the input is invalid or exceeds the allowed size.
     */
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

    /**
     * Converts a byte array into an uppercase hexadecimal string.
     *
     * Each byte is represented by two hexadecimal characters,
     * separated by a single space.
     *
     * @param bytes Byte array to convert.
     * @return Formatted hexadecimal string (e.g. "01 FF").
     */
    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
