# VideoTyper for Android

Native Android port of the desktop VideoTyper typing game (Kotlin, Jetpack Compose, Media3/ExoPlayer). Portrait phone layout; typing uses the regular on-screen keyboard (GBoard etc.).

## How it plays

The movie plays with the app's own subtitle strip under the video. Each subtitle line gets one word pre-highlighted in yellow. When the line ends, playback pauses on a frame from the middle of the line and the word is spoken aloud. The child types the word letter by letter — wrong letters are ignored, each correct letter plays its letter sound, and the next letter flashes red/yellow in the subtitle. If they stall, TTS hints "Type X" (after 2 s, then every 5 s). On completion a reward jingle plays, the whole line replays, and the movie continues.

Layout: since typing is the primary interaction, the on-screen keyboard stays visible for the whole session rather than appearing per round. The video is pinned at the very top at full width and a fixed 16:9 height, the subtitle sits directly beneath it, and the transport controls sit in a compact row just above the keyboard. Nothing reflows when a typing round starts — the keyboard is already there.

Anti-mash: a wrong key (or gesture-typed/multi-character input) silently drains the entire screen — video included — to grayscale and ignores all input for 1 s, doubling up to 8 s on repeats; any press while gray extends it. Entering gray cuts TTS off mid-utterance and freezes the letter flash — gray means fully unresponsive, with no sounds, messages, or animation to make mashing entertaining; the UI stays fully visible (clearly alive, just colorless). When gray ends, a "Type X" prompt sounds immediately to herald that typing works again. An accepted correct letter resets the escalation. Video desaturation uses a TextureView surface with a saturation-0 hardware-layer paint; the Compose UI gets a saveLayer color filter.

## Opening videos

The **Menu** chip opens a separate, keyboard-free chooser screen with three sections:
- **Recent** — a horizontally scrollable ribbon of poster-frame thumbnails of recently opened videos (tap to replay). Thumbnails are generated with `MediaMetadataRetriever` (local/http directly, `smb://` via a jcifs-backed `MediaDataSource`) and disk-cached. The list is a capped, persisted history.
- **Local** — the system file picker (local storage, SD card, and any documents provider).
- **Network** — saved SMB servers you add once (host + optional label/user/pass/domain; persisted). Tap a server to **browse** its shares and folders (`NetworkBrowserScreen`, backed by jcifs-ng) and tap a video file to play it — no path typing. A direct `http(s)://` stream field is also provided. Guest access is used when credentials are blank.

The transport buttons (Menu, Play, Stop) are never disabled; during a typing round they act as escape hatches (Menu leaves to the chooser, Play abandons the word and resumes, Stop resets).

Subtitles are read from the embedded subtitle track (e.g. SRT inside MKV), same as the desktop app; the first English/undetermined text track is selected automatically.
- E-AC-3/AC-3/DTS audio in MKV rips is handled by the bundled FFmpeg decoder extension (nextlib), since most phones lack those hardware decoders.

## Building

Requires JDK 17+ and the Android SDK (compileSdk 36). From `android/`:

```
gradlew.bat assembleDebug     # APK lands in app/build/outputs/apk/debug/
```

or open the `android/` folder in Android Studio. Install with `adb install app/build/outputs/apk/debug/app-debug.apk`.

## Structure

- `app/src/main/java/com/videotyper/game/GameController.kt` — the game state machine, driven by ExoPlayer's live subtitle cue callbacks (the desktop version pre-extracted the SRT with ffmpeg instead).
- `game/WordSelector.kt` — word-picking rules ported from the desktop app (skip `(SOUNDS)`, speaker labels, contraction fragments; prefer 3+ letter words).
- `game/AudioFeedback.kt` — letter WAVs (SoundPool), TTS (word + hints), generated reward beeps (AudioTrack).
- `player/SmbDataSource.kt` + `player/SchemeDataSource.kt` — `smb://` support for Media3.
- `ui/PlayerScreen.kt` — Compose UI: video surface, subtitle strip with flashing next-letter, transport controls, and the always-on hidden text field that keeps the keyboard up.
- `ui/MenuScreen.kt` — the video chooser (recents ribbon, local picker, network URL); `ui/ThumbnailLoader.kt` generates/caches poster frames; `data/RecentsStore.kt` persists the recents list.
- `MainActivity.kt` — swaps between the menu and player screens and records opened videos into recents.
