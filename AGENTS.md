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
  target language or only "ooh"s; lines it still leaves out, copies back unchanged though they
  are not in the target language, or leaves "" in another script are asked for once more. The prompt
  (`web/private/src/Translator.php`, with a register note per language in `Languages.php`) asks
  for natural sung language, not word for word. The proxy reports its `Cache::PROMPT_VERSION`
  ("Sonara-N") with each answer and the apps save it; a saved translation counts while its N is at
  least the app's `MODE`. To redo every translation: bump `PROMPT_VERSION`, and `MODE` in both
  apps' `LyricsTranslator.kt` to the same N. Both apps ask the owner's proxy (`web/`, PHP, deployed at
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
  Kuromoji for Japanese (byte-order marks and zero-width characters dropped first: they changed the
  first word's reading; a few sung readings such as 二人 futari fixed in `WORDS`), Revised Romanization for Korean, ICU (Android's built-in, ICU4J on the
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
  take the song's colour (`ui/component/PlayerSheet.kt`, `PlayerTint`), with a light top edge and
  a darker scrim so they never blend into the player; elsewhere they keep the theme's surface.
  No colour animations there: lyric lines redraw every frame, so colours are worked out once per
  page and passed down.
- Sharing (requested by the owner): every share and copied link is Sonara's own
  (`utils/SonaraLinks.kt`, shared with the desktop): `https://sonara.rashiiddsr.my.id/open/?song=|
  playlist=|album=|artist=<YouTube id>`, `&jct=` for a collaboration invite. The site keeps nothing
  and loads nothing: with the app installed Android opens it (verified App Links,
  `.well-known/assetlinks.json`, and `sonara://open/?…`) as the YouTube Music link it stands for
  (`SonaraLinks.toYouTube` in `handleDeepLinkIntent`); otherwise `/open/` offers Sonara or YouTube
  Music. Only Sonara's links open the app: music.youtube.com links are YouTube Music's (no link
  filter, no "default app" setting). A shared song opens as its menu, large with Play
  (`YouTubeSongMenu(preview = true)`), not played at once.
- Playlist import (Library > + > Import playlist, and the desktop's Import): YouTube Music links as
  before (YouTube copies the playlist); Spotify, Deezer and Apple Music playlists and albums are
  read by the proxy (`/api/import`, no keys) and matched on YouTube Music
  in the app (`utils/ExternalImport.kt`): songs only, never music videos, by ISRC when given,
  else title + artists with the length and album to choose; what isn't found is listed and not
  added. Every failure has its own message (`YouTubePlaylistEditor.ImportError`). Spotify's Web
  API refuses apps whose owner has no Premium (403 on everything, confirmed in the admin Test), so
  a Spotify playlist is read the way the web player reads it (owner's choice: no Spotify app, no
  Web API): GraphQL at `api-partner.spotify.com/pathfinder/v2/query` (`SpotifyPathfinder.php`,
  `fetchPlaylist`, then every other page of 100 at once with `fetchPlaylistContents`, six in
  flight), with the access token every embed page carries and a client token from
  `clienttoken.spotify.com`, both kept encrypted (`SpotifyWeb.php`), so most imports make no call
  but the pages. A 401, a forgotten query hash (the new one is read from the web player's script
  and kept a week) and a moment's 429/5xx are each asked again once. Anonymous first, then the
  owner's `sp_dc` cookie from the admin page when one is saved. A way rests for 5 minutes only after
  3 failed imports in a row (the admin Test brings it back). Only when GraphQL cannot read the list
  does Spotify's public embed page answer (`SpotifyEmbed.php`, the first 100 tracks); albums always
  come from the embed page. No ISRC from Spotify. One import answers with at most
  `ImportList::MAX_TRACKS` (2000) songs; only whole answers are kept (`ImportCache`, an hour), so
  a list cut short is asked for again. Answers say `via` (graphql, embed, deezer, apple) and the
  admin overview counts whole and 100-only Spotify playlists.
  Songs still not found and without an ISRC are looked up once more: `/api/isrc` asks Deezer's
  open search (`IsrcSource.php`, no key, answers kept) and the app searches again with the ISRC,
  which names one recording exactly. Only the finished list is shown, never a per-song count.
  Import asks for a link and one switch, "Use the playlist's own details" (on): the name and
  description come from the source (`ImportSource.description`, every platform and YouTube); off,
  they are typed in the sheet.
- Collaboration needs a playlist that isn't private: turning it on makes a private playlist
  unlisted first (`CollaborateSheet`); choosing Private in the editor shows a red line and turns
  collaboration off before saving.
- Menus: always exactly three action cards (`NewActionGrid`), never two or four; with no third
  action of its own, a menu takes one from its list (Play/Shuffle for auto playlists, Add to queue
  for a playlist only in Sonara). No spacers between a menu's groups.
- The mini player can't be swiped away (that stopped the music); it stays until the queue ends.
- Autoplay (Settings, `AutoRadioQueueKey` on Android, `Settings.autoplay` on the desktop): once the
  last song of a queue with nothing more to load starts, a radio of it is added to the queue
  (`MusicService.appendRadio`, desktop `Player.extendWithRadio`), so there is no gap and Next
  works on the last song; when that radio brings only songs already queued, the songs before it
  are tried. Playing a song from History plays its radio, never the list of what was heard.
- Volume normalization is one piece of code for every platform (`playback/audio/Loudness.kt`,
  compiled by the desktop as it is): YouTube's `loudnessDb` is relative to -14 LUFS (measured:
  `perceptualLoudnessDb` = `loudnessDb` - 14), songs go to the chosen Loudness level (Balanced
  -14 LUFS by default; the desktop has the same setting), down by up to 15 dB and up by up to 6 dB,
  through `Loudness.Limiter` (stereo-linked, -1 dBFS while raising) instead of clipping. Album
  mode: songs of one album in a row, not shuffled, share the album's gain (length-weighted energy
  mean); the album's unmeasured songs are looked up once, two at a time, through the playback
  extractor (YouTube's plain player call refuses without it). Gain changes ramp over 50 ms.
- Gapless everywhere (`playback/audio/Gapless.kt`, shared with the desktop): the phone and TV
  through ExoPlayer's playlist; the desktop hands the open sound card line to the next song's
  session (`Player.continueGapless`) instead of draining and reopening it, when prepareNext has
  the next song on disk; Cast keeps the phone's next song in the TV's queue with preload
  (`SonaraCast.queueNextOnTv`, the song id in each item's customData), and a song picked by hand
  pauses the TV at once and starts from 0 on both; play from the phone is never sent while the TV
  still holds another song (paused, then a new song picked), the new song's load starts it. Crossfade never runs inside an album played in
  its order (`Gapless.albumContinues`, same album id, not shuffled); the old "Disable for gapless
  albums" setting is gone and this is always so.
- Playlist covers (`YouTubePlaylistEditor.replaceCover`, every platform): the image is made small
  first (at most 1000 px, JPEG 85), sent once, all within 60 s (`COVER_LIMIT_MS`), with no
  second attempt; Sonara never shows a cover YouTube doesn't show yet: it waits for YouTube's own
  cover URL, and an accepted cover not shown within the minute comes with the next refresh.
  YouTube allows an own cover only on a phone-verified account: the upload still succeeds but the
  apply answers HTTP 403, which every platform reports as "verify your phone number at
  youtube.com/verify" (`CoverUpload.NEEDS_VERIFIED_PHONE`), not as a refused image.
- Toasts are never hidden behind a sheet or dialog: the phone's `ErToastHost` opens each message
  in a small window of its own (not focusable, not touchable), added after whatever is open; the
  desktop draws its toast last, in a `Popup`. Lists that may hold a song twice (the desktop's queue
  and song lists) key rows by place, never by the song's id alone.
- Updates are looked up at launch and every 6 hours while the app runs (desktop: in the tray
  too); "Install unknown apps" allowed in Android's settings carries on with the install. On
  Windows the installer is handed to `cmd /c start` and the app quits 2.5 s later: the MSI's first
  step closes Sonara, and `taskkill /T` took the installer down with it (so `wix/main.wxs` kills
  by name only, never the tree). A Mac quits the same way, so the copy in Applications can be
  replaced.
- Tablets: held upright, a tablet is the phone app exactly (bottom bar, phone player); the
  tablet layouts (`WindowLayout.wide`: rail, side-panel player, two panes) are landscape only.
- Update dialogs show only a release's "What's new" and "Fixes" sections
  (`ReleaseCheck.highlights`). Older apps show the whole notes, so keep release notes free of
  tables: lists only.
- The app always speaks English, whatever the phone's language, with no setting to change it
  (owner's choice, `utils/AppLanguage.kt`). The Indonesian strings stay in `values-in/` for now.
- Sign-in is required (`WelcomeScreen`): one Google sign-in page in a sheet, inside the app, because
  YouTube Music's web API only accepts the session cookies from that flow: an OAuth token from
  the native account chooser was tested and rejected by WEB_REMIX (HTTP 400).

## Sonara TV (Android TV): the `tv` flavor

Requested by the owner as an app of its own: package `com.ermusic.tv`, released as
`TV-Sonara-<version>.apk` (never starting with "Sonara", so phones' updaters never pick it), built
with `./gradlew :app:assembleTvRelease`. It is a product flavor of this same app, so the player
(MusicService), library, lyrics, romanization, translator, lyrics refresh and updater are the
phone's own code; nothing crucial may be missing on the TV compared with the phone. The screens
are the TV's own, in `app/src/tv/`, laid out like the big TV music apps:
- TV only: the manifest requires leanback, has only `TvActivity` (LEANBACK_LAUNCHER) and drops the
  phone's activities, widgets, camera and local-file permissions, so R8 strips the phone UI; on a
  phone `TvRoot` shows "Sonara TV is for TVs". `AppEntry` per flavor says which activity
  notifications and the media session open.
- Size: the Kuromoji dictionary is left out of the TV APK and fetched once from Maven Central,
  checked by SHA-256 (`TvDictionary`, `LyricsRomanizer.tokenizerFactory`); x86 libraries are left
  out (`androidComponents` in `app/build.gradle.kts`).
- Start: splash, Welcome (Get started), sign-in, then the app. Top bar: what's playing (left,
  opens Now Playing), the tabs Home, Search, Browse, Library (middle), the account (right, opens
  Settings). Pages keep the safe area (`Tv.SafeX`/`SafeY` in `TvUi.kt`); no mini player.
- Albums, playlists, Liked songs, artists and moods open `TvCollection`; albums and playlists
  have Add to home screen (`TvChannel`, a preview channel; Google TV may not show it).
- Now Playing (`TvNowPlaying`): cover, time bar (left/right seek 10 s), controls, like, radio,
  sleep timer, and Up next or Lyrics. The idle screen (`TvIdle`, after 45 s without the remote
  while playing) is the spinning record with the lyrics beside it, or alone when there are none.
- Song menu (`TvSongMenu`): holding OK on a song (rows and cards) or the playlist button in Now
  Playing offers Like, Add to playlist, Play next, Add to queue and Start radio, synced to the
  account like the phone (`LocalSyncUtils` is provided on the TV). The TV creates, imports and
  downloads nothing.
- Focus: Now Playing and the song menu keep the remote inside them (`onExit` cancelled), so it
  never wanders onto the pages behind; when a front screen closes, or focus is lost, it goes back
  to the page on the item it left (`focusRestorer`). The search field takes the remote only after
  OK on it, so the TV keyboard never opens by itself.
- Lyrics (`TvLyrics`): timed lyrics follow the song; untimed ones just stand still.
- Settings are all on one page (`TvSettings`), with real switches; no "All settings".
- Focus is drawn by `tvFocus` (bigger, light outline). Crossfade is on (5 s) by default on the
  TV only (`CrossfadeEnabledDefault`).
- Sign-in: a QR code and a rotating 6-digit code for Sonara on a phone (Settings > Sign in on a
  TV, `TvLinkScreen`), relayed by the proxy (`/api/tv/start|poll|lookup|deliver`) and sealed for
  the TV alone (`tvlink/TvLink.kt`: ECDH P-256, HKDF-SHA256, AES-256-GCM); or Google's own
  sign-in typed on the TV (`LoginSheet`). The TV polls every 5 s (the proxy allows 120 per 10 min).

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
the running app itself, and on a real uninstall asks keep / delete through
`runtimein\javaw.exe ... com.ermusic.desktop.UninstallMain` (not Sonara.exe, which runs the app in a
second process the installer wouldn't wait for); `SONARA_DATA=keep|delete` answers silent uninstalls.
Under the installer HKCU is not the user's: name the user's hive as `HKU\[UserSID]`. A Mac and Linux
have no uninstaller, so Settings has Uninstall Sonara. No backup or restore (removed at the owner's
request). Settings keeps a line under a title only where the title alone doesn't say it. The queue, song and place are kept in `player.json` and come
back, paused, at the next start (`Player.restore`/`keepSaved`).

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
