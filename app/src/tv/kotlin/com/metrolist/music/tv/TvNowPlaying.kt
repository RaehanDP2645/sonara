/**
 * Sonara (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Now playing on Sonara TV, the bar under the pages, and the idle screen (a spinning record with
 * the cover, and the lyrics). The lyrics are the phone's own (LyricsPage): word by word, line by
 * line or plain, with romanization and the translation as set; when a song has none, the idle
 * screen shows the record alone, centred.
 */

package com.metrolist.music.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import com.metrolist.music.LocalDatabase
import com.metrolist.music.R
import com.metrolist.music.db.entities.LyricsEntity
import com.metrolist.music.extensions.metadata
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.PlayerConnection
import com.metrolist.music.ui.player.ArtworkBackground
import com.metrolist.music.ui.player.LyricsPage
import com.metrolist.music.ui.player.SpinningRecord
import com.metrolist.music.ui.player.rememberPlayerArtwork
import com.metrolist.music.utils.makeTimeString
import com.metrolist.music.viewmodels.LyricsMenuViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val TvNowPlayingBarHeight = 88.dp

/** How far left or right on the time bar moves, in ms. */
private const val SEEK_STEP_MS = 10_000L

/** Lyric offset steps, in ms (the phone sets the same per-song offset). */
private const val OFFSET_STEP_MS = 250

private enum class Panel { Lyrics, Queue }

/** The song playing, at the bottom of every page; OK opens Now Playing. */
@Composable
fun TvNowPlayingBar(
    metadata: MediaMetadata,
    playing: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .padding(start = TvSize.Rail + TvSize.Gutter, end = TvSize.Gutter, bottom = 14.dp)
                .fillMaxWidth()
                .height(TvNowPlayingBarHeight - 14.dp)
                .tvFocusable(shape = RoundedCornerShape(20.dp), scale = 1.02f, onClick = onOpen)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f))
                .padding(horizontal = 12.dp),
    ) {
        AsyncImage(
            model = metadata.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(50.dp).clip(RoundedCornerShape(10.dp)),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(metadata.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
            Text(metadata.artists.joinToString { it.name }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            stringResource(if (playing) R.string.tv_playing else R.string.tv_paused),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
fun TvNowPlaying(
    playerConnection: PlayerConnection,
    onClose: () -> Unit,
) {
    val metadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val song = metadata ?: return
    val playing by playerConnection.isPlaying.collectAsStateWithLifecycle()
    val current by playerConnection.currentSong.collectAsStateWithLifecycle()
    val shuffle by playerConnection.shuffleModeEnabled.collectAsStateWithLifecycle()
    val repeat by playerConnection.repeatMode.collectAsStateWithLifecycle()
    val artwork = rememberPlayerArtwork(song.thumbnailUrl)
    var panel by remember { mutableStateOf(Panel.Lyrics) }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }

    Box(Modifier.fillMaxSize()) {
        ArtworkBackground(artwork = artwork)
        Row(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 48.dp)) {
            Column(Modifier.weight(0.42f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                AsyncImage(
                    model = song.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth(0.72f).aspectRatio(1f).clip(RoundedCornerShape(22.dp)),
                )
                Spacer(Modifier.height(28.dp))
                Text(song.title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(song.artists.joinToString { it.name }, song.album?.title).joinToString(" · "),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(24.dp))
                TvTimeBar(playerConnection)
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    TvButton(R.drawable.skip_previous, null, onClick = playerConnection::seekToPrevious)
                    TvButton(
                        if (playing) R.drawable.pause else R.drawable.play,
                        null,
                        primary = true,
                        onClick = playerConnection::togglePlayPause,
                        modifier = Modifier.focusRequester(playFocus),
                    )
                    TvButton(R.drawable.skip_next, null, onClick = playerConnection::seekToNext)
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton(if (current?.song?.liked == true) R.drawable.favorite else R.drawable.favorite_border, null, selected = current?.song?.liked == true, onClick = playerConnection::toggleLike)
                    TvButton(R.drawable.shuffle, null, selected = shuffle, onClick = { playerConnection.player.shuffleModeEnabled = !shuffle })
                    TvButton(
                        if (repeat == Player.REPEAT_MODE_ONE) R.drawable.repeat_one else R.drawable.repeat,
                        null,
                        selected = repeat != Player.REPEAT_MODE_OFF,
                        onClick = { playerConnection.player.repeatMode = (repeat + 1) % 3 },
                    )
                    TvButton(R.drawable.radio, null, onClick = playerConnection::startRadioSeamlessly)
                    TvSleepButton(playerConnection)
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton(R.drawable.lyrics, stringResource(R.string.lyrics), selected = panel == Panel.Lyrics, onClick = { panel = Panel.Lyrics })
                    TvButton(R.drawable.queue_music, stringResource(R.string.queue), selected = panel == Panel.Queue, onClick = { panel = Panel.Queue })
                }
            }
            Spacer(Modifier.width(48.dp))
            Box(Modifier.weight(0.58f).fillMaxHeight()) {
                when (panel) {
                    Panel.Lyrics -> TvLyricsPanel(playerConnection, song)
                    Panel.Queue -> TvQueuePanel(playerConnection)
                }
            }
        }
    }
}

/** The time bar: left and right move 10 seconds while it has the remote. */
@Composable
private fun TvTimeBar(playerConnection: PlayerConnection) {
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
            .fillMaxWidth(0.9f)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { playerConnection.seekTo((position - SEEK_STEP_MS).coerceAtLeast(0)); true }
                    Key.DirectionRight -> { playerConnection.seekTo((position + SEEK_STEP_MS).coerceAtMost(duration)); true }
                    else -> false
                }
            }.focusable(),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (focused) 8.dp else 5.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)),
        ) {
            Box(Modifier.fillMaxWidth(share).fillMaxHeight().background(if (focused) Color.White else MaterialTheme.colorScheme.primary))
        }
        Spacer(Modifier.height(8.dp))
        Row {
            Text(makeTimeString(position), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(makeTimeString(duration), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Sleep timer: OK steps through 15, 30 and 60 minutes, then off. */
@Composable
private fun TvSleepButton(playerConnection: PlayerConnection) {
    val timer = playerConnection.service.sleepTimer ?: return
    var minutes by remember { mutableStateOf(0) }
    val label = if (timer.isActive && minutes > 0) stringResource(R.string.tv_minutes, minutes) else null
    TvButton(R.drawable.bedtime, label, selected = timer.isActive, onClick = {
        val next = listOf(15, 30, 60).firstOrNull { it > minutes } ?: 0
        if (next == 0) timer.clear() else timer.start(next)
        minutes = next
    })
}

/** The phone's lyrics, with the lyric offset and "search again" under them. */
@Composable
private fun TvLyricsPanel(
    playerConnection: PlayerConnection,
    song: MediaMetadata,
) {
    val database = LocalDatabase.current
    val scope = rememberCoroutineScope()
    val menu: LyricsMenuViewModel = hiltViewModel()
    val lyrics by playerConnection.currentLyrics.collectAsStateWithLifecycle(initialValue = null)
    val current by playerConnection.currentSong.collectAsStateWithLifecycle()
    val offset = current?.song?.lyricsOffset ?: 0
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LyricsPage(mediaMetadata = song, sliderPositionProvider = { null }, modifier = Modifier.fillMaxSize())
        }
        if (lyrics != null && lyrics?.lyrics != LyricsEntity.LYRICS_NOT_FOUND) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                TvButton(R.drawable.remove, null, onClick = {
                    current?.song?.let { entity -> scope.launch { database.query { upsert(entity.copy(lyricsOffset = entity.lyricsOffset - OFFSET_STEP_MS)) } } }
                })
                Text("${if (offset >= 0) "+" else ""}${offset} ms", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TvButton(R.drawable.add, null, onClick = {
                    current?.song?.let { entity -> scope.launch { database.query { upsert(entity.copy(lyricsOffset = entity.lyricsOffset + OFFSET_STEP_MS)) } } }
                })
                Spacer(Modifier.width(12.dp))
                TvButton(R.drawable.refresh, stringResource(R.string.tv_search_lyrics_again), onClick = { menu.refetchLyrics(song, lyrics) })
            }
        }
    }
}

