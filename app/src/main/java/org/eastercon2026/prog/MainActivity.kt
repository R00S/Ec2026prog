package org.eastercon2026.prog

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.tabs.TabLayout
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.eastercon2026.prog.adapter.EventAdapter
import org.eastercon2026.prog.db.AppDatabase
import org.eastercon2026.prog.db.EventEntity
import org.eastercon2026.prog.model.Event
import org.eastercon2026.prog.model.EventState
import org.eastercon2026.prog.network.ProgrammeFetcher
import org.eastercon2026.prog.network.VersionChecker
import org.eastercon2026.prog.util.StateManager
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: EventAdapter
    private lateinit var tabLayout: TabLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var emptyView: TextView
    private lateinit var stateManager: StateManager
    private lateinit var database: AppDatabase

    private val httpClient = OkHttpClient()

    /** APK downloaded but waiting for the user to allow app installations. */
    private var pendingInstallApk: File? = null

    private var allEvents: List<Event> = emptyList()
    private var selectedDay: String = DAY_ALL
    private val visibleStates: MutableSet<EventState> = mutableSetOf(
        EventState.GOING, EventState.INTERESTED, EventState.DEFAULT
    )

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
        setupFilterChips()
        setupSwipeToHide()
        loadFromDatabase()
        checkVersion()
    }

    override fun onResume() {
        super.onResume()
        // Reload states from SharedPreferences in case they changed in detail view
        stateManager.reload()
        if (allEvents.isNotEmpty()) {
            displayEvents(allEvents)
        }
        // Resume an install that was waiting for the "install unknown apps" permission.
        pendingInstallApk?.let { apk ->
            if (apk.exists() && packageManager.canRequestPackageInstalls()) {
                pendingInstallApk = null
                launchInstaller(apk)
            }
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
            R.id.action_check_updates -> {
                checkVersion(manual = true)
                true
            }
            R.id.action_about -> {
                showAboutDialog()
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
        val days = listOf(DAY_ALL, "Friday", "Saturday", "Sunday")
        days.forEach { day ->
            val tab = tabLayout.newTab().setText(
                when (day) {
                    DAY_ALL -> getString(R.string.tab_all)
                    "Friday" -> getString(R.string.tab_fri)
                    "Saturday" -> getString(R.string.tab_sat)
                    "Sunday" -> getString(R.string.tab_sun)
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

    private fun setupSwipeToHide() {
        val swipeBackground = ColorDrawable(getColor(R.color.accent_hidden))
        val swipeCallback = object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ) = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                if (position == RecyclerView.NO_POSITION) return
                val item = adapter.currentList[position]
                stateManager.toggleState(item.event.itemId, EventState.HIDDEN)
                displayEvents(allEvents)
            }

            override fun onChildDraw(
                c: Canvas, recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
            ) {
                val itemView = viewHolder.itemView
                swipeBackground.setBounds(itemView.left, itemView.top, itemView.right, itemView.bottom)
                swipeBackground.draw(c)
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        }
        ItemTouchHelper(swipeCallback).attachToRecyclerView(recyclerView)
    }

    private fun setupFilterChips() {
        val chipStateMap = mapOf(
            R.id.chipGoing to EventState.GOING,
            R.id.chipInterested to EventState.INTERESTED,
            R.id.chipDefault to EventState.DEFAULT,
            R.id.chipHidden to EventState.HIDDEN,
            R.id.chipPassed to EventState.PASSED
        )
        for ((chipId, state) in chipStateMap) {
            val chip = findViewById<Chip>(chipId)
            chip.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) visibleStates.add(state) else visibleStates.remove(state)
                displayEvents(allEvents)
            }
        }
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
                    Gson().fromJson<List<Event>>(json, type) ?: emptyList()
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
        val fetcher = ProgrammeFetcher()
        lifecycleScope.launch {
            val events = withContext(Dispatchers.IO) {
                fetcher.fetchProgramme { done, total ->
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
                saveDataMeta(fetcher.lastSource)
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
        }.filter { it.state in visibleStates }
            .sortedWith(compareBy { parseStartTime(it.event.startTime) })

        adapter.submitList(items)
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        if (items.isEmpty()) emptyView.text = getString(R.string.no_events_for_day)
    }

    private fun parseStartTime(startTime: String): LocalDateTime {
        if (startTime.isBlank()) return LocalDateTime.MAX
        val parsers = listOf(
            DateTimeFormatter.ISO_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        )
        for (fmt in parsers) {
            try { return LocalDateTime.parse(startTime, fmt) } catch (e: DateTimeParseException) { /* try next */ }
        }
        return LocalDateTime.MAX
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
            putStringArrayListExtra(
                DetailActivity.EXTRA_PARTICIPANTS,
                ArrayList(event.participants.orEmpty())
            )
        }
        startActivity(intent)
    }

    private fun checkVersion(manual: Boolean = false) {
        val prefs = getSharedPreferences(PREFS_UPDATE, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!manual && now - prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L) < UPDATE_CHECK_INTERVAL_MS) {
            return
        }
        prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, now).apply()

        val currentVersion = currentVersionName()
        lifecycleScope.launch {
            val update = withContext(Dispatchers.IO) {
                VersionChecker().checkForUpdate(currentVersion)
            }
            if (update == null) {
                if (manual) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.update_up_to_date, currentVersion),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return@launch
            }

            val latestVersion = update.tagName.trimStart('v')
            if (!manual) {
                // Respect "Later" and "Skip this version" for the same release.
                if (prefs.getString(KEY_SKIPPED_VERSION, null) == latestVersion) return@launch
                if (prefs.getString(KEY_SNOOZED_VERSION, null) == latestVersion &&
                    now < prefs.getLong(KEY_SNOOZE_UNTIL, 0L)
                ) {
                    return@launch
                }
            }
            showUpdateDialog(latestVersion, update.downloadUrl)
        }
    }

    @Suppress("DEPRECATION")
    private fun currentVersionName(): String {
        return try {
            val info = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                packageManager.getPackageInfo(packageName, 0)
            }
            info.versionName ?: "0"
        } catch (e: Exception) {
            "0"
        }
    }

    private fun showUpdateDialog(version: String, downloadUrl: String) {
        val prefs = getSharedPreferences(PREFS_UPDATE, Context.MODE_PRIVATE)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_available_title))
            .setMessage(getString(R.string.update_available_message, version))
            .setPositiveButton(getString(R.string.update_download)) { _, _ ->
                downloadAndInstallApk(downloadUrl)
            }
            .setNeutralButton(getString(R.string.update_skip)) { _, _ ->
                prefs.edit().putString(KEY_SKIPPED_VERSION, version).apply()
            }
            .setNegativeButton(getString(R.string.update_later)) { _, _ ->
                prefs.edit()
                    .putString(KEY_SNOOZED_VERSION, version)
                    .putLong(KEY_SNOOZE_UNTIL, System.currentTimeMillis() + UPDATE_SNOOZE_MS)
                    .apply()
            }
            .show()
    }

    private fun downloadAndInstallApk(downloadUrl: String) {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_downloading))
            .setMessage(getString(R.string.update_downloading_message))
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            val apkFile = withContext(Dispatchers.IO) {
                try {
                    val request = Request.Builder().url(downloadUrl).build()
                    val response = httpClient.newCall(request).execute()
                    if (!response.isSuccessful) return@withContext null
                    val file = File(cacheDir, UPDATE_APK_NAME)
                    response.body?.byteStream()?.use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (file.length() > 0L) file else null
                } catch (e: Exception) {
                    Log.e(TAG, "APK download failed: ${e.message}")
                    null
                }
            }
            progressDialog.dismiss()
            if (apkFile != null) {
                installApk(apkFile)
            } else {
                Toast.makeText(this@MainActivity, getString(R.string.update_download_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun installApk(apkFile: File) {
        if (!apkFile.exists()) {
            Toast.makeText(this, getString(R.string.update_download_failed), Toast.LENGTH_LONG).show()
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            pendingInstallApk = apkFile
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.update_install_permission_title))
                .setMessage(getString(R.string.update_install_permission_message))
                .setPositiveButton(getString(R.string.update_open_settings)) { _, _ ->
                    openInstallSettings()
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    pendingInstallApk = null
                }
                .show()
            return
        }
        launchInstaller(apkFile)
    }

    private fun openInstallSettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No unknown-sources settings screen: ${e.message}")
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e2: ActivityNotFoundException) {
                Log.e(TAG, "No app settings screen: ${e2.message}")
                Toast.makeText(this, getString(R.string.update_no_installer), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun launchInstaller(apkFile: File) {
        val uri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            apkFile
        )
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No installer for ACTION_VIEW: ${e.message}")
            try {
                startActivity(
                    Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                        data = uri
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                )
            } catch (e2: ActivityNotFoundException) {
                Log.e(TAG, "No package installer available: ${e2.message}")
                Toast.makeText(this, getString(R.string.update_no_installer), Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Data source / refresh metadata ─────────────────────────────────

    private fun saveDataMeta(source: String) {
        val ts = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.ENGLISH))
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_DATA_SOURCE, source)
            .putString(KEY_LAST_REFRESH, ts)
            .apply()
    }

    private fun getDataMeta(): Pair<String, String> {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val source = prefs.getString(KEY_DATA_SOURCE, getString(R.string.about_bundled))
            ?: getString(R.string.about_bundled)
        val refresh = prefs.getString(KEY_LAST_REFRESH, null)
            ?: getString(R.string.about_never_refreshed)
        return Pair(source, refresh)
    }

    // ── About dialog ────────────────────────────────────────────────────

    private fun showAboutDialog() {
        val currentVersion = currentVersionName()

        val (source, refresh) = getDataMeta()
        val message = buildString {
            appendLine(getString(R.string.about_version, currentVersion))
            appendLine(getString(R.string.about_latest, getString(R.string.about_checking)))
            appendLine()
            appendLine(getString(R.string.about_data_source, source))
            appendLine(getString(R.string.about_last_refresh, refresh))
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.about_title))
            .setMessage(message)
            .setPositiveButton(getString(R.string.about_close), null)
            .show()

        // Fetch latest release version in background and update the message
        lifecycleScope.launch {
            val latest = withContext(Dispatchers.IO) {
                VersionChecker().getLatestRelease()
            }
            val latestLabel = if (latest != null) latest.tagName else "–"
            val updatedMessage = buildString {
                appendLine(getString(R.string.about_version, currentVersion))
                val upToDate = latest == null ||
                    latest.tagName.trimStart('v') == currentVersion
                if (upToDate) {
                    appendLine(getString(R.string.about_up_to_date))
                } else {
                    appendLine(getString(R.string.about_latest, latestLabel))
                }
                appendLine()
                appendLine(getString(R.string.about_data_source, source))
                appendLine(getString(R.string.about_last_refresh, refresh))
            }
            if (dialog.isShowing) {
                dialog.setMessage(updatedMessage)
            }
        }
    }

    private fun EventEntity.toEvent() = Event(
        itemId = itemId,
        title = title,
        startTime = startTime,
        endTime = endTime,
        location = location,
        description = description,
        day = day,
        participants = participants.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    )

    private fun Event.toEntity() = EventEntity(
        itemId = itemId,
        title = title,
        startTime = startTime,
        endTime = endTime,
        location = location,
        description = description,
        day = day,
        participants = participants.orEmpty().joinToString("\n")
    )

    companion object {
        private const val TAG = "MainActivity"
        const val DAY_ALL = "All"
        private const val PREFS_NAME = "data_meta"
        private const val KEY_DATA_SOURCE = "data_source"
        private const val KEY_LAST_REFRESH = "last_refresh"

        private const val PREFS_UPDATE = "update_prefs"
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        private const val KEY_SNOOZED_VERSION = "snoozed_version"
        private const val KEY_SNOOZE_UNTIL = "snooze_until"
        private const val KEY_SKIPPED_VERSION = "skipped_version"
        private const val UPDATE_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private const val UPDATE_SNOOZE_MS = 24 * 60 * 60 * 1000L
        private const val UPDATE_APK_NAME = "update.apk"
    }
}
