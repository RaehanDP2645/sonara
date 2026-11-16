/**
 * Sonara (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * The Android app's AI translator (app/.../lyrics/LyricsTranslator.kt), through the owner's proxy
 * (web/, sonara.rashiiddsr.my.id/api), which holds the AI providers' keys, tries one provider after
 * another and keeps every song's translation for everyone; the app holds no key. Requests are
 * signed with the sealed client secret (BuildSecrets.API_SECRET), as the phone signs them. When
 * the proxy can't translate, lyrics show without a translation. Results are kept on disk per song
 * and language.
 */

package com.ermusic.desktop.playback

import com.ermusic.desktop.data.keepNewest
import com.ermusic.desktop.data.KEEP_FILES
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import com.ermusic.desktop.AppFiles
import com.ermusic.desktop.BuildSecrets
import com.ermusic.desktop.Os
import com.ermusic.desktop.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.TimeUnit

object LyricsTranslator {
    private const val MODE = "Sonara-3"
    private const val REQUEST_LINES = 300
    private const val TRANSLATE_PATH = "/api/translate"
    private const val DEFAULT_PAUSE_SECONDS = 60L
    private const val MAX_PAUSE_SECONDS = 3600L
    private const val TIMEOUT_SECONDS = 90L
    private const val PAIR = '\t'

    val languages =
        listOf(
            "en" to "English", "id" to "Bahasa Indonesia", "ms" to "Bahasa Melayu", "ja" to "日本語", "ko" to "한국어",
            "zh-CN" to "中文（简体）", "zh-TW" to "中文（繁體）", "th" to "ไทย", "vi" to "Tiếng Việt", "fil" to "Filipino",
            "hi" to "हिन्दी", "ar" to "العربية", "es" to "Español", "pt-BR" to "Português (Brasil)", "fr" to "Français",
            "de" to "Deutsch", "it" to "Italiano", "ru" to "Русский", "tr" to "Türkçe",
        )

