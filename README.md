# jPlay

jPlay is a music discovery player for **Android (with Android Auto)** and **Windows**. Start from songs you like and it
builds a queue of full songs, blends them like a DJ, and learns your listening mood. The logo takes your mood's colour.

## Download
Get the latest build from [Releases](https://github.com/jyoung2000/iPlay/releases):

| File | Platform |
|---|---|
| `jplay-0.20.17.apk` | Android 8+ (and Android Auto). Installs over earlier Airwave Discovery releases and keeps your library. |
| `jPlay-0.20.17-x64-setup.exe` | Windows 10/11 installer |
| `jPlay-0.20.17-portable.exe` | Windows portable app (no install) |

The signing certificates are self-signed, so Play Protect and Windows SmartScreen may warn on first install.

## New in 0.20.17
- Shuffle on the Discover / now playing queue plays each song once, and never plays one artist more than 4 times in a
  row while the queue holds other artists. Top-ups and listening mood bring in more artists.
- The X takes any song out of the queue; if it is the one playing, the next song starts. In Android Auto the X works
  at the end of the list, under shuffle, and while the next song is still downloading.
- The heart stands alone (no playlist tick beside it).
- Android Auto: the app works with the phone app closed, resumes when the car asks to play, and the radio clock follows
  the song on air.

## Upgrading from Airwave Discovery
jPlay is the new name for Airwave Discovery. Updating keeps your library, settings, favourites and downloads: on
first start the app moves its old "Airwave Discovery" folders to "jPlay" (nothing is copied, deleted or downloaded again).

## Source
The full source is in this repository. See [BUILD.md](BUILD.md) to build the Android and Windows apps.
