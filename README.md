# <ins>Maydo</ins>

[![ci](
  <https://github.com/eggplants/maydo/actions/workflows/ci.yml/badge.svg>
)](
  <https://github.com/eggplants/maydo/actions/workflows/ci.yml>
) [![release](
  <https://github.com/eggplants/maydo/actions/workflows/release.yml/badge.svg>
)](
  <https://github.com/eggplants/maydo/actions/workflows/release.yml>
) [![GitHub Release](
  <https://img.shields.io/github/v/release/eggplants/maydo?color=white>
)](
  <https://github.com/eggplants/maydo/releases/latest>
)

An Android video player built on [mpv] with [yt-dlp] inside: open a file, or give it the URL of a
web page, and yt-dlp finds the stream that mpv plays.

[mpv]: <https://mpv.io/>
[yt-dlp]: <https://github.com/yt-dlp/yt-dlp>

## Features

- **Play anything mpv or yt-dlp can open**: paste a URL, type search words (yt-dlp plays the
  first YouTube result), share a link from a browser or any other app, or open a local file.
- **Player**: quality limit, hardware decoding, video, audio and subtitle tracks, playback
  speed, double-tap to seek, picture-in-picture.
- **yt-dlp updates**: the APK comes with a yt-dlp release; the settings download newer ones from
  [its GitHub releases](https://github.com/yt-dlp/yt-dlp/releases), checked against GitHub's
  SHA-256 digest, without a new APK.
- **App updates**: the settings install new releases of Maydo from
  [GitHub](https://github.com/eggplants/maydo/releases), streamed straight into Android's
  package installer without saving the APK anywhere.
- `mpv.conf` and yt-dlp's config file can be edited in the app.
- History, English and Japanese UI, dynamic color.

## Development

[mise](https://mise.jdx.dev/) and the Android SDK with NDK r28c
(`sdkmanager "ndk;28.2.13676358"`) required.

```bash
# first
mise install
mise generate git-pre-commit -w

mise run build
mise run test
mise run lint
mise run format
```

## Build for distribution

```bash
# 1. Create the key (PKCS12, so the key password is the keystore password):
keytool -genkeypair -keystore release.jks -alias maydo -keyalg RSA -keysize 4096 -validity 36500

# 2. Fill in MAYDO_KEYSTORE_BASE64 and MAYDO_KEY_ALIAS:
cp .env.example .env
{
  echo "MAYDO_KEYSTORE_BASE64=$(base64 -w0 release.jks)"
  echo "MAYDO_KEY_ALIAS=maydo"
} >> .env

# 3. Add the two same passwords by hand: MAYDO_KEYSTORE_PASSWORD / MAYDO_KEY_PASSWORD
$EDITOR .env

# 4. Send everything to the repository's Actions secrets, and check it:
gh secret set --env-file .env
gh secret list

# 5. Test in local
mise run build:release

# 6. Tag: v<versionName>-<versionCode>, with a higher versionCode every time
git tag vX.Y.Z-N -m ... && git push origin vX.Y.Z-N
```

The release workflow attaches `maydo-<version>-<abi>.apk` for each ABI and a universal one; the
in-app updater picks the one for the device's ABI by that name.

## License

[MIT](LICENSE). `app/src/main/assets/ytdl_hook.lua` is a modified copy of mpv's
[ytdl_hook.lua](https://github.com/mpv-player/mpv/blob/master/player/lua/ytdl_hook.lua), under
mpv's LGPL-2.1-or-later.
