/**
 * Sonara (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Requested by the owner: importing a playlist (or album) from Spotify, Deezer or Apple Music.
 * The owner's proxy reads the list of tracks (/api/import: the platforms' keys stay on the
 * server); then each track is found on YouTube Music here, among songs only (never a music
 * video), by title and artists, with the album and the length to choose between close results,
 * and by its ISRC first when the platform gives one. A song still not found, whose platform gave
 * no ISRC (Spotify's embed page, Apple Music), is looked up once more: the proxy asks Deezer for
 * its ISRC (/api/isrc) and the search is tried again with it. What can't be found is listed,
 * never added at a guess. Shared with the desktop app (no Android classes); each app signs the
 * call itself.
 */

package com.metrolist.music.utils

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import kotlin.math.abs

object ExternalImport {
    enum class Platform(val label: String) { SPOTIFY("Spotify"), DEEZER("Deezer"), APPLE("Apple Music") }

    /** Why a link can't be imported; each has its own message in the apps. */
    enum class Problem {
        UNSUPPORTED_LINK,
        NOT_FOUND,
        SPOTIFY_EDITORIAL,
        EMPTY,
        NOT_CONFIGURED,
        SOURCE_ERROR,
        RATE_LIMITED,
        OFFLINE,
        NOTHING_FOUND,
    }

    data class Track(
        val title: String,
        val artists: List<String>,
        val album: String?,
        val durationMs: Long?,
        val isrc: String?,
    )

    data class Playlist(
        val platform: Platform,
        val title: String,
        val owner: String?,
        /** What the platform says the playlist is about, when it says anything. */
        val description: String?,
        val image: String?,
        val total: Int,
        val truncated: Boolean,
        val tracks: List<Track>,
    )

    /** The YouTube Music songs found, in the playlist's order, and the tracks that weren't. */
    data class Matched(
        val videoIds: List<String>,
        val missing: List<Track>,
    )

    /** Signs and sends a POST to the proxy; answers the HTTP status (0 offline) and the body. */
    fun interface Api {
        suspend fun post(
            path: String,
            body: String,
        ): Pair<Int, String>
    }

    /** The platform of a pasted link, or null (YouTube links are imported the YouTube way). */
    fun platformOf(link: String): Platform? {
        val text = link.trim().lowercase()
        return when {
            text.startsWith("spotify:") || Regex("""^(https?://)?([a-z0-9-]+\.)*(spotify\.com|spotify\.link|spotify\.app\.link)/""").containsMatchIn(text) -> Platform.SPOTIFY
            Regex("""^(https?://)?([a-z0-9-]+\.)*(deezer\.com|deezer\.page\.link)/""").containsMatchIn(text) -> Platform.DEEZER
            Regex("""^(https?://)?music\.apple\.com/""").containsMatchIn(text) -> Platform.APPLE
            else -> null
        }
    }

