/**
 * Sonara TV's frame: the sign-in until there is an account, then the rail (Home, Search,
 * Library, Settings), the page, the now-playing bar, and over them Now Playing and the idle
 * screen. Every page the phone has (album, artist, playlist, radio, results, all settings) is
 * the phone's own, reached through the same routes (navigationBuilder), so nothing the phone can
 * do is missing here.
 */

package com.metrolist.music.tv

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.metrolist.innertube.utils.parseCookieString
import com.metrolist.music.LocalArtistNameAliases
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalDownloadUtil
import com.metrolist.music.LocalIsOnline
import com.metrolist.music.LocalNavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.LocalSyncUtils
import com.metrolist.music.R
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.playback.DownloadUtil
import com.metrolist.music.playback.PlayerConnection
import com.metrolist.music.ui.component.BottomSheetMenu
import com.metrolist.music.ui.component.BottomSheetPage
import com.metrolist.music.ui.component.ErToastHost
import com.metrolist.music.ui.component.LocalBottomSheetPageState
import com.metrolist.music.ui.component.LocalMenuState
import com.metrolist.music.ui.component.LocalSheetHost
import com.metrolist.music.ui.component.SheetHost
import com.metrolist.music.ui.component.shimmer.ShimmerTheme
import com.metrolist.music.ui.screens.navigationBuilder
import com.metrolist.music.ui.theme.ErMusicTheme
import com.metrolist.music.ui.theme.appBackdrop
import com.metrolist.music.update.AppUpdater
import com.metrolist.music.update.UpdateDialog
import com.metrolist.music.utils.ArtistNameAliases
import com.metrolist.music.utils.NetworkConnectivityObserver
import com.metrolist.music.utils.SyncUtils
import com.metrolist.music.utils.rememberPreference
import com.valentinilk.shimmer.LocalShimmerTheme
import kotlinx.coroutines.delay

/** After this long without the remote while a song plays, the idle screen comes up. */
private const val IDLE_AFTER_MS = 45_000L

private enum class TvTab(val route: String, val label: Int, val iconOff: Int, val iconOn: Int) {
    Home("tv_home", R.string.home, R.drawable.nav_home_off, R.drawable.nav_home_on),
    Search("tv_search", R.string.search, R.drawable.nav_browse_off, R.drawable.nav_browse_on),
    Library("tv_library", R.string.filter_library, R.drawable.nav_library_off, R.drawable.nav_library_on),
    Settings("tv_settings", R.string.settings, R.drawable.settings, R.drawable.settings),
}

@Composable
fun TvRoot(
    playerConnection: PlayerConnection?,
    database: MusicDatabase,
    downloadUtil: DownloadUtil,
    syncUtils: SyncUtils,
    networkObserver: NetworkConnectivityObserver,
) {
    // A TV is always dark: rooms are dim and a bright screen dazzles.
    ErMusicTheme(darkTheme = true) {
        val aliases by ArtistNameAliases.aliases.collectAsStateWithLifecycle()
        val isOnline by networkObserver.networkStatus.collectAsStateWithLifecycle()
        val cookie by rememberPreference(InnerTubeCookieKey, "")
        val signedIn = remember(cookie) { "SAPISID" in parseCookieString(cookie) }
        CompositionLocalProvider(
            LocalDatabase provides database,
            LocalPlayerConnection provides playerConnection,
            LocalDownloadUtil provides downloadUtil,
            LocalSyncUtils provides syncUtils,
            LocalShimmerTheme provides ShimmerTheme,
            LocalArtistNameAliases provides aliases,
            LocalIsOnline provides isOnline,
            LocalPlayerAwareWindowInsets provides WindowInsets(bottom = TvNowPlayingBarHeight),
        ) {
            Box(Modifier.fillMaxSize().appBackdrop()) {
                if (signedIn) TvShell(playerConnection) else TvSignIn()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TvShell(playerConnection: PlayerConnection?) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val activity = context as Activity
    val snackbar = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val metadata by (playerConnection?.mediaMetadata ?: remember { kotlinx.coroutines.flow.MutableStateFlow(null) }).collectAsStateWithLifecycle()
    val playing by (playerConnection?.isPlaying ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsStateWithLifecycle()

    var nowPlaying by remember { mutableStateOf(false) }
    var idle by remember { mutableStateOf(false) }
    var lastKey by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // A song picked again (or OK on the bar) opens Now Playing, as the phone opens its player.
    LaunchedEffect(playerConnection) {
        playerConnection?.openPlayerRequests?.collect { nowPlaying = true }
    }
    LaunchedEffect(playing, lastKey) {
        if (!playing) return@LaunchedEffect
        delay(IDLE_AFTER_MS)
        idle = true
    }
    LaunchedEffect(Unit) { AppUpdater.checkAtStart(context) }

    CompositionLocalProvider(LocalNavController provides navController) {
        Box(
            Modifier
                .fillMaxSize()
                .onPreviewKeyEvent {
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
            Row(Modifier.fillMaxSize()) {
                TvRail(route) { tab ->
                    navController.navigate(tab.route) {
                        popUpTo(TvTab.Home.route) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    NavHost(navController, startDestination = TvTab.Home.route) {
                        composable(TvTab.Home.route) { TvHome() }
                        composable(TvTab.Search.route) { TvSearch() }
                        composable(TvTab.Library.route) { TvLibrary() }
                        composable(TvTab.Settings.route) { TvSettings() }
                        navigationBuilder(navController, scrollBehavior, activity, snackbar)
                    }
                }
            }
            metadata?.let { song ->
                TvNowPlayingBar(
                    metadata = song,
                    playing = playing,
                    onOpen = { nowPlaying = true },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }

            BottomSheetMenu(state = LocalMenuState.current, modifier = Modifier.align(Alignment.BottomCenter))
            SheetHost(LocalSheetHost.current)
            BottomSheetPage(state = LocalBottomSheetPageState.current, modifier = Modifier.align(Alignment.BottomCenter))
            ErToastHost(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))

            if (nowPlaying && metadata != null && playerConnection != null) {
                BackHandler { nowPlaying = false }
                TvNowPlaying(playerConnection, onClose = { nowPlaying = false })
            }
            if (idle && metadata != null && playerConnection != null) {
                TvIdle(playerConnection)
            }

            val updatePrompt by AppUpdater.prompt.collectAsStateWithLifecycle()
            updatePrompt?.let { release -> UpdateDialog(release) { AppUpdater.dismissPrompt() } }
        }
    }
}

/** The rail: Home, Search, Library, Settings, as icons with their names under them. */
@Composable
private fun TvRail(
    route: String?,
    onSelect: (TvTab) -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(TvSize.Rail).fillMaxHeight().padding(top = 36.dp),
    ) {
        Icon(painterResource(R.drawable.logo_mark), contentDescription = null, tint = androidx.compose.ui.graphics.Color.Unspecified, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(40.dp))
        TvTab.entries.forEach { tab ->
            val on = route == tab.route
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier =
                    Modifier
                        .padding(vertical = 6.dp)
                        .tvFocusable(shape = RoundedCornerShape(16.dp), scale = 1.08f) { onSelect(tab) }
                        .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else androidx.compose.ui.graphics.Color.Transparent)
                        .padding(horizontal = 10.dp, vertical = 10.dp),
            ) {
                Icon(
                    painterResource(if (on) tab.iconOn else tab.iconOff),
                    contentDescription = null,
                    tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp),
                )
                Text(
                    stringResource(tab.label),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
