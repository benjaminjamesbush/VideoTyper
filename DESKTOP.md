# VideoTyper — desktop version

The original prototype, `video_player.py` (Python + VLC + tkinter). It plays a video, extracts the embedded subtitle track with ffmpeg, and runs the same type-the-word game with spoken hints and letter sounds. The [Android app](README.md) is the maintained version; this desktop one is kept as-is.

Like the Android app, it needs a video with an **embedded text subtitle track** (e.g. an MKV with an SRT/subrip track) — that's where the words to type come from.

## Requirements

- **Python 3**
- **[VLC media player](https://www.videolan.org/)** and **[ffmpeg](https://ffmpeg.org/)**, installed and on your `PATH`
- Python packages (python-vlc, pyttsx3, pygame, numpy, Pillow):

## Run

```
pip install -r requirements.txt
python video_player.py
```

Click **Open Video**, choose a file with an embedded subtitle track, and it starts. The bundled `letter_sounds/` folder provides the per-letter voice clips.
