package rozertech.azerva.devicelink.domain.model

/**
 * Represents an event recorded during application execution.
 *
 * @property id Unique identifier of the event.
 * @property kind Category of the recorded event.
 * @property title Short descriptive title of the event.
 * @property detail Additional information about the event.
 * @property at Timestamp when the event occurred, in milliseconds.
 */
data class EventLog(
    val id: Long = 0,
    val kind: EventKind,
    val title: String,
    val detail: String,
    val at: Long
)

