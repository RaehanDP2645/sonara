/**
 * SONARA (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * The built-in updater on the desktop, as on the phone (app/.../update/AppUpdater.kt): at every
 * start, and whenever About asks, the newest release on GitHub is looked up (ReleaseCheck, shared
 * with the phone); a newer one is offered in a dialog at most once a day unless updates are
 * switched off or that version was skipped, and a required one (sonara-min) every time. The file for this system is downloaded, then:
 * - Windows: the .msi runs (it closes Sonara itself and keeps the data, wix/main.wxs);
 * - macOS: the .dmg for this Mac (Apple Silicon or Intel) opens, to drag Sonara to Applications;
 * - Linux: an AppImage replaces itself and starts again; with the .deb, the package opens in the
 *   system's installer.
 */

package com.ermusic.desktop.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ermusic.desktop.AppFiles
import com.ermusic.desktop.BuildSecrets
import com.ermusic.desktop.Os
import com.ermusic.desktop.data.Settings
import com.metrolist.music.update.Release
import com.metrolist.music.update.ReleaseAsset
import com.metrolist.music.update.ReleaseCheck
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.system.exitProcess

object DesktopUpdater {
    sealed interface Download {
        data object Idle : Download

        /** [progress] from 0 to 1, or -1 when the size is unknown. */
        data class Running(val progress: Float) : Download

        data class Ready(val file: File) : Download

        data object Failed : Download
    }

    val currentVersion: String get() = BuildSecrets.APP_VERSION

    /** The release the update dialog shows now, or null. */
    var prompt by mutableStateOf<Release?>(null)

    var download by mutableStateOf<Download>(Download.Idle)
        private set

    /** Running from an AppImage: its own path (set by the AppImage runtime). */
    private val appImage: File? get() = System.getenv("APPIMAGE")?.let(::File)?.takeIf { it.isFile }

    /** The file of [release] for this system, or null when the release has none. */
    fun assetFor(release: Release): ReleaseAsset? {
        fun ending(suffix: String) = release.assets.firstOrNull { it.name.endsWith(suffix, ignoreCase = true) }
        return when {
            Os.isWindows -> ending(".msi")
            Os.isMac -> if (System.getProperty("os.arch") == "aarch64") ending("-mac-arm64.dmg") else ending("-mac-intel.dmg")
            Os.isLinux -> if (appImage != null) ending(".AppImage") else ending(".deb")
            else -> null
        }
    }

    /** Looks the newest release up: the newer release, null when this is the newest, a failure when offline. */
    suspend fun check(): Result<Release?> =
        ReleaseCheck.latest().map { release ->
            release.takeIf { ReleaseCheck.isNewer(it.version, currentVersion) && assetFor(it) != null }
        }

    /** Whether [release] must be installed before Sonara can be used (below its sonara-min). */
    fun isRequired(release: Release): Boolean = release.isRequiredFor(currentVersion)

    /**
     * At start: looks the newest release up. A required one is shown every time and can't be put
     * off; an optional one at most once a day, unless switched off or skipped. Offline, nothing.
     */
    suspend fun checkAtStart() {
        val release = check().getOrNull() ?: return
        if (isRequired(release)) {
            prompt = release
            return
        }
        val now = System.currentTimeMillis()
        if (now - Settings.updateLastCheck.value < ReleaseCheck.CHECK_EVERY_MS) return
        if (Settings.updateNotify.value && Settings.updateSkipped.value != release.version) {
            Settings.updateLastCheck.value = now
            prompt = release
        }
    }

    /** "Don't show again": this version is not offered again; a later one is. */
    fun skip(release: Release) {
        Settings.updateSkipped.value = release.version
        prompt = null
    }

    suspend fun downloadAndInstall(release: Release) {
        val asset = assetFor(release) ?: return
        val target = File(File(AppFiles.root, "updates"), asset.name)
        (download as? Download.Ready)?.file?.takeIf { it == target && it.isFile }?.let {
            install(it)
            return
        }
        if (download is Download.Running) return
        target.parentFile?.listFiles()?.filter { it != target }?.forEach { it.delete() }
        download = Download.Running(0f)
        ReleaseCheck
            .download(asset, target) { download = Download.Running(it) }
            .onSuccess { file ->
                download = Download.Ready(file)
                install(file)
            }.onFailure { download = Download.Failed }
    }

    fun resetFailed() {
        if (download is Download.Failed) download = Download.Idle
    }

    /** Opens or runs the downloaded file the way this system installs it (see the top of this file). */
    fun install(file: File) {
        runCatching {
            when {
                Os.isWindows -> ProcessBuilder("msiexec", "/i", file.absolutePath).start()
                Os.isMac -> ProcessBuilder("open", file.absolutePath).start()
                Os.isLinux && file.name.endsWith(".AppImage", ignoreCase = true) -> replaceAppImage(file)
                Os.isLinux -> ProcessBuilder("xdg-open", file.absolutePath).start()
            }
        }.onFailure { download = Download.Failed }
    }

    /** Puts the new AppImage where this one is and starts it; this one then quits. */
    private fun replaceAppImage(file: File) {
        val current = appImage ?: return run { ProcessBuilder("xdg-open", file.parentFile.absolutePath).start() }
        val staged = File(current.parentFile, ".${current.name}.new")
        file.copyTo(staged, overwrite = true)
        staged.setExecutable(true, false)
        Files.move(staged.toPath(), current.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        ProcessBuilder(current.absolutePath).start()
        exitProcess(0)
    }
}
