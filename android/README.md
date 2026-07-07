# VideoTyper for Android

Native Android port of the desktop VideoTyper typing game (Kotlin, Jetpack Compose, Media3/ExoPlayer). Portrait phone layout; typing uses the regular on-screen keyboard (GBoard etc.).

## How it plays

The movie plays with the app's own subtitle strip under the video. Each subtitle line gets one word pre-highlighted in yellow. When the line ends, playback pauses on a frame from the middle of the line, the word is spoken aloud, and the on-screen keyboard pops up. The child types the word letter by letter — wrong letters are ignored, each correct letter plays its letter sound, and the next letter flashes red/yellow in the subtitle. If they stall, TTS hints "Type X" (after 2 s, then every 5 s). On completion a reward jingle plays, the whole line replays, and the movie continues.

Anti-mash: a wrong key (or gesture-typed/multi-character input) silently drains the entire screen — video included — to grayscale and ignores all input for 1 s, doubling up to 8 s on repeats; any press while gray extends it. While gray, the letter flash freezes and TTS hints pause, and the failure path is intentionally free of sounds, messages, and animation so that mashing is never entertaining; the UI stays fully visible (clearly alive, just colorless). An accepted correct letter resets the escalation. Video desaturation uses a TextureView surface with a saturation-0 hardware-layer paint; the Compose UI gets a saveLayer color filter.

## Opening videos

- **Open** — system file picker (local storage, SD card, and any documents provider).
- **Network** — enter a URL: `smb://user:pass@server/share/movie.mkv` (Windows/NAS shares, guest access if no credentials; jcifs-ng under the hood) or any `http(s)://` stream.
- Subtitles are read from the embedded subtitle track (e.g. SRT inside MKV), same as the desktop app; the first English/undetermined text track is selected automatically.
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
- `ui/PlayerScreen.kt` — Compose UI: video surface, subtitle strip with flashing next-letter, transport controls, hidden text field that summons the IME during typing rounds.
