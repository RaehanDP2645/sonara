package com.metrolist.music.playback

import com.metrolist.music.playback.audio.Loudness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoudnessTest {
    @Test
    fun `loudnessDb is measured from YouTube's -14 LUFS`() {
        // Measured on YouTube: Blinding Lights gives loudnessDb 7.19 and perceptualLoudnessDb -6.81.
        assertEquals(-6.81, Loudness.measuredLufs(7.19, null)!!, 0.001)
        assertEquals(-6.81, Loudness.measuredLufs(7.19, -6.81)!!, 0.001)
        assertNull(Loudness.measuredLufs(null, null))
    }

    @Test
    fun `loud songs go down, quiet ones up, within the limits`() {
        assertEquals(-707, normalizationGainMb(-6.93, null, -14f))
        assertEquals(600, normalizationGainMb(-30.0, null, -14f))
        assertEquals(-1500, normalizationGainMb(10.0, null, -14f))
        assertEquals(300, normalizationGainMb(-17.0, null, -14f))
        assertNull(normalizationGainMb(null, null, -14f))
    }

    @Test
    fun `an album shares one gain`() {
        assertEquals(400, normalizationGainMb(-10.0, -18.0, -14f))
        assertEquals(400, normalizationGainMb(-25.0, -18.0, -14f))
    }

    @Test
    fun `album loudness is the energy mean weighted by length`() {
        val same = Loudness.albumLufs(listOf(Loudness.Song(-10.0, 200.0), Loudness.Song(-10.0, 100.0)), lookedUp = false)
        assertEquals(-10.0, same!!, 0.0001)
        // Energy, not dB, is averaged: a loud song weighs more than a quiet one.
        val mixed = Loudness.albumLufs(listOf(Loudness.Song(-8.0, 180.0), Loudness.Song(-20.0, 180.0)), lookedUp = false)!!
        assertTrue(mixed > -14.0 && mixed < -10.0)
        // Not every song known yet: no album gain, until they have all been looked up.
        val partial = listOf(Loudness.Song(-8.0, 180.0), Loudness.Song(null, 180.0), Loudness.Song(-9.0, 180.0))
        assertNull(Loudness.albumLufs(partial, lookedUp = false))
        assertEquals(-8.47, Loudness.albumLufs(partial, lookedUp = true)!!, 0.01)
        assertNull(Loudness.albumLufs(listOf(Loudness.Song(-8.0, 180.0)), lookedUp = true))
    }

    @Test
    fun `the album is the run of its songs around the one playing`() {
        val queue = listOf("a", "x", "x", "x", null, "x")
        assertEquals(1..3, Loudness.albumRun(queue, 2) { it })
        assertNull(Loudness.albumRun(queue, 0) { it })
        assertNull(Loudness.albumRun(queue, 4) { it })
        assertNull(Loudness.albumRun(queue, 5) { it })
    }

    @Test
    fun `the limiter keeps raised peaks under the ceiling`() {
        val limiter = Loudness.Limiter(48_000).apply { targetGain = Loudness.linear(6.0) }
        val ceiling = Loudness.linear(Loudness.CEILING_DB)
        val frame = DoubleArray(2)
        var loudest = 0.0
        repeat(48_000) { i ->
            val s = 0.9 * kotlin.math.sin(i * 0.05)
            frame[0] = s
            frame[1] = -s
            limiter.process(frame, 2)
            loudest = maxOf(loudest, kotlin.math.abs(frame[0]), kotlin.math.abs(frame[1]))
        }
        assertTrue("peak $loudest", loudest <= ceiling + 1e-9)
        // Quiet passages are raised by the full gain.
        frame[0] = 0.01
        frame[1] = 0.01
        repeat(48_000) { limiter.process(frame.also { it[0] = 0.01; it[1] = 0.01 }, 2) }
        assertEquals(0.01 * Loudness.linear(6.0), frame[0], 1e-4)
    }
}
