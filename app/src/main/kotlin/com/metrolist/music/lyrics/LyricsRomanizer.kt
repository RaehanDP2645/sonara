/**
 * Sonara (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Lyrics in Latin letters, under lines written in another script, made on the device with no
 * network and no AI: Japanese through Kuromoji's dictionary (kanji readings), Korean by the
 * Revised Romanization rules, and Chinese (pinyin with tone marks) and every other script
 * (Cyrillic, Greek, Thai, Arabic, Hebrew, Devanagari and more) through ICU's transliterators. A line that is
 * already in Latin letters, or that no romanizer handles, gets nothing. The desktop app compiles
 * this file as it is (desktop/build.gradle.kts, with ICU4J behind android.icu there).
 */

package com.metrolist.music.lyrics

import android.icu.text.Transliterator
import com.atilika.kuromoji.ipadic.Token
import com.atilika.kuromoji.ipadic.Tokenizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.Character.UnicodeScript
import java.text.Normalizer
import java.util.Collections

object LyricsRomanizer {
    /**
     * The romanization of each line of one song that needs one, keyed by the line as given
     * (trimmed). The whole song is read first: a line of kanji only is Japanese when the song has
     * kana anywhere, Chinese otherwise.
     */
    suspend fun romanize(lines: List<String>): Map<String, String> =
        withContext(Dispatchers.Default) {
            val distinct = lines.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            val japanese = distinct.any { line -> line.codePoints().anyMatch { isKana(it) } }
            buildMap {
                for (line in distinct) {
                    val romanized = runCatching { romanizeLine(line, japanese) }.getOrNull() ?: continue
                    if (worthShowing(line, romanized)) put(line, romanized)
                }
            }
        }

    /** Whether [line] has letters that are not Latin, so a romanization could help. */
    fun needsRomanization(line: String): Boolean =
        line.codePoints().anyMatch { cp -> Character.isLetter(cp) && UnicodeScript.of(cp).let { it != UnicodeScript.LATIN && it != UnicodeScript.COMMON && it != UnicodeScript.INHERITED } }

    private fun romanizeLine(
        line: String,
        japaneseSong: Boolean,
    ): String? {
        if (!needsRomanization(line)) return null
        val scripts = line.codePoints().toArray().filter { Character.isLetter(it) }.map { UnicodeScript.of(it) }.toSet()
        val result =
            when {
                UnicodeScript.HANGUL in scripts -> korean(line)
                UnicodeScript.HIRAGANA in scripts || UnicodeScript.KATAKANA in scripts -> japanese(line)
                UnicodeScript.HAN in scripts -> if (japaneseSong) japanese(line) else chinese(line)
                UnicodeScript.CYRILLIC in scripts -> cyrillic(line)
                UnicodeScript.GREEK in scripts -> icu(line, "Greek-Latin/UNGEGN", "Greek-Latin")
                else -> icu(line, "Any-Latin")
            } ?: return null
        return tidy(result)
    }

    private fun worthShowing(
        line: String,
        romanized: String,
    ): Boolean {
        if (romanized.isBlank() || needsRomanization(romanized)) return false
        val plain = { s: String -> s.lowercase().filter { it.isLetterOrDigit() } }
        return plain(romanized) != plain(line)
    }

    /** Full-width punctuation and spaces to plain ones, no double spaces, no space before punctuation. */
    private fun tidy(text: String): String =
        Normalizer
            .normalize(text, Normalizer.Form.NFKC)
            .replace('、', ',')
            .replace('。', '.')
            .replace('「', '"')
            .replace('」', '"')
            .replace('『', '"')
            .replace('』', '"')
            .replace('・', ' ')
            .replace("ʹ", "")
            .replace("ʺ", "")
            .replace(Regex("\\s+"), " ")
            .replace(Regex("\\s+([,.!?;:)\\]}\"'])(?=\\s|$)"), "$1")
            .replace(Regex("([(\\[{])\\s+"), "$1")
            .trim()

    // ---- Japanese ----

    /**
     * Makes the Japanese tokenizer: from the dictionary bundled with the app by default. Sonara TV
     * doesn't bundle it (13 MB) and makes one from a dictionary it downloads (TvDictionary); until
     * that is there this gives null, and Japanese lines get no romanization.
     */
    @Volatile
    var tokenizerFactory: () -> Tokenizer? = { Tokenizer() }

    @Volatile
    private var tokenizer: Tokenizer? = null

    private fun tokenizer(): Tokenizer? =
        tokenizer ?: synchronized(this) {
            tokenizer ?: runCatching { tokenizerFactory() }.getOrNull()?.also { tokenizer = it }
        }

