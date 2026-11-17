/**
 * Sonara (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Sonara TV's frame, as TV music apps do it: the logo while it starts, a welcome with one button,
 * the sign-in, then a top bar (what's playing on the left, Home / Search / Browse / Library in
 * the middle, the account on the right) over the page. Now Playing and the idle screen cover it.
 * On a phone it only says it is made for TVs.
 */

package com.metrolist.music.tv

import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.FocusRequester
import com.metrolist.music.LocalSyncUtils
import com.metrolist.music.utils.SyncUtils
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.innertube.utils.parseCookieString
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.AccountImageUrlKey
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.playback.PlayerConnection
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.ui.theme.ErMusicTheme
import com.metrolist.music.update.AppUpdater
import com.metrolist.music.update.UpdateDialog
import com.metrolist.music.utils.SonaraLinks
import com.metrolist.music.utils.YouTubePlaylistEditor
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.delay

/** After this long without the remote while a song plays, the idle screen comes up. */
private const val IDLE_AFTER_MS = 45_000L
private const val SPLASH_MS = 900L

/** Whether this is a TV (the phone app is Sonara; this one is only for TVs). */
fun Context.isTelevision(): Boolean =
    (getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

@Composable
fun TvRoot(
    playerConnection: PlayerConnection?,
    database: MusicDatabase,
    syncUtils: SyncUtils,
    link: String?,
    onLinkHandled: () -> Unit,
) {
    ErMusicTheme(darkTheme = true) {
        val context = LocalContext.current
        Box(Modifier.fillMaxSize().background(Color(0xFF07100F))) {
            if (!context.isTelevision()) {
                NotATv()
                return@Box
            }
            var splash by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                delay(SPLASH_MS)
                splash = false
            }
            val cookie by rememberPreference(InnerTubeCookieKey, "")
            val signedIn = remember(cookie) { "SAPISID" in parseCookieString(cookie) }
            var started by remember { mutableStateOf(false) }
            CompositionLocalProvider(LocalDatabase provides database, LocalPlayerConnection provides playerConnection, LocalSyncUtils provides syncUtils) {
                AnimatedContent(
                    targetState =
                        when {
                            splash -> 0
                            signedIn -> 3
                            started -> 2
                            else -> 1
                        },
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "tvStart",
                ) { stage ->
                    when (stage) {
                        0 -> Splash()
                        1 -> Welcome(onStart = { started = true })
                        2 -> TvSignIn(onBack = { started = false })
                        else -> TvShell(playerConnection, link, onLinkHandled)
                    }
                }
            }
        }
    }
}

@Composable
private fun Splash() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.tv_banner), contentDescription = null, modifier = Modifier.width(320.dp))
    }
}

/** The first screen: the name, one line, one button. */
@Composable
private fun Welcome(onStart: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(horizontal = Tv.SafeX, vertical = Tv.SafeY),
    ) {
        Image(painterResource(R.drawable.tv_banner), contentDescription = null, modifier = Modifier.width(360.dp))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.tv_welcome_line), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(40.dp))
        val focus = remember { androidx.compose.ui.focus.FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        TvButton(R.drawable.play, stringResource(R.string.tv_get_started), primary = true, onClick = onStart, modifier = Modifier.focusRequester(focus))
    }
}

/** Opened on a phone: Sonara TV is for TVs; the phone app is Sonara. */
@Composable
private fun NotATv() {
    val context = LocalContext.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(32.dp),
    ) {
        Image(painterResource(R.drawable.tv_banner), contentDescription = null, modifier = Modifier.width(260.dp))
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.tv_only_title), style = MaterialTheme.typography.headlineSmall, color = Color.White, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.tv_only_line), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        TvButton(R.drawable.download, stringResource(R.string.tv_get_phone_app), primary = true, onClick = {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, "https://sonara.rashiiddsr.my.id/".toUri())) }
        })
    }
}

