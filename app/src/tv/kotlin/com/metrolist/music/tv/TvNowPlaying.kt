/**
 * Sonara (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Now Playing on Sonara TV, as TV music apps lay it out: the cover's colours behind everything,
 * the cover, the song and the controls on the left, Up next or the lyrics on the right. The idle
 * screen is the spinning record with the song, and the lyrics when it has them.
 */

package com.metrolist.music.tv

import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import com.metrolist.music.R
import com.metrolist.music.extensions.metadata
import com.metrolist.music.playback.PlayerConnection
import com.metrolist.music.ui.player.ArtworkBackground
import com.metrolist.music.ui.player.SpinningRecord
import com.metrolist.music.ui.player.rememberPlayerArtwork
import com.metrolist.music.utils.makeTimeString
import kotlinx.coroutines.delay

/** Left and right on the time bar move this far. */
private const val SEEK_MS = 10_000L

private enum class Side { UpNext, Lyrics }

@Composable
fun TvNowPlaying(
    playerConnection: PlayerConnection,
    onMenu: (com.metrolist.music.models.MediaMetadata) -> Unit,
) {
    val metadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val song = metadata ?: return
    val playing by playerConnection.isPlaying.collectAsStateWithLifecycle()
    val current by playerConnection.currentSong.collectAsStateWithLifecycle()
    val shuffle by playerConnection.shuffleModeEnabled.collectAsStateWithLifecycle()
    val repeat by playerConnection.repeatMode.collectAsStateWithLifecycle()
    var side by remember { mutableStateOf(Side.UpNext) }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }

    // The pages stay behind this screen: the remote must never wander onto them, unseen.
    Box(Modifier.fillMaxSize().background(Color.Black).focusProperties { onExit = { cancelFocusChange() } }.focusGroup()) {
        ArtworkBackground(artwork = rememberPlayerArtwork(song.thumbnailUrl), pureBlack = true)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.25f), Color.Black.copy(alpha = 0.7f)))))
        Row(Modifier.fillMaxSize().padding(horizontal = Tv.SafeX, vertical = Tv.SafeY + 8.dp)) {
            Column(Modifier.weight(0.55f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom) {
                AsyncImage(song.thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(250.dp).clip(RoundedCornerShape(18.dp)))
                Spacer(Modifier.height(26.dp))
                Text(song.title, style = MaterialTheme.typography.displaySmall, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(song.artists.joinToString { it.name }, song.album?.title).joinToString(" · "),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(22.dp))
                TimeBar(playerConnection)
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TvButton(if (shuffle) R.drawable.shuffle_on else R.drawable.shuffle, null, selected = shuffle, onClick = { playerConnection.player.shuffleModeEnabled = !shuffle })
                    TvButton(R.drawable.skip_previous, null, onClick = playerConnection::seekToPrevious)
                    TvButton(if (playing) R.drawable.pause else R.drawable.play, null, primary = true, onClick = playerConnection::togglePlayPause, modifier = Modifier.focusRequester(playFocus))
                    TvButton(R.drawable.skip_next, null, onClick = playerConnection::seekToNext)
                    TvButton(
                        if (repeat == Player.REPEAT_MODE_ONE) R.drawable.repeat_one else R.drawable.repeat,
                        null,
                        selected = repeat != Player.REPEAT_MODE_OFF,
                        onClick = { playerConnection.player.repeatMode = (repeat + 1) % 3 },
                    )
                    Spacer(Modifier.width(12.dp))
                    TvButton(if (current?.song?.liked == true) R.drawable.favorite else R.drawable.favorite_border, null, selected = current?.song?.liked == true, onClick = playerConnection::toggleLike)
                    TvButton(R.drawable.radio, null, onClick = playerConnection::startRadioSeamlessly)
                    SleepButton(playerConnection)
                    TvButton(R.drawable.playlist_add, null, onClick = { onMenu(song) })
                }
            }
            Spacer(Modifier.width(48.dp))
            Column(Modifier.weight(0.45f).fillMaxHeight()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvButton(R.drawable.queue_music, stringResource(R.string.tv_up_next), selected = side == Side.UpNext, onClick = { side = Side.UpNext })
                    TvButton(R.drawable.lyrics, stringResource(R.string.lyrics), selected = side == Side.Lyrics, onClick = { side = Side.Lyrics })
                }
                Spacer(Modifier.height(16.dp))
                when (side) {
                    Side.UpNext -> UpNext(playerConnection)
                    Side.Lyrics -> TvLyrics(playerConnection, Modifier.fillMaxSize(), size = 28f)
                }
            }
        }
    }
}

