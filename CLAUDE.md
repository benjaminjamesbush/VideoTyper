## Project Overview

VideoTyper is a typing game application that plays videos with subtitles, periodically pausing to test the user's typing skills by having them type highlighted words from the subtitles.

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