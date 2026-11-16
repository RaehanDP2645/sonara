/**
 * Sonara (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Sonara TV's settings: the ones a TV needs, on rows OK changes, with the phone's own keys (so
 * they behave exactly as on the phone), and "All settings" for the phone's full settings page,
 * so nothing the phone offers is out of reach.
 */

package com.metrolist.music.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.music.App
import com.metrolist.music.BuildConfig
import com.metrolist.music.LocalNavController
import com.metrolist.music.R
import com.metrolist.music.constants.AccountNameKey
import com.metrolist.music.constants.AudioNormalizationKey
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.constants.AudioQualityKey
import com.metrolist.music.constants.AutoRadioQueueKey
import com.metrolist.music.constants.CrossfadeDurationKey
import com.metrolist.music.constants.CrossfadeEnabledDefault
import com.metrolist.music.constants.CrossfadeEnabledKey
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.constants.LyricsRomanizeKey
import com.metrolist.music.constants.LyricsRomanizeShowKey
import com.metrolist.music.constants.LyricsTranslateKey
import com.metrolist.music.constants.LyricsTranslateLanguageKey
import com.metrolist.music.constants.ROMANIZE_SHOW_BOTH
import com.metrolist.music.constants.ROMANIZE_SHOW_LATIN
import com.metrolist.music.constants.UpdateNotifyKey
import com.metrolist.music.lyrics.LyricsTranslator
import com.metrolist.music.update.AppUpdater
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Crossfade lengths OK steps through, in seconds. */
private val CROSSFADE_STEPS = listOf(3f, 5f, 8f, 10f, 12f)

