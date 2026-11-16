/**
 * SONARA (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.ermusic.desktop.playback

import com.ermusic.desktop.data.keepNewest
import com.ermusic.desktop.data.KEEP_FILES
import java.io.File
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import androidx.compose.runtime.snapshotFlow
import com.ermusic.desktop.data.Settings
import com.ermusic.desktop.AppFiles
import com.metrolist.music.lyrics.LyricsFreshness
import android.content.Context
import com.metrolist.paxsenix.Paxsenix
import com.metrolist.music.lyrics.BetterLyricsProvider
import com.metrolist.music.lyrics.KuGouLyricsProvider
import com.metrolist.music.lyrics.LrcLibLyricsProvider
import com.metrolist.music.lyrics.LyricsPlusProvider
import com.metrolist.music.lyrics.LyricsProvider
import com.metrolist.music.lyrics.LyricsUtils
import com.metrolist.music.lyrics.PaxsenixLyricsProvider
import com.metrolist.music.lyrics.YouTubeLyricsProvider
import com.metrolist.music.lyrics.YouTubeSubtitleLyricsProvider
import com.metrolist.music.lyrics.ZemerLyricsProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

data class LyricWord(val text: String, val startMs: Long, val endMs: Long, val trailingSpace: Boolean)

/** [words] is there only when the source timed each word. */
data class LyricLine(val timeMs: Long, val text: String, val words: List<LyricWord>? = null)

/** [synced] lines carry real times; otherwise the times are only their order. */
data class Lyrics(val lines: List<LyricLine>, val synced: Boolean, val source: String)

/**
 * The Android app's lyric sources, compiled from its own files, tried at once and taken in its
 * order: the first with an answer wins, so word-by-word sources come before line-by-line ones.
 */
object LyricsRepository {
    private const val PER_SOURCE_TIMEOUT_MS = 12_000L
    private const val MS_PER_SECOND = 1000L
    private const val SECONDS_DIVISOR = 1000

    private val sources: List<LyricsProvider> =
        listOf(
            BetterLyricsProvider,
            LrcLibLyricsProvider,
            KuGouLyricsProvider,
            PaxsenixLyricsProvider,
            LyricsPlusProvider,
            ZemerLyricsProvider,
            YouTubeSubtitleLyricsProvider,
            YouTubeLyricsProvider,
        )
    private val context = Context().also { Paxsenix.init(it) }
    private val cache = ConcurrentHashMap<String, Lyrics>()
    private val timestamp = Regex("""\[\d+:\d+""")

    /** Lyrics found before, as the source gave them: the source's name, then the text. */
    private val dir = File(AppFiles.root, "lyrics").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One search per song, however many ask at once (the player getting ready, the lyrics page). */
    private val searching = ConcurrentHashMap<String, Deferred<Lyrics?>>()

    private fun stored(id: String) = File(dir, "$id.txt")

    /** When each song's lyrics were last looked up (LyricsFreshness: at most once a day). */
    private val checks = LyricsCheckTimes(File(AppFiles.root, "lyrics-checks.properties"))
    private val ranking: List<String> get() = sources.map { it.name }

    /**
     * Saved lyrics that may not be the best (a source lower in the list, untimed) are looked up
     * again in the background, once a day at most; better ones replace them, and the lyrics page
     * shows them the next time it opens them.
     */
    private fun recheckLater(track: Track, saved: Lyrics, savedText: String) {
        if (!LyricsFreshness.isDue(saved.source, savedText, ranking, checks.last(track.id), System.currentTimeMillis())) return
        scope.launch {
            val found = search(track)
            checks.mark(track.id)
            if (found != null && LyricsFreshness.isBetter(found.second.source, found.first, saved.source, savedText, ranking)) {
                runCatching { stored(track.id).writeText(found.second.source + "\n" + found.first) }
                LyricsTranslator.forget(track.id)
                cache[track.id] = found.second
            }
        }
    }

    /** Run at start: the lyrics of the songs played longest ago go past a few thousand. */
    fun tidy() = keepNewest(dir, KEEP_FILES)

    suspend fun fetch(track: Track): Lyrics? {
        cache[track.id]?.let { return it }
        val search = searching.computeIfAbsent(track.id) { scope.async(start = CoroutineStart.LAZY) { find(track) } }
        search.invokeOnCompletion { searching.remove(track.id, search) }
        return search.await()
    }

