/**
 * Sonara (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Sonara TV's pages and what OK does on an item: a song plays (and Now Playing opens), anything
 * else opens its page. Pages stack; Back goes back.
 */

package com.metrolist.music.tv

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.music.db.entities.Album
import com.metrolist.music.db.entities.Artist
import com.metrolist.music.db.entities.Playlist
import com.metrolist.music.db.entities.Song
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.PlayerConnection
import com.metrolist.music.playback.queues.YouTubeQueue

enum class TvTab { Home, Search, Browse, Library }

sealed interface TvPage {
    data class Album(val browseId: String) : TvPage

    /** A YouTube playlist ([browseId]) or one only in the library ([localId]). */
    data class Playlist(val browseId: String?, val localId: String? = null, val title: String? = null) : TvPage

    data object Liked : TvPage

    data class Artist(val browseId: String) : TvPage

    data class Mood(val title: String, val browseId: String, val params: String?) : TvPage

    data object Settings : TvPage
}

@Stable
class TvNavigator {
    var tab by mutableStateOf(TvTab.Home)
    val pages = mutableStateListOf<TvPage>()
    var nowPlaying by mutableStateOf(false)

    val page: TvPage? get() = pages.lastOrNull()

    fun open(page: TvPage) {
        pages.add(page)
    }

    fun select(tab: TvTab) {
        pages.clear()
        this.tab = tab
    }

    /** Back: Now Playing, then the last page, then Home; false when there is nowhere left to go. */
    fun back(): Boolean =
        when {
            nowPlaying -> {
                nowPlaying = false
                true
            }
            pages.isNotEmpty() -> {
                pages.removeAt(pages.lastIndex)
                true
            }
            tab != TvTab.Home -> {
                tab = TvTab.Home
                true
            }
            else -> false
        }

    /** OK on an item: songs play, the rest open their page. */
    fun act(
        item: Any,
        player: PlayerConnection?,
    ) {
        when (item) {
            is SongItem -> {
                player?.playQueue(YouTubeQueue(item.endpoint ?: WatchEndpoint(videoId = item.id), item.toMediaMetadata()))
                nowPlaying = true
            }
            is Song -> {
                player?.playQueue(YouTubeQueue.radio(item.toMediaMetadata()))
                nowPlaying = true
            }
            is AlbumItem -> open(TvPage.Album(item.browseId))
            is Album -> open(TvPage.Album(item.id))
            is ArtistItem -> open(TvPage.Artist(item.id))
            is Artist -> open(TvPage.Artist(item.id))
            is PlaylistItem -> open(TvPage.Playlist(item.id.removePrefix("VL"), title = item.title))
            is Playlist -> open(TvPage.Playlist(item.playlist.browseId, item.id, item.playlist.name))
        }
    }
}