    private val dir = File(AppFiles.root, "translations").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }
    private val http =
        OkHttpClient.Builder().callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
    private val vocals =
        Regex(
            "o+h*|a+h+|aa+|e+h+|u+h+|u+m+|m+|h+m+|(ha)+h?|l+a+|n+a+|d+a+|d+o+|b+a+|s+h+a+|w+h?o+a*h*|" +
                "ye+a*h*|ye+|y+o+|he+y+|a+y+|o+o+h*|la+la+|na+na+|doo+|shoo+|bop|uh+|ooh+|woo+|whoo+",
        )

    private fun isVocalsOnly(line: String): Boolean {
        val words = line.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
        return words.isEmpty() || words.all { vocals.matches(it) }
    }

    private fun comparable(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

    fun shouldShow(source: String, translation: String?): Boolean =
        !translation.isNullOrBlank() && !isVocalsOnly(source) && comparable(translation) != comparable(source)

    /** Source line to translation, or an empty map when that isn't possible right now. */
    suspend fun translate(trackId: String, lines: List<String>, target: String): Map<String, String> =
        withContext(Dispatchers.IO) {
            if (BuildSecrets.API_SECRET.isBlank() || System.currentTimeMillis() < pausedUntil) return@withContext emptyMap()
            val file = File(dir, "$trackId.$target.$MODE.tsv")
            // The player readies the translation when the song starts; the lyrics page, opened
            // meanwhile, waits for that answer instead of asking again.
            asking.computeIfAbsent(file.name) { Mutex() }.withLock {
                if (file.exists()) {
                    file.setLastModified(System.currentTimeMillis())
                    return@withLock decode(file.readText())
                }
                val sources = lines.map { it.trim() }.filter { it.isNotEmpty() && !isVocalsOnly(it) }.distinct()
                val (found, current) = sources.takeIf { it.isNotEmpty() }?.let { batch -> firstAnswer(batch, target) } ?: return@withLock emptyMap()
                // Saved only when the proxy made it with the current prompt; one from a proxy not
                // yet updated shows now and is asked for again next time.
                if (current) file.writeText(found.entries.joinToString("\n") { (source, text) -> "${source.replace('\t', ' ')}$PAIR${text.replace('\t', ' ')}" })
                found
            }
        }

    private val asking = ConcurrentHashMap<String, Mutex>()

    /** Run at start, as the lyrics are. */
    fun tidy() = keepNewest(dir, KEEP_FILES)

    /** The song's saved translations go (its lyrics were replaced): they are made again. */
    fun forget(trackId: String) {
        dir.listFiles { file -> file.name.startsWith("$trackId.") }.orEmpty().forEach { it.delete() }
    }

    private fun decode(stored: String): Map<String, String> =
        stored.lineSequence().mapNotNull { row ->
            val split = row.indexOf(PAIR)
            if (split <= 0) null else row.substring(0, split) to row.substring(split + 1)
        }.toMap()

    /** When the proxy said "too many requests": nothing is asked again before then. */
    @Volatile
    private var pausedUntil = 0L

    /**
     * All of the song's lines, in parts of [REQUEST_LINES], and whether every part came from the
     * current prompt ([MODE]); null when any part failed.
     */
    private fun firstAnswer(sources: List<String>, target: String): Pair<Map<String, String>, Boolean>? =
        runCatching {
            val parts = sources.chunked(REQUEST_LINES).map { ask(it, target) }
            parts.fold(emptyMap<String, String>()) { all, part -> all + part.first } to parts.all { it.second }
        }.getOrNull()

    /** One signed request to the proxy, answered `{"lines": [{"n": 1, "t": "..."}]}`. */
    private fun ask(batch: List<String>, target: String): Pair<Map<String, String>, Boolean> {
        val body =
            buildJsonObject {
                put("target", target)
                putJsonArray("lines") { batch.forEach { add(it) } }
            }.toString()
        val install = installId()
        val time = (System.currentTimeMillis() / 1000).toString()
        val request =
            Request.Builder()
                .url(BuildSecrets.API_URL.trimEnd('/') + "/translate")
                .header("X-Sonara-Install", install)
                .header("X-Sonara-Time", time)
                .header("X-Sonara-Client", "desktop-${Os.name.lowercase()}/${BuildSecrets.APP_VERSION}")
                .header("X-Sonara-Signature", signature(BuildSecrets.API_SECRET, TRANSLATE_PATH, time, install, body))
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        val text =
            http.newCall(request).execute().use { response ->
                if (response.code == 429) {
                    val wait = response.header("Retry-After")?.toLongOrNull() ?: DEFAULT_PAUSE_SECONDS
                    pausedUntil = System.currentTimeMillis() + wait.coerceIn(1, MAX_PAUSE_SECONDS) * 1000
                }
                check(response.isSuccessful) { "HTTP ${response.code}" }
                response.body.string()
            }
        val lines = checkNotNull(parse(text)) { "unexpected answer" }.mapNotNull { (n, line) -> batch.getOrNull(n - 1)?.let { it to line } }.toMap()
        // The proxy says which prompt made it (none before 2.5.1: the old one).
        val prompt = ((runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject)?.get("prompt") as? JsonPrimitive)?.contentOrNull
        return lines to (prompt == MODE)
    }

    /** Hex HMAC-SHA256 of `POST`, the path, the time, the install and the body's SHA-256, as the phone signs. */
    internal fun signature(secret: String, path: String, time: String, install: String, body: String): String {
        val bodyHash = MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8)).toHex()
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")) }
        return mac.doFinal("POST\n$path\n$time\n$install\n$bodyHash".toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    @Synchronized
    private fun installId(): String =
        Settings.installId.value.ifBlank { UUID.randomUUID().toString().also { Settings.installId.value = it } }

    private fun parse(content: String): Map<Int, String>? {
        val cleaned = content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val root = runCatching { json.parseToJsonElement(cleaned) }.getOrNull() as? JsonObject ?: return null
        val rows = root["lines"] as? JsonArray ?: return null
        return rows.mapNotNull { row ->
            val item = row as? JsonObject ?: return@mapNotNull null
            val n = (item["n"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: return@mapNotNull null
            val t = (item["t"] as? JsonPrimitive)?.contentOrNull?.replace('\n', ' ')?.trim()
            if (t.isNullOrEmpty()) null else n to t
        }.toMap()
    }
}
