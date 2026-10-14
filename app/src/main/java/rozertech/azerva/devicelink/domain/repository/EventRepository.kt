package rozertech.azerva.devicelink.domain.repository

import kotlinx.coroutines.flow.Flow
import rozertech.azerva.devicelink.domain.model.EventKind
import rozertech.azerva.devicelink.domain.model.EventLog


/**
 * Defines the contract for managing application event logs.
 *
 * Abstracts event persistence operations from the underlying
 * storage implementation.
 */
interface EventRepository {

    /**
     * Observes stored application events.
     *
     * @return A Flow emitting lists of event logs.
     */
    fun events(): Flow<List<EventLog>>

    /**
     * Records a new application event.
     *
     * @param kind Category of the event.
     * @param title Short descriptive title of the event.
     * @param detail Additional information about the event.
     */
    suspend fun add(
        kind: EventKind,
        title: String,
        detail: String
    )

    /**
     * Removes all stored application events.
     */
    suspend fun clear()
}
