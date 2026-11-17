/**
 * Sonara (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Sonara TV's tabs. Home leads with one big pick and then shelves; Search answers as you type or
 * speak; Browse has new releases, charts and moods; Library is the account's. They read the same
 * sources as the phone (HomeViewModel, YouTube Music, the library), with its filters.
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.models.filterExplicit
import com.metrolist.innertube.models.filterVideoSongs
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.AlbumSortType
import com.metrolist.music.constants.ArtistSortType
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.constants.PlaylistSortType
import com.metrolist.music.db.entities.Album
import com.metrolist.music.db.entities.Artist
import com.metrolist.music.db.entities.Playlist
import com.metrolist.music.ui.player.rememberPlayerArtwork
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.viewmodels.HomeViewModel
import kotlinx.coroutines.delay

private val Bottom = PaddingValues(top = 8.dp, bottom = 40.dp)

@Composable
fun TvHome(nav: TvNavigator) {
    val viewModel: HomeViewModel = hiltViewModel()
    val player = LocalPlayerConnection.current
    val quickPicks by viewModel.quickPicks.collectAsStateWithLifecycle()
    val keepListening by viewModel.keepListening.collectAsStateWithLifecycle()
    val playlists by viewModel.accountPlaylists.collectAsStateWithLifecycle()
    val homePage by viewModel.homePage.collectAsStateWithLifecycle()
    val speedDial by viewModel.speedDialItems.collectAsStateWithLifecycle()
    val hero = quickPicks?.firstOrNull() ?: homePage?.sections?.firstOrNull()?.items?.firstOrNull()

    LazyColumn(contentPadding = Bottom, modifier = Modifier.fillMaxSize()) {
        hero?.let { item(key = "hero", contentType = "hero") { Hero(it) { nav.act(it, player) } } }
        item(key = "quick", contentType = "shelf") { Shelf(stringResource(R.string.quick_picks), quickPicks.orEmpty().drop(1), nav) }
        item(key = "speed", contentType = "shelf") { Shelf(stringResource(R.string.speed_dial), speedDial, nav) }
        item(key = "keep", contentType = "shelf") { Shelf(stringResource(R.string.keep_listening), keepListening.orEmpty(), nav) }
        item(key = "playlists", contentType = "shelf") { Shelf(stringResource(R.string.your_youtube_playlists), playlists.orEmpty(), nav) }
        homePage?.sections.orEmpty().forEachIndexed { index, section ->
            item(key = "section:$index", contentType = "shelf") { Shelf(section.title, section.items, nav) }
        }
    }
}

/** The big pick at the top of Home: its cover, washed in its colour, and Play. */
@Composable
private fun Hero(
    item: Any,
    onPlay: () -> Unit,
) {
    val look = tvLook(item)
    val accent = rememberPlayerArtwork(look.image)?.accent ?: MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .padding(horizontal = Tv.SafeX)
                .padding(bottom = 30.dp)
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.55f), Color.Transparent)))
                .padding(26.dp),
    ) {
        AsyncImage(look.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(168.dp).clip(RoundedCornerShape(14.dp)))
        Spacer(Modifier.width(28.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.quick_picks), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(look.title, style = MaterialTheme.typography.displaySmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            look.subtitle?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Spacer(Modifier.height(18.dp))
            TvButton(R.drawable.play, stringResource(R.string.play), primary = true, onClick = onPlay)
        }
    }
}

@Composable
private fun Shelf(
    title: String,
    items: List<Any>,
    nav: TvNavigator,
) {
    val player = LocalPlayerConnection.current
    TvShelf(title, items) { item -> TvCard(item, onClick = { nav.act(item, player) }) }
}

/** The account's filters (hide explicit, hide music videos), as the phone applies them. */
@Composable
private fun rememberFilters(): (List<YTItem>) -> List<YTItem> {
    val hideExplicit by rememberPreference(HideExplicitKey, false)
    val hideVideos by rememberPreference(HideVideoSongsKey, false)
    return remember(hideExplicit, hideVideos) { { items -> items.filterExplicit(hideExplicit).filterVideoSongs(hideVideos) } }
}

