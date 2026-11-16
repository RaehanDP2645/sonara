/**
 * Sonara (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.R
import com.metrolist.music.ui.component.DefaultDialog
import com.metrolist.music.ui.theme.Spacing
import com.metrolist.music.utils.appScope
import kotlinx.coroutines.launch

/**
 * Offers a newer release: its notes, then Download (with progress, then Android's installer),
 * Later (asked again on another day) or Don't show again (this version is skipped). A required
 * update (below the release's sonara-min) can't be closed: only Download/Install or Close Sonara.
 */
@Composable
fun UpdateDialog(
    release: Release,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val download by AppUpdater.download.collectAsStateWithLifecycle()
    val required = AppUpdater.isRequired(release)
    LaunchedEffect(Unit) { AppUpdater.resetFailed() }

    DefaultDialog(
        onDismiss = { if (!required) onDismiss() },
        title = {
            Text(
                if (required) {
                    stringResource(R.string.er_update_required_title)
                } else {
                    stringResource(R.string.er_update_title, release.version)
                },
            )
        },
        buttons = {
            if (required) {
                TextButton(onClick = { (context as? android.app.Activity)?.finishAffinity() }) {
                    Text(stringResource(R.string.er_update_quit))
                }
            }
            when (val state = download) {
                is AppUpdater.Download.Running ->
                    if (!required) TextButton(onClick = onDismiss) { Text(stringResource(R.string.er_update_hide)) }
                is AppUpdater.Download.Ready ->
                    TextButton(onClick = { AppUpdater.install(context, state.file) }) { Text(stringResource(R.string.er_update_install)) }
                else -> {
                    if (!required) {
                        TextButton(onClick = {
                            scope.launch { AppUpdater.skip(context, release) }
                            onDismiss()
                        }) { Text(stringResource(R.string.er_update_skip)) }
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.er_update_later)) }
                    }
                    TextButton(onClick = {
                        // Kept going when the dialog closes: the installer opens once it's done.
                        appScope.launch { AppUpdater.downloadAndInstall(context.applicationContext, release) }
                    }) { Text(stringResource(R.string.er_update_download)) }
                }
            }
        },
        horizontalAlignment = androidx.compose.ui.Alignment.Start,
    ) {
        Text(
            if (required) {
                stringResource(R.string.er_update_required, release.version)
            } else {
                stringResource(R.string.er_update_current, AppUpdater.currentVersion)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (release.notes.isNotBlank()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .padding(top = Spacing.M)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(plainNotes(release.notes), style = MaterialTheme.typography.bodyMedium)
            }
        }
        when (val state = download) {
            is AppUpdater.Download.Running -> {
                Text(
                    stringResource(R.string.er_update_downloading),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = Spacing.M, bottom = Spacing.S),
                )
                if (state.progress >= 0f) {
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            AppUpdater.Download.Failed ->
                Text(
                    stringResource(R.string.er_update_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = Spacing.M),
                )
            else -> Unit
        }
    }
}

/** Release notes are Markdown: shown without the marks (**, #, `), links as their text. */
internal fun plainNotes(markdown: String): String =
    markdown
        .replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
        .replace(Regex("(?m)^#{1,6}\\s*"), "")
        .replace("**", "")
        .replace("`", "")
        .replace(Regex("(?m)^\\s*[-*]\\s+"), "• ")
        .trim()
