/**
 * SONARA (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.ermusic.desktop.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** The translation request is signed exactly as the proxy (web/private/src/Signature.php) checks it. */
class TranslatorSignatureTest {
    @Test
    fun signatureMatchesTheProxy() {
        val body = "{\"target\":\"id\",\"lines\":[\"夜空に届け\"]}"
        // From PHP: hash_hmac('sha256', "POST\n/api/translate\n<time>\n<install>\n" . hash('sha256', body), secret)
        assertEquals(
            "05eebe6a22842db57ce23d32542bc011a5f20bfeea6acd30b3a8b194ebf770f0",
            LyricsTranslator.signature("test-secret_ABC", "/api/translate", "1700000000", "123e4567-e89b-42d3-a456-426614174000", body),
        )
    }
}
