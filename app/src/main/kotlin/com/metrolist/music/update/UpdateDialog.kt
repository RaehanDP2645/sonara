/**
 * Sonara (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
 * Offers a newer release: what's new in it (ReleaseCheck.highlights: not the download table),
 * then one wide main button (Download, with progress, then Install for Android's installer) and
 * under it Don't show again (this version is skipped) and Later (asked again on another day).
 * A required update (below the release's sonara-min) can't be closed: Download/Install or Close.
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
    val notes = remember(release.notes) { ReleaseCheck.highlights(release.notes) }
    LaunchedEffect(Unit) { AppUpdater.resetFailed() }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { AppUpdater.resumeInstall(context) }

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
        if (notes.isNotBlank()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .padding(top = Spacing.M)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(notes, style = MaterialTheme.typography.bodyMedium)
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

        // The main action, wide, then the quiet ones in one row under it.
        val state = download
        when {
            state is AppUpdater.Download.Ready ->
                Button(onClick = { AppUpdater.install(context, state.file) }, modifier = Modifier.fillMaxWidth().padding(top = Spacing.L)) {
                    Text(stringResource(R.string.er_update_install))
                }
            state !is AppUpdater.Download.Running ->
                Button(
                    onClick = {
                        // Kept going when the dialog closes: the installer opens once it's done.
                        appScope.launch { AppUpdater.downloadAndInstall(context.applicationContext, release) }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.L),
                ) { Text(stringResource(R.string.er_update_download)) }
        }
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.XS),
        ) {
            when {
                required ->
                    TextButton(onClick = { (context as? android.app.Activity)?.finishAffinity() }) {
                        Text(stringResource(R.string.er_update_quit))
                    }
                state is AppUpdater.Download.Running || state is AppUpdater.Download.Ready ->
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.er_update_hide)) }
                else -> {
                    TextButton(onClick = {
                        scope.launch { AppUpdater.skip(context, release) }
                        onDismiss()
                    }) { Text(stringResource(R.string.er_update_skip)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.er_update_later)) }
                }
            }
        }
    }
}
