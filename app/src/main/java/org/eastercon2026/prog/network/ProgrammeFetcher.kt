package org.eastercon2026.prog.network

import android.util.Log
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
        Log.d(TAG, "Starting programme fetch from $baseUrl")
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
        // Eastercon 2026 is 2–5 April 2026 (Fri–Mon)
        val dayPatterns = mapOf(
            "friday" to "Friday", "fri" to "Friday",
            "saturday" to "Saturday", "sat" to "Saturday",
            "sunday" to "Sunday", "sun" to "Sunday",
            "monday" to "Monday", "mon" to "Monday"
        )

        // Check page text for day name
        val pageText = doc.text().lowercase()
        for ((pattern, day) in dayPatterns) {
            if (pageText.contains(pattern)) return day
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
    }
}
