# HarmooPlayer

An Android video player that plays almost any video file, loads SRT subtitles,
and searches subtitle sites for matching subtitles.

## Features

- **Plays AVI, MKV, MPG/MPEG, MP4, MOV, WMV, FLV, WebM, TS, M2TS, OGG, 3GP** and more, using
  libVLC (the engine inside VLC). It falls back to software decoding for codecs phones often can't
  decode in hardware: DivX/Xvid, MPEG-2, AC3, DTS and others.
- **Subtitles:** load `.srt` (also `.ass`, `.ssa`, `.vtt`, `.sub`), choose between subtitle tracks
  embedded in MKVs, adjust timing in 0.1 s / 1 s steps, and set text size and encoding.
- **Online subtitle search** on **OpenSubtitles.com** and **SubDL.com**:
  - The app computes the OpenSubtitles *movie hash* of the file, so subtitles made for your exact
    release show up first with a ✓. These are almost always in sync.
  - It reads the title, year and SxxEyy from the file name (`The.Office.S03E07.720p.mkv` →
    "The Office", season 3, episode 7). You can edit the search.
  - Tap a result to download it, unzip it if needed, and load it. The app remembers that subtitle
    for the video next time.
- Library of all videos on the device with thumbnails, plus "Open file" for any folder, and "Open
  with" from file managers. You can also play network streams (http/https/rtsp).
- Resumes where you left off. Double-tap left or right to skip 10 s, double-tap the middle to
  pause. Buttons for aspect ratio and rotation. Works with keyboards and TV remotes.

## Get the APK (no Android Studio needed)

1. Create a new **GitHub** repository and upload the contents of this folder, including the
   hidden `.github` folder.
2. GitHub Actions builds the APKs automatically (about 5 minutes). Open the **Actions** tab, then the
   latest "Build APK" run, then **Artifacts**, and download `HarmooPlayer-apks`.
3. Install the right APK on your phone (allow "Install unknown apps" when asked):
   - `app-arm64-v8a-release.apk`: almost every phone from the last 7+ years. **Pick this one.**
   - `app-armeabi-v7a-release.apk`: older 32-bit phones and some cheap TV boxes.
   - `app-universal-release.apk`: works on everything, but it's about 4× larger.

## Or build it yourself

Open the folder in **Android Studio** (Ladybug or newer), let Gradle sync, then use
**Run ▶** or **Build → Build APK(s)**. From the command line: `./gradlew assembleRelease`
(requires JDK 17 and the Android SDK).

## Turn on online subtitle search (free)

Each subtitle site needs a free API key. Add one or both in **⋮ → Settings**:

- **OpenSubtitles.com:** create an account, then go to <https://www.opensubtitles.com/consumers>,
  create a consumer, and copy its API key. Adding your username and password is optional but raises
  the daily download limit. Without a login the quota is small.
- **SubDL.com:** create an account at <https://subdl.com> and copy the API key from your account
  panel.

Set your languages in Settings too, e.g. `en` or `en,es`.

## Using subtitles while watching

Tap the screen, then tap the **subtitles icon** in the top bar:

| Option | What it does |
| --- | --- |
| Search online… | Searches the sites for this video; tap a result to load it |
| Load .srt file… | Pick any subtitle file from your phone |
| Choose subtitle track | Switch between embedded tracks (e.g. in MKVs) and loaded files |
| Subtitle timing (sync)… | Move subtitles earlier or later |
| Turn subtitles off | |

If subtitles show garbled accented letters, set the encoding in Settings (e.g. `Windows-1250`
for Central European, `Windows-1251` for Cyrillic, `Windows-1256` for Arabic).

## Notes

- If a video shows green or garbled frames, turn **Hardware decoding** off in Settings.
- libVLC is licensed under the LGPL 2.1+. If you distribute this app, credit VideoLAN and note that
  libVLC is used under the LGPL.
- Project layout: `PlayerActivity.kt` (player), `SubtitleSearchActivity.kt` (search UI),
  `subs/` (OpenSubtitles + SubDL clients, movie hash, file-name parser, zip handling).
