/**
 * Sonara (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * An album, playlist, Liked songs, an artist or a mood on Sonara TV: the cover and title, Play,
 * Shuffle and Add to home screen, then the songs (or, for an artist and a mood, their shelves).
 */

package com.metrolist.music.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.models.filterExplicit
import com.metrolist.innertube.models.filterVideoSongs
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.constants.SongSortType
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.ui.component.ErToast
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import com.metrolist.music.utils.makeTimeString
import kotlinx.coroutines.flow.first

/** Songs the page reads at most (a big playlist is read in parts up to here). */
private const val MAX_SONGS = 500

private data class Loaded(
    val title: String,
    val subtitle: String?,
    val image: String?,
    val round: Boolean,
    val songs: List<MediaMetadata>,
    val shelves: List<Pair<String, List<YTItem>>> = emptyList(),
    /** What the home screen row opens for this page, when it can go there. */
    val homeLink: TvChannel.Pin? = null,
)

@Composable
fun TvCollection(
    page: TvPage,
    nav: TvNavigator,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val player = LocalPlayerConnection.current
    val loaded by produceState<Loaded?>(null, page) {
        val hideExplicit = context.dataStore.get(HideExplicitKey, false)
        val hideVideos = context.dataStore.get(HideVideoSongsKey, false)
        fun List<SongItem>.allowed() = filterExplicit(hideExplicit).filterVideoSongs(hideVideos).map { it.toMediaMetadata() }
        value =
            when (page) {
                is TvPage.Album ->
                    YouTube.album(page.browseId).getOrNull()?.let { album ->
                        Loaded(
                            album.album.title, album.album.artists?.joinToString { it.name }, album.album.thumbnail, false, album.songs.allowed(),
                            homeLink = TvChannel.Pin("album", page.browseId, album.album.title, album.album.thumbnail),
                        )
                    }
                is TvPage.Playlist ->
                    if (page.browseId != null) {
                        YouTube.playlist(page.browseId).getOrNull()?.let { first ->
                            val songs = first.songs.toMutableList()
                            var next = first.songsContinuation ?: first.continuation
                            while (next != null && songs.size < MAX_SONGS) {
                                val more = YouTube.playlistContinuation(next).getOrNull() ?: break
                                songs += more.songs
                                next = more.continuation
                            }
                            Loaded(
                                first.playlist.title, first.playlist.author?.name, first.playlist.thumbnail, false, songs.take(MAX_SONGS).allowed(),
                                homeLink = TvChannel.Pin("playlist", page.browseId, first.playlist.title, first.playlist.thumbnail),
                            )
                        }
                    } else {
                        val local = page.localId ?: return@produceState
                        val playlist = database.playlist(local).first()
                        Loaded(
                            playlist?.playlist?.name ?: page.title.orEmpty(), null, playlist?.thumbnails?.firstOrNull(), false,
                            database.playlistSongs(local).first().map { it.song.toMediaMetadata() },
                        )
                    }
                TvPage.Liked ->
                    Loaded(context.getString(R.string.liked_songs), null, null, false, database.likedSongs(SongSortType.CREATE_DATE, true).first().map { it.toMediaMetadata() })
                is TvPage.Artist ->
                    YouTube.artist(page.browseId).getOrNull()?.let { artist ->
                        val shelves = artist.sections.map { it.title to it.items.filterExplicit(hideExplicit).filterVideoSongs(hideVideos) }.filter { it.second.isNotEmpty() }
                        val songs = artist.sections.flatMap { it.items }.filterIsInstance<SongItem>().allowed()
                        Loaded(artist.artist.title, artist.monthlyListenerCount ?: artist.subscriberCountText, artist.artist.thumbnail, true, songs, shelves)
                    }
                is TvPage.Mood ->
                    YouTube.browse(page.browseId, page.params).getOrNull()?.let { result ->
                        Loaded(page.title, null, null, false, emptyList(), result.items.map { (it.title ?: page.title) to it.items.filterExplicit(hideExplicit).filterVideoSongs(hideVideos) }.filter { it.second.isNotEmpty() })
                    }
                TvPage.Settings -> null
            } ?: Loaded("", null, null, false, emptyList())
    }

    val data = loaded
    if (data == null) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (data.title.isEmpty() && data.songs.isEmpty() && data.shelves.isEmpty()) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.tv_couldnt_load), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    fun play(
        start: Int,
        shuffle: Boolean = false,
    ) {
        if (data.songs.isEmpty()) return
        val songs = if (shuffle) data.songs.shuffled() else data.songs
        player?.playQueue(ListQueue(data.title, songs.map { it.toMediaItem() }, if (shuffle) 0 else start))
        nav.nowPlaying = true
    }
    val added = stringResource(R.string.tv_added_home)

    LazyColumn(contentPadding = PaddingValues(bottom = 40.dp), modifier = Modifier.fillMaxSize()) {
        item(key = "header", contentType = "header") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = Tv.SafeX).padding(bottom = 26.dp)) {
                if (data.image != null) {
                    AsyncImage(
                        data.image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(200.dp).clip(if (data.round) CircleShape else RoundedCornerShape(16.dp)),
                    )
                    Spacer(Modifier.width(32.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(data.title, style = MaterialTheme.typography.displaySmall, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    data.subtitle?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                    if (data.songs.isNotEmpty()) {
                        Text(
                            context.resources.getQuantityString(R.plurals.n_song, data.songs.size, data.songs.size),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (data.songs.isNotEmpty()) {
                            TvButton(R.drawable.play, stringResource(R.string.play), primary = true, onClick = { play(0) })
                            TvButton(R.drawable.shuffle, stringResource(R.string.shuffle), onClick = { play(0, shuffle = true) })
                        }
                        data.homeLink?.let { pin ->
                            TvButton(R.drawable.add, stringResource(R.string.tv_add_home), onClick = {
                                TvChannel.pin(context, pin)
                                ErToast.show(context, added)
                            })
                        }
                    }
                }
            }
        }
        data.shelves.forEachIndexed { index, (title, items) ->
            item(key = "shelf:$index", contentType = "shelf") {
                TvShelf(title, items) { item -> TvCard(item, onClick = { nav.act(item, player) }) }
            }
        }
        if (data.shelves.isEmpty()) {
            itemsIndexed(data.songs, key = { index, song -> "${song.id}:$index" }, contentType = { _, _ -> "song" }) { index, song ->
                SongRow(index, song) { play(index) }
            }
        }
    }
}

@Composable
private fun SongRow(
    index: Int,
    song: MediaMetadata,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .padding(horizontal = Tv.SafeX, vertical = 3.dp)
                .fillMaxWidth()
                .tvFocus(shape = RoundedCornerShape(12.dp), grow = 1.015f, onClick = onClick)
                .background(Color.White.copy(alpha = 0.03f))
                .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text("${index + 1}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(40.dp))
        AsyncImage(song.thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artists.joinToString { it.name }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        song.duration.takeIf { it > 0 }?.let {
            Text(makeTimeString(it * 1000L), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
