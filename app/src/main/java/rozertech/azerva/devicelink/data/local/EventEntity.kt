package rozertech.azerva.devicelink.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "events", indices = [Index("at")])
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id : Long = 0,
    val kind: String,
    val title: String,
    val detail: String,
    val at: Long,
)