    private fun japanese(line: String): String? {
        val tk = tokenizer() ?: return null
        val tokens = synchronized(tk) { tk.tokenize(line) }.filter { it.surface.isNotBlank() }
        val out = StringBuilder()
        tokens.forEachIndexed { index, token ->
            val kana = readingOf(token)
            val nextKana = tokens.getOrNull(index + 1)?.let(::readingOf)
            val romaji = particle(token) ?: kanaToRomaji(kana, nextKana)
            if (romaji.isEmpty()) return@forEachIndexed
            if (out.isNotEmpty() && joinsWithSpace(token, tokens.getOrNull(index - 1))) out.append(' ')
            out.append(romaji)
        }
        return out.toString().lowercase()
    }

    /** Katakana for the token: its dictionary reading, or the token itself (kana, Latin, unknown words). */
    private fun readingOf(token: Token): String {
        val reading = token.reading
        return if (reading.isNullOrEmpty() || reading == "*") token.surface else reading
    }

    /** は, へ and を as particles are said wa, e and o. */
    private fun particle(token: Token): String? {
        if (token.partOfSpeechLevel1 != "助詞") return null
        return when (token.surface) {
            "は" -> "wa"
            "へ" -> "e"
            "を" -> "o"
            else -> null
        }
    }

    /** Words get a space between them; endings, auxiliaries and punctuation stick to what they follow. */
    private fun joinsWithSpace(
        token: Token,
        previous: Token?,
    ): Boolean {
        if (previous == null) return true
        if (token.partOfSpeechLevel1 == "記号") return token.surface.any { it.isLetterOrDigit() }
        if (previous.partOfSpeechLevel1 == "接頭詞") return false
        if (token.partOfSpeechLevel1 == "助動詞") return false
        if (token.partOfSpeechLevel2 == "接尾" || token.partOfSpeechLevel2 == "非自立" && token.partOfSpeechLevel1 == "動詞") return false
        if (token.partOfSpeechLevel2 == "接続助詞" && token.surface in setOf("て", "で", "ば")) return false
        return true
    }

    private fun isKana(cp: Int): Boolean = UnicodeScript.of(cp).let { it == UnicodeScript.HIRAGANA || it == UnicodeScript.KATAKANA } && cp != 'ー'.code

    /** Hiragana to katakana, so one table serves both. */
    private fun toKatakana(text: String): String =
        buildString(text.length) {
            for (ch in text) append(if (ch in 'ぁ'..'ゖ') ch + 0x60 else ch)
        }

    internal fun kanaToRomaji(
        kanaText: String,
        nextKana: String? = null,
    ): String {
        val kana = toKatakana(kanaText)
        val out = StringBuilder(kana.length * 2)
        var i = 0
        while (i < kana.length) {
            val two = if (i + 1 < kana.length) KANA[kana.substring(i, i + 2)] else null
            if (two != null) {
                out.append(two)
                i += 2
                continue
            }
            val ch = kana[i]
            when (ch) {
                'ッ' -> {
                    // Doubles the next consonant: in this token, or the first of the next one.
                    val following = if (i + 1 < kana.length) kanaToRomaji(kana.substring(i + 1)) else nextKana?.let { kanaToRomaji(it) }
                    following?.firstOrNull()?.takeIf { it.isLetter() && it !in "aeiou" }?.let { out.append(if (it == 'c') 't' else it) }
                }
                'ー' -> out.lastOrNull { it in "aeiou" }?.let { out.append(it) }
                else -> out.append(KANA[ch.toString()] ?: ch.toString())
            }
            i += 1
        }
        return out.toString()
    }

