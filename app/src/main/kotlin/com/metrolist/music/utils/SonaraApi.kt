/**
 * Sonara (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import com.metrolist.music.BuildConfig
import com.metrolist.music.lyrics.LyricsTranslator
import com.metrolist.music.lyrics.SealedKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Signed POSTs to the owner's proxy (web/), signed as the translator signs its requests. */
object SonaraApi {
    private const val CONNECT_MS = 15_000
    private const val READ_MS = 60_000
    private val secret by lazy { SealedKey.open(BuildConfig.SONARA_API_SECRET_SEALED) }

    fun forApp(context: Context): ExternalImport.Api =
        ExternalImport.Api { path, body -> post(context.applicationContext, path, body) }

    /** The HTTP status (0 when the proxy can't be reached) and the answer's body. */
    suspend fun post(
        context: Context,
        path: String,
        body: String,
    ): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            runCatching {
                check(secret.isNotBlank()) { "No client secret built in" }
                val install = LyricsTranslator.installId(context)
                val time = (System.currentTimeMillis() / 1000).toString()
                val base = BuildConfig.SONARA_API_URL.trimEnd('/').removeSuffix("/api")
                val connection = URL(base + path).openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = CONNECT_MS
                    connection.readTimeout = READ_MS
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setRequestProperty("X-Sonara-Install", install)
                    connection.setRequestProperty("X-Sonara-Time", time)
                    connection.setRequestProperty("X-Sonara-Client", (if (BuildConfig.IS_TV) "tv/" else "android/") + BuildConfig.VERSION_NAME)
                    connection.setRequestProperty("X-Sonara-Signature", LyricsTranslator.signature(secret, path, time, install, body))
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    val status = connection.responseCode
                    val text = (if (status in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                    status to text
                } finally {
                    connection.disconnect()
                }
            }.getOrDefault(0 to "")
        }
}
