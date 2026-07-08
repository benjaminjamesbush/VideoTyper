# VideoTyper

A typing game that plays videos with subtitles and periodically pauses to have you type a highlighted word from the current subtitle line before the movie continues. The target word is spoken aloud, each correct letter plays its sound, wrong keys are ignored, and gentle hints nudge you along. It's a native Android app (Kotlin, Jetpack Compose, Media3/ExoPlayer). A Python desktop prototype also exists — see [DESKTOP.md](DESKTOP.md).

## Demo

https://github.com/user-attachments/assets/a00bf606-0342-4f8d-9b41-ad44c43a075d

## Install

### ⬇ [Download the latest APK](https://github.com/benjaminjamesbush/VideoTyper/releases/latest/download/VideoTyper.apk)

Open it on your phone and allow installing from unknown sources when prompted. Android 8.0+ (API 26), portrait phones. The build is signed, so later versions update cleanly over it. (Older versions and release notes: [all releases](https://github.com/benjaminjamesbush/VideoTyper/releases).)

## What videos work

The game gets the words to type from the video's own subtitle track, so it needs a video with an **embedded text subtitle track** — in practice an **MKV with an SRT / subrip track**. These do **not** work:

- videos with no subtitles (there's nothing to type),
- external `.srt` sidecar files (the subtitles must be embedded in the video),
- image-based subtitles such as PGS or VobSub (no text to extract).

The first English / undetermined text track is selected automatically. Play from local storage, an SMB network share, or an http(s) stream. E-AC-3 / AC-3 / DTS audio in MKV rips plays via a bundled FFmpeg decoder (nextlib), since most phones can't decode those natively.

## How it plays

The movie plays with the app's own subtitle strip under the video. Each subtitle line gets one word pre-highlighted in yellow. When the line ends, playback pauses on a frame from the middle of the line and the word is spoken aloud. You type the word letter by letter — wrong letters are ignored, each correct letter plays its letter sound, and the next letter flashes red/yellow in the subtitle. If you stall, a spoken hint says the next letter (after 2 s, then every 5 s). On completion a reward jingle plays, the whole line replays, and the movie continues.

Since typing is the primary interaction, the on-screen keyboard stays visible for the whole session rather than appearing per round. The video is pinned at the very top at full width and a fixed 16:9 height, the subtitle sits directly beneath it, and the transport controls sit in a compact row just above the keyboard — nothing reflows when a typing round starts.

**Anti-mash:** a wrong key (or gesture-typed / multi-character input) silently drains the entire screen — video included — to grayscale and ignores all input for 1 s, doubling up to 8 s on repeats; any press while gray extends it. Entering gray cuts speech off mid-utterance and freezes the letter flash — gray means fully unresponsive, with no sounds, messages, or animation to make mashing entertaining, while the UI stays fully visible (clearly alive, just colorless). When gray ends, the next-letter hint sounds immediately to signal typing works again. An accepted correct letter resets the escalation.

## Choosing a video (the Menu)

The **Menu** chip opens a separate, keyboard-free chooser with three sections:

- **Recent** — a horizontally scrollable ribbon of portrait (2:3) poster thumbnails of recently opened videos (tap to replay). Thumbnails are Plex/Emby-style: an official poster matched from the filename, downloaded and disk-cached, with a decoded video frame as fallback. Poster sources: **TMDB** (primary; released APKs bundle a key so it works out of the box), plus keyless **iTunes** (movies) and **TVmaze** (TV) fallbacks. Tap the **✎** on a poster to open the thumbnail manager (search by an editable title, pick from a poster grid, force the video frame, or return to Automatic). SMB recents are hidden unless their server answers when the chooser opens.
- **Local** — the system file picker (local storage, SD card, and any documents provider).
- **Network** — saved SMB servers you add once (host + optional label / user / pass / domain, persisted). Tap a server to browse its shares and folders and tap a video to play it — no path typing. A direct http(s) stream field is also provided. Guest access is used when credentials are blank.

Menu and Stop are always available (Menu leaves to the chooser, Stop resets). Play is disabled during a typing round — there is deliberately no way to skip the word.

## Building from source

Requires JDK 17+ and the Android SDK (compileSdk 36). From `android/`:

```
gradlew.bat assembleDebug      # debug APK in app/build/outputs/apk/debug/
```

or open the `android/` folder in Android Studio.

**Posters (TMDB).** Released APKs bundle a TMDB API key so poster matching works with no setup. A source build still works — it falls back to the keyless iTunes / TVmaze sources plus a video frame — but to enable TMDB, drop your own free key into `android/keys.properties` (`TMDB_API_KEY=...`, untracked; see `keys.properties.example`) or paste it into the app's in-app **Settings**. Builds without a key print a warning. A signed **release** build (`assembleRelease`) additionally needs your own keystore configured in `keys.properties`.

*This product uses the TMDB API but is not endorsed or certified by TMDB.*

## Project structure

Under `android/app/src/main/java/com/videotyper/`:

- `game/GameController.kt` — the game state machine, driven by ExoPlayer's live subtitle cue callbacks.
- `game/WordSelector.kt` — word-picking rules (skip `(SOUNDS)`, speaker labels, contraction fragments; prefer 3+ letter words).
- `game/AudioFeedback.kt` — letter WAVs (SoundPool), text-to-speech (word + hints), generated reward beeps (AudioTrack).
- `ui/PlayerScreen.kt` — the play UI: video surface, subtitle strip with flashing next-letter, transport controls, and the always-on hidden text field that keeps the keyboard up.
- `ui/MenuScreen.kt` (video chooser), `ui/NetworkBrowserScreen.kt` (SMB folder browser), `ui/ThumbnailManagerScreen.kt` (poster picker), `ui/SettingsScreen.kt` (TMDB key).
- `ui/ThumbnailLoader.kt` — thumbnail resolver (online poster → frame fallback, cached); `data/PosterSearch.kt` (TMDB/iTunes/TVmaze), `data/TitleParser.kt` (filename→title), plus the `data/*Store.kt` persistence and `data/Http.kt`.
- `player/SmbDataSource.kt`, `player/SchemeDataSource.kt`, `player/SmbSupport.kt`, `player/SmbMediaDataSource.kt` — `smb://` support for Media3 (jcifs-ng).
- `MainActivity.kt` — swaps between the menu, player, network-browser, thumbnail-manager, and settings screens; records opened videos into recents.

## Desktop version

The original prototype (`video_player.py`, Python + VLC + tkinter) is kept as-is — see [DESKTOP.md](DESKTOP.md). The Android app is the maintained version.

## License

MIT — see [LICENSE](LICENSE). Bundled dependencies (Media3, jcifs-ng, nextlib, etc.) retain their own licenses.
