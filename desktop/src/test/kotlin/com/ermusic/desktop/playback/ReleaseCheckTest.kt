/**
 * SONARA (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.ermusic.desktop.playback

import com.metrolist.music.update.ReleaseCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The updater's release check, shared with the phone (ReleaseCheck.kt). */
class ReleaseCheckTest {
    @Test
    fun versionsCompareByNumber() {
        assertTrue(ReleaseCheck.isNewer("2.5.0", "2.4.2"))
        assertTrue(ReleaseCheck.isNewer("2.10.0", "2.9.9"))
        assertTrue(ReleaseCheck.isNewer("v2.4.3", "2.4.2+abc123"))
        assertFalse(ReleaseCheck.isNewer("2.4.2", "2.4.2"))
        assertFalse(ReleaseCheck.isNewer("2.4.2", "2.4.10"))
        assertFalse(ReleaseCheck.isNewer("2.4", "2.4.0"))
    }

    @Test
    fun latestReleaseIsRead() {
        val release =
            ReleaseCheck.parse(
                """{"tag_name":"v2.5.0","html_url":"https://github.com/x/y/releases/tag/v2.5.0","body":"Notes","draft":false,"prerelease":false,
                   "assets":[{"name":"Sonara-2.5.0.apk","browser_download_url":"https://e/a.apk","size":10},
                             {"name":"Sonara-2.5.0.msi","browser_download_url":"https://e/a.msi","size":20}]}""",
            )!!
        assertEquals("2.5.0", release.version)
        assertEquals(listOf("Sonara-2.5.0.apk", "Sonara-2.5.0.msi"), release.assets.map { it.name })
        assertNull(ReleaseCheck.parse("""{"tag_name":"v3.0.0","prerelease":true,"assets":[]}"""))
        assertNull(ReleaseCheck.parse("not json"))
    }

    @Test
    fun hiddenMinimumMakesAnUpdateRequired() {
        fun body(notes: String) = """{"tag_name":"v2.6.0","body":${kotlinx.serialization.json.JsonPrimitive(notes)},"assets":[]}"""
        val required = ReleaseCheck.parse(body("Fixes playback.\n<!-- sonara-min: 2.5.0 -->"))!!
        assertEquals("2.5.0", required.minVersion)
        assertEquals("Fixes playback.", required.notes)
        assertTrue(required.isRequiredFor("2.4.2"))
        assertFalse(required.isRequiredFor("2.5.0"))
        assertFalse(required.isRequiredFor("2.5.1"))
        val optional = ReleaseCheck.parse(body("Fixes playback."))!!
        assertNull(optional.minVersion)
        assertFalse(optional.isRequiredFor("1.0.0"))
    }

    @Test
    fun updateDialogShowsWhatsNewWithoutTheDownloadTable() {
        val notes =
            "## Sonara 2.5.2\n\n### Download\n\n| System | File |\n|---|---|\n| Android | `Sonara.apk` |\n\n" +
                "### What's new\n\n- **Better** lyrics\n\n### Fixes\n\n- A [bug](https://x.y) fixed\n\n### Good to know\n\n- SmartScreen tip"
        assertEquals("What's new\n• Better lyrics\n\nFixes\n• A bug fixed", ReleaseCheck.highlights(notes))
        assertEquals("Line one\n• item", ReleaseCheck.highlights("Line one\n| a | b |\n|---|---|\n- item"))
    }
}
