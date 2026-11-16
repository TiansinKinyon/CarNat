package com.baidu.carlife.xiaomi.update

import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException

internal enum class UpdateSource(val label: String, val host: String) {
    GITHUB("GitHub", "github.com"),
    GITEE("Gitee", "gitee.com");

    val repository: String get() = "https://$host/$PROJECT_PATH"

    val releaseApi: String get() = when (this) {
        GITHUB -> "https://api.github.com/repos/$PROJECT_PATH/releases?per_page=20"
        GITEE -> "https://gitee.com/api/v5/repos/$PROJECT_PATH/releases?per_page=20"
    }

    fun releasePage(tag: String): String = "$repository/releases/tag/$tag"

    private companion object {
        const val PROJECT_PATH = "TiansinKinyon/CarNat"
    }
}

internal data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int =
        compareValuesBy(this, other, ReleaseVersion::major, ReleaseVersion::minor, ReleaseVersion::patch)

    companion object {
        private val stableTag = Regex("[vV]?(\\d+)\\.(\\d+)(?:\\.(\\d+))?")

        fun parse(tag: String): ReleaseVersion? {
            val parts = stableTag.matchEntire(tag)?.groupValues ?: return null
            return ReleaseVersion(
                parts[1].toIntOrNull() ?: return null,
                parts[2].toIntOrNull() ?: return null,
                parts[3].ifEmpty { "0" }.toIntOrNull() ?: return null,
            )
        }
    }
}

internal data class UpdateRelease(val tag: String, val version: ReleaseVersion, val notes: String)

internal class ReleaseHttpException(val status: Int) : IOException("HTTP $status")

internal object ReleaseClient {
    suspend fun latestStable(source: UpdateSource): UpdateRelease? = withContext(Dispatchers.IO) {
        val connection = URL(source.releaseApi).openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("User-Agent", "CarNat")
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            if (status != 200) throw ReleaseHttpException(status)

            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val releases = JSONArray(response)
            var latest: UpdateRelease? = null
            for (index in 0 until releases.length()) {
                val release = releases.optJSONObject(index) ?: throw JSONException("Expected a release object")
                if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
                val tag = release.optString("tag_name")
                val version = ReleaseVersion.parse(tag) ?: continue
                if (latest == null || version > latest.version) {
                    val notes = if (release.isNull("body")) "" else release.getString("body").trim()
                    latest = UpdateRelease(tag, version, notes)
                }
            }
            latest
        } finally {
            connection.disconnect()
        }
    }
}
