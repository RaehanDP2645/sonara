/**
 * Sonara (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * The built-in updater on Android (requested by the owner): at every launch, and whenever About
 * asks, the newest release is looked up (ReleaseCheck); a newer one is offered in a dialog
 * (UpdateDialog) at most once a day, unless updates are switched off or that version was skipped.
 * A required one (the release's sonara-min is above this version) is shown at every launch and
 * can't be closed. Its APK is
 * downloaded into the cache and handed to Android's installer, which asks the user to confirm
 * (and, the first time, to allow Sonara to install apps). The APK is signed with the same key, so
 * it installs over this one and everything is kept.
 */

package com.metrolist.music.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.datastore.preferences.core.edit
import com.metrolist.music.BuildConfig
import com.metrolist.music.constants.UpdateLastCheckKey
import com.metrolist.music.constants.UpdateNotifyKey
import com.metrolist.music.constants.UpdateSkippedVersionKey
import com.metrolist.music.utils.dataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.io.File

object AppUpdater {
    sealed interface Download {
        data object Idle : Download

        /** [progress] from 0 to 1, or -1 when the size is unknown. */
        data class Running(val progress: Float) : Download

        data class Ready(val file: File) : Download

        data object Failed : Download
    }

    private val availableState = MutableStateFlow<Release?>(null)

    /** A newer release with an APK for this app, once found. */
    val available: StateFlow<Release?> = availableState.asStateFlow()

    private val downloadState = MutableStateFlow<Download>(Download.Idle)
    val download: StateFlow<Download> = downloadState.asStateFlow()

    private val promptState = MutableStateFlow<Release?>(null)

    /** The release the update dialog shows now (MainActivity shows it), or null. */
    val prompt: StateFlow<Release?> = promptState.asStateFlow()

    fun showPrompt(release: Release) {
        promptState.value = release
    }

    fun dismissPrompt() {
        promptState.value = null
    }

    private var checkedAtStart = false

    /**
     * At start (once per launch): looks the newest release up. A required one (below the
     * release's sonara-min) is shown every time and can't be put off; an optional one is offered
     * at most once a day, unless switched off or skipped. Offline, nothing is shown.
     */
    suspend fun checkAtStart(context: Context) {
        if (checkedAtStart) return
        checkedAtStart = true
        val release = check(context).getOrNull() ?: return
        if (isRequired(release)) {
            showPrompt(release)
            return
        }
        val now = System.currentTimeMillis()
        if (now - (context.dataStore.data.first()[UpdateLastCheckKey] ?: 0L) < ReleaseCheck.CHECK_EVERY_MS) return
        if (shouldOffer(context, release)) {
            context.dataStore.edit { it[UpdateLastCheckKey] = now }
            showPrompt(release)
        }
    }

    /** Whether [release] must be installed before Sonara can be used. */
    fun isRequired(release: Release): Boolean = release.isRequiredFor(currentVersion)

    val currentVersion: String get() = BuildConfig.VERSION_NAME.substringBefore('+')

    fun apkOf(release: Release): ReleaseAsset? =
        release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) && it.name.startsWith("Sonara", ignoreCase = true) }
            ?: release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }

    /**
     * Looks the newest release up: the newer release, or null when this is the newest; a
     * failure when GitHub can't be reached.
     */
    suspend fun check(context: Context): Result<Release?> =
        ReleaseCheck.latest().map { release ->
            release.takeIf { ReleaseCheck.isNewer(it.version, currentVersion) && apkOf(it) != null }
        }.onSuccess { availableState.value = it }
            .onFailure { Timber.w(it, "Update check failed") }

    /** Whether to offer [release] by itself: updates are on and the user didn't skip this version. */
    suspend fun shouldOffer(
        context: Context,
        release: Release,
    ): Boolean {
        val prefs = context.dataStore.data.first()
        return (prefs[UpdateNotifyKey] ?: true) && prefs[UpdateSkippedVersionKey] != release.version
    }

    /** "Don't show again": this version is not offered again; a later one is. */
    suspend fun skip(
        context: Context,
        release: Release,
    ) {
        context.dataStore.edit { it[UpdateSkippedVersionKey] = release.version }
    }

    /** Downloads the release's APK (once; a finished download is reused) and opens the installer. */
    suspend fun downloadAndInstall(
        context: Context,
        release: Release,
    ) {
        val asset = apkOf(release) ?: return
        val target = File(File(context.cacheDir, "updates"), "Sonara-${release.version}.apk")
        val ready = (downloadState.value as? Download.Ready)?.file?.takeIf { it == target && it.isFile }
        if (ready != null) {
            install(context, ready)
            return
        }
        if (downloadState.value is Download.Running) return
        target.parentFile?.listFiles()?.filter { it != target }?.forEach { it.delete() }
        downloadState.value = Download.Running(0f)
        ReleaseCheck
            .download(asset, target) { downloadState.value = Download.Running(it) }
            .onSuccess { file ->
                downloadState.value = Download.Ready(file)
                install(context, file)
            }.onFailure {
                Timber.w(it, "Update download failed")
                downloadState.value = Download.Failed
            }
    }

    fun resetFailed() {
        if (downloadState.value is Download.Failed) downloadState.value = Download.Idle
    }

    /**
     * Hands the APK to Android's installer. When Sonara may not install apps yet, opens the
     * setting that allows it; installing again from the dialog then works.
     */
    fun install(
        context: Context,
        file: File,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val allow =
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(allow) }
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.FileProvider", file)
        val intent =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Timber.w(e, "No installer for the update")
        }
    }
}
