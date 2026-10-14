package rozertech.azerva.devicelink.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import rozertech.azerva.devicelink.domain.model.EventEntity

@Database(entities = [EventEntity::class], version = 1, exportSchema = false)
abstract class EventsDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
}
