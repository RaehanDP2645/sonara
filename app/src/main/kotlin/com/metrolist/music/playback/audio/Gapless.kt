/**
 * SONARA (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.audio

/**
 * Gapless playback, the same on the phone, the TV, the desktop and while casting: one song runs
 * straight into the next, with no silence between them.
 *
 * Crossfade never runs inside an album played in its own order: albums are released to play song
 * after song as they are (a live album, a DJ mix, songs that flow into each other), and YouTube
 * doesn't say which ones flow, so every album played in order is treated as one. This used to be a
 * setting ("Disable for gapless albums", on); it is now always so.
 */
object Gapless {
    /** Whether the next song carries the album on: same album, the queue in its own order. */
    fun albumContinues(
        currentAlbum: String?,
        nextAlbum: String?,
        shuffled: Boolean,
    ): Boolean = !shuffled && !currentAlbum.isNullOrBlank() && currentAlbum == nextAlbum
}
