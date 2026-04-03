package org.eastercon2026.prog.network

import android.util.Log
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class VersionInfo(
    val tagName: String,
    val name: String,
    val htmlUrl: String,
    val downloadUrl: String
)

class VersionChecker {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val releasesUrl =
        "https://api.github.com/repos/R00S/Ec2026prog/releases/latest"

    fun checkForUpdate(currentVersion: String): VersionInfo? {
        return try {
            val request = Request.Builder()
                .url(releasesUrl)
                .header("Accept", "application/vnd.github.v3+json")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "GitHub API returned ${response.code}")
                return null
            }
            val body = response.body?.string() ?: return null
            val json = JsonParser.parseString(body).asJsonObject

            val tagName = json.get("tag_name")?.asString ?: return null
            val name = json.get("name")?.asString ?: tagName
            val htmlUrl = json.get("html_url")?.asString ?: ""

            // Find APK asset download URL
            val downloadUrl = json.getAsJsonArray("assets")
                ?.firstOrNull { it.asJsonObject.get("name")?.asString?.endsWith(".apk") == true }
                ?.asJsonObject?.get("browser_download_url")?.asString
                ?: htmlUrl

            val latestVersion = tagName.trimStart('v')
            if (isNewer(latestVersion, currentVersion)) {
                VersionInfo(tagName, name, htmlUrl, downloadUrl)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Version check failed: ${e.message}")
            null
        }
    }

    private fun isNewer(latest: String, current: String): Boolean {
        return try {
            // Strip any suffix after a hyphen (e.g. "1.1.0-45d18e7" → "1.1.0")
            val latestClean = latest.substringBefore("-")
            val currentClean = current.substringBefore("-")
            val latestParts = latestClean.split(".").map { it.toIntOrNull() ?: 0 }
            val currentParts = currentClean.split(".").map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(latestParts.size, currentParts.size)) {
                val l = latestParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "VersionChecker"
    }
}
