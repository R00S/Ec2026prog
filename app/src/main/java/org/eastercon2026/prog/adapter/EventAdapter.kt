package org.eastercon2026.prog.adapter

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.eastercon2026.prog.R
import org.eastercon2026.prog.model.Event
import org.eastercon2026.prog.model.EventState

class EventAdapter(
    private val onItemClick: (Event) -> Unit
) : ListAdapter<EventAdapter.EventItem, EventAdapter.ViewHolder>(DIFF_CALLBACK) {

    data class EventItem(
        val event: Event,
        val state: EventState
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_event, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), onItemClick)
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleView: TextView = itemView.findViewById(R.id.eventTitle)
        private val timeView: TextView = itemView.findViewById(R.id.eventTime)
        private val locationView: TextView = itemView.findViewById(R.id.eventLocation)
        private val stateIndicator: View = itemView.findViewById(R.id.stateIndicator)
        private val cardContainer: View = itemView.findViewById(R.id.cardContainer)

        fun bind(item: EventItem, onItemClick: (Event) -> Unit) {
            val ctx = itemView.context
            titleView.text = item.event.title
            locationView.text = item.event.location.ifEmpty { ctx.getString(R.string.unknown_location) }

            val timeText = buildTimeString(item.event.startTime, item.event.endTime)
            timeView.text = timeText

            val alpha: Float
            val strikethrough: Boolean

            when (item.state) {
                EventState.GOING -> {
                    cardContainer.setBackgroundColor(ctx.getColor(R.color.bg_going))
                    stateIndicator.setBackgroundColor(ctx.getColor(R.color.accent_going))
                    alpha = 1.0f
                    strikethrough = false
                }
                EventState.INTERESTED -> {
                    cardContainer.setBackgroundColor(ctx.getColor(R.color.bg_interested))
                    stateIndicator.setBackgroundColor(ctx.getColor(R.color.accent_interested))
                    alpha = 1.0f
                    strikethrough = false
                }
                EventState.HIDDEN -> {
                    cardContainer.setBackgroundColor(ctx.getColor(R.color.bg_hidden))
                    stateIndicator.setBackgroundColor(ctx.getColor(R.color.bg_hidden))
                    alpha = 0.5f
                    strikethrough = false
                }
                EventState.PASSED -> {
                    cardContainer.setBackgroundColor(ctx.getColor(R.color.bg_passed))
                    stateIndicator.setBackgroundColor(ctx.getColor(R.color.bg_passed))
                    alpha = 0.6f
                    strikethrough = true
                }
                EventState.DEFAULT -> {
                    cardContainer.setBackgroundColor(ctx.getColor(R.color.bg_default))
                    stateIndicator.setBackgroundColor(ctx.getColor(R.color.bg_default))
                    alpha = 1.0f
                    strikethrough = false
                }
            }

            titleView.alpha = alpha
            timeView.alpha = alpha
            locationView.alpha = alpha

            if (strikethrough) {
                titleView.paintFlags = titleView.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                titleView.paintFlags = titleView.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }

            itemView.setOnClickListener { onItemClick(item.event) }
        }

        private fun buildTimeString(startTime: String, endTime: String): String {
            if (startTime.isEmpty()) return ""
            val start = formatDisplayTime(startTime)
            val end = if (endTime.isNotEmpty()) formatDisplayTime(endTime) else ""
            return if (end.isNotEmpty()) "$start – $end" else start
        }

        private fun formatDisplayTime(time: String): String {
            // If time is already short (e.g., "10:00"), return as-is
            if (time.matches(Regex("\\d{1,2}:\\d{2}(:\\d{2})?( ?[AaPp][Mm])?"))) return time
            // Try to extract just time portion from ISO datetime
            return if (time.contains("T")) {
                time.substringAfter("T").take(5)
            } else if (time.contains(" ") && time.length > 10) {
                time.substringAfterLast(" ").take(5).ifEmpty { time.take(16) }
            } else {
                time.take(16)
            }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<EventItem>() {
            override fun areItemsTheSame(old: EventItem, new: EventItem) =
                old.event.itemId == new.event.itemId

            override fun areContentsTheSame(old: EventItem, new: EventItem) =
                old == new
        }
    }
}
