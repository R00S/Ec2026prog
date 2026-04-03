package org.eastercon2026.prog.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "events",
    indices = [Index(value = ["itemId"], unique = true)]
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val itemId: String,
    val title: String,
    val startTime: String,
    val endTime: String,
    val location: String,
    val description: String,
    val day: String
)
