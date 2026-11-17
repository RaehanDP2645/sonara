/**
 * Sonara (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * What the remote can do with one song: hold OK on it in any list, or open it from Now Playing.
 * Liking and adding to a playlist reach the account the same way the phone does; a TV makes no
 * playlists of its own and downloads nothing, so those are not offered here.
 */

package com.metrolist.music.tv

import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.LocalSyncUtils
import com.metrolist.music.R
import com.metrolist.music.constants.PlaylistSortType
import com.metrolist.music.db.entities.Playlist
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.ui.component.ErToast
import com.metrolist.innertube.models.WatchEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The song the menu is open for, or null. Kept by the page that shows a list. */
@Composable
fun TvSongMenu(
    song: MediaMetadata,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val syncUtils = LocalSyncUtils.current
    val player = LocalPlayerConnection.current
    val scope = rememberCoroutineScope()
    val library by database.song(song.id).collectAsStateWithLifecycle(initialValue = null)
    val liked = library?.song?.liked == true
    var picking by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(picking) { runCatching { first.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(620.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    song.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        song.artists.joinToString { it.name },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(22.dp))

            if (!picking) {
                MenuRow(
                    icon = if (liked) R.drawable.favorite else R.drawable.favorite_border,
                    text = stringResource(if (liked) R.string.liked else R.string.like),
                    modifier = Modifier.focusRequester(first),
                ) {
                    scope.launch(Dispatchers.IO) { toggleLike(database, syncUtils, song, library?.song) }
                }
                MenuRow(R.drawable.playlist_add, stringResource(R.string.add_to_playlist)) { picking = true }
                MenuRow(R.drawable.playlist_play, stringResource(R.string.play_next)) {
                    player?.playNext(song.toMediaItem())
                    onDismiss()
                }
                MenuRow(R.drawable.queue_music, stringResource(R.string.add_to_queue)) {
                    player?.addToQueue(song.toMediaItem())
                    onDismiss()
                }
                MenuRow(R.drawable.radio, stringResource(R.string.start_radio)) {
                    player?.playQueue(YouTubeQueue(WatchEndpoint(videoId = song.id), song))
                    onDismiss()
                }
            } else {
                val playlists by produceState(emptyList<Playlist>()) {
                    database.playlists(PlaylistSortType.CREATE_DATE, true).collect { value = it }
                }
                val editable = playlists.filter { it.playlist.isEditable }
                if (editable.isEmpty()) {
                    Text(
                        stringResource(R.string.tv_no_playlists),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 18.dp),
                    )
                    MenuRow(R.drawable.arrow_back, stringResource(R.string.er_back), modifier = Modifier.focusRequester(first)) { picking = false }
                } else {
                    val added = stringResource(R.string.tv_added_to_playlist)
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(bottom = 4.dp),
                        modifier = Modifier.heightIn(max = 320.dp),
                    ) {
                        items(editable, key = { it.id }, contentType = { "playlist" }) { playlist ->
                            MenuRow(
                                icon = R.drawable.queue_music,
                                text = playlist.playlist.name,
                                modifier = if (playlist == editable.first()) Modifier.focusRequester(first) else Modifier,
                            ) {
                                scope.launch(Dispatchers.IO) {
                                    addToPlaylist(database, syncUtils, playlist, song)
                                }
                                ErToast.show(context, added.format(playlist.playlist.name))
                                onDismiss()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(
    icon: Int,
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .tvFocus(shape = RoundedCornerShape(12.dp), grow = 1.02f, onClick = onClick)
                .background(Color.White.copy(alpha = 0.05f))
                .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(18.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Likes or unlikes [song], and tells YouTube Music, exactly as the phone's menus do. */
private fun toggleLike(
    database: com.metrolist.music.db.MusicDatabase,
    syncUtils: com.metrolist.music.utils.SyncUtils,
    song: MediaMetadata,
    known: SongEntity?,
) {
    database.transaction {
        if (known == null) {
            insert(song, SongEntity::toggleLike)
            syncUtils.likeSong(song.toSongEntity().let(SongEntity::toggleLike))
        } else {
            val updated = known.toggleLike()
            update(updated)
            syncUtils.likeSong(updated)
        }
    }
}

/** Puts [song] in [playlist] here and on YouTube Music. */
private suspend fun addToPlaylist(
    database: com.metrolist.music.db.MusicDatabase,
    syncUtils: com.metrolist.music.utils.SyncUtils,
    playlist: Playlist,
    song: MediaMetadata,
) {
    database.transaction { insert(song) }
    database.addSongsToPlaylist(playlist, listOf(song.id to null))
    playlist.playlist.browseId?.let { browseId ->
        syncUtils.scheduleAddToPlaylist(browseId, playlist.id, listOf(song.id))
    }
}