    private val KANA: Map<String, String> =
        mapOf(
            "キャ" to "kya", "キュ" to "kyu", "キョ" to "kyo", "シャ" to "sha", "シュ" to "shu", "ショ" to "sho",
            "チャ" to "cha", "チュ" to "chu", "チョ" to "cho", "ニャ" to "nya", "ニュ" to "nyu", "ニョ" to "nyo",
            "ヒャ" to "hya", "ヒュ" to "hyu", "ヒョ" to "hyo", "ミャ" to "mya", "ミュ" to "myu", "ミョ" to "myo",
            "リャ" to "rya", "リュ" to "ryu", "リョ" to "ryo", "ギャ" to "gya", "ギュ" to "gyu", "ギョ" to "gyo",
            "ジャ" to "ja", "ジュ" to "ju", "ジョ" to "jo", "ヂャ" to "ja", "ヂュ" to "ju", "ヂョ" to "jo",
            "ビャ" to "bya", "ビュ" to "byu", "ビョ" to "byo", "ピャ" to "pya", "ピュ" to "pyu", "ピョ" to "pyo",
            "シェ" to "she", "ジェ" to "je", "チェ" to "che", "ティ" to "ti", "ディ" to "di", "トゥ" to "tu", "ドゥ" to "du",
            "ファ" to "fa", "フィ" to "fi", "フェ" to "fe", "フォ" to "fo", "ウィ" to "wi", "ウェ" to "we", "ウォ" to "wo",
            "ヴァ" to "va", "ヴィ" to "vi", "ヴェ" to "ve", "ヴォ" to "vo", "ツァ" to "tsa", "イェ" to "ye", "デュ" to "dyu",
            "ア" to "a", "イ" to "i", "ウ" to "u", "エ" to "e", "オ" to "o",
            "カ" to "ka", "キ" to "ki", "ク" to "ku", "ケ" to "ke", "コ" to "ko",
            "サ" to "sa", "シ" to "shi", "ス" to "su", "セ" to "se", "ソ" to "so",
            "タ" to "ta", "チ" to "chi", "ツ" to "tsu", "テ" to "te", "ト" to "to",
            "ナ" to "na", "ニ" to "ni", "ヌ" to "nu", "ネ" to "ne", "ノ" to "no",
            "ハ" to "ha", "ヒ" to "hi", "フ" to "fu", "ヘ" to "he", "ホ" to "ho",
            "マ" to "ma", "ミ" to "mi", "ム" to "mu", "メ" to "me", "モ" to "mo",
            "ヤ" to "ya", "ユ" to "yu", "ヨ" to "yo",
            "ラ" to "ra", "リ" to "ri", "ル" to "ru", "レ" to "re", "ロ" to "ro",
            "ワ" to "wa", "ヰ" to "i", "ヱ" to "e", "ヲ" to "o", "ン" to "n",
            "ガ" to "ga", "ギ" to "gi", "グ" to "gu", "ゲ" to "ge", "ゴ" to "go",
            "ザ" to "za", "ジ" to "ji", "ズ" to "zu", "ゼ" to "ze", "ゾ" to "zo",
            "ダ" to "da", "ヂ" to "ji", "ヅ" to "zu", "デ" to "de", "ド" to "do",
            "バ" to "ba", "ビ" to "bi", "ブ" to "bu", "ベ" to "be", "ボ" to "bo",
            "パ" to "pa", "ピ" to "pi", "プ" to "pu", "ペ" to "pe", "ポ" to "po", "ヴ" to "vu",
            "ァ" to "a", "ィ" to "i", "ゥ" to "u", "ェ" to "e", "ォ" to "o", "ャ" to "ya", "ュ" to "yu", "ョ" to "yo", "ヮ" to "wa",
        )

    // ---- Chinese ----

    /** Pinyin with tone marks, a syllable per character. */
    private fun chinese(line: String): String? = icu(line, "Han-Latin", ascii = false)

    // ---- Korean (Revised Romanization, with the sound changes between syllables) ----

    private fun korean(line: String): String {
        val out = StringBuilder()
        var pendingFinal: Char? = null
        fun flushFinal(nextInitial: Char?) {
            val final = pendingFinal ?: return
            out.append(nextInitial?.let { JONG["$final$it"] } ?: JONG[final.toString()] ?: "")
            pendingFinal = null
        }
        for (ch in line) {
            if (ch in '가'..'힣') {
                val index = ch.code - 0xAC00
                val initial = (0x1100 + index / (21 * 28)).toChar()
                val vowel = (0x1161 + (index % (21 * 28)) / 28).toChar()
                val finalIndex = index % 28
                flushFinal(initial)
                out.append(CHO[initial] ?: "").append(JUNG[vowel] ?: "")
                pendingFinal = if (finalIndex == 0) null else (0x11A7 + finalIndex).toChar()
            } else {
                flushFinal(null)
                out.append(ch)
            }
        }
        flushFinal(null)
        return out.toString()
    }

    private val CHO =
        mapOf(
            'ᄀ' to "g", 'ᄁ' to "kk", 'ᄂ' to "n", 'ᄃ' to "d", 'ᄄ' to "tt", 'ᄅ' to "r", 'ᄆ' to "m", 'ᄇ' to "b", 'ᄈ' to "pp",
            'ᄉ' to "s", 'ᄊ' to "ss", 'ᄋ' to "", 'ᄌ' to "j", 'ᄍ' to "jj", 'ᄎ' to "ch", 'ᄏ' to "k", 'ᄐ' to "t", 'ᄑ' to "p", 'ᄒ' to "h",
        )

    private val JUNG =
        mapOf(
            'ᅡ' to "a", 'ᅢ' to "ae", 'ᅣ' to "ya", 'ᅤ' to "yae", 'ᅥ' to "eo", 'ᅦ' to "e", 'ᅧ' to "yeo", 'ᅨ' to "ye", 'ᅩ' to "o",
            'ᅪ' to "wa", 'ᅫ' to "wae", 'ᅬ' to "oe", 'ᅭ' to "yo", 'ᅮ' to "u", 'ᅯ' to "wo", 'ᅰ' to "we", 'ᅱ' to "wi", 'ᅲ' to "yu",
            'ᅳ' to "eu", 'ᅴ' to "ui", 'ᅵ' to "i",
        )

