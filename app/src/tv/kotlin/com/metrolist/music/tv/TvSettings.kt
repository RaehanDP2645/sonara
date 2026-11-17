/**
 * Sonara (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Sonara TV's settings, all here: switches OK turns on and off, and values OK steps through. They
 * use the phone's own keys, so they behave as on the phone.
 */

package com.metrolist.music.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.metrolist.music.App
import com.metrolist.music.BuildConfig
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
private val CROSSFADE = listOf(3f, 5f, 8f, 12f)

@Composable
fun TvSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accountName by rememberPreference(AccountNameKey, "")
    val (quality, onQuality) = rememberEnumPreference(AudioQualityKey, AudioQuality.AUTO)
    val (crossfade, onCrossfade) = rememberPreference(CrossfadeEnabledKey, CrossfadeEnabledDefault)
    val (seconds, onSeconds) = rememberPreference(CrossfadeDurationKey, 5f)
    val (normalize, onNormalize) = rememberPreference(AudioNormalizationKey, true)
    val (autoplay, onAutoplay) = rememberPreference(AutoRadioQueueKey, true)
    val (hideExplicit, onHideExplicit) = rememberPreference(HideExplicitKey, false)
    val (hideVideos, onHideVideos) = rememberPreference(HideVideoSongsKey, false)
    val (romanize, onRomanize) = rememberPreference(LyricsRomanizeKey, true)
    val (show, onShow) = rememberPreference(LyricsRomanizeShowKey, ROMANIZE_SHOW_BOTH)
    val (translate, onTranslate) = rememberPreference(LyricsTranslateKey, false)
    val (language, onLanguage) = rememberPreference(LyricsTranslateLanguageKey, LyricsTranslator.DEFAULT_LANGUAGE)
    val (notify, onNotify) = rememberPreference(UpdateNotifyKey, true)
    var status by remember { mutableStateOf<String?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val checking = stringResource(R.string.er_update_checking)
    val upToDate = stringResource(R.string.er_update_up_to_date)
    val failed = stringResource(R.string.er_update_check_failed)

    LazyColumn(
        contentPadding = PaddingValues(start = Tv.SafeX, end = Tv.SafeX, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "account-h", contentType = "h") { Heading(stringResource(R.string.account)) }
        item(key = "account", contentType = "row") {
            TvChoiceRow(accountName.ifBlank { stringResource(R.string.account) }, stringResource(if (confirmSignOut) R.string.tv_sign_out_confirm else R.string.action_logout)) {
                if (confirmSignOut) scope.launch { App.forgetAccount(context) } else confirmSignOut = true
            }
        }

        item(key = "play-h", contentType = "h") { Heading(stringResource(R.string.er_settings_playback)) }
        item(key = "quality", contentType = "row") {
            TvChoiceRow(stringResource(R.string.audio_quality), stringResource(qualityName(quality))) {
                onQuality(AudioQuality.entries[(quality.ordinal + 1) % AudioQuality.entries.size])
            }
        }
        item(key = "crossfade", contentType = "row") { TvSwitchRow(stringResource(R.string.er_crossfade), crossfade, onCrossfade) }
        if (crossfade) {
            item(key = "seconds", contentType = "row") {
                TvChoiceRow(stringResource(R.string.tv_crossfade_length), stringResource(R.string.er_seconds, seconds.roundToInt())) {
                    onSeconds(CROSSFADE.firstOrNull { it > seconds } ?: CROSSFADE.first())
                }
            }
        }
        item(key = "normalize", contentType = "row") { TvSwitchRow(stringResource(R.string.er_normalize_volume), normalize, onNormalize) }
        item(key = "autoplay", contentType = "row") { TvSwitchRow(stringResource(R.string.er_autoplay), autoplay, onAutoplay) }
        item(key = "explicit", contentType = "row") { TvSwitchRow(stringResource(R.string.er_hide_explicit), hideExplicit, onHideExplicit) }
        item(key = "videos", contentType = "row") { TvSwitchRow(stringResource(R.string.er_hide_videos), hideVideos, onHideVideos) }

        item(key = "lyrics-h", contentType = "h") { Heading(stringResource(R.string.er_settings_lyrics)) }
        item(key = "romanize", contentType = "row") { TvSwitchRow(stringResource(R.string.er_romanize_lyrics), romanize, onRomanize) }
        if (romanize) {
            item(key = "show", contentType = "row") {
                TvChoiceRow(
                    stringResource(R.string.er_romanize_show),
                    stringResource(if (show == ROMANIZE_SHOW_LATIN) R.string.er_romanize_show_latin else R.string.er_romanize_show_both),
                ) { onShow(if (show == ROMANIZE_SHOW_LATIN) ROMANIZE_SHOW_BOTH else ROMANIZE_SHOW_LATIN) }
            }
        }
        item(key = "translate", contentType = "row") { TvSwitchRow(stringResource(R.string.er_ai_translator), translate, onTranslate) }
        if (translate) {
            item(key = "language", contentType = "row") {
                TvChoiceRow(stringResource(R.string.er_translate_to), LyricsTranslator.languageName(language)) {
                    val codes = LyricsTranslator.languages.map { it.first }
                    onLanguage(codes[(codes.indexOf(language) + 1).mod(codes.size)])
                }
            }
        }

        item(key = "updates-h", contentType = "h") { Heading(stringResource(R.string.er_updates)) }
        item(key = "check", contentType = "row") {
            TvChoiceRow(stringResource(R.string.er_update_check), status ?: BuildConfig.VERSION_NAME) {
                status = checking
                scope.launch {
                    AppUpdater.check(context)
                        .onSuccess { release ->
                            status = release?.version ?: upToDate
                            release?.let(AppUpdater::showPrompt)
                        }.onFailure { status = failed }
                }
            }
        }
        item(key = "notify", contentType = "row") { TvSwitchRow(stringResource(R.string.er_update_notify), notify, onNotify) }
    }
}

private fun qualityName(quality: AudioQuality): Int =
    when (quality) {
        AudioQuality.AUTO -> R.string.audio_quality_auto
        AudioQuality.HIGH -> R.string.audio_quality_high
        AudioQuality.LOW -> R.string.audio_quality_low
    }

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 20.dp, bottom = 2.dp, start = 4.dp))
}
