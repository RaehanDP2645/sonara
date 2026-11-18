/**
 * SONARA (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import java.io.ByteArrayOutputStream
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import com.metrolist.music.ui.component.RemovedCover
import android.content.Context
import android.net.Uri
import com.metrolist.innertube.YouTube
import com.metrolist.music.R
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.ui.component.ErToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.time.LocalDateTime

/**
 * Saves title, description, visibility and an optional cover change on YouTube first; SONARA
 * only shows what YouTube accepted, and the toast says so. Nothing is changed locally ahead of
 * the account: a refused cover stays the old one. Returns true when everything was accepted
 * (the editor then closes); on false it stays open with the user's input.
 *
 * A playlist that exists only in SONARA (no browseId) is saved locally.
 */
suspend fun savePlaylistEdits(
    context: Context,
    database: MusicDatabase,
    playlist: PlaylistEntity,
    title: String,
    description: String,
    privacy: PlaylistPrivacy,
    newCover: Uri?,
): Boolean {
    val removeCover = newCover == RemovedCover
    val result = withContext(Dispatchers.IO) {
        val browseId = playlist.browseId
        if (browseId == null) {
            val localCover = newCover?.takeUnless { removeCover }?.let { copyCover(context, playlist.id, it) }
            val thumbnail = if (removeCover) null else localCover ?: playlist.thumbnailUrl
            database.query { update(playlist.copy(name = title, thumbnailUrl = thumbnail, lastUpdateTime = LocalDateTime.now())) }
            return@withContext SaveResult.SAVED
        }
        // Only what changed is sent: a new cover alone does not resend the title and description.
        val known = YouTubePlaylistEditor.cachedDetails(browseId)
        val sameDetails = known != null && known.description == description && known.privacy == privacy
        if (title != playlist.name || !sameDetails) {
            val edited = YouTubePlaylistEditor.edit(browseId, title, description, privacy)
            // InnerTubeX's rename only covers the title; enough when nothing else changed.
            val renamed = !edited && sameDetails && YouTube.renamePlaylist(browseId, title).isSuccess
            if (!edited && !renamed) return@withContext SaveResult.FAILED
        }
        var thumbnail = playlist.thumbnailUrl
        var coverPending = false
        var needsVerifiedPhone = false
        val coverOk = when {
            removeCover -> YouTubePlaylistEditor.removeCover(browseId).also { if (it) thumbnail = null }
            newCover != null -> {
                // Made small first, sent once, and shown only once YouTube shows it.
                val jpeg = compressCover(context, newCover)
                val change = jpeg?.let { YouTubePlaylistEditor.replaceCover(browseId, it) }
                if (change?.accepted == true) {
                    change.url?.let { thumbnail = it } ?: run { coverPending = true }
                } else {
                    needsVerifiedPhone = change?.needsVerifiedPhone == true
                    Timber.tag("SonaraWeb").w("cover not accepted by YouTube (${jpeg?.size} bytes)")
                }
                change?.accepted == true
            }
            else -> true
        }
        database.query { update(playlist.copy(name = title, thumbnailUrl = thumbnail, lastUpdateTime = LocalDateTime.now())) }
        val customCover = when {
            removeCover && coverOk -> false
            newCover != null && coverOk -> true
            else -> known?.customCover ?: false
        }
        YouTubePlaylistEditor.rememberDetails(browseId, PlaylistDetails(description, privacy, customCover))
        when {
            !coverOk && needsVerifiedPhone -> SaveResult.COVER_NEEDS_VERIFIED_PHONE
            !coverOk -> SaveResult.COVER_FAILED
            coverPending -> SaveResult.COVER_PENDING
            else -> SaveResult.SAVED
        }
    }
    // The playlist's page, wherever the edit was opened from (its header, its menu, the library),
    // reloads to show what YouTube now has.
    if (result != SaveResult.FAILED) PlaylistEditEvents.saved.tryEmit(SavedPlaylistEdit(playlist.id, description))
    withContext(Dispatchers.Main) {
        ErToast.show(
            context,
            when (result) {
                SaveResult.SAVED -> R.string.er_playlist_saved
                SaveResult.COVER_PENDING -> R.string.er_playlist_cover_pending
                SaveResult.COVER_NEEDS_VERIFIED_PHONE -> R.string.er_playlist_cover_verify_phone
                SaveResult.COVER_FAILED -> R.string.er_playlist_cover_failed
                SaveResult.FAILED -> R.string.er_playlist_save_failed
            },
        )
    }
    return result == SaveResult.SAVED || result == SaveResult.COVER_PENDING
}

private enum class SaveResult { SAVED, COVER_PENDING, COVER_NEEDS_VERIFIED_PHONE, COVER_FAILED, FAILED }

/**
 * The chosen cover made small before it is sent: at most [YouTubePlaylistEditor.COVER_SIDE]
 * pixels on its longest side, JPEG, as the desktop sends it. A full-size photo took too long to
 * upload, and YouTube shows covers smaller than that anyway.
 */
internal fun compressCover(
    context: Context,
    source: Uri,
): ByteArray? =
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longest = maxOf(bounds.outWidth, bounds.outHeight).takeIf { it > 0 } ?: return null
        var sample = 1
        while (longest / (sample * 2) >= YouTubePlaylistEditor.COVER_SIDE) sample *= 2
        val decoded =
            context.contentResolver.openInputStream(source)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return null
        val scale = YouTubePlaylistEditor.COVER_SIDE.toFloat() / maxOf(decoded.width, decoded.height)
        val sized =
            if (scale < 1f) {
                Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
            } else {
                decoded
            }
        ByteArrayOutputStream().use { out ->
            sized.compress(Bitmap.CompressFormat.JPEG, YouTubePlaylistEditor.COVER_QUALITY, out)
            out.toByteArray()
        }
    }.getOrNull()

/**
 * Deletes a playlist on YouTube first, then in SONARA, like every other account change: a
 * playlist YouTube refused to delete stays (it would only come back with the next sync), and
 * the toast says which. One that exists only in SONARA is deleted here at once.
 */
fun deletePlaylistEverywhere(
    context: Context,
    database: MusicDatabase,
    playlist: PlaylistEntity,
) {
    appScope.launch {
        val deleted = playlist.browseId?.let { YouTube.deletePlaylist(it).isSuccess } ?: true
        Timber.tag("SonaraWeb").d("delete playlist ${playlist.browseId}: $deleted")
        if (deleted) {
            database.withTransaction {
                if (playlist.bookmarkedAt != null) update(playlist.toggleLike())
                delete(playlist)
            }
        }
        withContext(Dispatchers.Main) {
            ErToast.show(context, if (deleted) R.string.er_playlist_deleted else R.string.er_playlist_delete_failed)
        }
    }
}

/** A playlist edit YouTube accepted ([playlistId] is SONARA's own id). */
data class SavedPlaylistEdit(
    val playlistId: String,
    val description: String,
)

object PlaylistEditEvents {
    val saved = MutableSharedFlow<SavedPlaylistEdit>(extraBufferCapacity = 8)
}

/** Copies the cropped cover into app storage (the crop output lives in the cache). */
private fun copyCover(
    context: Context,
    playlistId: String,
    source: Uri,
): String? =
    runCatching {
        val dir = File(context.filesDir, "playlist_covers").apply { mkdirs() }
        val file = File(dir, "${playlistId}_${System.currentTimeMillis()}.jpg")
        context.contentResolver.openInputStream(source)?.use { input -> file.outputStream().use { input.copyTo(it) } }
        Uri.fromFile(file).toString()
    }.getOrNull()
