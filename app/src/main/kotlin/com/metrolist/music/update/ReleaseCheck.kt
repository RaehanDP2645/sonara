/**
 * Sonara (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Requested by the owner: the built-in updater. The newest release on GitHub
 * (rashiiddsr/sonara) is compared with the running version; every app (Android, and the desktop
 * app, which compiles this file as it is) shows it and downloads its own file from it. A release
 * counts as an update only when its version number is higher, so every update needs a new version.
 *
 * An update is optional (Later, Don't show again) unless the release notes carry the hidden line
 * `<!-- sonara-min: 2.5.0 -->`: every version below that one must update before it can be used.
 */

package com.metrolist.music.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Release(
    /** "2.5.0", from the tag "v2.5.0". */
    val version: String,
    val notes: String,
    val pageUrl: String,
    val assets: List<ReleaseAsset>,
    /** The lowest version still allowed to run (`<!-- sonara-min: x.y.z -->` in the notes), or null. */
    val minVersion: String? = null,
) {
    /** Whether [current] is below [minVersion], so this update can't be put off. */
    fun isRequiredFor(current: String): Boolean = minVersion != null && ReleaseCheck.compare(current, minVersion) < 0
}

data class ReleaseAsset(
    val name: String,
    val url: String,
    val size: Long,
)

object ReleaseCheck {
    const val REPOSITORY = "rashiiddsr/sonara"
    private const val LATEST = "https://api.github.com/repos/$REPOSITORY/releases/latest"
    private const val TIMEOUT_MS = 15_000
    private const val BUFFER_BYTES = 64 * 1024

    /** Once a day at most, unless asked for in About. */
    const val CHECK_EVERY_MS = 24L * 60 * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true }

    private val minMarker = Regex("""<!--\s*sonara-min:\s*v?([0-9][0-9.]*)\s*-->""", RegexOption.IGNORE_CASE)
    private val htmlComment = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)

    /** The newest published release (drafts and pre-releases are never "latest" on GitHub). */
    suspend fun latest(): Result<Release> =
        withContext(Dispatchers.IO) {
            runCatching {
                val connection = (URL(LATEST).openConnection() as HttpURLConnection)
                try {
                    connection.connectTimeout = TIMEOUT_MS
                    connection.readTimeout = TIMEOUT_MS
                    connection.setRequestProperty("Accept", "application/vnd.github+json")
                    connection.setRequestProperty("User-Agent", "Sonara-updater")
                    check(connection.responseCode == HttpURLConnection.HTTP_OK) { "GitHub: HTTP ${connection.responseCode}" }
                    checkNotNull(parse(connection.inputStream.bufferedReader().use { it.readText() })) { "GitHub: unexpected answer" }
                } finally {
                    connection.disconnect()
                }
            }
        }

    internal fun parse(text: String): Release? {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
        if ((root["draft"] as? JsonPrimitive)?.booleanOrNull == true) return null
        if ((root["prerelease"] as? JsonPrimitive)?.booleanOrNull == true) return null
        val tag = root.string("tag_name") ?: return null
        val assets =
            (root["assets"] as? JsonArray).orEmpty().mapNotNull { item ->
                val asset = item as? JsonObject ?: return@mapNotNull null
                ReleaseAsset(
                    name = asset.string("name") ?: return@mapNotNull null,
                    url = asset.string("browser_download_url") ?: return@mapNotNull null,
                    size = (asset["size"] as? JsonPrimitive)?.longOrNull ?: 0L,
                )
            }
        val body = root.string("body").orEmpty()
        return Release(
            version = tag.removePrefix("v").removePrefix("V"),
            notes = body.replace(htmlComment, "").trim(),
            pageUrl = root.string("html_url") ?: "https://github.com/$REPOSITORY/releases",
            assets = assets,
            minVersion = minMarker.find(body)?.groupValues?.get(1)?.trimEnd('.'),
        )
    }

    /** Whether [latest] is a higher version than [current]: "2.10.0" > "2.9.1"; "2.4.2+abc" is 2.4.2. */
    fun isNewer(
        latest: String,
        current: String,
    ): Boolean = compare(latest, current) > 0

    internal fun compare(
        a: String,
        b: String,
    ): Int {
        fun parts(version: String) =
            version.trim().removePrefix("v").substringBefore('+').substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val left = parts(a)
        val right = parts(b)
        for (i in 0 until maxOf(left.size, right.size)) {
            val difference = left.getOrElse(i) { 0 }.compareTo(right.getOrElse(i) { 0 })
            if (difference != 0) return difference
        }
        return 0
    }

    /**
     * Downloads [asset] into [target] (through a .part file, so a broken download never looks
     * whole), telling [progress] the share done, 0 to 1 (or -1 when the size is unknown).
     */
    suspend fun download(
        asset: ReleaseAsset,
        target: File,
        progress: (Float) -> Unit,
    ): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                target.parentFile?.mkdirs()
                val part = File(target.path + ".part")
                val connection = (URL(asset.url).openConnection() as HttpURLConnection)
                try {
                    connection.connectTimeout = TIMEOUT_MS
                    connection.readTimeout = TIMEOUT_MS * 4
                    connection.instanceFollowRedirects = true
                    connection.setRequestProperty("User-Agent", "Sonara-updater")
                    check(connection.responseCode == HttpURLConnection.HTTP_OK) { "Download: HTTP ${connection.responseCode}" }
                    val total = connection.contentLengthLong.takeIf { it > 0 } ?: asset.size.takeIf { it > 0 }
                    var done = 0L
                    connection.inputStream.use { input ->
                        part.outputStream().use { output ->
                            val buffer = ByteArray(BUFFER_BYTES)
                            while (true) {
                                ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                done += read
                                progress(if (total != null) (done.toFloat() / total).coerceIn(0f, 1f) else -1f)
                            }
                        }
                    }
                    if (total != null) check(done == total) { "Download: incomplete" }
                    target.delete()
                    check(part.renameTo(target)) { "Download: could not save" }
                    target
                } catch (e: Throwable) {
                    part.delete()
                    throw e
                } finally {
                    connection.disconnect()
                }
            }
        }
}
