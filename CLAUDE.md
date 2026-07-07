## Project Overview

VideoTyper is a typing game application that plays videos with subtitles, periodically pausing to test the user's typing skills by having them type highlighted words from the subtitles.

Two implementations live in this repo: the original desktop app (`video_player.py`, Python + VLC + tkinter) and a native Android port (`android/`, Kotlin + Jetpack Compose + Media3/ExoPlayer — see `android/README.md` for architecture and build instructions). The Android app is portrait-only, uses the system on-screen keyboard for typing, and plays videos from local storage, `smb://` network shares (jcifs-ng), or `http(s)://` URLs. Build note: the repo sits on a network share whose I/O is flaky under Gradle, so build output is routed to `C:\Users\Ben\.videotyper\` via `videotyper.localBuildDir` in `android/gradle.properties`, and builds should pass `--project-cache-dir C:\Users\Ben\.videotyper\gradle-cache`.

## Technology Decision

After discussing requirements (handling large video files, MKV support, embedded subtitles), the recommended approach is:
- **Python + python-vlc** for full video format support including MKV with embedded subtitles
- VLC handles all codec/format complexities that web-based solutions struggle with

## Core Features for MVP

1. Load video file (any format VLC supports)
2. Load or extract subtitle track
3. Parse subtitles into timed text segments
4. Pause video at predetermined intervals
5. Highlight word in subtitle for user to type
6. Validate typed input
7. Resume playback on correct input

## Implementation Notes

- Use python-vlc for video playback and subtitle track access
- Consider pysrt or webvtt-py for subtitle parsing
- Use tkinter or PyQt5 for the UI
- Focus on MVP first - no scoring, statistics, or difficulty settings initially

## Important Debugging Notes

- ALWAYS capture error output when running the video player: use `2>&1` to capture stderr
- Example: `python video_player.py 2>&1`
- CRITICAL: NEVER run the video player without `2>&1` - each run takes several minutes of user time
- DO NOT WASTE USER TIME by forgetting to capture errors
- ALWAYS RUN IN BACKGROUND with run_in_background=true - the player WILL crash/timeout/error
- NEVER assume success from a timeout - that means it crashed and you lost the error
- EVERY RUN WILL HAVE ERRORS - CAPTURE THEM PROPERLY
- Example: Run with `2>&1` AND `run_in_background=true` ALWAYS
- SAVE OUTPUT TO A FILE: `python video_player.py > output.log 2>&1` with run_in_background=true
- THEN CHECK THE FILE AFTER: `cat output.log` to see the errors
- DO NOT RUN WITHOUT SAVING TO FILE - YOU WILL LOSE THE OUTPUT