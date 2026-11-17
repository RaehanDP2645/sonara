/**
 * Sonara (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Sonara's row on the TV's home screen: the albums and playlists added with "Add to home screen",
 * newest first. Each opens Sonara on that page (sonara://open/?...). Android TV shows the row;
 * some launchers (Google TV among them) don't show apps' rows, and then this does nothing.
 */

package com.metrolist.music.tv

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import com.metrolist.music.R
import com.metrolist.music.utils.SonaraLinks
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

object TvChannel {
    /** Something on the row: [kind] is "album" or "playlist". */
    data class Pin(
        val kind: String,
        val id: String,
        val title: String,
        val image: String?,
    )

    private const val PREFS = "tv_channel"
    private const val CHANNEL = "channel"
    private const val PINS = "pins"
    private const val PROGRAMS = "programs"

    /** The row holds this many at most; the oldest leave first. */
    private const val MAX_PINS = 20

    /** Puts the row on the home screen (once) and refreshes what it shows. */
    fun publish(context: Context) {
        val app = context.applicationContext
        thread(name = "tv-channel") { runCatching { refresh(app) } }
    }

    /** Adds [pin] to the front of the row. */
    fun pin(
        context: Context,
        pin: Pin,
    ) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pins = listOf(pin) + read(prefs.getString(PINS, null)).filterNot { it.kind == pin.kind && it.id == pin.id }
        prefs.edit { putString(PINS, write(pins.take(MAX_PINS))) }
        publish(app)
    }

    private fun refresh(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val helper = PreviewChannelHelper(context)
        var channelId = prefs.getLong(CHANNEL, -1L)
        if (channelId < 0 || helper.getPreviewChannel(channelId) == null) {
            val channel =
                PreviewChannel
                    .Builder()
                    .setDisplayName(context.getString(R.string.tv_channel_name))
                    .setAppLinkIntentUri("sonara://open/".toUri())
                    .setLogo(ContextCompat.getDrawable(context, R.mipmap.ic_launcher)!!.toBitmap(160, 160))
                    .build()
            channelId = helper.publishDefaultChannel(channel)
            prefs.edit { putLong(CHANNEL, channelId) }
            runCatching { TvContractCompat.requestChannelBrowsable(context, channelId) }
        }

        // Every refresh puts the row back as the pins say: old programs out, the pins in.
        prefs.getString(PROGRAMS, null)?.split(',')?.mapNotNull { it.toLongOrNull() }?.forEach { runCatching { helper.deletePreviewProgram(it) } }
        val ids =
            read(prefs.getString(PINS, null)).mapIndexedNotNull { index, pin ->
                val link = if (pin.kind == "album") SonaraLinks.album(pin.id) else SonaraLinks.playlist(pin.id)
                val program =
                    PreviewProgram
                        .Builder()
                        .setChannelId(channelId)
                        .setType(if (pin.kind == "album") TvContractCompat.PreviewPrograms.TYPE_ALBUM else TvContractCompat.PreviewPrograms.TYPE_PLAYLIST)
                        .setTitle(pin.title)
                        .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_1_1)
                        .setIntent(Intent(Intent.ACTION_VIEW, ("sonara://open/?" + link.substringAfter('?')).toUri()).setPackage(context.packageName))
                        .setWeight(MAX_PINS - index)
                        .apply { pin.image?.let { setPosterArtUri(it.toUri()) } }
                        .build()
                runCatching { helper.publishPreviewProgram(program) }.getOrNull()
            }
        prefs.edit { putString(PROGRAMS, ids.joinToString(",")) }
    }

    private fun read(json: String?): List<Pin> =
        runCatching {
            val array = JSONArray(json ?: return emptyList())
            List(array.length()) { i ->
                array.getJSONObject(i).let { Pin(it.getString("kind"), it.getString("id"), it.getString("title"), it.optString("image").ifEmpty { null }) }
            }
        }.getOrDefault(emptyList())

    private fun write(pins: List<Pin>): String =
        JSONArray(
            pins.map { pin ->
                JSONObject().put("kind", pin.kind).put("id", pin.id).put("title", pin.title).put("image", pin.image.orEmpty())
            },
        ).toString()
}