/** Typing with the TV's keyboard or the remote's microphone; results come as you type. */
@Composable
fun TvSearch(nav: TvNavigator) {
    var query by remember { mutableStateOf("") }
    var searched by remember { mutableStateOf("") }
    val filter = rememberFilters()
    val voice =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
                    query = it
                    searched = it
                }
            }
        }
    val suggestions by produceState(emptyList<String>(), query) {
        if (query.isBlank() || query == searched) {
            value = emptyList()
            return@produceState
        }
        delay(250)
        value = YouTube.searchSuggestions(query).getOrNull()?.queries.orEmpty().take(6)
    }
    val results by produceState(emptyList<Pair<String, List<YTItem>>>(), searched) {
        value = emptyList()
        if (searched.isBlank()) return@produceState
        value = YouTube.searchSummary(searched).getOrNull()?.summaries.orEmpty()
            .map { it.title to filter(it.items) }
            .filter { it.second.isNotEmpty() }
    }

    LazyColumn(contentPadding = Bottom, modifier = Modifier.fillMaxSize()) {
        item(key = "field", contentType = "field") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = Tv.SafeX).padding(bottom = 18.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .border(2.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f), CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), CircleShape)
                        .padding(horizontal = 26.dp, vertical = 15.dp),
                ) {
                    if (query.isEmpty()) Text(stringResource(R.string.er_search_field_hint), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { searched = query.trim() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(14.dp))
                TvButton(R.drawable.mic, null, onClick = {
                    runCatching {
                        voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
                    }
                })
            }
        }
        if (suggestions.isNotEmpty()) {
            item(key = "suggestions", contentType = "chips") {
                LazyRow(contentPadding = PaddingValues(horizontal = Tv.SafeX), horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 24.dp)) {
                    items(suggestions, key = { it }, contentType = { "chip" }) { text ->
                        Text(
                            text,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier =
                                Modifier
                                    .tvFocus(shape = CircleShape) {
                                        query = text
                                        searched = text
                                    }.background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                    .padding(horizontal = 20.dp, vertical = 11.dp),
                        )
                    }
                }
            }
        }
        results.forEachIndexed { index, (title, items) ->
            item(key = "result:$index:$title", contentType = "shelf") { Shelf(title, items, nav) }
        }
    }
}

/** New releases, charts, and moods and genres to open. */
@Composable
fun TvBrowse(nav: TvNavigator) {
    val filter = rememberFilters()
    val explore by produceState<com.metrolist.innertube.pages.ExplorePage?>(null) { value = YouTube.explore().getOrNull() }
    val charts by produceState(emptyList<Pair<String, List<YTItem>>>()) {
        value = YouTube.getChartsPage().getOrNull()?.sections.orEmpty().map { it.title to filter(it.items) }.filter { it.second.isNotEmpty() }
    }
    LazyColumn(contentPadding = Bottom, modifier = Modifier.fillMaxSize()) {
        item(key = "new", contentType = "shelf") { Shelf(stringResource(R.string.tv_new_releases), explore?.newReleaseAlbums.orEmpty(), nav) }
        charts.forEachIndexed { index, (title, items) -> item(key = "chart:$index", contentType = "shelf") { Shelf(title, items, nav) } }
        explore?.moodAndGenres?.takeIf { it.isNotEmpty() }?.let { moods ->
            item(key = "moods", contentType = "moods") {
                Column(Modifier.padding(bottom = 26.dp)) {
                    TvShelfTitle(stringResource(R.string.tv_moods_genres))
                    LazyRow(contentPadding = PaddingValues(horizontal = Tv.SafeX, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(moods, key = { it.endpoint.browseId + it.title }, contentType = { "mood" }) { mood ->
                            Box(
                                Modifier
                                    .size(width = 200.dp, height = 96.dp)
                                    .tvFocus { nav.open(TvPage.Mood(mood.title, mood.endpoint.browseId, mood.endpoint.params)) }
                                    .background(Color(mood.stripeColor).copy(alpha = 0.85f))
                                    .padding(16.dp),
                                contentAlignment = Alignment.BottomStart,
                            ) {
                                Text(mood.title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 2)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The account's library: liked songs, playlists, albums and artists. */
@Composable
fun TvLibrary(nav: TvNavigator) {
    val database = LocalDatabase.current
    val playlists by produceState(emptyList<Playlist>()) { database.playlists(PlaylistSortType.CREATE_DATE, true).collect { value = it } }
    val albums by produceState(emptyList<Album>()) { database.albums(AlbumSortType.CREATE_DATE, true).collect { value = it } }
    val artists by produceState(emptyList<Artist>()) { database.artists(ArtistSortType.CREATE_DATE, true).collect { value = it } }
    val likedCount by produceState(0) { database.likedSongsCount().collect { value = it } }
    val context = LocalContext.current
    LazyColumn(contentPadding = Bottom, modifier = Modifier.fillMaxSize()) {
        item(key = "liked", contentType = "liked") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .padding(horizontal = Tv.SafeX)
                        .padding(bottom = 30.dp)
                        .tvFocus(shape = RoundedCornerShape(16.dp), grow = 1.03f) { nav.open(TvPage.Liked) }
                        .background(Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), Color.Transparent)))
                        .padding(22.dp),
            ) {
                Box(Modifier.size(84.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(R.drawable.favorite), contentDescription = null, tint = Color.Black, modifier = Modifier.size(40.dp))
                }
                Spacer(Modifier.width(22.dp))
                Column {
                    Text(stringResource(R.string.liked_songs), style = MaterialTheme.typography.headlineSmall, color = Color.White)
                    Text(context.resources.getQuantityString(R.plurals.n_song, likedCount, likedCount), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item(key = "playlists", contentType = "shelf") { Shelf(stringResource(R.string.playlists), playlists, nav) }
        item(key = "albums", contentType = "shelf") { Shelf(stringResource(R.string.albums), albums, nav) }
        item(key = "artists", contentType = "shelf") { Shelf(stringResource(R.string.artists), artists, nav) }
    }
}
