/**
 * Sonara (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Sonara TV's window. The player, library, lyrics and settings are the phone app's own (one
 * code base, one MusicService); this activity only binds to the service as MainActivity does and
 * shows the remote-first screens of tv/.
 */

package com.metrolist.music.tv

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.playback.DownloadUtil
import com.metrolist.music.playback.MusicService
import com.metrolist.music.playback.MusicService.MusicBinder
import com.metrolist.music.playback.PlayerConnection
import com.metrolist.music.utils.NetworkConnectivityObserver
import com.metrolist.music.utils.SyncUtils
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class TvActivity : FragmentActivity() {
    @Inject
    lateinit var database: MusicDatabase

    @Inject
    lateinit var downloadUtil: DownloadUtil

    @Inject
    lateinit var syncUtils: SyncUtils

    @Inject
    lateinit var networkObserver: NetworkConnectivityObserver

    private var playerConnection: PlayerConnection? = null
    private var playerConnectionSnapshot by mutableStateOf<PlayerConnection?>(null)
    private var isServiceBound = false
    private var link by mutableStateOf<String?>(null)

    private val serviceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                service: IBinder?,
            ) {
                if (service is MusicBinder) {
                    playerConnection = PlayerConnection(this@TvActivity, service, database, lifecycleScope)
                    playerConnectionSnapshot = playerConnection
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                playerConnection?.dispose()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        TvDictionary.install(this)
        link = intent?.dataString
        setContent {
            TvRoot(
                playerConnection = playerConnectionSnapshot,
                database = database,
                syncUtils = syncUtils,
                link = link,
                onLinkHandled = { link = null },
            )
        }
    }

    // A song or playlist picked on the home screen row while Sonara TV is open.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        link = intent.dataString
    }

    override fun onStart() {
        super.onStart()
        if (::syncUtils.isInitialized) syncUtils.tryAutoSync()
        if (!MusicService.isRunning) {
            val serviceIntent = Intent(this, MusicService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    ContextCompat.startForegroundService(this, serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } catch (e: IllegalStateException) {
                Timber.w(e, "Failed to start the music service")
            }
        }
        if (!isServiceBound) {
            bindService(Intent(this, MusicService::class.java), serviceConnection, BIND_AUTO_CREATE)
            isServiceBound = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        playerConnection?.dispose()
        playerConnection = null
        playerConnectionSnapshot = null
        if (isServiceBound) {
            runCatching { unbindService(serviceConnection) }
            isServiceBound = false
        }
    }
}
