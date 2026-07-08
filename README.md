# VideoTyper

A typing game that plays videos with subtitles and periodically pauses to have you type a highlighted word from the current subtitle line before the movie continues. The target word is spoken aloud, each correct letter plays its sound, wrong keys are ignored, and gentle hints nudge you along.

## Demo

https://github.com/user-attachments/assets/a00bf606-0342-4f8d-9b41-ad44c43a075d

There are two implementations in this repo:

- **Android** ([`android/`](android/)) — the maintained app. Kotlin, Jetpack Compose, Media3/ExoPlayer. Portrait phone layout, plays from local storage, SMB network shares, or http(s) streams, with an on-screen keyboard, a poster-thumbnail library, and an anti-mash mechanic. See [`android/README.md`](android/README.md) for details.
- **Desktop** (`video_player.py`) — the original prototype. Python + VLC + tkinter. See [Desktop: running](#desktop-running).

## Android: building

Requires JDK 17+ and the Android SDK (compileSdk 36). From `android/`:

```
./gradlew assembleDebug      # APK in app/build/outputs/apk/debug/
```

or open the `android/` folder in Android Studio.

### Posters (TMDB key)

Recent videos are matched to official posters. Released builds bundle a TMDB API key so this works with no setup. If you build from source, the app still works — it falls back to the keyless iTunes/TVmaze sources and a video frame — but to enable TMDB, either drop your own key into `android/keys.properties` (`TMDB_API_KEY=...`, untracked; see `keys.properties.example`) or paste it into the app's in-app **Settings**. Builds without a key print a warning.

This product uses the TMDB API but is not endorsed or certified by TMDB.

## Desktop: running

The original prototype, `video_player.py` (Python + VLC + tkinter). It plays a video, extracts the embedded subtitle track with ffmpeg, and runs the same type-the-word game with spoken hints and letter sounds. The Android app is the maintained version; the desktop one is kept as-is.

Requires **Python 3**, plus **[VLC media player](https://www.videolan.org/)** and **[ffmpeg](https://ffmpeg.org/)** installed and on your `PATH`. Then:

```
pip install -r requirements.txt
python video_player.py
```

Click **Open Video**, choose a file with an embedded subtitle track (e.g. an MKV), and it starts. The bundled `letter_sounds/` folder provides the per-letter voice clips.

## License

MIT — see [LICENSE](LICENSE). Bundled dependencies (Media3, jcifs-ng, nextlib, etc.) retain their own licenses.
