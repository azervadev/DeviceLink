package rozertech.azerva.devicelink.domain.repository

import kotlinx.coroutines.flow.Flow
import rozertech.azerva.devicelink.domain.model.EventKind
import rozertech.azerva.devicelink.domain.model.EventLog

interface EventRepository {
    interface EventRepository {
        fun events(): Flow<List<EventLog>>
        suspend fun add(kind: EventKind, title: String, detail: String)
        suspend fun clear()
    }

    fun events(): Flow<List<EventLog>>
    suspend fun add(kind: EventKind, title: String, detail: String)
    suspend fun clear()
}