/**
 * Sonara (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Requested by the owner: Sonara shares its own links (sonara.rashiiddsr.my.id/open/?...) instead
 * of music.youtube.com ones. What they point at is still YouTube Music's: the link only carries
 * YouTube's id, and nothing is stored on the server. With Sonara installed, Android opens them in
 * the app (verified App Links); without it, the site's /open/ page offers Sonara or YouTube Music.
 * Shared with the desktop app, so it uses no Android classes.
 */

package com.metrolist.music.utils

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

object SonaraLinks {
    const val HOST = "sonara.rashiiddsr.my.id"
    private const val OPEN = "https://$HOST/open/?"
    private const val YTM = "https://music.youtube.com"
    private val id = Regex("[A-Za-z0-9_-]{6,64}")
    // YouTube's join token, decoded: any visible characters (it can hold "/" and "+").
    private val token = Regex("""[^\s&]{4,512}""")

    fun song(videoId: String): String = OPEN + "song=" + videoId

    fun playlist(playlistId: String): String = OPEN + "playlist=" + playlistId.removePrefix("VL")

    fun album(albumId: String): String = OPEN + "album=" + albumId

    fun artist(channelId: String): String = OPEN + "artist=" + channelId

    /** A collaboration invite: the playlist and YouTube's join token. */
    fun invite(
        playlistId: String,
        joinToken: String,
    ): String = playlist(playlistId) + "&jct=" + URLEncoder.encode(joinToken, "UTF-8")

    /**
     * The Sonara link for a YouTube Music (or YouTube) [url]: watch?v= is a song, playlist?list=
     * a playlist (with jct an invite), browse/MPREb_ an album, channel/ an artist. Anything else
     * is shared as it is.
     */
    fun share(url: String): String {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return url
        val host = uri.host?.lowercase() ?: return url
        if (!(host == "music.youtube.com" || host.endsWith("youtube.com") || host == "youtu.be")) return url
        val query = queryOf(uri)
        val path = uri.path.orEmpty()
        return when {
            host == "youtu.be" -> path.trim('/').takeIf { id.matches(it) }?.let(::song)
            path == "/watch" -> query["v"]?.takeIf { id.matches(it) }?.let(::song)
            path == "/playlist" ->
                query["list"]?.takeIf { id.matches(it) }?.let { list ->
                    val jct = query["jct"]
                    if (jct != null) invite(list, jct) else if (list.startsWith("OLAK5uy_")) album(list) else playlist(list)
                }
            path.startsWith("/browse/MPREb_") -> path.removePrefix("/browse/").let(::album)
            path.startsWith("/browse/VL") -> playlist(path.removePrefix("/browse/"))
            path.startsWith("/channel/") -> path.removePrefix("/channel/").trim('/').takeIf { id.matches(it) }?.let(::artist)
            else -> null
        } ?: url
    }

    /**
     * The YouTube Music link a Sonara link stands for (https://sonara.../open/?... or
     * sonara://open/?...), so the app opens it the way it opens YouTube Music links; null when
     * [url] isn't a Sonara share link.
     */
    fun toYouTube(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val ours =
            (uri.scheme == "https" && uri.host == HOST && uri.path.orEmpty().trimEnd('/') == "/open") ||
                (uri.scheme == "sonara" && uri.host == "open")
        if (!ours) return null
        val query = queryOf(uri)
        query["song"]?.takeIf { id.matches(it) }?.let { return "$YTM/watch?v=$it" }
        query["playlist"]?.takeIf { id.matches(it) }?.let { list ->
            val jct = query["jct"]?.takeIf { token.matches(it) }
            return "$YTM/playlist?list=$list" + (jct?.let { "&jct=" + URLEncoder.encode(it, "UTF-8") } ?: "")
        }
        query["album"]?.takeIf { id.matches(it) }?.let {
            return if (it.startsWith("MPREb_")) "$YTM/browse/$it" else "$YTM/playlist?list=$it"
        }
        query["artist"]?.takeIf { id.matches(it) }?.let { return "$YTM/channel/$it" }
        return null
    }

    private fun queryOf(uri: URI): Map<String, String> =
        uri.rawQuery
            .orEmpty()
            .split('&')
            .mapNotNull { part ->
                val split = part.indexOf('=')
                if (split <= 0) return@mapNotNull null
                val key = URLDecoder.decode(part.substring(0, split), "UTF-8")
                val value = URLDecoder.decode(part.substring(split + 1), "UTF-8")
                key to value
            }.toMap()
}
