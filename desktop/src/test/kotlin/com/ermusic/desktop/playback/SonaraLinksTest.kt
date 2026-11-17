/**
 * SONARA (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.ermusic.desktop.playback

import com.metrolist.innertube.models.Album
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.utils.ExternalImport
import com.metrolist.music.utils.SonaraLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sonara's share links and the import's song matching (both shared with the phone). */
class SonaraLinksTest {
    private val open = "https://sonara.rashiiddsr.my.id/open/?"

    @Test
    fun youTubeLinksBecomeSonaraLinks() {
        assertEquals(open + "song=dQw4w9WgXcQ", SonaraLinks.share("https://music.youtube.com/watch?v=dQw4w9WgXcQ&si=abc"))
        assertEquals(open + "song=dQw4w9WgXcQ", SonaraLinks.share("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals(open + "playlist=PLabc123456", SonaraLinks.share("https://music.youtube.com/playlist?list=PLabc123456"))
        assertEquals(open + "album=OLAK5uy_abc123", SonaraLinks.share("https://music.youtube.com/playlist?list=OLAK5uy_abc123"))
        assertEquals(open + "album=MPREb_abc123", SonaraLinks.share("https://music.youtube.com/browse/MPREb_abc123"))
        assertEquals(open + "artist=UCabcdef123", SonaraLinks.share("https://music.youtube.com/channel/UCabcdef123"))
        assertEquals(open + "playlist=PLabc123456&jct=tok%2Fen", SonaraLinks.share("https://music.youtube.com/playlist?list=PLabc123456&jct=tok%2Fen"))
        assertEquals("https://example.com/x", SonaraLinks.share("https://example.com/x"))
    }

    @Test
    fun sonaraLinksOpenAsYouTubeMusicLinks() {
        assertEquals("https://music.youtube.com/watch?v=dQw4w9WgXcQ", SonaraLinks.toYouTube(open + "song=dQw4w9WgXcQ"))
        assertEquals("https://music.youtube.com/playlist?list=PLabc123456&jct=tok%2Fen", SonaraLinks.toYouTube(open + "playlist=PLabc123456&jct=tok%2Fen"))
        assertEquals("https://music.youtube.com/browse/MPREb_abc123", SonaraLinks.toYouTube("sonara://open/?album=MPREb_abc123"))
        assertEquals("https://music.youtube.com/channel/UCabcdef123", SonaraLinks.toYouTube("https://sonara.rashiiddsr.my.id/open?artist=UCabcdef123"))
        assertNull(SonaraLinks.toYouTube("https://sonara.rashiiddsr.my.id/tv/?s=abc"))
        assertNull(SonaraLinks.toYouTube(open + "song=x"))
    }

    @Test
    fun platformsAreRecognised() {
        assertEquals(ExternalImport.Platform.SPOTIFY, ExternalImport.platformOf("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=1"))
        assertEquals(ExternalImport.Platform.SPOTIFY, ExternalImport.platformOf("spotify:album:4aawyAB9vmqN3uQ7FjRGTy"))
        assertEquals(ExternalImport.Platform.DEEZER, ExternalImport.platformOf("https://www.deezer.com/en/playlist/1479458365"))
        assertEquals(ExternalImport.Platform.APPLE, ExternalImport.platformOf("https://music.apple.com/us/playlist/x/pl.u-abc"))
        assertNull(ExternalImport.platformOf("https://music.youtube.com/playlist?list=PL1"))
    }

    private fun song(
        title: String,
        artist: String,
        seconds: Int?,
        album: String? = null,
    ) = SongItem(
        id = title.hashCode().toString(),
        title = title,
        artists = listOf(Artist(name = artist, id = null)),
        album = album?.let { Album(name = it, id = "A") },
        duration = seconds,
        thumbnail = "",
    )

    @Test
    fun theRightVersionWins() {
        val track = ExternalImport.Track("Blinding Lights", listOf("The Weeknd"), "After Hours", 200_040, null)
        val original = ExternalImport.score(track, song("Blinding Lights", "The Weeknd", 200, "After Hours"))
        val live = ExternalImport.score(track, song("Blinding Lights (Live)", "The Weeknd", 230))
        val other = ExternalImport.score(track, song("Blinding Lights", "Some Cover Band", 199))
        assertTrue(original > live)
        assertTrue(original > other)
        assertTrue(original >= 0.9)
        assertEquals("blinding lights", ExternalImport.plain("Blinding Lights (feat. Someone) - 2020 Remastered"))
    }
}
