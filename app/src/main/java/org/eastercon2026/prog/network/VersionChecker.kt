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

    /** Does not follow redirects so the latest tag can be read from the Location header. */
    private val noRedirectClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val repoUrl = "https://github.com/R00S/Ec2026prog"
    private val latestReleaseUrl = "$repoUrl/releases/latest"
    private val releasesApiUrl = "https://api.github.com/repos/R00S/Ec2026prog/releases/latest"

    fun checkForUpdate(currentVersion: String): VersionInfo? {
        val info = getLatestRelease() ?: return null
        val latestVersion = info.tagName.trimStart('v')
        return if (isNewer(latestVersion, currentVersion)) info else null
    }

    /**
     * Resolves the newest release without the GitHub REST API.
     *
     * https://github.com/<owner>/<repo>/releases/latest redirects to
     * /releases/tag/<tag>; reading that redirect is not subject to the
     * unauthenticated API limit of 60 requests per hour per IP. The API is
     * kept only as a fallback.
     */
    fun getLatestRelease(): VersionInfo? =
        getLatestReleaseFromRedirect() ?: getLatestReleaseFromApi()

    private fun getLatestReleaseFromRedirect(): VersionInfo? {
        return try {
            val request = Request.Builder()
                .url(latestReleaseUrl)
                .header("Accept", "text/html")
                .build()
            noRedirectClient.newCall(request).execute().use { response ->
                val location = response.header("Location") ?: return null
                val tagName = location.substringAfter("/releases/tag/", "")
                    .substringBefore("?")
                    .trim('/')
                if (tagName.isEmpty()) return null
                val version = tagName.trimStart('v')
                VersionInfo(
                    tagName = tagName,
                    name = "Fantastika 2026 Programme Guide $version",
                    htmlUrl = "$repoUrl/releases/tag/$tagName",
                    // Stable asset name published alongside every versioned APK.
                    downloadUrl = "$repoUrl/releases/latest/download/Ec2026prog-latest.apk"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Release redirect lookup failed: ${e.message}")
            null
        }
    }

    private fun getLatestReleaseFromApi(): VersionInfo? {
        return try {
            val request = Request.Builder()
                .url(releasesApiUrl)
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

            val downloadUrl = json.getAsJsonArray("assets")
                ?.firstOrNull { it.asJsonObject.get("name")?.asString?.endsWith(".apk") == true }
                ?.asJsonObject?.get("browser_download_url")?.asString
                ?: htmlUrl

            VersionInfo(tagName, name, htmlUrl, downloadUrl)
        } catch (e: Exception) {
            Log.w(TAG, "getLatestRelease failed: ${e.message}")
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
