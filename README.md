# jPlay

jPlay is a music discovery player for **Android (with Android Auto)** and **Windows**. Start from songs you like and it
builds a queue of full songs, blends them like a DJ, and learns your listening mood. The logo takes your mood's colour.

## Download
Get the latest build from [Releases](https://github.com/jyoung2000/iPlay/releases):

| File | Platform |
|---|---|
| `jplay-0.20.3.apk` | Android 8+ (and Android Auto). Installs over earlier Airwave Discovery releases and keeps your library. |
| `jPlay-0.20.3-x64-setup.exe` | Windows 10/11 installer |
| `jPlay-0.20.3-x64-portable.exe` | Windows portable app (no install) |

The signing certificates are self-signed, so Play Protect and Windows SmartScreen may warn on first install.

## New in 0.20.3
- Radio: many more stations per area (iHeart's directory, so Chicago now has V103 and Power 92), playback that is
  really live (2 s from the newest audio, with Go live and BEHIND LIVE), and more accurate, quicker song information.
- Genre tags on one line, about two at a time, gliding through the rest. Custom discovery sliders fill to the handle.
- Find my next favorite works without starting songs when custom choices are set.
- Taste map: a tempo axis with one number per genre column, and song details when you tap a cover. A spinning arrow
  shows the app learning while music plays.
- Settings: a label over the algorithm buttons, names in line with their checkboxes, an app-styled file button.

## Upgrading from Airwave Discovery
jPlay is the new name for Airwave Discovery. Updating keeps your library, settings, favourites and downloads: on
first start the app moves its old "Airwave Discovery" folders to "jPlay" (nothing is copied, deleted or downloaded again).

## Source
The full source is in this repository. See [BUILD.md](BUILD.md) to build the Android and Windows apps.
