package org.eastercon2026.prog.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import okhttp3.OkHttpClient
import okhttp3.Request
import org.eastercon2026.prog.model.Event
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit

class ProgrammeFetcher {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val baseUrl = "https://guide.eastercon2026.org"

    fun fetchProgramme(progressCallback: ((Int, Int) -> Unit)? = null): List<Event> {
        Log.d(TAG, "Starting programme fetch")

        // Try fetching the latest programme.json from the GitHub repository first.
        // This is the most reliable source because the scraper CI action keeps it up to date.
        val githubEvents = tryGitHubRaw()
        if (githubEvents.isNotEmpty()) {
            Log.d(TAG, "Got ${githubEvents.size} events from GitHub raw")
            progressCallback?.invoke(githubEvents.size, githubEvents.size)
            return githubEvents
        }

        Log.d(TAG, "GitHub raw fetch failed, trying live site $baseUrl")

        // Try Grenadine program.json API (used by convention guide sites)
        val grenadineEvents = tryGrenadineApi()
        if (grenadineEvents.isNotEmpty()) {
            Log.d(TAG, "Got ${grenadineEvents.size} events from Grenadine API")
            progressCallback?.invoke(grenadineEvents.size, grenadineEvents.size)
            return grenadineEvents
        }

        // Try other common JSON API endpoints
        val jsonEvents = tryJsonApis()
        if (jsonEvents.isNotEmpty()) {
            Log.d(TAG, "Got ${jsonEvents.size} events from JSON API")
            progressCallback?.invoke(jsonEvents.size, jsonEvents.size)
            return jsonEvents
        }

        // Fall back to HTML scraping
        return scrapeHtml(progressCallback)
    }

    // ── GitHub raw programme.json ────────────────────────────────────────

