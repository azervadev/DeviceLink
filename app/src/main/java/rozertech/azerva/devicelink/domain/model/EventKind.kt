package rozertech.azerva.devicelink.domain.model

/**
 * Defines the categories of events recorded by the application.
 *
 * @property BLE Events related to Bluetooth Low Energy operations.
 * @property NFC Events related to Near Field Communication operations.
 * @property SYSTEM Events related to application or system operations.
 */
enum class EventKind {
    BLE,
    NFC,
    SYSTEM
}