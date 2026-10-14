package rozertech.azerva.devicelink.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {
    @Query("SELECT * FROM events ORDER BY at DESC LIMIT 250")
    fun observeRecent(): Flow<List<EventEntity>>

    @Insert suspend fun insert(event: EventEntity)
    @Query("DELETE FROM events") suspend fun clear()
    @Query("DELETE FROM events WHERE at < :cutoff") suspend fun deleteBefore(cutoff: Long)
}