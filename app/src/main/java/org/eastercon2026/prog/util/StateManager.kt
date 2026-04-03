package org.eastercon2026.prog.util

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.eastercon2026.prog.model.EventState

class StateManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("event_states", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val stateMap: MutableMap<String, String>

    init {
        val json = prefs.getString(KEY_STATES, null)
        stateMap = if (json != null) {
            val type = object : TypeToken<MutableMap<String, String>>() {}.type
            gson.fromJson(json, type) ?: mutableMapOf()
        } else {
            mutableMapOf()
        }
    }

    fun getState(itemId: String): EventState =
        EventState.fromString(stateMap[itemId] ?: EventState.DEFAULT.name)

    fun setState(itemId: String, state: EventState) {
        if (state == EventState.DEFAULT) {
            stateMap.remove(itemId)
        } else {
            stateMap[itemId] = state.name
        }
        persist()
    }

    fun toggleState(itemId: String, state: EventState) {
        val current = getState(itemId)
        setState(itemId, if (current == state) EventState.DEFAULT else state)
    }

    private fun persist() {
        prefs.edit().putString(KEY_STATES, gson.toJson(stateMap)).apply()
    }

    companion object {
        private const val KEY_STATES = "states_json"
    }
}
