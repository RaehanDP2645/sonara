# SONARA: notes for contributors and AI agents

SONARA is a lean YouTube Music player for Android (Kotlin, Jetpack Compose, Material 3, Media3).
It is GPL-3.0 and derives from Metrolist <- InnerTune/OuterTune; the owner credits Metrolist as
its upstream (README, About). Keep the existing license headers intact.

## Build and verify

Requires JDK 21 and the Android SDK (compile SDK 37).

```bash
./gradlew :app:assembleFossDebug          # must stay green
./gradlew :app:testFossDebugUnitTest      # unit tests
./gradlew :app:lintFossDebug              # lint
./gradlew :app:assembleFossBenchmark      # R8 build used for performance work
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest   # needs a device or emulator
./gradlew :app:generateFossReleaseBaselineProfile         # regenerate the baseline profile
```

Judge performance only on the `benchmark` or `release` build type, never on debug. Record numbers
in `docs/perf.md`; never write numbers that were not measured.

## Conventions

- Commits: `type(scope): description`, e.g. `feat(player): add queue page`, `fix(download): retry failed items`.
- Strings: English in `app/src/main/res/values/`, Indonesian in `values-in/`. Other locales are
  legacy and not maintained.
- UI: use the tokens in `ui/theme/` (colors, type, shapes, spacing, motion) and the shared
  components in `ui/component/`. No magic numbers scattered across screens.
- Lazy lists: always pass `key` and `contentType`; list-item parameters must be stable.
- Scope: the app intentionally has no social/companion integrations (Discord, Last.fm, Listen
  Together, Spotify, alarms). Do not re-add them.
- Built-in updater, requested by the owner: `update/ReleaseCheck.kt` (shared with the desktop)
  reads the newest GitHub release of `rashiiddsr/sonara`; `update/AppUpdater.kt` and
  `UpdateDialog.kt` offer it (once a day at most, unless switched off in About or the version was
  skipped) and hand the APK to Android's installer; About has Check for updates. The desktop's
  `update/DesktopUpdater.kt` runs the .msi, opens the .dmg for this Mac, replaces an AppImage, or
  opens the .deb. A release is an update only when its version is higher: bump the version (and
  tag `v<version>`) for every update; re-uploading files to an existing release reaches no one.
- Experiment, requested by the owner and needing Google Play services: Google Cast
  (`playback/SonaraCast.kt`, Default Media Receiver). Keep it isolated so it can be removed.
- Experiment, requested by the owner: song recognition through Shazam's unofficial endpoint
  (`recognition/`, `com.metrolist.shazamkit`, `ui/screens/search/SongRecognitionSheet.kt`). It
  names recordings playing nearby, not sung or hummed tunes, and may stop working if Shazam
  blocks it. Keep it isolated so it can be removed; no history table, widget or tile.
- Experiment, requested by the owner: AI translator for lyrics (`lyrics/LyricsTranslator.kt`,
  `ui/player/LyricsPage.kt`, Settings > Lyrics). Off by default; when on, lyrics are translated
  as soon as they are fetched, into the chosen language (English by default). The model gets
  the whole song numbered and must answer for every line, with "" for lines already in the
  target language or only "ooh"s; lines it still leaves out are asked for once more. The prompt
  (`web/private/src/Translator.php`, with a register note per language in `Languages.php`) asks
  for natural sung language, not word for word. Changing the prompt: bump `Cache::PROMPT_VERSION`
  and `MODE` in both apps' `LyricsTranslator.kt`, so old translations are redone. Both apps ask the owner's proxy (`web/`, PHP, deployed at
  `sonara.rashiiddsr.my.id/api`; see `web/DEPLOY.md`), never an AI provider: the proxy holds the
  providers' keys encrypted, tries Gemini, Groq, OpenRouter and others in the owner's order, keeps
  every song's translation for everyone and limits each install and address. Requests are signed
  (HMAC-SHA256, headers `X-Sonara-*`) with a client secret sealed in `secrets/api.sealed` (opened
  by `SealedKey.kt` in both apps); to change it, put `SONARA_API_SECRET` in `local.properties`, run
  `./gradlew :app:sealApiSecret --no-configuration-cache`, and set the same secret in the proxy's
  admin page. Users are never asked for a key. AI only: when the proxy can't translate, lyrics
  show without a translation. Stored in `lyrics.translatedLyrics`.
- Lyrics romanization (`lyrics/LyricsRomanizer.kt`, shared with the desktop; Settings > Lyrics,
  on by default): lines in another script also in Latin letters, made on the device with no AI:
  Kuromoji for Japanese, Revised Romanization for Korean, ICU (Android's built-in, ICU4J on the
  desktop) for Chinese pinyin and every other script. No romanization when none can be made.
  Under it, Show: "Original and Latin" (default) or "Latin only", which puts the Latin letters in
  the line's place in the lyrics, the player's lyric preview (`LyricsPeek`) and the Cast subtitles.
- Translations under lyrics are italic, in a light shade of the song's colour, so they never read
  as the lyrics or their Latin letters. While casting, the TV's subtitles (`SonaraCast.toWebVtt`)
  carry the line, its Latin letters and the translation as the phone shows them.
