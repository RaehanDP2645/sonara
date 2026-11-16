/**
 * SONARA (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.ermusic.desktop.playback

import java.io.File
import java.util.Properties

/**
 * When each song's lyrics were last looked up (LyricsFreshness, as on the phone), in a small file
 * of its own. Kept to the songs looked up most recently.
 */
class LyricsCheckTimes(private val file: File) {
    private val times: MutableMap<String, Long> by lazy {
        val loaded = Properties()
        runCatching { file.takeIf { it.isFile }?.inputStream()?.use(loaded::load) }
        loaded.stringPropertyNames().associateWithTo(HashMap()) { loaded.getProperty(it).toLongOrNull() ?: 0L }
    }

    @Synchronized
    fun last(songId: String): Long = times[songId] ?: 0L

    @Synchronized
    fun mark(
        songId: String,
        now: Long = System.currentTimeMillis(),
    ) {
        times[songId] = now
        if (times.size > KEEP) {
            times.entries.sortedBy { it.value }.take(times.size - TRIM_TO).map { it.key }.forEach(times::remove)
        }
        val out = Properties()
        times.forEach { (id, time) -> out.setProperty(id, time.toString()) }
        runCatching {
            file.parentFile?.mkdirs()
            val part = File(file.path + ".part")
            part.outputStream().use { out.store(it, null) }
            if (!part.renameTo(file)) {
                file.delete()
                part.renameTo(file)
            }
        }
    }

    private companion object {
        const val KEEP = 5_000
        const val TRIM_TO = 4_000
    }
}
