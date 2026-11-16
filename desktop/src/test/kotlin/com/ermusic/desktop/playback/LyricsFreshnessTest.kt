/**
 * SONARA (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.ermusic.desktop.playback

import com.metrolist.music.lyrics.LyricsFreshness
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When saved lyrics are looked up again, and when new ones replace them (shared with the phone). */
class LyricsFreshnessTest {
    private val ranking = listOf("BetterLyrics", "LrcLib", "KuGou")
    private val timed = "[00:01.00]Hello\n[00:03.00]World"
    private val plain = "Hello\nWorld"
    private val day = LyricsFreshness.CHECK_EVERY_MS
    private val now = 10 * day

    @Test
    fun bestLyricsAndOwnLyricsAreNeverLookedUpAgain() {
        assertFalse(LyricsFreshness.isDue("BetterLyrics", timed, ranking, 0L, now))
        assertFalse(LyricsFreshness.isDue("Manual", plain, ranking, 0L, now))
    }

    @Test
    fun othersAtMostOnceADay() {
        assertTrue(LyricsFreshness.isDue("LrcLib", timed, ranking, 0L, now))
        assertTrue(LyricsFreshness.isDue("BetterLyrics", plain, ranking, now - day, now))
        assertTrue(LyricsFreshness.isDue("", LyricsFreshness.NOT_FOUND, ranking, now - day, now))
        assertFalse(LyricsFreshness.isDue("LrcLib", timed, ranking, now - day + 1, now))
        assertFalse(LyricsFreshness.isDue("", LyricsFreshness.NOT_FOUND, ranking, now - 1000, now))
    }

    @Test
    fun onlyBetterLyricsReplaceSavedOnes() {
        // A higher source, timed over untimed, found over not found, the same source updated.
        assertTrue(LyricsFreshness.isBetter("BetterLyrics", timed, "LrcLib", timed, ranking))
        assertTrue(LyricsFreshness.isBetter("KuGou", timed, "BetterLyrics", plain, ranking))
        assertTrue(LyricsFreshness.isBetter("KuGou", plain, "", LyricsFreshness.NOT_FOUND, ranking))
        assertTrue(LyricsFreshness.isBetter("LrcLib", "[00:01.00]Hello!", "LrcLib", timed, ranking))
        // A lower source, untimed over timed, not found, the same text, the user's own.
        assertFalse(LyricsFreshness.isBetter("KuGou", timed, "LrcLib", timed, ranking))
        assertFalse(LyricsFreshness.isBetter("BetterLyrics", plain, "KuGou", timed, ranking))
        assertFalse(LyricsFreshness.isBetter("", LyricsFreshness.NOT_FOUND, "KuGou", plain, ranking))
        assertFalse(LyricsFreshness.isBetter("LrcLib", timed, "LrcLib", timed, ranking))
        assertFalse(LyricsFreshness.isBetter("BetterLyrics", timed, "Manual", plain, ranking))
    }
}
