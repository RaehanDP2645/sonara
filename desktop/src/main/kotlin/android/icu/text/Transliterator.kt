/**
 * SONARA (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 */

package android.icu.text

/**
 * Android's built-in ICU transliterator, for the lyric romanizer shared with the Android app
 * (LyricsRomanizer.kt): on the desktop, ICU4J does the work.
 */
class Transliterator private constructor(
    private val icu: com.ibm.icu.text.Transliterator,
) {
    fun transliterate(text: String): String = icu.transliterate(text)

    companion object {
        fun getInstance(id: String): Transliterator = Transliterator(com.ibm.icu.text.Transliterator.getInstance(id))
    }
}
