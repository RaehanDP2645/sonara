/**
 * Sonara (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Requested by the owner: saved lyrics are looked up again, at most once a day per song, when
 * they may not be the best there is: from a source further down the list, not timed line by
 * line, or not found at all. Lyrics from the first source, timed, are kept for good, and so are
 * lyrics the user wrote or edited. The new lyrics replace the saved ones only when they are
 * better: timed beats untimed, then a source higher in the list wins; the same source with a
 * changed text is an update and replaces them too. Shared with the desktop app.
 */

package com.metrolist.music.lyrics

object LyricsFreshness {
    /** At most one look-up a day per song. */
    const val CHECK_EVERY_MS = 24L * 60 * 60 * 1000

    /** What a song with no lyrics at any source is saved as. */
    const val NOT_FOUND = "LYRICS_NOT_FOUND"

    /** Lyrics typed or edited by the user: never replaced. */
    private val OWN = setOf("Manual")

    private val timestamp = Regex("""^\s*\[\d{1,3}:\d{2}""", RegexOption.MULTILINE)

    fun isTimed(text: String): Boolean = timestamp.containsMatchIn(text)

    /**
     * Whether saved lyrics ([text] from [provider]) should be looked up again now: never for the
     * best kind ([ranking]'s first source, timed) or the user's own; otherwise once
     * [CHECK_EVERY_MS] has passed since [lastCheck] (0: never checked).
     */
    fun isDue(
        provider: String,
        text: String,
        ranking: List<String>,
        lastCheck: Long,
        now: Long,
    ): Boolean {
        if (provider in OWN) return false
        val best = text != NOT_FOUND && isTimed(text) && provider == ranking.firstOrNull()
        if (best) return false
        return now - lastCheck >= CHECK_EVERY_MS
    }

    /** Whether lyrics just found ([newText] from [newProvider]) should replace the saved ones. */
    fun isBetter(
        newProvider: String,
        newText: String,
        oldProvider: String,
        oldText: String,
        ranking: List<String>,
    ): Boolean {
        if (newText.isBlank() || newText == NOT_FOUND) return false
        if (oldProvider in OWN) return false
        if (oldText.isBlank() || oldText == NOT_FOUND) return true
        val newScore = score(newProvider, newText, ranking)
        val oldScore = score(oldProvider, oldText, ranking)
        if (newScore != oldScore) return newScore < oldScore
        return newProvider == oldProvider && newText.trim() != oldText.trim()
    }

    /** Lower is better: timed first, then the source's place in the list. */
    private fun score(
        provider: String,
        text: String,
        ranking: List<String>,
    ): Int {
        val place = ranking.indexOf(provider).let { if (it < 0) ranking.size else it }
        return (if (isTimed(text)) 0 else 1000) + place
    }
}
