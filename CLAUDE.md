# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Maydo is an Android video player (Kotlin, Jetpack Compose) built on libmpv with yt-dlp inside:
local files play in mpv, and web pages go through mpv's ytdl_hook to yt-dlp, whose streams mpv
plays. yt-dlp updates from its GitHub releases in the settings, and the app updates itself from
its own GitHub releases.

- libmpv is `io.github.marlboro-advance:mpv-android`, the AAR mpvEx uses (`is.xyz.mpv`:
  `MPVLib`, `BaseMPVView`). It ships for all four ABIs, and so does the APK (one per ABI plus a
  universal one, `splits.abi`).
- Python 3.12 and QuickJS come prebuilt from youtubedl-android's `library` artifact
  (`isTransitive = false`), used only for its binaries, never for its Java API.
- yt-dlp is the zipapp (`yt-dlp` asset) of https://github.com/yt-dlp/yt-dlp/releases. The
  version in the APK and its SHA-256 are in `gradle.properties` (`maydo.ytdlp.*`); Gradle
  downloads it into the assets (`DownloadYtdlp` in `app/build.gradle.kts`).

## Commands

Tools (JDK 21, ktlint, ktfmt, ...) are pinned in `mise.toml`, which is also the canonical list
of tasks. The build also needs NDK r28c (`sdkmanager "ndk;28.2.13676358"`).

```bash
mise run build                         # ./gradlew assembleDebug
mise run build:release                 # signed release APKs from .env and release.jks (.env.example)
mise run test                          # ./gradlew test (JVM unit tests)
./gradlew testDebugUnitTest --tests 'io.github.eggplants.maydo.VersionsTest'  # a single test class
mise run format                        # ktlint --format, then ktfmt
mise run lint                          # ktlint + ktfmt check + Android Lint
mise run ci                            # what CI runs
```

Kotlin style is godlo's: `.editorconfig`, ktfmt (`--kotlinlang-style`, 100 columns) owns the
layout and runs last, ktlint with `android_studio` style checks the rest.

The machine this is developed on has 14 GB of memory: the emulator and a Gradle build together
have run it out. Stop the emulator before a full build, or pass `--max-workers=2`.

## How yt-dlp runs

This is the unusual part; read it before touching the player.

1. **`native/ytdlp.c` → `libytdlp.so`**: mpv's ytdl_hook runs one executable, but yt-dlp is a
   zipapp Python has to be started on. The launcher execs `$MAYDO_PYTHON $MAYDO_YTDLP args...`.
   Gradle builds it with the NDK's clang (`BuildYtdlpLauncher`) into generated jniLibs, named
   `lib*.so` so that the installer puts it in nativeLibraryDir, the only place an app may run
   programs from (hence `useLegacyPackaging = true`).
2. **mpv cannot start it.** libmpv's subprocess command uses `clone(CLONE_VM | CLONE_VFORK)`;
   the child shares the app's memory and its `sigaction()`/`close()` go through ART's
   libsigchain and fdsan, which kills the child and corrupts the app (an fdsan abort on a
   binder thread). So `ytdl=no`, and `app/src/main/assets/ytdl_hook.lua` (mpv's own, from the
   libmpv in use, with `Maydo:` changes) is loaded with `scripts=` instead: its `exec()` sets
   `user-data/maydo/ytdl-request` and waits in a coroutine; the on_load hook is deferred
   meanwhile. `player/YtdlRunner.kt` observes that property, runs the launcher with
   `ProcessBuilder` (and nothing but the launcher), and answers with the script message
   `maydo-ytdl-result`, in the form of mpv's subprocess result. The property's value arrives
   JSON-quoted (a node read as a string).
3. **`core/YtdlpRuntime.kt`** unpacks `libpython.zip.so` into `noBackupFilesDir/python` once
   per APK (stamped with `lastUpdateTime`), copies the bundled yt-dlp to `files/yt-dlp/` unless
   a newer one was downloaded, copies the hook script out, and builds the launcher's environment
   (`PYTHONHOME`, `LD_LIBRARY_PATH`, `SSL_CERT_FILE`, `HOME`, `XDG_CONFIG_HOME`, `TMPDIR`, ...).
   `update()` downloads a release, checks GitHub's digest, runs it with `--version`, then
   swaps it in.

mpv options that ytdl_hook reads (`script-opts`, `ytdl-raw-options`) are set before init as
whole lists with mpv's `%length%` quoting (the app's paths hold `=`); the `-append` forms do
not work through `setOptionString` before init. `--js-runtimes quickjs:<libqjs.so>` lets yt-dlp
solve YouTube's challenges; the zipapp bundles yt-dlp-ejs.

