/**
 * SONARA (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.os.SystemClock
import android.os.Looper
import android.os.Handler
import com.metrolist.music.ui.utils.resize
import com.metrolist.music.lyrics.LyricsUtils
import com.google.android.gms.cast.TextTrackStyle
import com.google.android.gms.cast.MediaTrack
import android.util.Base64
import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import androidx.core.content.getSystemService
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.extensions.metadata
import com.metrolist.music.utils.InnerTubeXPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.math.abs
import com.google.android.gms.cast.MediaMetadata as CastMetadata

private const val LYRICS_TRACK_ID = 1L
private const val TV_ART_PX = 1200

/**
 * Synced lyrics (LRC) as WebVTT cues, each line's cue made by [cue] (the line itself, or with its
 * Latin letters and translation, as the phone shows them); null when the lyrics have no timing.
 */
internal fun toWebVtt(
    lrc: String,
    cue: (String) -> String = { it },
): String? {
    val lines = LyricsUtils.parseLyrics(lrc).filter { it.text.isNotBlank() }
    if (lines.size < 2 || lines.all { it.time == 0L }) return null
    fun stamp(ms: Long) = "%02d:%02d:%02d.%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
    return buildString {
        append("WEBVTT\n\n")
        lines.forEachIndexed { i, line ->
            val end = lines.getOrNull(i + 1)?.time ?: (line.time + 5_000)
            append(stamp(line.time)).append(" --> ").append(stamp(maxOf(end, line.time + 500))).append('\n')
            // A cue ends at its first blank line, so its own lines never contain one.
            val text = cue(line.text.trim()).lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
            append(text.ifEmpty { line.text.trim() }).append("\n\n")
        }
    }
}

/** Default Media Receiver; plays a plain stream URL with metadata, no custom receiver needed. */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            // The app's own MediaSession and notification stay in charge; Cast adds none.
            .setCastMediaOptions(CastMediaOptions.Builder().setNotificationOptions(null).setMediaSessionEnabled(false).build())
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

data class CastState(
    val available: Boolean = false,
    val casting: Boolean = false,
    val deviceName: String? = null,
    val error: String? = null,
)

/**
 * Experimental Google Cast. While a Cast device is connected the phone keeps playing the queue
 * muted, and the TV mirrors it: the current track is resolved to a stream URL and loaded on the
 * Default Media Receiver, then play/pause, seeks and track changes follow the phone. Queue, skip,
 * like and lyrics therefore keep working unchanged.
 *
 * The two only stay together if Sonara keeps checking, so besides following the phone's events it
 * reads the TV's status: a song repeated on the phone starts again on the TV, a TV that stopped on
 * an error gets a fresh stream where the phone is, a TV that drifted is moved back, and pause or
 * play pressed on the TV (or in Google Home) reaches the phone. A dropped connection waits for the
 * TV to come back instead of ending the cast. Crossfade stays off while casting (MusicService).
 *
 * Known limits of this trial: the stream is fetched twice (phone and TV), downloads are streamed
 * rather than sent from the phone, and some streams may be refused by the TV because YouTube
 * expects headers the receiver cannot send.
 */
object SonaraCast {
    private val _state = MutableStateFlow(CastState())
    val state: StateFlow<CastState> = _state.asStateFlow()

    /** Multiplier for the phone's own volume: 0 while casting. */
    val localVolume = MutableStateFlow(1f)

    private var castContext: CastContext? = null
    private var player: Player? = null
    private var scope: CoroutineScope? = null
    private var appContext: Context? = null
    private var loadJob: Job? = null
    private var syncJob: Job? = null
    private var lyricsFor: (suspend (String) -> String?)? = null

    /** The song on the TV and what was sent for it, so a repeat reloads without a new lookup. */
    private var loadedId: String? = null
    private var loadedInfo: MediaInfo? = null
    private var loadingId: String? = null

    /** Failed loads in a row for [failedId]; after [MAX_FAILURES] that song is left alone. */
    private var failedId: String? = null
    private var failures = 0

    /** When Sonara last told the TV something; the TV's status settles for a moment after. */
    private var lastCommandAt = 0L
    private var client: RemoteMediaClient? = null

    fun context(context: Context): CastContext? {
        castContext?.let { return it }
        return runCatching { CastContext.getSharedInstance(context.applicationContext) }
            .onFailure { Timber.w(it, "Cast unavailable") }
            .getOrNull()
            ?.also {
                castContext = it
                _state.value = _state.value.copy(available = true)
            }
    }