    /** Reads the playlist behind [link] through the proxy; [Problem] says why not. */
    suspend fun resolve(
        api: Api,
        link: String,
    ): Result<Playlist> {
        val (status, text) = runCatching { api.post("/api/import", JSONObject().put("url", link.trim()).toString()) }.getOrDefault(0 to "")
        if (status == 0) return failure(Problem.OFFLINE)
        val root = runCatching { JSONObject(text) }.getOrNull()
        if (status !in 200..299) {
            return failure(
                when (root?.optString("error")) {
                    "unsupported_link" -> Problem.UNSUPPORTED_LINK
                    "not_found" -> Problem.NOT_FOUND
                    "spotify_editorial" -> Problem.SPOTIFY_EDITORIAL
                    "empty" -> Problem.EMPTY
                    "not_configured" -> Problem.NOT_CONFIGURED
                    "rate_limited" -> Problem.RATE_LIMITED
                    else -> if (status == 429) Problem.RATE_LIMITED else Problem.SOURCE_ERROR
                },
            )
        }
        root ?: return failure(Problem.SOURCE_ERROR)
        val platform =
            when (root.optString("source")) {
                "spotify" -> Platform.SPOTIFY
                "deezer" -> Platform.DEEZER
                "apple" -> Platform.APPLE
                else -> return failure(Problem.SOURCE_ERROR)
            }
        val tracks =
            (root.optJSONArray("tracks") ?: JSONArray()).let { array ->
                (0 until array.length()).mapNotNull { i ->
                    val item = array.optJSONObject(i) ?: return@mapNotNull null
                    val title = item.optString("title").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    val artists = item.optJSONArray("artists")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
                    Track(
                        title = title,
                        artists = artists,
                        album = item.optString("album").takeIf { it.isNotBlank() && it != "null" },
                        durationMs = if (item.isNull("duration_ms")) null else item.optLong("duration_ms").takeIf { it > 0 },
                        isrc = item.optString("isrc").takeIf { it.isNotBlank() && it != "null" },
                    )
                }
            }
        if (tracks.isEmpty()) return failure(Problem.EMPTY)
        return Result.success(
            Playlist(
                platform = platform,
                title = root.optString("title").ifBlank { platform.label },
                owner = root.optString("owner").takeIf { it.isNotBlank() && it != "null" },
                description = root.optString("description").takeIf { it.isNotBlank() && it != "null" },
                image = root.optString("image").takeIf { it.isNotBlank() && it != "null" },
                total = root.optInt("total", tracks.size),
                truncated = root.optBoolean("truncated"),
                tracks = tracks,
            ),
        )
    }

    /**
     * Finds each track on YouTube Music (songs only), a few at a time. What is left over is asked
     * about once more, by ISRC, when [api] is given; only the finished list is ever shown.
     */
    suspend fun match(
        tracks: List<Track>,
        api: Api? = null,
    ): Matched =
        coroutineScope {
            val gate = Semaphore(PARALLEL_SEARCHES)
            val found =
                tracks
                    .map { track -> async { gate.withPermit { runCatching { find(track) }.getOrNull() } } }
                    .awaitAll()
                    .toMutableList()

            // Songs still not found whose platform gave no ISRC: Deezer may know one, and an ISRC
            // names one recording exactly, so searching again with it finds the right song.
            if (api != null) {
                val asking = tracks.indices.filter { found[it] == null && tracks[it].isrc == null }.take(ISRC_BATCH * ISRC_BATCHES)
                for (batch in asking.chunked(ISRC_BATCH)) {
                    val isrcs = isrcsFor(api, batch.map { tracks[it] })
                    var hits = 0
                    batch.forEachIndexed { position, index ->
                        isrcs.getOrNull(position)?.let { isrc ->
                            hits++
                            found[index] = runCatching { find(tracks[index].copy(isrc = isrc)) }.getOrNull()
                        }
                    }
                    // Deezer knows nothing about this music either: no point asking about the rest.
                    if (hits == 0) break
                }
            }
            Matched(
                videoIds = found.filterNotNull().distinct(),
                missing = tracks.filterIndexed { index, _ -> found[index] == null },
            )
        }

    /** Deezer's ISRC for each track, through the proxy; null where it has none. */
    private suspend fun isrcsFor(
        api: Api,
        tracks: List<Track>,
    ): List<String?> {
        val body =
            JSONObject()
                .put(
                    "tracks",
                    JSONArray().apply {
                        tracks.forEach { track ->
                            put(
                                JSONObject()
                                    .put("title", track.title)
                                    .put("artists", JSONArray(track.artists))
                                    .put("duration_ms", track.durationMs ?: JSONObject.NULL),
                            )
                        }
                    },
                ).toString()
        val (status, text) = runCatching { api.post("/api/isrc", body) }.getOrDefault(0 to "")
        if (status !in 200..299) return emptyList()
        val array = runCatching { JSONObject(text).optJSONArray("isrc") }.getOrNull() ?: return emptyList()
        return (0 until array.length()).map { i ->
            array.optString(i).trim().uppercase().takeIf(ISRC::matches)
        }
    }

