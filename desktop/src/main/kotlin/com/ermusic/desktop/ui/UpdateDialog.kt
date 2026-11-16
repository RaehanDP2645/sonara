/**
 * SONARA (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.ermusic.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.ermusic.desktop.Os
import com.ermusic.desktop.update.DesktopUpdater
import com.metrolist.music.update.Release
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

/** Downloads keep going when the dialog closes. */
private val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * Offers a newer release, as the phone does: its notes, then Download (with progress, then the
 * installer for this system), Later (asked again on another day) or Don't show again. A required
 * update (below the release's sonara-min) can't be closed: only Download/Install or Quit.
 */
@Composable
fun UpdateDialog(release: Release) {
    LaunchedEffect(Unit) { DesktopUpdater.resetFailed() }
    val download = DesktopUpdater.download
    val required = DesktopUpdater.isRequired(release)
    val close = { DesktopUpdater.prompt = null }
    AlertDialog(
        onDismissRequest = { if (!required) close() },
        properties = DialogProperties(dismissOnBackPress = !required, dismissOnClickOutside = !required),
        title = { Text(if (required) "Update Sonara to keep using it" else "Sonara ${release.version} is available") },
        text = {
            Column(Modifier.widthIn(max = 520.dp)) {
                Text(
                    if (required) "This version no longer works. Install Sonara ${release.version} to go on." else "You have ${DesktopUpdater.currentVersion}.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (release.notes.isNotBlank()) {
                    Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).padding(top = Spacing.M).verticalScroll(rememberScrollState())) {
                        Text(plainNotes(release.notes), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                when (download) {
                    is DesktopUpdater.Download.Running -> {
                        Text("Downloading…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Spacing.M, bottom = Spacing.S))
                        if (download.progress >= 0f) {
                            LinearProgressIndicator(progress = { download.progress }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                    is DesktopUpdater.Download.Ready ->
                        Text(installHint(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Spacing.M))
                    DesktopUpdater.Download.Failed ->
                        Text("The download didn't finish. Try again.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = Spacing.M))
                    DesktopUpdater.Download.Idle -> Unit
                }
            }
        },
        confirmButton = {
            when (download) {
                is DesktopUpdater.Download.Running ->
                    if (!required) TextButton(onClick = close, modifier = Modifier.handCursor()) { Text("Hide") }
                is DesktopUpdater.Download.Ready -> TextButton(onClick = { DesktopUpdater.install(download.file) }, modifier = Modifier.handCursor()) { Text("Install") }
                else -> TextButton(onClick = { updateScope.launch { DesktopUpdater.downloadAndInstall(release) } }, modifier = Modifier.handCursor()) { Text("Download") }
            }
        },
        dismissButton = {
            if (required) {
                TextButton(onClick = { exitProcess(0) }, modifier = Modifier.handCursor()) { Text("Quit Sonara") }
            } else if (download !is DesktopUpdater.Download.Running && download !is DesktopUpdater.Download.Ready) {
                TextButton(onClick = { DesktopUpdater.skip(release) }, modifier = Modifier.handCursor()) { Text("Don't show again") }
                TextButton(onClick = close, modifier = Modifier.handCursor()) { Text("Later") }
            }
        },
    )
}

private fun installHint(): String =
    when {
        Os.isWindows -> "The installer is open. It closes Sonara and keeps your data."
        Os.isMac -> "The disk image is open: drag Sonara to Applications, replacing the old one."
        else -> "The update is open in your system's installer."
    }

/** Release notes are Markdown: shown without the marks (**, #, `), links as their text. */
private fun plainNotes(markdown: String): String =
    markdown
        .replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
        .replace(Regex("(?m)^#{1,6}\\s*"), "")
        .replace("**", "")
        .replace("`", "")
        .replace(Regex("(?m)^\\s*[-*]\\s+"), "• ")
        .trim()
