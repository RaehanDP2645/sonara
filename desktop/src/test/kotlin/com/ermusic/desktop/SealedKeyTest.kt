/**
 * SONARA (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.ermusic.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Properties

/** The built-in sealed key opens, and to exactly the key in local.properties when one is there. */
class SealedKeyTest {
    private companion object {
        const val MIN_KEY_LENGTH = 20
    }

    private val expected: String? =
        listOf(File("../local.properties"), File("local.properties"))
            .firstOrNull(File::isFile)
            ?.let { file -> Properties().apply { file.inputStream().use(::load) }.getProperty("SONARA_API_SECRET")?.trim() }
            ?.takeIf { it.isNotEmpty() }
            ?: System.getenv("SONARA_API_SECRET")?.trim()?.takeIf { it.isNotEmpty() }

    @Test
    fun sealedKeyOpensToTheOwnersKey() {
        // Compared without printing either value.
        assertTrue("the built-in key opens", BuildSecrets.API_SECRET.length > MIN_KEY_LENGTH)
        if (expected != null) assertTrue("the sealed key opens to the configured one", BuildSecrets.API_SECRET == expected)
    }

    @Test
    fun damagedSealOpensToNothing() {
        assertEquals("", SealedKey.open("not a sealed key"))
    }
}
