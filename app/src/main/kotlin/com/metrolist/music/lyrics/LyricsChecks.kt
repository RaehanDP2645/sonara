/**
 * Sonara (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * When each song's lyrics were last looked up (LyricsFreshness), in a small preferences file of
 * its own, so the lyrics table keeps its schema. Kept to the songs played most recently.
 */
object LyricsChecks {
    private const val FILE = "lyrics_checks"
    private const val KEEP = 5_000
    private const val TRIM_TO = 4_000

    // Off the main thread: the file is read from disk the first time.
    suspend fun lastCheck(
        context: Context,
        songId: String,
    ): Long = withContext(Dispatchers.IO) { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getLong(songId, 0L) }

    suspend fun markChecked(
        context: Context,
        songId: String,
        now: Long = System.currentTimeMillis(),
    ) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs.edit { putLong(songId, now) }
        val all = prefs.all
        if (all.size > KEEP) {
            val oldest =
                all.entries
                    .sortedBy { (it.value as? Long) ?: 0L }
                    .take(all.size - TRIM_TO)
                    .map { it.key }
            prefs.edit { oldest.forEach(::remove) }
        }
    }
}
