/**
 * Sonara (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Sonara TV's Home, Library and Search. They read the phone's own sources (HomeViewModel, the
 * library in the database, the phone's search results page), so what the phone shows and filters
 * (hide explicit, hide music videos) the TV does too. OK opens or plays like a tap on the phone
 * (rememberItemActions); holding OK opens the item's menu.
 */

package com.metrolist.music.tv

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.innertube.YouTube
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalNavController
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.AlbumSortType
import com.metrolist.music.constants.ArtistSortType
import com.metrolist.music.constants.PlaylistSortType
import com.metrolist.music.constants.SongSortType
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.ui.screens.rememberItemActions
import com.metrolist.music.utils.SearchRoutes
import com.metrolist.music.viewmodels.HomeViewModel
import kotlinx.coroutines.delay

@Composable
fun TvHome() {
    val viewModel: HomeViewModel = hiltViewModel()
    val actions = rememberItemActions()
    val quickPicks by viewModel.quickPicks.collectAsStateWithLifecycle()
    val keepListening by viewModel.keepListening.collectAsStateWithLifecycle()
    val playlists by viewModel.accountPlaylists.collectAsStateWithLifecycle()
    val homePage by viewModel.homePage.collectAsStateWithLifecycle()
    val speedDial by viewModel.speedDialItems.collectAsStateWithLifecycle()

    LazyColumn(contentPadding = PaddingValues(top = 40.dp, bottom = TvNowPlayingBarHeight + 24.dp), modifier = Modifier.fillMaxSize()) {
        item(key = "speed", contentType = "shelf") { CardShelf(stringResource(R.string.speed_dial), speedDial, actions) }
        item(key = "quick", contentType = "shelf") { CardShelf(stringResource(R.string.quick_picks), quickPicks.orEmpty(), actions) }
        item(key = "keep", contentType = "shelf") { CardShelf(stringResource(R.string.keep_listening), keepListening.orEmpty(), actions) }
        item(key = "playlists", contentType = "shelf") { CardShelf(stringResource(R.string.your_youtube_playlists), playlists.orEmpty(), actions) }
        homePage?.sections.orEmpty().forEachIndexed { index, section ->
            item(key = "section:$index:${section.title}", contentType = "shelf") { CardShelf(section.title, section.items, actions) }
        }
    }
}

@Composable
private fun CardShelf(
    title: String,
    items: List<Any>,
    actions: com.metrolist.music.ui.screens.ItemActions,
) {
    TvShelf(title = title, items = items, key = ::tvKey) { item ->
        val look = tvLook(item)
        TvCoverCard(
            title = look.title,
            subtitle = look.subtitle,
            imageUrl = look.image,
            round = look.round,
            onClick = { actions.open(item) },
            onLongClick = { actions.menu(item) },
        )
    }
}

@Composable
fun TvLibrary() {
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current
    val actions = rememberItemActions()
    val liked by produceState(emptyList<com.metrolist.music.db.entities.Song>()) {
        database.likedSongs(SongSortType.CREATE_DATE, true).collect { value = it }
    }
    val playlists by produceState(emptyList<com.metrolist.music.db.entities.Playlist>()) {
        database.playlists(PlaylistSortType.CREATE_DATE, true).collect { value = it }
    }
    val albums by produceState(emptyList<com.metrolist.music.db.entities.Album>()) {
        database.albums(AlbumSortType.CREATE_DATE, true).collect { value = it }
    }
    val artists by produceState(emptyList<com.metrolist.music.db.entities.Artist>()) {
        database.artists(ArtistSortType.CREATE_DATE, true).collect { value = it }
    }
    val likedTitle = stringResource(R.string.liked_songs)

    LazyColumn(contentPadding = PaddingValues(top = 40.dp, bottom = TvNowPlayingBarHeight + 24.dp), modifier = Modifier.fillMaxSize()) {
        item(key = "liked-actions", contentType = "actions") {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(start = TvSize.Gutter, bottom = 20.dp)) {
                TvButton(R.drawable.play, stringResource(R.string.play), primary = true, onClick = {
                    if (liked.isNotEmpty()) playerConnection?.playQueue(ListQueue(likedTitle, liked.map { it.toMediaItem() }))
                })
                TvButton(R.drawable.shuffle, stringResource(R.string.shuffle), onClick = {
                    if (liked.isNotEmpty()) playerConnection?.playQueue(ListQueue(likedTitle, liked.shuffled().map { it.toMediaItem() }))
                })
            }
        }
        item(key = "liked", contentType = "shelf") { CardShelf(likedTitle, liked, actions) }
        item(key = "playlists", contentType = "shelf") { CardShelf(stringResource(R.string.playlists), playlists, actions) }
        item(key = "albums", contentType = "shelf") { CardShelf(stringResource(R.string.albums), albums, actions) }
        item(key = "artists", contentType = "shelf") { CardShelf(stringResource(R.string.artists), artists, actions) }
    }
}

/**
 * Typing with the TV's keyboard or the remote's microphone; the results are the phone's results
 * page (the same ranking and filters). Suggestions come as you type.
 */
@Composable
fun TvSearch() {
    val navController = LocalNavController.current
    var query by remember { mutableStateOf("") }
    fun search(text: String) {
        val q = text.trim()
        if (q.isNotEmpty()) navController.navigate(SearchRoutes.resultRoute(q))
    }
    val voice =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { spoken ->
                    query = spoken
                    search(spoken)
                }
            }
        }
    val suggestions by produceState(emptyList<String>(), query) {
        if (query.isBlank()) {
            value = emptyList()
            return@produceState
        }
        delay(250)
        value = YouTube.searchSuggestions(query).getOrNull()?.queries.orEmpty().take(8)
    }

    Column(Modifier.fillMaxSize().padding(top = 48.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = TvSize.Gutter)) {
            Box(
                Modifier
                    .weight(1f)
                    .border(2.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f), RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), RoundedCornerShape(28.dp))
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                if (query.isEmpty()) {
                    Text(stringResource(R.string.er_search_field_hint), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search(query) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(16.dp))
            TvButton(R.drawable.mic, null, onClick = {
                runCatching {
                    voice.launch(
                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM),
                    )
                }
            })
            Spacer(Modifier.width(12.dp))
            TvButton(R.drawable.search, null, primary = true, onClick = { search(query) })
        }
        Spacer(Modifier.height(28.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = TvSize.Gutter),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(suggestions, key = { it }, contentType = { "suggestion" }) { text ->
                Text(
                    text,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier =
                        Modifier
                            .tvFocusable(shape = CircleShape) { search(text) }
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
    }
}
