/**
 * SONARA (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.ermusic.desktop.ui

import kotlinx.coroutines.launch
import kotlin.system.exitProcess
import com.ermusic.desktop.window.Uninstall
import com.ermusic.desktop.window.DataChoice
import com.ermusic.desktop.BuildSecrets
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberCoroutineScope
import com.ermusic.desktop.update.DesktopUpdater
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.ermusic.desktop.data.Downloads
import com.ermusic.desktop.data.Pref
import com.ermusic.desktop.data.Settings
import com.ermusic.desktop.playback.Equalizer
import com.ermusic.desktop.playback.LyricsTranslator
import com.ermusic.desktop.playback.SongCache
import com.ermusic.desktop.Os
import com.ermusic.desktop.keepsPlayingInTray
import com.ermusic.desktop.window.StartAtLogin
import kotlin.math.roundToInt

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(bottom = Spacing.XL)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = Spacing.S, bottom = Spacing.S))
        Column(Modifier.fillMaxWidth().clip(ErShape.Medium).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(Spacing.M)) { content() }
    }
}

@Composable
private fun SettingRow(title: String, description: String?, trailing: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.S)) {
        Column(Modifier.weight(1f).padding(end = Spacing.L)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        trailing()
    }
}

@Composable
private fun Toggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
        modifier = Modifier.handCursor(),
    )
}

@Composable
private fun SwitchRow(title: String, description: String?, pref: Pref<Boolean>) {
    SettingRow(title, description) { Toggle(pref.value) { pref.value = it } }
}

@Composable
private fun <T> ChoiceRow(title: String, description: String?, options: List<Pair<T, String>>, selected: T, menuModifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    SettingRow(title, description) {
        Box {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(ErShape.Small).clickable { open = true }.handCursor().padding(Spacing.S)) {
                Text(options.firstOrNull { it.first == selected }?.second.orEmpty(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Icon(icon("expand_more"), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(Size.IconSmall))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = ErShape.Medium, containerColor = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = menuModifier) {
                options.forEach { (value, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = {
                        onSelect(value)
                        open = false
                    }, modifier = Modifier.handCursor())
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(app: AppState) {
    var cacheBytes by remember { mutableLongStateOf(SongCache.sizeBytes()) }
    var startsAtLogin by remember { mutableStateOf(StartAtLogin.enabled()) }
    var uninstalling by remember { mutableStateOf(false) }
    if (uninstalling) UninstallDialog(app) { uninstalling = false }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        ScrollingColumn(Modifier.widthIn(max = Layout.ReadingMaxWidth).fillMaxSize(), contentPadding = PaddingValues(horizontal = Spacing.L, vertical = Spacing.L)) {
            item(key = "title", contentType = "title") { Text("Settings", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(start = Spacing.S, bottom = Spacing.L)) }
            item(key = "look", contentType = "group") {
                Group("Appearance") {
                    ChoiceRow("Theme", null, listOf("dark" to "Dark", "light" to "Light", "system" to "Same as ${Os.name}"), Settings.theme.value) { Settings.theme.value = it }
                    SwitchRow("Open the song panel when playing", null, Settings.openNowPlaying)
                }
            }
            item(key = "playback", contentType = "group") {
                Group("Playback") {
                    SwitchRow("High quality audio", null, Settings.highQuality)
                    SwitchRow("Normalize volume", null, Settings.normalize)
                    SwitchRow("Mono audio", null, Settings.mono)
                    SwitchRow("Autoplay", "Similar songs when the queue ends", Settings.autoplay)
                    val seconds = Settings.crossfadeSeconds.value
                    SettingRow("Crossfade", if (seconds == 0) "Off" else "$seconds seconds between songs") {
                        ThinSlider(value = seconds.toFloat() / MAX_CROSSFADE, onChange = { Settings.crossfadeSeconds.value = (it * MAX_CROSSFADE).roundToInt() }, modifier = Modifier.width(CROSSFADE_WIDTH))
                    }
                }
            }
            item(key = "equalizer", contentType = "group") {
                Group("Equalizer") {
                    SwitchRow("Equalizer", null, Settings.eqEnabled)
                    if (Settings.eqEnabled.value) EqualizerControls()
                }
            }
            item(key = "content", contentType = "group") {
                Group("Content") {
                    SwitchRow("Hide explicit content", null, Settings.hideExplicit)
                    SwitchRow("Hide music videos", null, Settings.hideVideos)
                }
            }
            item(key = "lyrics", contentType = "group") {
                Group("Lyrics") {
                    SwitchRow("Romanization", null, Settings.romanize)
                    if (Settings.romanize.value) {
                        ChoiceRow("Show", null, listOf("both" to "Original and Latin", "latin" to "Latin only"), Settings.romanizeShow.value) { Settings.romanizeShow.value = it }
                    }
                    SwitchRow("AI translator", null, Settings.translator)
                    if (Settings.translator.value) {
                        ChoiceRow("Translate into", null, LyricsTranslator.languages, Settings.translateLanguage.value, Modifier.height(MENU_HEIGHT)) { Settings.translateLanguage.value = it }
                    }
                }
            }
            item(key = "app", contentType = "group") {
                Group(Os.name) {
                    if (Os.isMac) {
                        SwitchRow("Keep playing in the menu bar", null, Settings.closeToTray)
                    } else if (Os.isLinux) {
                        if (keepsPlayingInTray) SwitchRow("Keep playing in the tray", null, Settings.closeToTray)
                    } else {
                        SwitchRow("Keep playing in the tray", null, Settings.closeToTray)
                    }
                    if (StartAtLogin.available) {
                        SettingRow(StartAtLogin.label, null) {
                            Toggle(startsAtLogin) {
                                StartAtLogin.set(it)
                                startsAtLogin = StartAtLogin.enabled()
                            }
                        }
                    }
                }
            }
            item(key = "storage", contentType = "group") {
                Group("Storage") {
                    ChoiceRow(
                        "Keep for playing again",
                        "Recent songs, also offline",
                        SongCache.sizes.map { it to if (it < MB_PER_GB) "$it MB" else "${it / MB_PER_GB} GB" },
                        Settings.songCacheMb.value,
                    ) {
                        Settings.songCacheMb.value = it
                        SongCache.trim()
                        cacheBytes = SongCache.sizeBytes()
                    }
                    SettingRow("Song cache", "%.0f MB".format(cacheBytes / BYTES_PER_MB)) {
                        OutlinedButton(onClick = {
                            SongCache.clear()
                            cacheBytes = SongCache.sizeBytes()
                            app.showToast("Cache cleared")
                        }, modifier = Modifier.handCursor()) { Text("Clear") }
                    }
                    SettingRow("Downloads", songCount(Downloads.tracks.size)) {
                        OutlinedButton(onClick = { Downloads.tracks.toList().forEach { Downloads.remove(it.id) } }, enabled = Downloads.tracks.isNotEmpty(), modifier = Modifier.handCursor()) { Text("Remove all") }
                    }
                    SettingRow("Uninstall Sonara", null) {
                        OutlinedButton(onClick = { if (Os.isWindows) openAppsSettings() else uninstalling = true }, modifier = Modifier.handCursor()) {
                            Text(if (Os.isWindows) "Open Apps" else "Uninstall…")
                        }
                    }
                }
            }
            item(key = "account", contentType = "group") {
                Group("Account") {
                    SettingRow(app.profile?.name ?: "Google account", app.profile?.email) {
                        OutlinedButton(onClick = app.onSignOut, modifier = Modifier.handCursor()) { Text("Sign out") }
                    }
                }
            }
            item(key = "about", contentType = "group") {
                Group("About") {
                    Text("Sonara $APP_VERSION for ${Os.name}", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(Spacing.XS))
                    Text("GPL-3.0, derived from Metrolist", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(Spacing.M))
                    UpdateRow()
                    SwitchRow("Tell me about new versions", null, Settings.updateNotify)
                }
            }
        }
    }
}

/** Asks GitHub for the newest release now; a newer one opens the update dialog, even one skipped earlier. */
@Composable
private fun UpdateRow() {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    SettingRow("Check for updates", status) {
        OutlinedButton(
            onClick = {
                status = "Checking…"
                scope.launch {
                    DesktopUpdater.check()
                        .onSuccess { release ->
                            status = release?.let { "Version ${it.version} is available" } ?: "You have the newest version"
                            release?.let { DesktopUpdater.prompt = it }
                        }.onFailure { status = "Couldn't check. Try again later." }
                }
            },
            enabled = status != "Checking…",
            modifier = Modifier.handCursor(),
        ) { Text("Check") }
    }
}

