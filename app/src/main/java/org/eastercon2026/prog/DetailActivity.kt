package org.eastercon2026.prog

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.MenuItem
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import org.eastercon2026.prog.model.EventState
import org.eastercon2026.prog.util.StateManager
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

class DetailActivity : AppCompatActivity() {

    private lateinit var stateManager: StateManager
    private lateinit var itemId: String

    private lateinit var btnGoing: MaterialButton
    private lateinit var btnInterested: MaterialButton
    private lateinit var btnHidden: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        stateManager = StateManager(this)

        itemId = intent.getStringExtra(EXTRA_ITEM_ID) ?: ""
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val startTime = intent.getStringExtra(EXTRA_START_TIME) ?: ""
        val endTime = intent.getStringExtra(EXTRA_END_TIME) ?: ""
        val location = intent.getStringExtra(EXTRA_LOCATION) ?: ""
        val description = intent.getStringExtra(EXTRA_DESCRIPTION) ?: ""
        val day = intent.getStringExtra(EXTRA_DAY) ?: ""

        supportActionBar?.title = day
        findViewById<TextView>(R.id.detailTitle).text = title
        val timeText = buildTimeString(startTime, endTime)
        findViewById<TextView>(R.id.detailTime).text = timeText
        val locationView = findViewById<TextView>(R.id.detailLocation)
        if (location.isNotEmpty()) {
            locationView.text = location
        } else {
            locationView.text = getString(R.string.unknown_location)
        }
        findViewById<TextView>(R.id.detailDescription).text =
            description.ifEmpty { getString(R.string.no_description) }

        btnGoing = findViewById(R.id.btnGoing)
        btnInterested = findViewById(R.id.btnInterested)
        btnHidden = findViewById(R.id.btnHidden)

        updateButtonStates()

        btnGoing.setOnClickListener {
            stateManager.toggleState(itemId, EventState.GOING)
            updateButtonStates()
        }
        btnInterested.setOnClickListener {
            stateManager.toggleState(itemId, EventState.INTERESTED)
            updateButtonStates()
        }
        btnHidden.setOnClickListener {
            stateManager.toggleState(itemId, EventState.HIDDEN)
            updateButtonStates()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun updateButtonStates() {
        val state = stateManager.getState(itemId)

        val activeGoing = state == EventState.GOING
        val activeInterested = state == EventState.INTERESTED
        val activeHidden = state == EventState.HIDDEN

        setButtonActive(btnGoing, activeGoing, R.color.accent_going)
        setButtonActive(btnInterested, activeInterested, R.color.accent_interested)
        setButtonActive(btnHidden, activeHidden, R.color.accent_hidden)
    }

    private fun setButtonActive(button: MaterialButton, active: Boolean, colorRes: Int) {
        val color = ContextCompat.getColor(this, colorRes)
        if (active) {
            button.backgroundTintList = ColorStateList.valueOf(color)
            button.setTextColor(ContextCompat.getColor(this, android.R.color.white))
            button.strokeColor = ColorStateList.valueOf(color)
        } else {
            button.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, android.R.color.transparent)
            )
            button.setTextColor(color)
            button.strokeColor = ColorStateList.valueOf(color)
            button.strokeWidth = resources.getDimensionPixelSize(R.dimen.button_stroke_width)
        }
    }

    private fun buildTimeString(startTime: String, endTime: String): String {
        if (startTime.isEmpty()) return ""
        val startDt = parseDateTime(startTime)
        return if (startDt != null) {
            val datePart = startDt.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))
            val startHhmm = startDt.format(DateTimeFormatter.ofPattern("HH:mm"))
            if (endTime.isNotEmpty()) {
                val endDt = parseDateTime(endTime)
                val endHhmm = endDt?.format(DateTimeFormatter.ofPattern("HH:mm"))
                    ?: endTime.take(5)
                "$datePart · $startHhmm – $endHhmm"
            } else {
                "$datePart · $startHhmm"
            }
        } else {
            // Fallback: just strip the T separator for readability
            val end = if (endTime.isNotEmpty()) " – ${endTime.replace('T', ' ').take(16)}" else ""
            "${startTime.replace('T', ' ').take(16)}$end"
        }
    }

    private fun parseDateTime(value: String): LocalDateTime? {
        val parsers = listOf(
            DateTimeFormatter.ISO_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        )
        for (fmt in parsers) {
            try { return LocalDateTime.parse(value, fmt) } catch (e: DateTimeParseException) { /* try next */ }
        }
        return null
    }

    companion object {
        const val EXTRA_ITEM_ID = "extra_item_id"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_START_TIME = "extra_start_time"
        const val EXTRA_END_TIME = "extra_end_time"
        const val EXTRA_LOCATION = "extra_location"
        const val EXTRA_DESCRIPTION = "extra_description"
        const val EXTRA_DAY = "extra_day"
    }
}
