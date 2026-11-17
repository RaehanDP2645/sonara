/**
 * SONARA (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.ermusic.desktop.playback

import com.metrolist.music.lyrics.LyricsRomanizer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lyric romanizer shared with the phone app (LyricsRomanizer.kt), run with ICU4J. */
class LyricsRomanizerTest {
    private fun romanize(vararg lines: String) = runBlocking { LyricsRomanizer.romanize(lines.toList()) }

    @Test
    fun japaneseReadsKanjiParticlesAndSmallTsu() {
        val out = romanize("夜空に届け", "私は君を待っている", "ちょっと待って")
        println(out)
        assertEquals("yozora ni todoke", out["夜空に届け"])
        assertTrue(out.getValue("私は君を待っている").contains(" wa "))
        assertTrue(out.getValue("私は君を待っている").contains(" o "))
        assertTrue(out.getValue("ちょっと待って").startsWith("chotto"))
    }

    @Test
    fun kanjiOnlyLineIsJapaneseInAJapaneseSong() {
        val out = romanize("東京", "さよなら")
        println(out)
        assertEquals("toukyou", out["東京"])
    }

    @Test
    fun chineseGetsPinyin() {
        val out = romanize("我爱你")
        println(out)
        assertEquals("wǒ ài nǐ", out["我爱你"])
    }

    @Test
    fun koreanFollowsRevisedRomanization() {
        val out = romanize("사랑해", "안녕하세요", "Baby 보고 싶어")
        println(out)
        assertEquals("saranghae", out["사랑해"])
        assertEquals("annyeonghaseyo", out["안녕하세요"])
        assertEquals("Baby bogo sipeo", out["Baby 보고 싶어"])
    }

    @Test
    fun otherScripts() {
        val out = romanize("Привет, как дела", "Γεια σου", "สวัสดี", "नमस्ते")
        println(out)
        assertEquals("Privet, kak dela", out["Привет, как дела"])
        assertTrue(out.containsKey("Γεια σου"))
        assertTrue(out.containsKey("สวัสดี"))
        assertTrue(out.containsKey("नमस्ते"))
    }

    @Test
    fun latinLinesGetNothing() {
        val out = romanize("I love you", "Aku cinta kamu", "ooh")
        assertFalse(out.isNotEmpty())
    }

    @Test
    fun aByteOrderMarkDoesNotChangeTheFirstWord() {
        val line = "\uFEFF君の名前を呼んだ"
        assertEquals("kimi no namae o yonda", romanize(line)[line.trim()])
    }

    @Test
    fun songReadingsAndQuotes() {
        assertEquals("futari dake no sora", romanize("二人だけの空")["二人だけの空"])
        assertEquals("\"sayonara\" dakedatta", romanize("「さよなら」だけだった")["「さよなら」だけだった"])
    }
}