    /**
     * Lyrics for the song as soon as it is picked, and the next one, whether or not the lyrics are
     * open, translated straight away when the translator is on: as the phone app does, so they show
     * at once. Follows the translator setting too.
     */
    fun follow(player: Player, scope: CoroutineScope) {
        scope.launch {
            val songs = player.state.map { it.current to it.next }.distinctUntilChanged { a, b -> a.first?.id == b.first?.id && a.second?.id == b.second?.id }
            val translator = snapshotFlow { Settings.translator.value to Settings.translateLanguage.value }
            combine(songs, translator) { pair, (on, language) -> Triple(pair, on, language) }.collectLatest { (pair, on, language) ->
                listOfNotNull(pair.first, pair.second).forEach { track ->
                    val lyrics = runCatching { fetch(track) }.getOrNull() ?: return@forEach
                    if (on) runCatching { LyricsTranslator.translate(track.id, lyrics.lines.map { it.text }, language) }
                }
            }
        }
    }

    private suspend fun find(track: Track): Lyrics? =
        withContext(Dispatchers.IO) {
            readStored(track.id)?.let { (lyrics, text) ->
                recheckLater(track, lyrics, text)
                return@withContext lyrics.also { cache[track.id] = it }
            }
            // Nothing saved: looked for at every play, so a song played offline gets them later.
            val found = search(track) ?: return@withContext null
            checks.mark(track.id)
            runCatching { stored(track.id).writeText(found.second.source + "\n" + found.first) }
            found.second.also { cache[track.id] = it }
        }

    /** Every source at once, taken in their order: the first answer's text and lyrics. */
    private suspend fun search(track: Track): Pair<String, Lyrics>? =
        withContext(Dispatchers.IO) {
            val title = LyricsUtils.cleanTitleForSearch(track.title)
            val seconds = (track.durationMs / SECONDS_DIVISOR).toInt()
            coroutineScope {
                val pending =
                    sources.map { source ->
                        source to
                            async {
                                try {
                                    withTimeoutOrNull(PER_SOURCE_TIMEOUT_MS) { source.getLyrics(context, track.id, title, track.artists, seconds, track.albumName) }
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: Exception) {
                                    null
                                }
                            }
                    }
                var found: Pair<String, Lyrics>? = null
                for ((source, attempt) in pending) {
                    val text = attempt.await()?.getOrNull()?.let(LyricsUtils::filterLyricsCreditLines)
                    val lyrics = text?.let { parse(it, source.name) }
                    if (text != null && lyrics != null) {
                        found = text to lyrics
                        break
                    }
                }
                pending.forEach { (_, attempt) -> attempt.cancel() }
                found
            }
        }

    /** The saved lyrics and their text as the source gave it. */
    private fun readStored(id: String): Pair<Lyrics, String>? {
        val file = stored(id).takeIf { it.exists() } ?: return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        file.setLastModified(System.currentTimeMillis())
        val split = text.indexOf('\n')
        if (split <= 0) return null
        val body = text.substring(split + 1)
        return parse(body, text.substring(0, split))?.let { it to body }
    }

    private fun parse(text: String, source: String): Lyrics? {
        if (text.isBlank()) return null
        if (timestamp.containsMatchIn(text)) {
            val lines =
                runCatching { LyricsUtils.parseLyrics(text) }.getOrNull().orEmpty()
                    .filter { !it.isBackground && it.text.isNotBlank() }
                    .map { entry ->
                        LyricLine(
                            entry.time,
                            entry.text,
                            entry.words?.takeIf { it.isNotEmpty() }?.map { LyricWord(it.text, (it.startTime * MS_PER_SECOND).toLong(), (it.endTime * MS_PER_SECOND).toLong(), it.hasTrailingSpace) },
                        )
                    }
            if (lines.isNotEmpty()) return Lyrics(lines, synced = true, source)
        }
        val plain = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !timestamp.containsMatchIn(it) }
        return if (plain.isEmpty()) null else Lyrics(plain.mapIndexed { index, line -> LyricLine(index.toLong(), line) }, synced = false, source)
    }
}
