/**
 * SONARA (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * Volume normalization, the same on the phone, the TV and the desktop (which compiles this file
 * as it is).
 *
 * YouTube measures every song: `loudnessDb` is how far it is above YouTube's reference of
 * -14 LUFS, `perceptualLoudnessDb` the loudness itself in LUFS. Each song is brought to the
 * chosen level (Balanced, -14 LUFS, by default, as on YouTube and Spotify): loud songs down by up
 * to [MAX_CUT_DB], quiet ones up by up to [MAX_BOOST_DB], with [Limiter] holding the raised peaks
 * under [CEILING_DB] instead of letting them clip.
 *
 * Album mode: songs of one album played in a row, in the album's order (not shuffled), share one
 * gain, worked out from the loudness of the whole album ([albumLufs]). A quiet interlude stays
 * quiet next to the loud single, as the album was mastered, while the album as a whole still
 * matches everything else.
 */
object Loudness {
    /** The level YouTube's `loudnessDb` is measured from. */
    const val REFERENCE_LUFS = -14.0
    const val MAX_BOOST_DB = 6.0
    const val MAX_CUT_DB = 15.0
    const val CEILING_DB = -1.0

    /** Songs of an album looked at, around the one playing; enough for any regular album. */
    const val ALBUM_MAX_SONGS = 40

    /** The choices in Settings, by the names the phone stores. */
    enum class Level(val lufs: Double, val label: String) {
        AGGRESSIVE(-7.0, "Aggressive (-7 LUFS)"),
        LOUD(-11.0, "Loud (-11 LUFS)"),
        BALANCED(-14.0, "Balanced (-14 LUFS)"),
        QUIET(-19.0, "Quiet (-19 LUFS)"),
    }

    /** The song's loudness in LUFS, from what YouTube gave; null when it gave nothing. */
    fun measuredLufs(loudnessDb: Double?, perceptualLoudnessDb: Double?): Double? =
        perceptualLoudnessDb ?: loudnessDb?.let { it + REFERENCE_LUFS }

    /** The gain, in dB, that brings [lufs] to [targetLufs], within what is allowed. */
    fun gainDb(lufs: Double, targetLufs: Double): Double = (targetLufs - lufs).coerceIn(-MAX_CUT_DB, MAX_BOOST_DB)

    /** The album's gain when there is one, else the song's; null when neither is known. */
    fun gainDb(songLufs: Double?, albumLufs: Double?, targetLufs: Double): Double? =
        (albumLufs ?: songLufs)?.let { gainDb(it, targetLufs) }

    fun linear(db: Double): Double = 10.0.pow(db / DB_PER_DECADE)

    /** One song of an album: its loudness, when known, and its length. */
    class Song(val lufs: Double?, val seconds: Double)

    /**
     * The album's loudness: the mean of its songs' energy, each weighted by its length, as the
     * album would measure played through. Only once every song is known, unless they have all been
     * looked up ([lookedUp]) and a few just have no measurement; null for fewer than two.
     */
    fun albumLufs(songs: List<Song>, lookedUp: Boolean): Double? {
        if (!lookedUp && songs.any { it.lufs == null }) return null
        val known = songs.filter { it.lufs != null }
        if (known.size < 2) return null
        var energy = 0.0
        var total = 0.0
        for (song in known) {
            val weight = song.seconds.takeIf { it > 0 } ?: 1.0
            energy += weight * 10.0.pow(song.lufs!! / ENERGY_DB)
            total += weight
        }
        return ENERGY_DB * log10(energy / total)
    }

    /**
     * The songs around [index] that come from the same album, one after another: the album being
     * played. Null when the song stands alone (a playlist, a radio, a single).
     */
    fun <T> albumRun(queue: List<T>, index: Int, albumOf: (T) -> String?): IntRange? {
        val album = queue.getOrNull(index)?.let(albumOf) ?: return null
        var first = index
        while (first > 0 && index - first < ALBUM_MAX_SONGS && albumOf(queue[first - 1]) == album) first--
        var last = index
        while (last < queue.lastIndex && last - first + 1 < ALBUM_MAX_SONGS && albumOf(queue[last + 1]) == album) last++
        return if (last > first) first..last else null
    }

    /**
     * The normalization gain and a stereo-linked peak limiter, for samples at full scale 1.0.
     * The gain moves smoothly to a new target, so a change never clicks. While the gain raises a
     * song, peaks above [CEILING_DB] are turned down at once and let back up over [RELEASE_S];
     * otherwise the ceiling is full scale, which only catches what would have clipped anyway.
     */
    class Limiter(sampleRate: Int) {
        private val rampCoefficient = exp(-1.0 / (RAMP_S * sampleRate))
        private val releaseCoefficient = exp(-1.0 / (RELEASE_S * sampleRate))
        private val ceiling = linear(CEILING_DB)

        @Volatile
        var targetGain = 1.0

        private var gain = Double.NaN
        private var envelope = 0.0

        /** After a seek: no ramp from where the song was. */
        fun snap() {
            gain = targetGain
            envelope = 0.0
        }

        /** Processes one frame (one sample per channel) in place. */
        fun process(frame: DoubleArray, channels: Int) {
            val target = targetGain
            gain = if (gain.isNaN()) target else target + (gain - target) * rampCoefficient
            var peak = 0.0
            for (c in 0 until channels) {
                frame[c] *= gain
                peak = max(peak, abs(frame[c]))
            }
            envelope = max(peak, envelope * releaseCoefficient)
            val limit = if (gain > 1.0) ceiling else 1.0
            if (envelope > limit) {
                val reduction = limit / envelope
                for (c in 0 until channels) frame[c] = (frame[c] * reduction).coerceIn(-1.0, 1.0)
            }
        }
    }

    private const val DB_PER_DECADE = 20.0
    private const val ENERGY_DB = 10.0
    private const val RAMP_S = 0.05
    private const val RELEASE_S = 0.15
}