    /** The best song for [track], or null when nothing is close enough. */
    private suspend fun find(track: Track): String? {
        track.isrc?.let { isrc ->
            songs(isrc).firstOrNull { score(track, it) >= ISRC_ACCEPT }?.let { return it.id }
        }
        val query = (listOf(track.title) + track.artists.take(2)).joinToString(" ")
        val best = songs(query).maxByOrNull { score(track, it) } ?: return null
        return best.id.takeIf { score(track, best) >= ACCEPT }
    }

    private suspend fun songs(query: String): List<SongItem> =
        YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull()?.items.orEmpty()
            .filterIsInstance<SongItem>()
            .filterNot { it.isVideoSong }
            .take(CANDIDATES)

    /**
     * How well [song] fits [track], about 0..1: the title counts most, then the artists, then the
     * length; the album helps between versions. A live, remix, cover or karaoke version that the
     * track doesn't ask for counts against.
     */
    internal fun score(
        track: Track,
        song: SongItem,
    ): Double {
        val wantTitle = plain(track.title)
        val gotTitle = plain(song.title)
        val title =
            when {
                wantTitle == gotTitle -> 1.0
                gotTitle.startsWith(wantTitle) || wantTitle.startsWith(gotTitle) -> 0.85
                else -> overlap(wantTitle, gotTitle)
            }
        val wantArtists = track.artists.map(::plain).filter { it.isNotEmpty() }
        val gotArtists = song.artists.map { plain(it.name) }
        val artist =
            if (wantArtists.isEmpty()) {
                0.5
            } else {
                wantArtists.count { want -> gotArtists.any { got -> got == want || got.contains(want) || want.contains(got) } }.toDouble() / wantArtists.size
            }
        val length =
            track.durationMs?.let { ms ->
                song.duration?.let { seconds ->
                    val diff = abs(ms / 1000 - seconds)
                    when {
                        diff <= 3 -> 1.0
                        diff <= 8 -> 0.6
                        diff <= 20 -> 0.2
                        else -> 0.0
                    }
                }
            } ?: 0.5
        val album =
            track.album?.let { want ->
                song.album?.name?.let { got -> if (plain(got) == plain(want)) 1.0 else 0.0 }
            } ?: 0.0
        val unwanted = VERSIONS.any { it in plain(song.title) && it !in wantTitle }
        return title * 0.5 + artist * 0.3 + length * 0.15 + album * 0.05 - if (unwanted) 0.3 else 0.0
    }

    /** Lower case, no accents, no "(feat. ...)", "- Remastered" and the like, single spaces. */
    internal fun plain(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("""\s*[(\[](feat|ft|with|from|remaster)[^)\]]*[)\]]"""), "")
            .replace(Regex("""\s+-\s+(\d{4}\s+)?remaster(ed)?.*$"""), "")
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .trim()

    private fun overlap(a: String, b: String): Double {
        val left = a.split(' ').filter { it.isNotEmpty() }.toSet()
        val right = b.split(' ').filter { it.isNotEmpty() }.toSet()
        if (left.isEmpty() || right.isEmpty()) return 0.0
        return (left intersect right).size.toDouble() / maxOf(left.size, right.size)
    }

    private fun <T> failure(problem: Problem): Result<T> = Result.failure(ImportProblem(problem))

    /** Carries a [Problem] out of [resolve]. */
    class ImportProblem(val problem: Problem) : Exception(problem.name)

    /** Searches at once: quick for 300 songs without looking like a flood to YouTube. */
    private const val PARALLEL_SEARCHES = 4
    private const val CANDIDATES = 5
    private const val ACCEPT = 0.62
    private const val ISRC_ACCEPT = 0.45

    /** Songs one /api/isrc call may ask about, and how many calls an import may make. */
    private const val ISRC_BATCH = 30
    private const val ISRC_BATCHES = 2
    private val ISRC = Regex("""[A-Z]{2}[A-Z0-9]{3}\d{7}""")
    private val VERSIONS = listOf("live", "remix", "karaoke", "instrumental", "cover", "acoustic", "sped up", "slowed")
}
