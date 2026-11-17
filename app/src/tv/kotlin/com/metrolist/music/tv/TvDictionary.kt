/**
 * Sonara (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Sonara TV leaves the Japanese dictionary (13 MB, more than half of the phone's APK) out of its
 * APK. The first time lyrics need it, the same library is fetched from Maven Central (where the
 * phone's build gets it), checked against its SHA-256, and its dictionary files are kept in the
 * app's storage; romanization of Japanese then works as on the phone. Until it is there, Japanese
 * lines simply show without Latin letters.
 */

package com.metrolist.music.tv

import android.content.Context
import com.atilika.kuromoji.ipadic.Tokenizer
import com.atilika.kuromoji.util.ResourceResolver
import com.metrolist.music.lyrics.LyricsRomanizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

object TvDictionary {
    private const val URL_JAR = "https://repo1.maven.org/maven2/com/atilika/kuromoji/kuromoji-ipadic/0.9.0/kuromoji-ipadic-0.9.0.jar"
    private const val SHA256 = "24909fd751c0b439f7af5131b080eb65bc85062f0c4977ca4a71c76abe74e0b6"
    private const val PREFIX = "com/atilika/kuromoji/ipadic/"
    private val FILES =
        listOf(
            "characterDefinitions.bin", "connectionCosts.bin", "doubleArrayTrie.bin", "tokenInfoDictionary.bin",
            "tokenInfoFeaturesMap.bin", "tokenInfoPartOfSpeechMap.bin", "tokenInfoTargetMap.bin", "unknownDictionary.bin",
        )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fetching = AtomicBoolean(false)

    /** Hooks the downloaded dictionary into the romanizer; called once when Sonara TV starts. */
    fun install(context: Context) {
        val dir = File(context.applicationContext.filesDir, "kuromoji-ipadic")
        LyricsRomanizer.tokenizerFactory = {
            if (ready(dir)) {
                FileBuilder(dir).build()
            } else {
                fetch(dir)
                null
            }
        }
    }

    private fun ready(dir: File) = FILES.all { File(dir, it).isFile }

    /** Downloads the library once (in the background), checks it, and keeps its dictionary. */
    private fun fetch(dir: File) {
        if (!fetching.compareAndSet(false, true)) return
        scope.launch {
            val jar = File(dir.parentFile, "kuromoji-ipadic.jar.part")
            try {
                val connection = URL(URL_JAR).openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 60_000
                val digest = MessageDigest.getInstance("SHA-256")
                connection.inputStream.use { input ->
                    jar.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                    }
                }
                connection.disconnect()
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                check(hash == SHA256) { "Dictionary checksum mismatch" }
                val staging = File(dir.parentFile, "kuromoji-ipadic.part").apply { deleteRecursively(); mkdirs() }
                ZipFile(jar).use { zip ->
                    FILES.forEach { name ->
                        val entry = checkNotNull(zip.getEntry(PREFIX + name)) { "Missing $name" }
                        zip.getInputStream(entry).use { input -> File(staging, name).outputStream().use { input.copyTo(it) } }
                    }
                }
                dir.deleteRecursively()
                check(staging.renameTo(dir)) { "Couldn't keep the dictionary" }
            } catch (e: Exception) {
                Timber.w(e, "Japanese dictionary download failed")
                // Tried again the next time lyrics need it.
                fetching.set(false)
            } finally {
                jar.delete()
            }
        }
    }

    /** The phone's tokenizer, reading its dictionary from [dir] instead of the APK. */
    private class FileBuilder(dir: File) : Tokenizer.Builder() {
        init {
            resolver = ResourceResolver { name -> FileInputStream(File(dir, name.substringAfterLast('/'))) as InputStream }
        }
    }
}