    /** A final consonant on its own, and before the next syllable's initial (ᄋ: carried over). */
    private val JONG: Map<String, String> =
        mapOf(
            "ᆨ" to "k", "ᆨᄋ" to "g", "ᆨᄂ" to "ngn", "ᆨᄅ" to "ngn", "ᆨᄆ" to "ngm", "ᆨᄒ" to "kh",
            "ᆩ" to "k", "ᆩᄋ" to "kk", "ᆩᄂ" to "ngn", "ᆩᄅ" to "ngn", "ᆩᄆ" to "ngm",
            "ᆪ" to "k", "ᆪᄋ" to "ks",
            "ᆫ" to "n", "ᆫᄅ" to "ll", "ᆬ" to "n", "ᆬᄋ" to "nj", "ᆭ" to "n", "ᆭᄋ" to "n",
            "ᆮ" to "t", "ᆮᄋ" to "d", "ᆮᄂ" to "nn", "ᆮᄆ" to "nm",
            "ᆯ" to "l", "ᆯᄋ" to "r", "ᆯᄂ" to "ll", "ᆯᄅ" to "ll",
            "ᆰ" to "k", "ᆰᄋ" to "lg", "ᆱ" to "m", "ᆱᄋ" to "lm", "ᆲ" to "l", "ᆲᄋ" to "lb", "ᆳ" to "l", "ᆳᄋ" to "ls",
            "ᆴ" to "l", "ᆴᄋ" to "lt", "ᆵ" to "p", "ᆵᄋ" to "lp", "ᆶ" to "l", "ᆶᄋ" to "r",
            "ᆷ" to "m", "ᆷᄅ" to "mn",
            "ᆸ" to "p", "ᆸᄋ" to "b", "ᆸᄂ" to "mn", "ᆸᄅ" to "mn", "ᆸᄆ" to "mm",
            "ᆹ" to "p", "ᆹᄋ" to "ps", "ᆹᄂ" to "mn", "ᆹᄆ" to "mm",
            "ᆺ" to "t", "ᆺᄋ" to "s", "ᆺᄂ" to "nn", "ᆺᄆ" to "nm",
            "ᆻ" to "t", "ᆻᄋ" to "ss", "ᆻᄂ" to "nn", "ᆻᄆ" to "nm",
            "ᆼ" to "ng", "ᆼᄋ" to "ng",
            "ᆽ" to "t", "ᆽᄋ" to "j", "ᆽᄂ" to "nn", "ᆽᄆ" to "nm",
            "ᆾ" to "t", "ᆾᄋ" to "ch", "ᆾᄂ" to "nn", "ᆾᄆ" to "nm",
            "ᆿ" to "k", "ᆿᄋ" to "k", "ᇀ" to "t", "ᇀᄋ" to "t", "ᇁ" to "p", "ᇁᄋ" to "p",
            "ᇂ" to "t", "ᇂᄋ" to "", "ᇂᄂ" to "nn", "ᇂᄀ" to "k", "ᇂᄃ" to "t", "ᇂᄌ" to "ch",
        )

    // ---- Every other script, through ICU ----

    private fun cyrillic(line: String): String? {
        val id =
            when {
                line.any { it in "іїєґІЇЄҐ" } -> "Ukrainian-Latin/BGN"
                line.any { it in "ђјљњћџЂЈЉЊЋЏ" } -> "Serbian-Latin/BGN"
                line.any { it in "ѓќѕЃЌЅ" } -> "Macedonian-Latin/BGN"
                line.any { it in "ўЎ" } -> "Belarusian-Latin/BGN"
                line.any { it in "ңөүҢӨҮ" } -> "Kyrgyz-Latin/BGN"
                else -> "Russian-Latin/BGN"
            }
        return icu(line, "$id; Latin-ASCII", "Cyrillic-Latin; Latin-ASCII", "Any-Latin; Latin-ASCII")
    }

    private val unavailable = Collections.synchronizedSet(mutableSetOf<String>())
    private val transliterators = Collections.synchronizedMap(mutableMapOf<String, Transliterator>())

    /** The first of [ids] this system's ICU has; then, unless [ascii] is false, Latin-ASCII for plain letters. */
    private fun icu(
        line: String,
        vararg ids: String,
        ascii: Boolean = true,
    ): String? {
        for (id in ids) {
            val full = if (!ascii || id.contains("Latin-ASCII")) id else "$id; Latin-ASCII"
            if (full in unavailable) continue
            val transliterator =
                transliterators[full] ?: runCatching { Transliterator.getInstance(full) }.getOrNull()?.also { transliterators[full] = it }
            if (transliterator == null) {
                unavailable += full
                continue
            }
            return synchronized(transliterator) { transliterator.transliterate(line) }
        }
        return null
    }
}
