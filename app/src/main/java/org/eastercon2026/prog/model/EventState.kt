package org.eastercon2026.prog.model

enum class EventState {
    DEFAULT,
    GOING,
    INTERESTED,
    HIDDEN,
    PASSED;

    companion object {
        fun fromString(value: String): EventState = try {
            valueOf(value)
        } catch (e: IllegalArgumentException) {
            DEFAULT
        }
    }
}