    /** [players] follows the service's current player, which is replaced during crossfades. */
    fun attach(
        context: Context,
        players: StateFlow<Player?>,
        scope: CoroutineScope,
        // The song's lyrics as WebVTT subtitles for the TV, or null.
        lyrics: suspend (String) -> String? = { null },
    ) {
        lyricsFor = lyrics
        val cast = context(context) ?: return
        this.appContext = context.applicationContext
        this.scope = scope
        scope.launch {
            players.collect { next ->
                player?.removeListener(playerListener)
                player = next
                next?.addListener(playerListener)
                // A player that took over (a crossfade) may already be on another song.
                if (session != null) loadCurrent()
            }
        }
        cast.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
        cast.sessionManager.currentCastSession?.takeIf { it.isConnected }?.let { onConnected(it, resumed = true) }
    }

    fun detach() {
        player?.removeListener(playerListener)
        castContext?.sessionManager?.removeSessionManagerListener(sessionListener, CastSession::class.java)
        client?.unregisterCallback(remoteCallback)
        client = null
        syncJob?.cancel()
        player = null
        scope = null
    }

    /**
     * Steps the Cast device's volume; true when a Cast session took it. Each step used to start
     * from the volume the TV last reported, which only changes once the TV answers, so quick or
     * held presses sent the same value again and again: the volume stuck, then jumped. Steps now
     * add up here and the latest value goes out at most every [VOLUME_SEND_INTERVAL_MS].
     */
    fun stepVolume(up: Boolean): Boolean {
        val s = session ?: return false
        val now = SystemClock.uptimeMillis()
        val base = targetVolume?.takeIf { now - lastVolumeStepAt < VOLUME_TARGET_KEEP_MS } ?: s.volume
        targetVolume = (base + if (up) VOLUME_STEP else -VOLUME_STEP).coerceIn(0.0, 1.0)
        lastVolumeStepAt = now
        volumeHandler.removeCallbacks(sendVolume)
        val wait = VOLUME_SEND_INTERVAL_MS - (now - lastVolumeSentAt)
        if (wait <= 0) sendVolume.run() else volumeHandler.postDelayed(sendVolume, wait)
        return true
    }

    private var targetVolume: Double? = null
    private var lastVolumeStepAt = 0L
    private var lastVolumeSentAt = 0L
    private val volumeHandler by lazy { Handler(Looper.getMainLooper()) }
    private val sendVolume = Runnable {
        val volume = targetVolume ?: return@Runnable
        runCatching { session?.volume = volume }
        lastVolumeSentAt = SystemClock.uptimeMillis()
    }

    private const val VOLUME_STEP = 0.05
    private const val VOLUME_SEND_INTERVAL_MS = 150L

    /** After this long without a press, the next one starts again from the TV's own volume. */
    private const val VOLUME_TARGET_KEEP_MS = 1500L

    /** How often the TV is checked against the phone. */
    private const val SYNC_EVERY_MS = 4_000L

    /** The TV's status is ignored this long after Sonara told it something. */
    private const val SETTLE_MS = 3_000L

    /** The TV is moved back when it is this far from the phone. */
    private const val DRIFT_MS = 6_000L

    private const val MAX_FAILURES = 3

    fun stopCasting() {
        castContext?.sessionManager?.endCurrentSession(true)
    }

    private val session get() = castContext?.sessionManager?.currentCastSession?.takeIf { it.isConnected }
    private val remote get() = session?.remoteMediaClient

    private fun settled() = SystemClock.uptimeMillis() - lastCommandAt > SETTLE_MS

    private fun sent() {
        lastCommandAt = SystemClock.uptimeMillis()
    }

    /** The phone's song, when the player is on one. */
    private fun phoneSong(): String? = player?.currentMediaItem?.metadata?.id

    private fun onConnected(
        session: CastSession,
        resumed: Boolean,
    ) {
        _state.value = _state.value.copy(casting = true, deviceName = session.castDevice?.friendlyName, error = null)
        localVolume.value = 0f
        client?.unregisterCallback(remoteCallback)
        client = session.remoteMediaClient?.also { it.registerCallback(remoteCallback) }
        // Back after a dropped connection: the TV may still be playing what it had.
        val stillOurs = resumed && loadedInfo != null && client?.mediaInfo?.contentId == loadedInfo?.contentId
        if (!stillOurs) {
            loadedId = null
            loadedInfo = null
        }
        loadingId = null
        failures = 0
        loadCurrent()
        syncJob?.cancel()
        syncJob =
            scope?.launch {
                while (true) {
                    delay(SYNC_EVERY_MS)
                    sync()
                }
            }
    }

