package org.eastercon2026.prog.db

import androidx.room.*

@Dao
interface EventDao {

    @Query("SELECT * FROM events ORDER BY startTime ASC")
    suspend fun getAllEvents(): List<EventEntity>

    @Query("SELECT * FROM events WHERE day = :day ORDER BY startTime ASC")
    suspend fun getEventsByDay(day: String): List<EventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<EventEntity>)

    @Query("DELETE FROM events")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM events")
    suspend fun count(): Int
}
