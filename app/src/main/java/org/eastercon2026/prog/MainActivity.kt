package org.eastercon2026.prog

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eastercon2026.prog.adapter.EventAdapter
import org.eastercon2026.prog.db.AppDatabase
import org.eastercon2026.prog.db.EventEntity
import org.eastercon2026.prog.model.Event
import org.eastercon2026.prog.model.EventState
import org.eastercon2026.prog.network.ProgrammeFetcher
import org.eastercon2026.prog.network.VersionChecker
import org.eastercon2026.prog.util.StateManager
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: EventAdapter
    private lateinit var tabLayout: TabLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var emptyView: TextView
    private lateinit var stateManager: StateManager
    private lateinit var database: AppDatabase

    private var allEvents: List<Event> = emptyList()
    private var selectedDay: String = DAY_ALL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        recyclerView = findViewById(R.id.recyclerView)
        tabLayout = findViewById(R.id.tabLayout)
        progressBar = findViewById(R.id.progressBar)
        progressText = findViewById(R.id.progressText)
        emptyView = findViewById(R.id.emptyView)

        stateManager = StateManager(this)
        database = AppDatabase.getInstance(this)

        adapter = EventAdapter { event -> openDetail(event) }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        recyclerView.addItemDecoration(
            DividerItemDecoration(this, DividerItemDecoration.VERTICAL)
        )

        setupTabs()
        loadFromDatabase()
        checkVersion()
    }

    override fun onResume() {
        super.onResume()
        // Refresh display in case states changed in detail view
        if (allEvents.isNotEmpty()) {
            displayEvents(allEvents)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> {
                refreshProgramme()
                true
            }
            R.id.action_buy_coffee -> {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://buymeacoffee.com/r00s")))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupTabs() {
        val days = listOf(DAY_ALL, "Friday", "Saturday", "Sunday", "Monday")
        days.forEach { day ->
            val tab = tabLayout.newTab().setText(
                when (day) {
                    DAY_ALL -> getString(R.string.tab_all)
                    "Friday" -> getString(R.string.tab_fri)
                    "Saturday" -> getString(R.string.tab_sat)
                    "Sunday" -> getString(R.string.tab_sun)
                    "Monday" -> getString(R.string.tab_mon)
                    else -> day
                }
            )
            tabLayout.addTab(tab)
        }
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                selectedDay = days[tab.position]
                displayEvents(allEvents)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun loadFromDatabase() {
        lifecycleScope.launch {
            val entities = withContext(Dispatchers.IO) {
                database.eventDao().getAllEvents()
            }
            if (entities.isNotEmpty()) {
                allEvents = entities.map { it.toEvent() }
                displayEvents(allEvents)
            } else {
                loadFromBundledAsset()
            }
        }
    }

    private fun loadFromBundledAsset() {
        lifecycleScope.launch {
            val events = withContext(Dispatchers.IO) {
                try {
                    val json = assets.open("programme.json").bufferedReader().use { it.readText() }
                    val type = object : TypeToken<List<Event>>() {}.type
                    Gson().fromJson<List<Event>>(json, type)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load bundled programme: ${e.message}")
                    emptyList()
                }
            }
            if (events.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    database.eventDao().insertAll(events.map { it.toEntity() })
                }
                allEvents = events
                displayEvents(allEvents)
            } else {
                refreshProgramme()
            }
        }
    }

    private fun refreshProgramme() {
        setLoading(true, getString(R.string.loading_fetching))
        lifecycleScope.launch {
            val events = withContext(Dispatchers.IO) {
                ProgrammeFetcher().fetchProgramme { done, total ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        if (progressBar.isIndeterminate) {
                            progressBar.isIndeterminate = false
                        }
                        progressBar.max = total
                        progressBar.progress = done
                        progressText.text = getString(R.string.loading_progress, done, total)
                    }
                }
            }
            if (events.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    database.eventDao().deleteAll()
                    database.eventDao().insertAll(events.map { it.toEntity() })
                }
                allEvents = events
                displayEvents(allEvents)
            } else {
                if (allEvents.isEmpty()) {
                    emptyView.visibility = View.VISIBLE
                    emptyView.text = getString(R.string.error_no_events)
                }
            }
            setLoading(false)
        }
    }

    private fun displayEvents(events: List<Event>) {
        val now = LocalDateTime.now()
        val filtered = if (selectedDay == DAY_ALL) events
        else events.filter { it.day == selectedDay }

        val items = filtered.map { event ->
            val userState = stateManager.getState(event.itemId)
            val effectiveState = if (userState == EventState.DEFAULT && isPassed(event.endTime, now)) {
                EventState.PASSED
            } else {
                userState
            }
            EventAdapter.EventItem(event, effectiveState)
        }

        adapter.submitList(items)
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        if (items.isEmpty()) emptyView.text = getString(R.string.no_events_for_day)
    }

    private fun isPassed(endTime: String, now: LocalDateTime): Boolean {
        if (endTime.isBlank()) return false
        val parsers = listOf(
            DateTimeFormatter.ISO_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("EEE dd MMM yyyy HH:mm"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
        )
        for (fmt in parsers) {
            try {
                val dt = LocalDateTime.parse(endTime, fmt)
                return dt.isBefore(now)
            } catch (e: DateTimeParseException) {
                continue
            }
        }
        return false
    }

    private fun setLoading(loading: Boolean, message: String = "") {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        progressText.visibility = if (loading) View.VISIBLE else View.GONE
        if (loading) {
            progressText.text = message
            progressBar.isIndeterminate = true
            emptyView.visibility = View.GONE
        }
    }

    private fun openDetail(event: Event) {
        val intent = Intent(this, DetailActivity::class.java).apply {
            putExtra(DetailActivity.EXTRA_ITEM_ID, event.itemId)
            putExtra(DetailActivity.EXTRA_TITLE, event.title)
            putExtra(DetailActivity.EXTRA_START_TIME, event.startTime)
            putExtra(DetailActivity.EXTRA_END_TIME, event.endTime)
            putExtra(DetailActivity.EXTRA_LOCATION, event.location)
            putExtra(DetailActivity.EXTRA_DESCRIPTION, event.description)
            putExtra(DetailActivity.EXTRA_DAY, event.day)
        }
        startActivity(intent)
    }

    @Suppress("DEPRECATION")
    private fun checkVersion() {
        lifecycleScope.launch {
            val info = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                packageManager.getPackageInfo(packageName, 0)
            }
            val currentVersion = info.versionName ?: "1.0.0"
            val update = withContext(Dispatchers.IO) {
                VersionChecker().checkForUpdate(currentVersion)
            }
            if (update != null) {
                showUpdateDialog(update.name, update.downloadUrl)
            }
        }
    }

    private fun showUpdateDialog(versionName: String, downloadUrl: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_available_title))
            .setMessage(getString(R.string.update_available_message, versionName))
            .setPositiveButton(getString(R.string.update_download)) { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse(downloadUrl))
                startActivity(intent)
            }
            .setNegativeButton(getString(R.string.update_later), null)
            .show()
    }

    private fun EventEntity.toEvent() = Event(
        itemId = itemId,
        title = title,
        startTime = startTime,
        endTime = endTime,
        location = location,
        description = description,
        day = day
    )

    private fun Event.toEntity() = EventEntity(
        itemId = itemId,
        title = title,
        startTime = startTime,
        endTime = endTime,
        location = location,
        description = description,
        day = day
    )

    companion object {
        private const val TAG = "MainActivity"
        const val DAY_ALL = "All"
    }
}