    private fun tryGitHubRaw(): List<Event> {
        Log.d(TAG, "Trying GitHub raw: $GITHUB_RAW_URL")
        return try {
            val request = Request.Builder().url(GITHUB_RAW_URL)
                .header("Accept", "application/json")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "GitHub raw returned HTTP ${response.code}")
                return emptyList()
            }
            val body = response.body?.string() ?: return emptyList()
            val type = object : TypeToken<List<Event>>() {}.type
            val events: List<Event> = Gson().fromJson(body, type) ?: emptyList()
            events
        } catch (e: Exception) {
            Log.w(TAG, "GitHub raw fetch failed: ${e.message}")
            emptyList()
        }
    }

    // ── Grenadine program.json API ───────────────────────────────────────

    private fun tryGrenadineApi(): List<Event> {
        val urls = listOf(
            "$baseUrl/program.json",
            "$baseUrl/program.json?scale=2.0"
        )
        for (url in urls) {
            Log.d(TAG, "Trying Grenadine API: $url")
            try {
                val json = fetchJson(url)
                if (json != null && json.isJsonArray && json.asJsonArray.size() > 0) {
                    return parseGrenadineItems(json.asJsonArray)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Grenadine API failed for $url: ${e.message}")
            }
        }
        return emptyList()
    }

    private fun parseGrenadineItems(array: JsonArray): List<Event> {
        return array.mapNotNull { element ->
            try {
                val obj = element.asJsonObject
                val itemId = obj.get("id")?.asString ?: return@mapNotNull null
                val title = obj.get("title")?.asString?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null

                val startTime = obj.get("datetime")?.asString
                    ?: obj.get("startTime")?.asString ?: ""
                val endTime = obj.get("endtime")?.asString
                    ?: obj.get("endTime")?.asString ?: ""

                // Location is in loc array: ["Room Name", ""]
                val location = try {
                    val loc = obj.getAsJsonArray("loc")
                    loc?.firstOrNull()?.asString ?: ""
                } catch (e: Exception) {
                    obj.get("location")?.asString ?: ""
                }

                // Description may contain HTML
                val descRaw = obj.get("desc")?.asString
                    ?: obj.get("description")?.asString ?: ""
                val description = if (descRaw.contains("<")) {
                    Jsoup.parse(descRaw).text()
                } else {
                    descRaw
                }

                val day = inferDay(startTime, "")

                Event(itemId, title, startTime, endTime, location, description, day)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse Grenadine item: ${e.message}")
                null
            }
        }
    }

    // ── Generic JSON API endpoints ───────────────────────────────────────

    private fun tryJsonApis(): List<Event> {
        val urls = listOf(
            "$baseUrl/api/events",
            "$baseUrl/api/programme",
            "$baseUrl/programme.json",
            "$baseUrl/events.json",
            "$baseUrl/data/events.json",
            "$baseUrl/api/items"
        )
        for (url in urls) {
            Log.d(TAG, "Trying JSON endpoint: $url")
            try {
                val json = fetchJson(url) ?: continue
                val array = when {
                    json.isJsonArray -> json.asJsonArray
                    json.isJsonObject -> {
                        val obj = json.asJsonObject
                        listOf("events", "programme", "items", "data")
                            .firstNotNullOfOrNull { key ->
                                obj.getAsJsonArray(key)?.takeIf { it.size() > 0 }
                            } ?: continue
                    }
                    else -> continue
                }
                if (array.size() > 0) {
                    val events = parseJsonItems(array)
                    if (events.isNotEmpty()) return events
                }
            } catch (e: Exception) {
                Log.w(TAG, "JSON API failed for $url: ${e.message}")
            }
        }
        return emptyList()
    }

    private fun parseJsonItems(array: JsonArray): List<Event> {
        return array.mapNotNull { element ->
            try {
                val obj = element.asJsonObject
                fun get(vararg keys: String): String {
                    for (k in keys) {
                        val v = obj.get(k)?.asString?.trim()
                        if (!v.isNullOrEmpty()) return v
                    }
                    return ""
                }

                val itemId = get("id", "itemId", "item_id")
                val title = get("title", "name", "summary")
                    .takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val startTime = get("startTime", "start_time", "start", "begins_at", "datetime_start")
                val endTime = get("endTime", "end_time", "end", "ends_at", "datetime_end")
                val location = get("location", "room", "venue", "place")
                val description = get("description", "body", "abstract", "content", "detail")
                val dayRaw = get("day", "weekday", "date")
                val day = DAY_NAMES[dayRaw.lowercase().take(3)] ?: inferDay(startTime, dayRaw)

                Event(itemId, title, startTime, endTime, location, description, day)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse JSON item: ${e.message}")
                null
            }
        }
    }

    private fun fetchJson(url: String): com.google.gson.JsonElement? {
        return try {
            val request = Request.Builder().url(url)
                .header("Accept", "application/json")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val trimmed = body.trim()
            if (!trimmed.startsWith("[") && !trimmed.startsWith("{")) return null
            JsonParser.parseString(body)
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching JSON $url: ${e.message}")
            null
        }
    }

    // ── HTML scraping fallback ───────────────────────────────────────────

    private fun scrapeHtml(progressCallback: ((Int, Int) -> Unit)?): List<Event> {
        val mainDoc = fetchDocument(baseUrl) ?: run {
            Log.e(TAG, "Failed to load main page")
            return emptyList()
        }
        val links = extractItemLinks(mainDoc)
        Log.d(TAG, "Found ${links.size} item links")

        if (links.isEmpty()) {
            Log.e(TAG, "No item links found")
            return emptyList()
        }

        val events = mutableListOf<Event>()
        links.forEachIndexed { index, link ->
            progressCallback?.invoke(index + 1, links.size)
            try {
                val event = fetchEventDetail(link)
                if (event != null) events.add(event)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch $link: ${e.message}")
            }
        }
        Log.d(TAG, "Fetched ${events.size} events total")
        return events
    }

    private fun fetchDocument(url: String): Document? {
        return try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "HTTP ${response.code} for $url")
                return null
            }
            val body = response.body?.string() ?: return null
            Jsoup.parse(body, url)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching $url: ${e.message}")
            null
        }
    }

    private fun extractItemLinks(doc: Document): List<String> {
        val links = mutableSetOf<String>()

        // Look for /id/ links that Creative Cat Apps guide uses
        doc.select("a[href]").forEach { a ->
            val href = a.attr("abs:href")
            if (href.contains("/id/") || href.matches(Regex(".*$baseUrl/\\d+$"))) {
                links.add(href)
            }
        }

        // If no /id/ links, look for any internal links to numbered pages
        if (links.isEmpty()) {
            doc.select("a[href]").forEach { a ->
                val href = a.attr("abs:href")
                if (href.startsWith(baseUrl) && href != baseUrl && href != "$baseUrl/") {
                    val path = href.removePrefix(baseUrl).trimStart('/')
                    if (path.matches(Regex("(id/)?\\d+.*"))) {
                        links.add(href)
                    }
                }
            }
        }

        // Broader fallback: any link containing a programme entry pattern
        if (links.isEmpty()) {
            doc.select("a[href]").forEach { a ->
                val href = a.attr("abs:href")
                if (href.startsWith(baseUrl) && href != baseUrl && href != "$baseUrl/") {
                    val path = href.removePrefix(baseUrl)
                    if (path.length > 1 && !path.contains("#") &&
                        !path.contains("?") && !path.endsWith(".css") && !path.endsWith(".js")
                    ) {
                        links.add(href)
                    }
                }
            }
        }

        return links.toList().sorted()
    }

    private fun fetchEventDetail(url: String): Event? {
        val doc = fetchDocument(url) ?: return null
        return parseEventFromDetailPage(doc, url)
    }

    private fun parseEventFromDetailPage(doc: Document, url: String): Event? {
        val itemId = extractItemId(url)

        // Try various selectors used by Creative Cat Apps guide sites
        val title = doc.select("h1, .event-title, .item-title, [class*='title']").firstOrNull()
            ?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: doc.title().trim().takeIf { it.isNotEmpty() }
            ?: return null

        val startTime = extractDateTime(doc, listOf(
            ".start-time", ".event-start", "[class*='start']", "time[datetime]",
            ".time", "[class*='time']", ".when", ".schedule-time"
        ))

        val endTime = extractDateTime(doc, listOf(
            ".end-time", ".event-end", "[class*='end']"
        ))

        val location = doc.select(
            ".location, .room, .venue, [class*='location'], [class*='room'], [class*='venue'], " +
            ".where, [class*='where']"
        ).firstOrNull()?.text()?.trim() ?: ""

        val description = extractDescription(doc)

        val day = inferDay(startTime, doc)

        return Event(
            itemId = itemId,
            title = title,
            startTime = startTime,
            endTime = endTime,
            location = location,
            description = description,
            day = day
        )
    }

    private fun extractItemId(url: String): String {
        // Extract numeric ID or last path segment
        val numMatch = Regex("/(\\d+)(?:[/?#]|$)").findAll(url).lastOrNull()
        if (numMatch != null) return numMatch.groupValues[1]
        return url.trimEnd('/').substringAfterLast('/')
    }

    private fun extractDateTime(doc: Document, selectors: List<String>): String {
        for (sel in selectors) {
            val el = doc.select(sel).firstOrNull() ?: continue
            // Prefer datetime attribute
            val dt = el.attr("datetime").trim().takeIf { it.isNotEmpty() }
                ?: el.text().trim().takeIf { it.isNotEmpty() }
                ?: continue
            return dt
        }
        // Try meta tags
        doc.select("meta[property='event:start_time'], meta[name='start_time']")
            .firstOrNull()?.let { return it.attr("content") }
        return ""
    }

    private fun extractDescription(doc: Document): String {
        val selectors = listOf(
            ".description", ".event-description", ".item-description",
            "[class*='description']", ".content", ".body", "article",
            ".event-body", ".programme-item-description", ".detail"
        )
        for (sel in selectors) {
            val el = doc.select(sel).firstOrNull() ?: continue
            val text = el.text().trim()
            if (text.isNotEmpty()) return text
        }
        // Fallback: get main content area text excluding nav/header/footer
        doc.select("nav, header, footer, script, style").remove()
        return doc.select("main, #main, #content, .main, body").firstOrNull()
            ?.text()?.trim() ?: ""
    }

    private fun inferDay(startTime: String, doc: Document): String {
        return inferDay(startTime, doc.text())
    }

    private fun inferDay(startTime: String, pageText: String): String {
        // Eastercon 2026 is 3–6 April 2026 (Fri–Mon)
        val dayPatterns = mapOf(
            "friday" to "Friday", "fri" to "Friday",
            "saturday" to "Saturday", "sat" to "Saturday",
            "sunday" to "Sunday", "sun" to "Sunday",
            "monday" to "Monday", "mon" to "Monday"
        )

        // Check page text for day name
        val lowerPageText = pageText.lowercase()
        for ((pattern, day) in dayPatterns) {
            if (lowerPageText.contains(pattern)) return day
        }

        // Check start time string
        val lowerStart = startTime.lowercase()
        for ((pattern, day) in dayPatterns) {
            if (lowerStart.contains(pattern)) return day
        }

        // Try parsing the date
        if (startTime.isNotEmpty()) {
            try {
                val parsers = listOf(
                    DateTimeFormatter.ISO_DATE_TIME,
                    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"),
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
                    DateTimeFormatter.ofPattern("EEE dd MMM yyyy HH:mm"),
                    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                )
                for (parser in parsers) {
                    try {
                        val dt = LocalDateTime.parse(startTime, parser)
                        return when (dt.dayOfWeek.value) {
                            5 -> "Friday"
                            6 -> "Saturday"
                            7 -> "Sunday"
                            1 -> "Monday"
                            else -> "Friday"
                        }
                    } catch (e: DateTimeParseException) {
                        continue
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not parse date: $startTime")
            }
        }

        return "Friday" // default
    }

    companion object {
        private const val TAG = "ProgrammeFetcher"
        private const val GITHUB_RAW_URL =
            "https://raw.githubusercontent.com/R00S/Ec2026prog/main/app/src/main/assets/programme.json"
        private val DAY_NAMES = mapOf(
            "fri" to "Friday", "friday" to "Friday",
            "sat" to "Saturday", "saturday" to "Saturday",
            "sun" to "Sunday", "sunday" to "Sunday",
            "mon" to "Monday", "monday" to "Monday"
        )
    }
}
