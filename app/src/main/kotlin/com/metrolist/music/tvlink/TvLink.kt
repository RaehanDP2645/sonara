/**
 * Sonara (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Requested by the owner: signing Sonara TV in from a phone. The TV shows a QR code and a
 * 6-digit code; a signed-in phone scans or types it, the owner confirms, and the phone sends its
 * YouTube Music session to the TV. The owner's proxy (web/, /api/tv/...) only relays it: the
 * session is sealed for the TV alone (ECDH P-256 with a key pair the TV makes for this sign-in,
 * HKDF-SHA256, AES-256-GCM), so the server never holds anything it can read.
 */

package com.metrolist.music.tvlink

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.metrolist.music.BuildConfig
import com.metrolist.music.lyrics.LyricsTranslator
import com.metrolist.music.lyrics.SealedKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object TvLink {
    /** The page a phone's own camera opens from the TV's QR code; Sonara reads the same link. */
    const val LINK_HOST = "sonara.rashiiddsr.my.id"
    const val LINK_PATH = "/tv/"

    /** The session the phone sends: what LoginScreen saves after a sign-in. */
    @Serializable
    data class Account(
        val cookie: String,
        val visitorData: String = "",
        val dataSyncId: String = "",
        val authUser: String = "0",
        val accountName: String = "",
        val accountEmail: String = "",
        val channelHandle: String = "",
        val imageUrl: String = "",
    )

    data class Session(val id: String, val code: String, val codeExpiresIn: Int, val expiresIn: Int)

    sealed interface Poll {
        data class Waiting(val code: String, val codeExpiresIn: Int) : Poll

        data class Ready(val phonePub: String, val iv: String, val data: String) : Poll

        data object Expired : Poll
    }

    /** A TV waiting for a sign-in, found by its QR code or its code. */
    data class Target(val session: String, val pub: String, val name: String)

    private val json = Json { ignoreUnknownKeys = true }
    private const val TIMEOUT_MS = 15_000
    private const val INFO = "sonara-tv-pair-v1"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    // ---- Links ----

    fun linkFor(session: String): String = "https://$LINK_HOST${LINK_PATH}?s=$session"

    /** The session in a scanned or opened link (the TV's QR, or sonara://tv?s=...), or null. */
    fun sessionFrom(text: String): String? {
        val uri = runCatching { Uri.parse(text.trim()) }.getOrNull() ?: return null
        val ours =
            (uri.scheme == "https" && uri.host == LINK_HOST && uri.path.orEmpty().trimEnd('/') == LINK_PATH.trimEnd('/')) ||
                (uri.scheme == "sonara" && uri.host == "tv")
        if (!ours) return null
        return uri.getQueryParameter("s")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{20,64}")) }
    }

    // ---- The TV ----

    fun newKeys(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), SecureRandom()) }.generateKeyPair()

    fun encode(key: PublicKey): String = Base64.encodeToString(key.encoded, Base64.NO_WRAP)

    suspend fun start(
        context: Context,
        keys: KeyPair,
        name: String,
    ): Result<Session> =
        call(context, "/api/tv/start", buildJsonObject {
            put("pub", encode(keys.public))
            put("name", name.trim().take(60).ifEmpty { "TV" })
        }).map { answer ->
            val root = checkNotNull(answer)
            Session(
                id = root.string("session") ?: error("no session"),
                code = root.string("code") ?: error("no code"),
                codeExpiresIn = root.int("code_expires_in") ?: 60,
                expiresIn = root.int("expires_in") ?: 600,
            )
        }

    suspend fun poll(
        context: Context,
        session: String,
    ): Result<Poll> =
        call(context, "/api/tv/poll", buildJsonObject { put("session", session) }, notFoundAsNull = true).map { root ->
            when {
                root == null -> Poll.Expired
                root.string("state") == "ready" ->
                    Poll.Ready(root.string("phone_pub").orEmpty(), root.string("iv").orEmpty(), root.string("data").orEmpty())
                else -> Poll.Waiting(root.string("code").orEmpty(), root.int("code_expires_in") ?: 60)
            }
        }

    /** The account a phone sealed for this TV ([keys], [session]). */
    fun open(
        keys: KeyPair,
        session: String,
        ready: Poll.Ready,
    ): Account {
        val key = sharedKey(keys.private, decodePublic(ready.phonePub), session)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, Base64.decode(ready.iv, Base64.NO_WRAP)))
        cipher.updateAAD(session.toByteArray(Charsets.UTF_8))
        val plain = cipher.doFinal(Base64.decode(ready.data, Base64.NO_WRAP))
        return json.decodeFromString(Account.serializer(), plain.toString(Charsets.UTF_8))
    }

    // ---- The phone ----

    /** The TV for a QR link's session or a typed code; null when there is none waiting. */
    suspend fun lookup(
        context: Context,
        session: String? = null,
        code: String? = null,
    ): Result<Target?> =
        call(context, "/api/tv/lookup", buildJsonObject {
            session?.let { put("session", it) }
            code?.let { put("code", it) }
        }, notFoundAsNull = true).map { root ->
            root?.let {
                Target(
                    session = it.string("session") ?: return@map null,
                    pub = it.string("pub") ?: return@map null,
                    name = it.string("name").orEmpty(),
                )
            }
        }

    /** Seals [account] for [target] alone and hands it to the proxy for the TV to pick up. */
    suspend fun deliver(
        context: Context,
        target: Target,
        account: Account,
    ): Result<Unit> {
        val mine = newKeys()
        val key = sharedKey(mine.private, decodePublic(target.pub), target.session)
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(target.session.toByteArray(Charsets.UTF_8))
        val sealed = cipher.doFinal(json.encodeToString(Account.serializer(), account).toByteArray(Charsets.UTF_8))
        return call(context, "/api/tv/deliver", buildJsonObject {
            put("session", target.session)
            put("phone_pub", encode(mine.public))
            put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            put("data", Base64.encodeToString(sealed, Base64.NO_WRAP))
        }).map { }
    }

    // ---- Crypto ----

    private fun decodePublic(base64: String): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(base64, Base64.NO_WRAP)))

    /** ECDH, then HKDF-SHA256 (salt: the session, so a key never serves two sign-ins). */
    private fun sharedKey(
        mine: PrivateKey,
        theirs: PublicKey,
        session: String,
    ): ByteArray {
        val secret = KeyAgreement.getInstance("ECDH").apply { init(mine); doPhase(theirs, true) }.generateSecret()
        return hkdf(secret, session.toByteArray(Charsets.UTF_8), INFO.toByteArray(Charsets.UTF_8), 32)
    }

    internal fun hkdf(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        val extract = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt, "HmacSHA256")) }.doFinal(ikm)
        val out = java.io.ByteArrayOutputStream()
        var previous = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(extract, "HmacSHA256")) }
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            out.write(previous)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    // ---- The proxy ----

    private val secret by lazy { SealedKey.open(BuildConfig.SONARA_API_SECRET_SEALED) }

    /** One signed POST to the proxy, as the translator signs (LyricsTranslator.signature). */
    private suspend fun call(
        context: Context,
        path: String,
        body: JsonObject,
        notFoundAsNull: Boolean = false,
    ): Result<JsonObject?> =
        withContext(Dispatchers.IO) {
            runCatching {
                check(secret.isNotBlank()) { "No client secret built in" }
                val text = body.toString()
                val install = LyricsTranslator.installId(context)
                val time = (System.currentTimeMillis() / 1000).toString()
                val base = BuildConfig.SONARA_API_URL.trimEnd('/').removeSuffix("/api")
                val connection = URL(base + path).openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = TIMEOUT_MS
                    connection.readTimeout = TIMEOUT_MS
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setRequestProperty("X-Sonara-Install", install)
                    connection.setRequestProperty("X-Sonara-Time", time)
                    connection.setRequestProperty("X-Sonara-Client", (if (BuildConfig.IS_TV) "tv/" else "android/") + BuildConfig.VERSION_NAME)
                    connection.setRequestProperty("X-Sonara-Signature", LyricsTranslator.signature(secret, path, time, install, text))
                    connection.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    val status = connection.responseCode
                    if (status == HttpURLConnection.HTTP_NOT_FOUND && notFoundAsNull) return@runCatching null
                    val answer = (if (status in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                    check(status in 200..299) { "TV sign-in: HTTP $status" }
                    json.parseToJsonElement(answer) as JsonObject
                } finally {
                    connection.disconnect()
                }
            }
        }

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull
}