@Composable
fun TvSettings() {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val accountName by rememberPreference(AccountNameKey, "")
    val (quality, onQuality) = rememberEnumPreference(AudioQualityKey, AudioQuality.AUTO)
    val (crossfade, onCrossfade) = rememberPreference(CrossfadeEnabledKey, CrossfadeEnabledDefault)
    val (crossfadeSeconds, onCrossfadeSeconds) = rememberPreference(CrossfadeDurationKey, 5f)
    val (normalize, onNormalize) = rememberPreference(AudioNormalizationKey, true)
    val (autoplay, onAutoplay) = rememberPreference(AutoRadioQueueKey, true)
    val (hideExplicit, onHideExplicit) = rememberPreference(HideExplicitKey, false)
    val (hideVideos, onHideVideos) = rememberPreference(HideVideoSongsKey, false)
    val (romanize, onRomanize) = rememberPreference(LyricsRomanizeKey, true)
    val (romanizeShow, onRomanizeShow) = rememberPreference(LyricsRomanizeShowKey, ROMANIZE_SHOW_BOTH)
    val (translate, onTranslate) = rememberPreference(LyricsTranslateKey, false)
    val (language, onLanguage) = rememberPreference(LyricsTranslateLanguageKey, LyricsTranslator.DEFAULT_LANGUAGE)
    val (notify, onNotify) = rememberPreference(UpdateNotifyKey, true)
    var updateStatus by remember { mutableStateOf<String?>(null) }
    val on = stringResource(R.string.tv_on)
    val off = stringResource(R.string.tv_off)
    fun flag(value: Boolean) = if (value) on else off

    LazyColumn(
        contentPadding = PaddingValues(top = 40.dp, bottom = TvNowPlayingBarHeight + 24.dp, start = TvSize.Gutter, end = TvSize.Gutter),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "account-title", contentType = "title") { Heading(stringResource(R.string.account)) }
        item(key = "account", contentType = "row") {
            Setting(accountName.ifBlank { stringResource(R.string.account) }, stringResource(R.string.action_logout)) {
                scope.launch { App.forgetAccount(context) }
            }
        }

        item(key = "playback-title", contentType = "title") { Heading(stringResource(R.string.er_settings_playback)) }
        item(key = "quality", contentType = "row") {
            Setting(stringResource(R.string.audio_quality), qualityName(quality)) {
                onQuality(AudioQuality.entries[(quality.ordinal + 1) % AudioQuality.entries.size])
            }
        }
        item(key = "crossfade", contentType = "row") {
            Setting(stringResource(R.string.er_crossfade), if (crossfade) stringResource(R.string.er_seconds, crossfadeSeconds.roundToInt()) else off) {
                // Off, then each length in turn, then off again.
                val next = CROSSFADE_STEPS.firstOrNull { it > crossfadeSeconds }
                when {
                    !crossfade -> { onCrossfadeSeconds(CROSSFADE_STEPS.first()); onCrossfade(true) }
                    next != null -> onCrossfadeSeconds(next)
                    else -> onCrossfade(false)
                }
            }
        }
        item(key = "normalize", contentType = "row") { Setting(stringResource(R.string.er_normalize_volume), flag(normalize)) { onNormalize(!normalize) } }
        item(key = "autoplay", contentType = "row") { Setting(stringResource(R.string.er_autoplay), flag(autoplay)) { onAutoplay(!autoplay) } }
        item(key = "explicit", contentType = "row") { Setting(stringResource(R.string.er_hide_explicit), flag(hideExplicit)) { onHideExplicit(!hideExplicit) } }
        item(key = "videos", contentType = "row") { Setting(stringResource(R.string.er_hide_videos), flag(hideVideos)) { onHideVideos(!hideVideos) } }

        item(key = "lyrics-title", contentType = "title") { Heading(stringResource(R.string.er_settings_lyrics)) }
        item(key = "romanize", contentType = "row") { Setting(stringResource(R.string.er_romanize_lyrics), flag(romanize)) { onRomanize(!romanize) } }
        if (romanize) {
            item(key = "romanize-show", contentType = "row") {
                Setting(
                    stringResource(R.string.er_romanize_show),
                    stringResource(if (romanizeShow == ROMANIZE_SHOW_LATIN) R.string.er_romanize_show_latin else R.string.er_romanize_show_both),
                ) { onRomanizeShow(if (romanizeShow == ROMANIZE_SHOW_LATIN) ROMANIZE_SHOW_BOTH else ROMANIZE_SHOW_LATIN) }
            }
        }
        item(key = "translate", contentType = "row") { Setting(stringResource(R.string.er_ai_translator), flag(translate)) { onTranslate(!translate) } }
        if (translate) {
            item(key = "language", contentType = "row") {
                Setting(stringResource(R.string.er_translate_to), LyricsTranslator.languageName(language)) {
                    val codes = LyricsTranslator.languages.map { it.first }
                    onLanguage(codes[(codes.indexOf(language) + 1).mod(codes.size)])
                }
            }
        }

        item(key = "updates-title", contentType = "title") { Heading(stringResource(R.string.er_updates)) }
        item(key = "check", contentType = "row") {
            val checking = stringResource(R.string.er_update_checking)
            val upToDate = stringResource(R.string.er_update_up_to_date)
            val failed = stringResource(R.string.er_update_check_failed)
            Setting(stringResource(R.string.er_update_check), updateStatus ?: BuildConfig.VERSION_NAME) {
                updateStatus = checking
                scope.launch {
                    AppUpdater.check(context)
                        .onSuccess { release ->
                            updateStatus = release?.version ?: upToDate
                            release?.let(AppUpdater::showPrompt)
                        }.onFailure { updateStatus = failed }
                }
            }
        }
        item(key = "notify", contentType = "row") { Setting(stringResource(R.string.er_update_notify), flag(notify)) { onNotify(!notify) } }

        item(key = "more-title", contentType = "title") { Heading(stringResource(R.string.tv_more)) }
        item(key = "all", contentType = "row") { Setting(stringResource(R.string.tv_all_settings), null) { navController.navigate("settings") } }
        item(key = "about", contentType = "row") { Setting(stringResource(R.string.about), null) { navController.navigate("settings/about") } }
    }
}

@Composable
private fun qualityName(quality: AudioQuality): String =
    stringResource(
        when (quality) {
            AudioQuality.AUTO -> R.string.audio_quality_auto
            AudioQuality.HIGH -> R.string.audio_quality_high
            AudioQuality.LOW -> R.string.audio_quality_low
        },
    )

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 24.dp, bottom = 4.dp, start = 4.dp),
    )
}

/** One setting: its name on the left, its value on the right; OK changes it. */
@Composable
private fun Setting(
    title: String,
    value: String?,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .widthIn(max = 880.dp)
                .fillMaxWidth()
                .tvFocusable(shape = RoundedCornerShape(16.dp), scale = 1.02f, onClick = onClick)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                .padding(horizontal = 24.dp, vertical = 18.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (value != null) {
            Spacer(Modifier.padding(start = 16.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}