    private fun onDisconnected() {
        loadJob?.cancel()
        syncJob?.cancel()
        client?.unregisterCallback(remoteCallback)
        client = null
        loadedId = null
        loadedInfo = null
        loadingId = null
        localVolume.value = 1f
        _state.value = _state.value.copy(casting = false, deviceName = null)
    }

    /**
     * Puts the phone's song on the TV where the phone is. [again] loads it even when the TV has
     * it already (a repeat, or a TV that stopped); [fresh] looks the stream up again (the old
     * one failed or expired).
     */
    private fun loadCurrent(
        again: Boolean = false,
        fresh: Boolean = false,
    ) {
        val p = player ?: return
        val item: MediaItem = p.currentMediaItem ?: return
        val meta = item.metadata ?: return
        val context = appContext ?: return
        if (!again && (loadedId == meta.id || loadingId == meta.id)) return
        if (failedId == meta.id && failures >= MAX_FAILURES) return
        loadJob?.cancel()
        loadingId = meta.id
        loadJob = scope?.launch {
            val reuse = loadedInfo?.takeIf { !fresh && loadedId == meta.id }
            val info = reuse ?: buildInfo(context, meta) ?: run {
                loadingId = null
                failed(meta.id, null)
                return@launch
            }
            val r = remote ?: run {
                loadingId = null
                return@launch
            }
            // Read now, not when asked: finding the stream takes a moment the phone keeps playing.
            val now = player?.takeIf { phoneSong() == meta.id }
            val request = MediaLoadRequestData.Builder()
                .setMediaInfo(info)
                .setAutoplay(now?.playWhenReady ?: true)
                .setCurrentTime(now?.currentPosition ?: 0L)
                .apply { if (info.mediaTracks?.isNotEmpty() == true) setActiveTrackIds(longArrayOf(LYRICS_TRACK_ID)) }
                .build()
            sent()
            r.load(request).setResultCallback { result ->
                if (loadingId == meta.id) loadingId = null
                if (result.status.isSuccess) {
                    loadedId = meta.id
                    loadedInfo = info
                    if (failedId == meta.id) failures = 0
                    _state.value = _state.value.copy(error = null)
                } else {
                    failed(meta.id, result.status.statusMessage ?: "Cast load failed (${result.status.statusCode})")
                }
            }
        }
    }

    private fun failed(
        id: String,
        message: String?,
    ) {
        if (failedId != id) {
            failedId = id
            failures = 0
        }
        failures++
        loadedId = null
        _state.value = _state.value.copy(error = message ?: _state.value.error ?: "Cast load failed")
    }

    private suspend fun buildInfo(
        context: Context,
        meta: com.metrolist.music.models.MediaMetadata,
    ): MediaInfo? {
        val playback = withContext(Dispatchers.IO) {
            InnerTubeXPlayer.playerResponseForPlayback(
                videoId = meta.id,
                audioQuality = AudioQuality.HIGH,
                connectivityManager = context.getSystemService<ConnectivityManager>()!!,
                allowBoundedRange = false,
            )
        }.getOrElse {
            _state.value = _state.value.copy(error = it.message)
            return null
        }
        val castMeta = CastMetadata(CastMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(CastMetadata.KEY_TITLE, meta.title)
            putString(CastMetadata.KEY_ARTIST, meta.artists.joinToString { it.name })
            meta.album?.title?.let { putString(CastMetadata.KEY_ALBUM_TITLE, it) }
            // Large artwork so it stays sharp on a TV.
            meta.thumbnailUrl?.let { addImage(WebImage(Uri.parse(it.resize(TV_ART_PX, TV_ART_PX)))) }
        }
        // Synced lyrics ride along as a subtitle track. A data: URL avoids serving files from
        // the phone (the receiver page is HTTPS and would block plain HTTP from the LAN).
        val vtt = lyricsFor?.invoke(meta.id)
        return MediaInfo.Builder(playback.streamUrl)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(playback.format.mimeType.substringBefore(";").trim())
            .setMetadata(castMeta)
            .apply {
                if (vtt != null) {
                    setMediaTracks(
                        listOf(
                            MediaTrack.Builder(LYRICS_TRACK_ID, MediaTrack.TYPE_TEXT)
                                .setName("Lyrics")
                                .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                                .setContentId("data:text/vtt;base64," + Base64.encodeToString(vtt.toByteArray(), Base64.NO_WRAP))
                                .setContentType("text/vtt")
                                .setLanguage("und")
                                .build(),
                        ),
                    )
                    setTextTrackStyle(
                        TextTrackStyle().apply {
                            fontScale = 1.3f
                            foregroundColor = 0xFFFFFFFF.toInt()
                            backgroundColor = 0x99000000.toInt()
                            edgeType = TextTrackStyle.EDGE_TYPE_NONE
                        },
                    )
                }
            }.build()
    }

