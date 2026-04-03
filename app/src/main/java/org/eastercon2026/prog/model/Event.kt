package org.eastercon2026.prog.model

data class Event(
    val itemId: String,
    val title: String,
    val startTime: String,
    val endTime: String,
    val location: String,
    val description: String,
    val day: String
)
