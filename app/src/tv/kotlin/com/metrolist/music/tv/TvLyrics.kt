/**
 * Sonara (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Lyrics on Sonara TV. Timed lyrics light up line by line and keep the line being sung in view;
 * lyrics without timing, or that can't be timed, just stand still. Under each line, as set: its
 * Latin letters (or the Latin letters in its place) and its translation.
 */

package com.metrolist.music.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.LocalDatabase
import com.metrolist.music.R
import com.metrolist.music.constants.LyricsRomanizeKey
import com.metrolist.music.constants.LyricsRomanizeShowKey
import com.metrolist.music.constants.LyricsTranslateKey
import com.metrolist.music.constants.LyricsTranslateLanguageKey
import com.metrolist.music.constants.ROMANIZE_SHOW_BOTH
import com.metrolist.music.constants.ROMANIZE_SHOW_LATIN
import com.metrolist.music.db.entities.LyricsEntity
import com.metrolist.music.lyrics.LyricsRomanizer
import com.metrolist.music.lyrics.LyricsTranslator
import com.metrolist.music.lyrics.LyricsUtils
import com.metrolist.music.lyrics.lyricsTextLooksSynced
import com.metrolist.music.playback.PlayerConnection
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.delay

private data class TvLine(val time: Long, val text: String)

@Composable
fun TvLyrics(
    playerConnection: PlayerConnection,
    modifier: Modifier = Modifier,
    size: Float = 30f,
    onNone: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.lyrics_not_found), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    },
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val entity by playerConnection.currentLyrics.collectAsStateWithLifecycle(initialValue = null)
    val song by playerConnection.currentSong.collectAsStateWithLifecycle()
    val romanize by rememberPreference(LyricsRomanizeKey, true)
    val show by rememberPreference(LyricsRomanizeShowKey, ROMANIZE_SHOW_BOTH)
    val translate by rememberPreference(LyricsTranslateKey, false)
    val language by rememberPreference(LyricsTranslateLanguageKey, LyricsTranslator.DEFAULT_LANGUAGE)

    val text = entity?.lyrics?.takeIf { it.isNotBlank() && it != LyricsEntity.LYRICS_NOT_FOUND }
    val synced = remember(text) { lyricsTextLooksSynced(text) }
    val lines =
        remember(text) {
            when {
                text == null -> emptyList()
                synced -> runCatching { LyricsUtils.parseLyrics(text) }.getOrDefault(emptyList()).filter { it.text.isNotBlank() }.map { TvLine(it.time, it.text) }
                else -> text.lines().filter { it.isNotBlank() }.map { TvLine(-1, it.trim()) }
            }
        }
    if (lines.isEmpty()) {
        onNone()
        return
    }
    val latin by produceState(emptyMap<String, String>(), lines, romanize) {
        value = if (romanize) runCatching { LyricsRomanizer.romanize(lines.map { it.text }) }.getOrDefault(emptyMap()) else emptyMap()
    }
    val translations = remember(entity, translate, language) { if (translate) LyricsTranslator.storedFor(entity, language).orEmpty() else emptyMap() }
    LaunchedEffect(translate, language, entity?.id) {
        if (translate) entity?.id?.let { LyricsTranslator.ensureTranslated(context, database, it) }
    }

    // The line being sung, read from the player a few times a second; none when not timed.
    var current by remember(lines) { mutableIntStateOf(-1) }
    LaunchedEffect(lines, synced, song?.song?.lyricsOffset) {
        if (!synced) return@LaunchedEffect
        val entries = lines.map { com.metrolist.music.lyrics.LyricsEntry(it.time, it.text) }
        while (true) {
            val position = playerConnection.currentPosition + (song?.song?.lyricsOffset ?: 0)
            current = LyricsUtils.findCurrentLineIndex(entries, position)
            delay(120)
        }
    }
    val list = rememberLazyListState()
    LaunchedEffect(current) {
        if (current < 0) return@LaunchedEffect
        val third = list.layoutInfo.viewportSize.height / 3
        list.animateScrollToItem(current, -third)
    }

    LazyColumn(
        state = list,
        userScrollEnabled = false,
        verticalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = PaddingValues(vertical = 40.dp),
        modifier = modifier,
    ) {
        itemsIndexed(lines, key = { index, line -> "$index:${line.time}" }, contentType = { _, _ -> "line" }) { index, line ->
            val roman = latin[line.text.trim()]
            val now = index == current
            val alpha by animateFloatAsState(
                when {
                    !synced -> 0.9f
                    now -> 1f
                    else -> 0.38f
                },
                label = "tvLyricAlpha",
            )
            Column(Modifier.fillMaxWidth().graphicsLayer { this.alpha = alpha }) {
                Text(
                    if (show == ROMANIZE_SHOW_LATIN && roman != null) roman else line.text,
                    color = Color.White,
                    fontSize = size.sp,
                    lineHeight = (size * 1.25f).sp,
                    fontWeight = FontWeight.Bold,
                )
                if (show != ROMANIZE_SHOW_LATIN && roman != null) {
                    Text(roman, color = Color.White.copy(alpha = 0.75f), fontSize = (size * 0.6f).sp, lineHeight = (size * 0.78f).sp)
                }
                translations[line.text.trim()]?.takeIf { LyricsTranslator.shouldShow(line.text, it) }?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary, fontSize = (size * 0.58f).sp, lineHeight = (size * 0.76f).sp, fontStyle = FontStyle.Italic)
                }
            }
        }
    }
}