/** The time bar: left and right move 10 seconds while it has the remote. */
@Composable
private fun TimeBar(playerConnection: PlayerConnection) {
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(playerConnection) {
        while (true) {
            position = playerConnection.currentPosition.coerceAtLeast(0)
            duration = playerConnection.duration.coerceAtLeast(0)
            delay(500)
        }
    }
    val share = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Column(
        Modifier
            .fillMaxWidth(0.92f)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { playerConnection.seekTo((position - SEEK_MS).coerceAtLeast(0)); true }
                    Key.DirectionRight -> { playerConnection.seekTo((position + SEEK_MS).coerceAtMost(duration)); true }
                    else -> false
                }
            }.focusable(),
    ) {
        Box(Modifier.fillMaxWidth().height(if (focused) 8.dp else 4.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.22f))) {
            Box(Modifier.fillMaxWidth(share).fillMaxHeight().background(if (focused) MaterialTheme.colorScheme.primary else Color.White))
        }
        Spacer(Modifier.height(8.dp))
        Row {
            Text(makeTimeString(position), style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.7f))
            Spacer(Modifier.weight(1f))
            Text(makeTimeString(duration), style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.7f))
        }
    }
}

/** Sleep timer: OK steps through 15, 30 and 60 minutes, then off. */
@Composable
private fun SleepButton(playerConnection: PlayerConnection) {
    val timer = playerConnection.service.sleepTimer ?: return
    var minutes by remember { mutableIntStateOf(0) }
    TvButton(R.drawable.bedtime, if (timer.isActive && minutes > 0) stringResource(R.string.tv_minutes, minutes) else null, selected = timer.isActive, onClick = {
        val next = listOf(15, 30, 60).firstOrNull { it > minutes } ?: 0
        if (next == 0) timer.clear() else timer.start(next)
        minutes = next
    })
}

/** What plays next; OK jumps to it. */
@Composable
private fun UpNext(playerConnection: PlayerConnection) {
    val windows by playerConnection.queueWindows.collectAsStateWithLifecycle()
    val index by playerConnection.currentWindowIndex.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    LaunchedEffect(index) { if (index >= 0) list.scrollToItem(index) }
    LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
        itemsIndexed(windows, key = { i, window -> "${window.uid}:$i" }, contentType = { _, _ -> "queue" }) { i, window ->
            val item = window.mediaItem.metadata ?: return@itemsIndexed
            val now = i == index
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .tvFocus(shape = RoundedCornerShape(12.dp), grow = 1.02f) {
                            playerConnection.player.seekToDefaultPosition(window.firstPeriodIndex)
                            playerConnection.player.playWhenReady = true
                        }.background(if (now) Color.White.copy(alpha = 0.14f) else Color.Transparent)
                        .padding(8.dp),
            ) {
                AsyncImage(item.thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(item.artists.joinToString { it.name }, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * The idle screen: the record spinning with the cover and the song under it, and the lyrics
 * beside it; the record alone, centred, when the song has none. Any key brings the pages back.
 */
@Composable
fun TvIdle(playerConnection: PlayerConnection) {
    val metadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val song = metadata ?: return
    val playing by playerConnection.isPlaying.collectAsStateWithLifecycle()
    var hasLyrics by remember(song.id) { mutableStateOf(true) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        ArtworkBackground(artwork = rememberPlayerArtwork(song.thumbnailUrl), pureBlack = true)
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = Tv.SafeX + 16.dp, vertical = Tv.SafeY + 16.dp)) {
            val disc = minOf(maxHeight * 0.7f, if (hasLyrics) maxWidth * 0.34f else maxWidth * 0.5f)
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (hasLyrics) Arrangement.Start else Arrangement.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    SpinningRecord(song.thumbnailUrl, playing = playing, size = disc)
                    Spacer(Modifier.height(24.dp))
                    Text(song.title, style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.width(disc))
                    Text(song.artists.joinToString { it.name }, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.width(disc))
                }
                if (hasLyrics) {
                    Spacer(Modifier.width(64.dp))
                    TvLyrics(playerConnection, Modifier.weight(1f).fillMaxHeight(), size = 34f, onNone = { LaunchedEffect(Unit) { hasLyrics = false } })
                }
            }
        }
    }
}
