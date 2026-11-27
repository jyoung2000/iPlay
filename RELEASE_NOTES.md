# jPlay 0.20.28 — Android discovery and radio

## Changes
- Includes the latest artist-discovery changes.
- Adds semantic Android Auto browse icons and embedded radio artwork.
- Handles the first A–Z station's immediate HTTP 404 by advancing within the same browse context to the next station, up to three attempts. This does not repair the remote station's dead stream; it avoids leaving playback stuck on that 404. Other network and decoder errors are not silently skipped.
- Android versionName `0.20.28`, versionCode `91`. Windows package version metadata is also `0.20.28`; no Windows installer is included in this release.

## Shipped APK verification
- APK signature verifies; signer certificate SHA-256 `e0762da5853f6ac2012fc680d0caca9840650fd4e8b18c3c095d4b3212dd394d`, matching the 0.20.27 APK.
- Manifest package `com.nowplaying.discovery`, versionCode `91`, versionName `0.20.28`; release manifest has no debuggable flag.
- Installed over signed 0.20.27 on emulator 5556 with `adb install -r`; the app launched and rendered the jPlay screen.
- Release and debug APKs contain the same 36 `assets/web/` entries. Bundled model files match the hashes in `BUILD.md`.

## Automated and emulator verification
- Android unit tests: 421 passed, 0 failures/errors.
- Android emulator device suites: RadioBrowse 5, CarArt 2, RadioArt 3, CarRadioControls 7 passed.
- Frozen-source live-radio suite: 4 passed, 0 skipped. The first A–Z station advanced after its pre-audio 404 and decoded 49,664 frames on the next station; WGN 720 decoded 50,176 frames.
- Web TypeScript check and Vite production build passed.
- Web Vitest: 3,387 passed, 7 skipped. Output included caught localhost `ECONNREFUSED` traces because the optional API server was absent; tests exited successfully, but this was not a clean/no-noise run.
- Windows Node tests: 98 passed, 0 failed. Server pytest: 111 passed, 13 deselected, 5 warnings.
- Physical phone/car testing was not performed.

## Source snapshot scope

The source archive is Android-focused and contains the app plus required shared workspace source and manifests. It omits unrelated monorepo apps; the standalone shared UI gallery/styleguide typecheck imports those omitted app sources and is not part of this snapshot's build path. The app-level web checks and Android build above passed. See `BUILD.md` and `SOURCE-MANIFEST.sha256` for reproducible build steps and per-file hashes.