    /** The song started again on the phone (repeat): start it again on the TV too. */
    private fun restartOnTv() {
        val r = remote ?: return
        val status = r.mediaStatus
        if (loadedId == phoneSong() && status != null && status.playerState != MediaStatus.PLAYER_STATE_IDLE) {
            sent()
            r.seek(MediaSeekOptions.Builder().setPosition(player?.currentPosition ?: 0L).build())
            if (player?.playWhenReady == true) r.play()
        } else {
            loadCurrent(again = true)
        }
    }

    /** Brings the TV back to the phone when it went its own way. */
    private fun sync() {
        val p = player ?: return
        val r = remote ?: return
        val id = phoneSong() ?: return
        if (id != loadedId) {
            loadCurrent()
            return
        }
        if (!settled() || loadJob?.isActive == true) return
        val status = r.mediaStatus ?: return
        when (status.playerState) {
            MediaStatus.PLAYER_STATE_IDLE -> {
                if (!p.playWhenReady) return
                when (status.idleReason) {
                    // Ended while the phone has more than a moment of it left: a repeat, or the TV ran ahead.
                    MediaStatus.IDLE_REASON_FINISHED ->
                        if (p.duration <= 0 || p.currentPosition + DRIFT_MS < p.duration) loadCurrent(again = true)
                    MediaStatus.IDLE_REASON_ERROR -> loadCurrent(again = true, fresh = true)
                    else -> loadCurrent(again = true)
                }
            }
            MediaStatus.PLAYER_STATE_PLAYING, MediaStatus.PLAYER_STATE_PAUSED -> {
                val drift = r.approximateStreamPosition - p.currentPosition
                if (abs(drift) > DRIFT_MS) {
                    sent()
                    r.seek(MediaSeekOptions.Builder().setPosition(p.currentPosition).build())
                }
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (session == null) return
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT || mediaItem?.metadata?.id == loadedId) restartOnTv() else loadCurrent()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val r = remote ?: return
            sent()
            val idle = r.mediaStatus?.playerState?.let { it == MediaStatus.PLAYER_STATE_IDLE } ?: true
            when {
                // The TV let the song go (it closes a paused song after a while): load it again.
                playWhenReady && idle -> loadCurrent(again = true)
                playWhenReady -> r.play()
                else -> r.pause()
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason != Player.DISCONTINUITY_REASON_SEEK) return
            if (oldPosition.mediaItemIndex != newPosition.mediaItemIndex) return
            sent()
            remote?.seek(MediaSeekOptions.Builder().setPosition(newPosition.positionMs).build())
        }
    }

    /** Pause and play pressed on the TV or in Google Home reach the phone. */
    private val remoteCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            val p = player ?: return
            val status = remote?.mediaStatus ?: return
            if (!settled() || loadedId != phoneSong()) return
            when (status.playerState) {
                MediaStatus.PLAYER_STATE_PAUSED -> if (p.playWhenReady) p.pause()
                MediaStatus.PLAYER_STATE_PLAYING -> if (!p.playWhenReady) p.play()
                MediaStatus.PLAYER_STATE_IDLE ->
                    if (status.idleReason == MediaStatus.IDLE_REASON_ERROR && p.playWhenReady) loadCurrent(again = true, fresh = true)
            }
        }
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = onConnected(session, resumed = false)
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = onConnected(session, resumed = true)
        override fun onSessionEnded(session: CastSession, error: Int) = onDisconnected()

        // A dropped connection: the phone stays muted and the TV keeps playing until it is back
        // (onSessionResumed) or Cast gives up (onSessionEnded).
        override fun onSessionSuspended(session: CastSession, reason: Int) {
            loadJob?.cancel()
            loadingId = null
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            _state.value = _state.value.copy(error = "Could not connect ($error)")
        }
        override fun onSessionStarting(session: CastSession) = Unit
        override fun onSessionEnding(session: CastSession) = Unit
        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
        override fun onSessionResumeFailed(session: CastSession, error: Int) = onDisconnected()
    }
}
