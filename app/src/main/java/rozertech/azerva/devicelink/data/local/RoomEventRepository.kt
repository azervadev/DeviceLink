package rozertech.azerva.devicelink.data.local

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import rozertech.azerva.devicelink.domain.model.EventKind
import rozertech.azerva.devicelink.domain.model.EventLog
import rozertech.azerva.devicelink.domain.repository.EventRepository
import javax.inject.Inject

class RoomEventRepository @Inject constructor(
    private val dao: EventDao
) : EventRepository {

    override fun events(): Flow<List<EventLog>> = dao.observeRecent().map { rows ->
        rows.map { row ->
            EventLog(row.id, EventKind.entries.firstOrNull { it.name == row.kind } ?: EventKind.SYSTEM,
                row.title, row.detail, row.at)
        }
    }

    override suspend fun add(kind: EventKind, title: String, detail: String) {
        // Bound untrusted BLE/NFC strings so the log cannot grow uncontrollably per event.
        dao.insert(
            EventEntity(
                kind = kind.name,
                title = title.take(100),
                detail = detail.take(500),
                at = System.currentTimeMillis()
            )
        )
    }

    override suspend fun clear() = dao.clear()
}
