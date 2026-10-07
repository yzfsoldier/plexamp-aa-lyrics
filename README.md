# Plexamp AA Lyrics

**Synced lyrics for Plexamp on Android Auto.**

Plexamp only shows lyrics on the phone. This patch puts them on your car's screen: while a song plays, the current line appears on Android Auto's "Now Playing" screen and advances in time with the music.

This repo contains **no Plexamp code and no APK**. It contains only the patch and a script that builds it into your own copy of the Plexamp APK.

## Features

- **Synced lyrics in the car.** The current line is shown large as the title, with "Title – Artist" below it.
- **Three-state button** in the Android Auto control bar, cycling through:

  | Mode | What you see |
  |---|---|
  | **On** | current line, with "Title – Artist" below |
  | **Expanded** | current line, the next line below it, and the line after that in the album field |
  | **Off** | the normal display without lyrics |

  The selected mode is remembered across restarts.
- **Car only.** On the phone, lock screen and notification everything stays as usual.
- **Settings dialog** via a shortcut on the app icon (long-press → "Lyrics AA"), including a connection test.
- Lyrics come **straight from your Plex server**: Plex Pass lyrics (LyricFind) as well as LRC files.

- **German or English**, following the phone language: settings dialog, app-icon shortcut and button labels ("Lyrics: On / Expanded / Off" or "Songtext: An / Erweitert / Aus"). Other languages fall back to English.

## Requirements

- Plexamp for Android as an APK (tested with **4.50.19**)
- a Plex server with synced (timed) lyrics, reachable via **HTTPS** (the usual `…plex.direct:32400` address)
- to build: Java JDK 11 or newer, `python3`, `curl`, `unzip`, `zip`; Linux, macOS or Windows with WSL

## Building

```bash
git clone https://github.com/yzfsoldier/plexamp-aa-lyrics.git
cd plexamp-aa-lyrics
./build.sh /path/to/Plexamp-4_50_19.apk
```

The result, `Plexamp-4_50_19-AA-Lyrics.apk`, ends up in the project folder. A full run takes about a minute.

What `build.sh` does:

1. on the first run, downloads the tools into `build/tools/` (apktool, dex-tools, uber-apk-signer, android.jar – about 90 MB in total, all from GitHub)
2. decompiles the APK with apktool
3. inserts the hooks into Plexamp's code (`build/patch_smali.py`)
4. compiles the patch from `src/` and converts it to Android dex
5. runs the self-tests in `test/`
6. rebuilds the APK and adds the patch as an extra `classesN.dex`
7. aligns and signs it

### Signing key

Android only accepts an update if it is signed with **the same key** as the installed app. Builds are signed with `ks.jks` in the project folder:

- **If the file is missing**, `build.sh` generates a new key automatically.
- **If you already have a patched version installed**, put its `ks.jks` into the project folder. The new build will then install right over it.

The key is listed in `.gitignore` and never ends up in the repo. To use a different key, set environment variables:

```bash
KEYSTORE=my.jks KS_ALIAS=myalias KS_PASS=secret ./build.sh Plexamp.apk
```

## Installing and setting up

1. **Uninstall the original Plexamp.** The patched app has a different signature. Your settings and downloads will be lost.
2. Install the built APK and **open Plexamp once**. This creates the shortcut on the app icon.
3. **Allow Android Auto to show apps from outside the Play Store:**
   - In the Android Auto settings, tap "Version" repeatedly until developer settings are unlocked.
   - Enable "Unknown sources" there.
4. **Enter your server:**
   - Long-press the Plexamp icon → **"Lyrics AA"** ("Songtexte AA" on German phones).
   - Easiest way: in Plex Web, open "Get Info → View XML" for any track and paste the full address from the browser's address bar. It already contains your token.
   - Tap **Test**, then **Save**.

The patched app won't receive updates from the Play Store. For a new Plexamp version, run `build.sh` again with the new APK.

## New Plexamp versions

`build/patch_smali.py` checks every place where the patch hooks into Plexamp. If Plexamp's code has changed there, the build stops with a specific message, for example:

```
  CustomActions (button): ERROR – CustomActions.buildLayout: anchor found 0 times (expected exactly 1)
```

So you never end up with a broken APK. The affected patch then needs to be adapted to the new code. Running the script again on already-patched code doesn't insert anything twice.

## How it works

Plexamp's React Native UI reports the playback state to an Android media session (Media3), and that session is what Android Auto displays. The patch hooks into exactly this hand-over (`TreblePlayer.updateFromState`):

1. When the track changes, `LyricsCore` looks up the track on your Plex server and fetches its lyrics stream (`/library/streams/…?format=xml`).
2. A 200 ms tick determines the current line from the playback position.
3. When the line changes, the media session gets a new title and subtitle. This only happens while Android Auto is connected.

| Path | Contents |
|---|---|
| `src/…/LyricsCore.java` | server requests, Plex XML and LRC parsers (plain Java, testable) |
| `src/…/LyricsHook.java` | Android Auto display, Off/On/Expanded button, Android Auto detection |
| `src/…/LyricsSettings.java` | settings dialog and app-icon shortcut |
| `src/…/I18n.java` | picks German or English based on the phone language |
| `stubs/` | placeholder signatures of Plexamp, Media3 and Guava classes, for compiling only |
| `test/` | self-tests: parsers, requests against a test server, input validation, languages |
| `build/patch_smali.py` | hooks in `TreblePlayer`, `MainActivity`, `CustomActions`, `PlexampSessionCallback` |
| `build/fetch-tools.sh` | downloads the build tools |
| `build.sh` | the complete build |

## Troubleshooting

```bash
adb logcat -s PlexampLyricsMod
```

The log shows whether Android Auto was detected, whether lyrics were loaded, and which display mode is active. Tokens are never written to the log.

## Limitations

- **Android Auto controls the layout.** Apps can't draw their own screens there, only fill in title, subtitle and album. How much of that is visible depends on the car.
- **Where the button appears**, next to "Next" or in the ⋯ menu, is up to Android Auto.
- **Only synced lyrics** are shown, not plain text without timestamps.
- **Unencrypted HTTP to the server** is blocked by Android for this app. Please use the HTTPS address.

## Disclaimer

Unofficial project, not affiliated with Plex Inc. Plex and Plexamp are trademarks of Plex Inc. Intended for personal use with a Plexamp APK you obtained yourself. Modifying the app may violate Plex's terms of service.

Please don't read along while driving. The Expanded mode is mainly meant for passengers.