- Lyrics refresh (`lyrics/LyricsFreshness.kt`, shared with the desktop): saved lyrics from a lower
  source, untimed, or not found are looked up again when the song plays, at most once a day
  (times in `LyricsChecks` / `lyrics-checks.properties`, not in the database); they are replaced
  only by better ones (timed first, then a higher source), and lose their translation then.
  Lyrics from the first source, timed, and the user's own ("Manual") are never looked up again.
- Sheets and dialogs over the open player (lyrics, queue, menus, sleep timer, Cast, details)
  take the song's colour (`ui/component/PlayerSheet.kt`, `PlayerTint`); elsewhere they keep the
  theme's surface.
- The app always speaks English, whatever the phone's language, with no setting to change it
  (owner's choice, `utils/AppLanguage.kt`). The Indonesian strings stay in `values-in/` for now.
- Sign-in is required (`WelcomeScreen`): one Google sign-in page in a sheet, inside the app, because
  YouTube Music's web API only accepts the session cookies from that flow: an OAuth token from
  the native account chooser was tested and rejected by WEB_REMIX (HTTP 400).

## Desktop app (`desktop/`): Windows, macOS, Linux

A Compose Desktop build of its own (`./gradlew -p desktop run`, `./gradlew -p desktop packageInstaller`),
kept apart so it cannot disturb the Android build. It compiles `innertube/` from source and uses
the desktop artifact of InnerTubeX. FFmpeg decodes (songs are fetched whole into a local cache,
because YouTube refuses the range requests a seek on a streamed URL makes). Sign-in opens Google's
page in a window of the default Chromium browser (Edge otherwise) on a profile of its own and reads
the cookies over the local debugging port; the session is stored encrypted with DPAPI. The AI key
is the built-in sealed one, into generated code under `build/`. `window/WindowChrome.kt` hands the
title bar's space to the app over JNA and keeps the native frame (resize, snap). Bump
`appVersion` for every installer. The Android app's lyric sources are compiled as they are
(`fromAndroid` in the build file, with small stand-ins under `src/main/kotlin/android/`). `SONARA_GUEST=1` skips sign-in for development.

Data and removal: on Windows the data lives in `%LOCALAPPDATA%\Sonara Data`, apart from the program
folder (`%LOCALAPPDATA%\Sonara`), because jpackage's uninstaller empties the program folder
(RemoveFolderEx) and every update runs the old version's uninstaller; up to 2.4.1 the data sat in
`Sonara\data` and the 2.4.2 installer moves it out first (`wix/main.wxs`). The installer also closes
the running app itself, and on a real uninstall asks keep / back up then delete / delete through
`runtimein\javaw.exe ... com.ermusic.desktop.UninstallMain` (not Sonara.exe, which runs the app in a
second process the installer wouldn't wait for); `SONARA_DATA=keep|delete` answers silent uninstalls.
Under the installer HKCU is not the user's: name the user's hive as `HKU\[UserSID]`. A Mac and Linux
have no uninstaller, so Settings has Uninstall Sonara; every platform has Back up and Restore
(`data/Backup.kt`, never the sign-in).

The same app runs on macOS, with nothing left out; `Os.kt` says where it runs, and each Windows piece
has a Mac counterpart: the app drawn under a see-through title bar with the traffic lights kept
(`WindowChrome`), the session sealed with a key in the login keychain (`auth/MacKeychain.kt`), media
keys and Control Center through Now Playing (`window/MacNowPlaying.kt`), Quit, Settings and the Dock
icon (`window/MacApp.kt`), a launch agent for opening at login (`window/StartAtLogin.kt`), Command
for Ctrl, data in `~/Library/Application Support/Sonara`. On a Mac, `./gradlew -p desktop packageDmg`
makes the .dmg. From Windows, `stageAppJars -Psonara.natives=macosx-arm64` and `mac/make_app.py`
with a Temurin JRE put an unsigned `Sonara.app` in a .zip. GitHub Actions (`mac.yml`) builds the
.dmg for Apple Silicon and for Intel on tags `v*`.

It runs on Linux too, with nothing left out: the native title bar stays (each desktop draws its
own), media controls and keys through MPRIS on the session bus (`window/LinuxMpris.kt`, tested on a
bus of its own over TCP), the session key in the keyring through `secret-tool` or else a file only
the user can read (`auth/LinuxKeyring.kt`), sign-in through an installed Chromium browser, Snap ones
with a profile under `~/snap` (`auth/EdgeSignIn.kt`), an autostart entry, data in
`$XDG_DATA_HOME/Sonara`, and a window class equal to the menu entry `sonara-Sonara`
(`window/LinuxDesktop.kt`), so the dock shows the right icon. The tray setting only shows where there
is a tray (GNOME has none). `./gradlew -p desktop packageDeb` and `linux/make_appimage.sh` package
it; GitHub Actions (`linux.yml`) builds both on Ubuntu 22.04 and starts the app once to check it.

## Database

Never change the Room schema without a proper `Migration` (or `AutoMigration` with a spec) and an
exported schema in `app/schemas/`. `fallbackToDestructiveMigration` is forbidden. When unsure,
leave the schema alone.