/** The queue; OK plays that song. */
@Composable
private fun TvQueuePanel(playerConnection: PlayerConnection) {
    val windows by playerConnection.queueWindows.collectAsStateWithLifecycle()
    val currentIndex by playerConnection.currentWindowIndex.collectAsStateWithLifecycle()
    val state = rememberLazyListState()
    LaunchedEffect(currentIndex) { if (currentIndex >= 0) state.scrollToItem(currentIndex) }
    LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 12.dp), modifier = Modifier.fillMaxSize()) {
        itemsIndexed(windows, key = { index, window -> "${window.uid}:$index" }, contentType = { _, _ -> "queue" }) { index, window ->
            val item = window.mediaItem.metadata ?: return@itemsIndexed
            val now = index == currentIndex
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .tvFocusable(shape = RoundedCornerShape(14.dp), scale = 1.02f) {
                            playerConnection.player.seekToDefaultPosition(window.firstPeriodIndex)
                            playerConnection.player.playWhenReady = true
                        }.background(if (now) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent)
                        .padding(10.dp),
            ) {
                AsyncImage(model = item.thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (now) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    Text(item.artists.joinToString { it.name }, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * The idle screen: the record spinning with the cover, the song under it, and the lyrics beside
 * it (or the record alone, centred, when the song has none). Any key brings the pages back.
 */
@Composable
fun TvIdle(playerConnection: PlayerConnection) {
    val metadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val song = metadata ?: return
    val playing by playerConnection.isPlaying.collectAsStateWithLifecycle()
    val lyrics by playerConnection.currentLyrics.collectAsStateWithLifecycle(initialValue = null)
    val hasLyrics = lyrics != null && lyrics?.lyrics?.isNotBlank() == true && lyrics?.lyrics != LyricsEntity.LYRICS_NOT_FOUND
    val artwork = rememberPlayerArtwork(song.thumbnailUrl)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        ArtworkBackground(artwork = artwork, pureBlack = true)
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 56.dp)) {
            val disc = minOf(maxHeight * 0.72f, if (hasLyrics) maxWidth * 0.36f else maxWidth * 0.5f)
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (hasLyrics) Arrangement.Start else Arrangement.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    SpinningRecord(song.thumbnailUrl, playing = playing, size = disc)
                    Spacer(Modifier.height(28.dp))
                    Text(song.title, style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.width(disc))
                    Text(song.artists.joinToString { it.name }, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.width(disc))
                }
                if (hasLyrics) {
                    Spacer(Modifier.width(64.dp))
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        LyricsPage(mediaMetadata = song, sliderPositionProvider = { null }, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}
