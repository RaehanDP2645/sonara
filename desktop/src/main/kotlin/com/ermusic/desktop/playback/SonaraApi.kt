/**
 * SONARA (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.ermusic.desktop.playback

import com.ermusic.desktop.BuildSecrets
import com.ermusic.desktop.Os
import com.metrolist.music.utils.ExternalImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Signed POSTs to the owner's proxy, as the phone sends them (playlist import). */
object SonaraApi : ExternalImport.Api {
    private val http = OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    override suspend fun post(
        path: String,
        body: String,
    ): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            runCatching {
                check(BuildSecrets.API_SECRET.isNotBlank()) { "No client secret built in" }
                val install = LyricsTranslator.installId()
                val time = (System.currentTimeMillis() / 1000).toString()
                val request =
                    Request.Builder()
                        .url(BuildSecrets.API_URL.trimEnd('/').removeSuffix("/api") + path)
                        .header("X-Sonara-Install", install)
                        .header("X-Sonara-Time", time)
                        .header("X-Sonara-Client", "desktop-${Os.name.lowercase()}/${BuildSecrets.APP_VERSION}")
                        .header("X-Sonara-Signature", LyricsTranslator.signature(BuildSecrets.API_SECRET, path, time, install, body))
                        .post(body.toRequestBody("application/json".toMediaType()))
                        .build()
                http.newCall(request).execute().use { response -> response.code to response.body.string() }
            }.getOrDefault(0 to "")
        }
}