@Composable
private fun TvShell(
    playerConnection: PlayerConnection?,
    link: String?,
    onLinkHandled: () -> Unit,
) {
    val context = LocalContext.current
    val nav = remember { TvNavigator() }
    val metadata by (playerConnection?.mediaMetadata ?: remember { kotlinx.coroutines.flow.MutableStateFlow(null) }).collectAsStateWithLifecycle()
    val playing by (playerConnection?.isPlaying ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsStateWithLifecycle()
    var idle by remember { mutableStateOf(false) }
    var lastKey by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(playerConnection) { playerConnection?.openPlayerRequests?.collect { nav.nowPlaying = true } }
    LaunchedEffect(playing, lastKey) {
        if (!playing) return@LaunchedEffect
        delay(IDLE_AFTER_MS)
        idle = true
    }
    LaunchedEffect(Unit) {
        AppUpdater.checkAtStart(context)
        TvChannel.publish(context)
    }
    // A song or playlist opened from the home screen row (sonara://open/?...).
    LaunchedEffect(link, playerConnection) {
        val youTube = link?.let(SonaraLinks::toYouTube) ?: return@LaunchedEffect
        val player = playerConnection ?: return@LaunchedEffect
        val uri = youTube.toUri()
        uri.getQueryParameter("v")?.let {
            player.playQueue(YouTubeQueue(WatchEndpoint(videoId = it)))
            nav.nowPlaying = true
        }
        if (uri.path == "/playlist") YouTubePlaylistEditor.playlistIdFromLink(youTube)?.let { nav.open(TvPage.Playlist(it)) }
        if (uri.path.orEmpty().startsWith("/browse/MPREb")) nav.open(TvPage.Album(uri.lastPathSegment.orEmpty()))
        onLinkHandled()
    }
    BackHandler(enabled = nav.nowPlaying || nav.pages.isNotEmpty() || nav.tab != TvTab.Home) { nav.back() }
    BackHandler(enabled = nav.menuSong != null) { nav.menuSong = null }

    // The page the remote returns to, on the card it had, whenever a screen in front closes; and
    // whenever the remote ends up on nothing at all (it vanished from the screen before).
    val contentFocus = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(true) }
    val front = nav.nowPlaying || idle || nav.menuSong != null
    LaunchedEffect(front, hasFocus, nav.page, nav.tab) {
        if (front && hasFocus) return@LaunchedEffect
        delay(FOCUS_SETTLE_MS)
        if (!front || !hasFocus) runCatching { contentFocus.requestFocus() }
    }

    Box(
        Modifier.fillMaxSize().onFocusChanged { hasFocus = it.hasFocus }.onPreviewKeyEvent {
            lastKey = System.currentTimeMillis()
            if (idle) {
                // The key that wakes the screen does nothing else.
                idle = false
                true
            } else {
                false
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            TopBar(nav, playerConnection)
            Box(Modifier.weight(1f).fillMaxWidth().focusRequester(contentFocus).focusRestorer().focusGroup()) {
                when (val page = nav.page) {
                    null ->
                        when (nav.tab) {
                            TvTab.Home -> TvHome(nav)
                            TvTab.Search -> TvSearch(nav)
                            TvTab.Browse -> TvBrowse(nav)
                            TvTab.Library -> TvLibrary(nav)
                        }
                    TvPage.Settings -> TvSettings()
                    else -> TvCollection(page, nav)
                }
            }
        }
        if (nav.nowPlaying && metadata != null && playerConnection != null) TvNowPlaying(playerConnection, onMenu = { nav.menuSong = it })
        if (idle && metadata != null && playerConnection != null) TvIdle(playerConnection)
        nav.menuSong?.let { song -> TvSongMenu(song, onDismiss = { nav.menuSong = null }) }
        val prompt by AppUpdater.prompt.collectAsStateWithLifecycle()
        prompt?.let { release -> UpdateDialog(release) { AppUpdater.dismissPrompt() } }
    }
}

/** What's playing (left), the tabs (middle), the account (right). */
@Composable
private fun TopBar(
    nav: TvNavigator,
    playerConnection: PlayerConnection?,
) {
    val metadata by (playerConnection?.mediaMetadata ?: remember { kotlinx.coroutines.flow.MutableStateFlow(null) }).collectAsStateWithLifecycle()
    val playing by (playerConnection?.isPlaying ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsStateWithLifecycle()
    val avatar by rememberPreference(AccountImageUrlKey, "")
    Box(Modifier.fillMaxWidth().height(Tv.TopBar + Tv.SafeY).padding(start = Tv.SafeX, end = Tv.SafeX, top = Tv.SafeY - 8.dp)) {
        Box(Modifier.align(Alignment.CenterStart).widthIn(max = 300.dp)) {
            val song = metadata
            if (song == null) {
                Icon(painterResource(R.drawable.logo_mark), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(34.dp))
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .tvFocus(shape = RoundedCornerShape(12.dp), grow = 1.04f) { nav.nowPlaying = true }
                            .background(Color.White.copy(alpha = 0.08f))
                            .padding(6.dp),
                ) {
                    AsyncImage(song.thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(38.dp).clip(RoundedCornerShape(8.dp)))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.widthIn(max = 200.dp)) {
                        Text(song.title, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(song.artists.joinToString { it.name }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(10.dp))
                    Icon(painterResource(if (playing) R.drawable.pause else R.drawable.play), contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(6.dp))
                }
            }
        }
        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvTab.entries.forEach { tab ->
                val on = nav.page == null && nav.tab == tab
                Text(
                    stringResource(
                        when (tab) {
                            TvTab.Home -> R.string.home
                            TvTab.Search -> R.string.search
                            TvTab.Browse -> R.string.er_browse
                            TvTab.Library -> R.string.filter_library
                        },
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (on) Color.Black else MaterialTheme.colorScheme.onSurface,
                    modifier =
                        Modifier
                            .tvFocus(shape = CircleShape, grow = 1.08f) { nav.select(tab) }
                            .background(if (on) Color.White else Color.Transparent)
                            .padding(horizontal = 20.dp, vertical = 9.dp),
                )
            }
        }
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .size(42.dp)
                .tvFocus(shape = CircleShape, grow = 1.1f) { nav.open(TvPage.Settings) }
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center,
        ) {
            if (avatar.isNotBlank()) {
                AsyncImage(avatar, contentDescription = stringResource(R.string.settings), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(CircleShape))
            } else {
                Icon(painterResource(R.drawable.settings), contentDescription = stringResource(R.string.settings), tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** A moment for a closing screen to go before the remote is put back on the page. */
private const val FOCUS_SETTLE_MS = 120L
