# jPlay

jPlay is a music discovery player for **Android (with Android Auto)** and **Windows**. Start from songs you like and it builds a queue of full songs, blends them like a DJ, and learns your listening mood.

## Download

The latest release is **0.20.28**. Download the Android APK and matching source archive from [GitHub Releases](https://github.com/jyoung2000/iPlay/releases/latest).

| File | Platform |
|---|---|
| `jplay-0.20.28.apk` | Android 8+ (including Android Auto) |
| `jplay-0.20.28-source.zip` | Corresponding source snapshot and rebuild instructions |

This release does **not** include a Windows installer. The Windows package version metadata is aligned to 0.20.28, but no Windows build is claimed or provided here.

The APK is self-signed; Play Protect may warn on first install. The 0.20.28 signing certificate matches 0.20.27, so install-over updates are supported.

## New in 0.20.28

- Updated artist discovery.
- Semantic Android Auto browse icons and embedded radio artwork.
- Radio playback now moves to the next station in the same browse list when a station immediately answers HTTP 404 before any audio is decoded. It tries at most three stations; other network and decoder failures are still reported rather than silently skipped.

## Release verification

- **Shipped APK:** signature verified; package `com.nowplaying.discovery`, version `0.20.28` / versionCode `91`, not debuggable. Installed over signed 0.20.27 on an emulator and the jPlay screen rendered. All 36 `assets/web/` entries match the debug APK.
- **Debug/test builds:** 421 Android unit tests passed. Emulator suites passed: RadioBrowse 5, CarArt 2, RadioArt 3, CarRadioControls 7. Four live-radio tests passed on the frozen source; the first A–Z HTTP-404 fallback decoded 49,664 frames, and WGN 720 decoded 50,176 frames.
- **Web/server/Windows checks:** TypeScript and Vite production build passed; Node tests 98/98; server pytest 111 passed, 13 deselected, 5 warnings. Vitest had 3,387 passed and 7 skipped; its output still contains caught localhost `ECONNREFUSED` traces because no API server was started, so it was not a noise-free run.
- **Not tested:** physical phone or car.

## Upgrading from Airwave Discovery

jPlay is the new name for Airwave Discovery. Updating keeps your library, settings, favourites and downloads: on first start the app moves its old “Airwave Discovery” folders to “jPlay” (nothing is copied, deleted or downloaded again).

## Source and license

The full app source is in this repository. See [`BUILD.md`](BUILD.md) to rebuild from the source snapshot and [`discovery-demo/docs/LICENSES.md`](discovery-demo/docs/LICENSES.md) for third-party software, model licenses, data-source terms, and use restrictions. This is a local demo for personal, non-commercial use; it is not affiliated with Apple, Deezer, Google/YouTube, SoundCloud, or Spotify.