## Architecture

- **`MaydoApp.kt`** -- the `AppContainer` (settings, `YtdlpRuntime`, `UpdateManager`); prepares
  the runtime and checks for updates on start. Reach it with `context.container`.
- **`player/`** -- `PlayerActivity` (single-task, own task affinity, picture-in-picture) holds
  `MpvView` (`BaseMPVView`, inflated from `activity_player.xml` because it wants an
  `AttributeSet`; hidden until mpv is initialized, since `BaseMPVView` only notices a surface
  created after `initialize`) under a `ComposeView` with `PlayerScreen`. One libmpv per process:
  later intents `loadfile` into the same one. A failure is "idle-active while loading" (mpv is
  set to `idle=yes`, `keep-open=yes`), shown with the error lines mpv and yt-dlp logged.
  A single content URI goes to mpv as `fdclose://<fd>` (the permission to read it may be the
  activity's alone). Playlists: m3u files and HLS are mpv's own; video sites' playlists come from
  ytdl_hook (`--yes-playlist` when `wholePlaylists`); a folder (tree URI, persisted by the home
  screen) is listed by `core/FolderPlaylist.kt` into a `memory://` m3u of content URIs, which
  mpv opens through FFmpeg's `android_content` protocol (libplayer sets FFmpeg's JNI context).
  Swipes (`player/SwipeGestures.kt`): across seeks on release, up and down sets the window's
  brightness (left half) or the media volume (right half); they never start in the system's
  edge zones or on a control that takes the drag (the seek bar). The system brightness setting
  is 0..255 in AOSP but 0..4095 on OPPO, so `SwipeMath.brightnessFraction` widens the range when
  the setting is beyond it.
  `load()` resets `playlist-start` every time, since ytdl_hook sets it as an option for
  `…&list=` links and it would carry over to the next playlist. With hardware decoding on, `HardwareCodecs`
  caps yt-dlp's `--format-sort` at the best codec the device decodes in hardware (YouTube's AV1
  falls back to software on many phones). A paused frame does not survive a new or resized
  surface with mediacodec, so `MpvView.surfaceChanged` re-decodes it (`seek 0 relative+exact`).
- **`core/`** -- `GitHub.kt` (releases API, streamed downloads checked against the asset's
  `digest`), `AppUpdater.kt` (APK streamed into a `PackageInstaller` session, never a file;
  the system asks the user), `UpdateManager.kt` (state of both updates for the UI),
  `SettingsRepository.kt` (DataStore, history), `Versions.kt`, `PlayInput.kt`, `SharedText.kt`.
- **`ui/`** -- `home/` (URL or search words, file picker, history), `settings/` (playback,
  yt-dlp, app updates, `ConfigEditorScreen` for `mpv.conf` and yt-dlp's config under
  `files/config/`, licenses).

## Versioning and releases

As in godlo: `versionName` / `versionCode` come from the latest `v<versionName>-<versionCode>`
tag (`git describe`); without tags it is `0.0.0` / `1`. The in-app updater compares
versionCodes with the tag of the latest GitHub release, so bump the code on every tag. Pushing
such a tag runs `.github/workflows/release.yml`, which signs with the `MAYDO_*` secrets and
attaches `maydo-<version>-<abi>.apk` (and `-universal.apk`); `Versions.pickApk` relies on those
names. Actions are pinned to full commit SHAs (`mise run pin`).

The store listing is in `fastlane/metadata/android/<locale>/`. There is no F-Droid recipe: the
self-updater and the prebuilt libmpv would not pass.

## Testing conventions

JVM unit tests in `app/src/test/java/io/github/eggplants/maydo/` cover the pure logic
(`Versions`, `PlayInput`, `SharedText`, `Tracks`, `HardwareCodecs.formatSort`, `Playlist`,
`FolderPlaylist`'s sorting and filtering). Keep new logic out of Android APIs and
Compose where you can. Nothing tests the player or the Lua hook automatically; check them on an
emulator (x86_64 APK) or a phone with a YouTube URL, a `ytdl://ytsearch:` search and a local
file. YouTube now and then answers a stream with HTTP 403 right after yt-dlp resolved it; Retry
gets it, so that alone is not a regression. The README's screenshots are
`fastlane/metadata/android/en-US/images/phoneScreenshots/` (1080x2400, status bar painted over).

## Commits

Follow `.claude/skills/commit/SKILL.md`: `type: subject` in English without a scope, and no
`Co-Authored-By` trailer.