@Composable
private fun EqualizerControls() {
    val gains = Equalizer.parse(Settings.eqGains.value)
    val preset = Equalizer.PRESETS.entries.firstOrNull { (_, values) -> values.map { it.toFloat() } == gains }?.key
    ChoiceRow("Preset", null, Equalizer.PRESETS.keys.map { it to it } + listOfNotNull(("Custom" to "Custom").takeIf { preset == null }), preset ?: "Custom") { name ->
        Equalizer.PRESETS[name]?.let { values -> Settings.eqGains.value = Equalizer.encode(values.map { it.toFloat() }) }
    }
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(top = Spacing.S)) {
        Equalizer.LABELS.forEachIndexed { band, label ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                VerticalSlider((gains[band] + Equalizer.MAX_GAIN_DB) / (2 * Equalizer.MAX_GAIN_DB), EQ_SLIDER_HEIGHT) { level ->
                    val value = (level * 2 * Equalizer.MAX_GAIN_DB - Equalizer.MAX_GAIN_DB).roundToInt().toFloat()
                    val current = Equalizer.parse(Settings.eqGains.value)
                    Settings.eqGains.value = Equalizer.encode(current.mapIndexed { index, old -> if (index == band) value else old })
                }
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private val EQ_SLIDER_HEIGHT = 140.dp
/** appVersion in build.gradle.kts, bumped for every installer. */
private const val APP_VERSION = BuildSecrets.APP_VERSION
private const val BYTES_PER_MB = 1_048_576.0
private const val MB_PER_GB = 1024
private const val MAX_CROSSFADE = 12
private val CROSSFADE_WIDTH = 160.dp
private val MENU_HEIGHT = 320.dp

private fun openAppsSettings() {
    runCatching { ProcessBuilder("explorer.exe", "ms-settings:appsfeatures").start() }
}

private fun quit(app: AppState) {
    app.player.shutdown()
    exitProcess(0)
}

/** A Mac's and Linux's way to remove Sonara, with the user's say over their data. */
@Composable
private fun UninstallDialog(app: AppState, onDismiss: () -> Unit) {
    var choice by remember { mutableStateOf(DataChoice.Keep) }
    var outcome by remember { mutableStateOf<String?>(null) }
    val done = outcome
    if (done != null) {
        AlertDialog(
            onDismissRequest = { quit(app) },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Uninstall Sonara", style = MaterialTheme.typography.titleLarge) },
            text = { Text(done) },
            confirmButton = { TextButton(onClick = { quit(app) }, modifier = Modifier.handCursor()) { Text("Quit") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("Uninstall Sonara", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                listOf(
                    DataChoice.Keep to "Keep my data",
                    DataChoice.Delete to "Delete everything",
                ).forEach { (option, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clip(ErShape.Small).clickable { choice = option }.handCursor().padding(vertical = Spacing.XS),
                    ) {
                        RadioButton(selected = choice == option, onClick = { choice = option })
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                outcome =
                    when (val result = Uninstall.uninstallHere(choice)) {
                        Uninstall.Result.Removed -> "Sonara was removed."
                        is Uninstall.Result.RemoveYourself -> "To finish: ${result.how}"
                    }
            }, modifier = Modifier.handCursor()) { Text("Uninstall") }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.handCursor()) { Text("Cancel") } },
    )
}
