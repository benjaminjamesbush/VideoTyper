import tkinter as tk
from tkinter import ttk, filedialog, font
import vlc
import sys
import os
from pathlib import Path
import time
import subprocess
import json
import re
import random
import threading
import pyttsx3
import queue

class VideoPlayer:
    def __init__(self, root):
        self.root = root
        self.root.title("VideoTyper - Basic Player")
        self.root.geometry("800x600")
        
        self.instance = vlc.Instance()
        self.player = self.instance.media_player_new()
        self.current_media = None
        self.is_user_seeking = False
        self.subtitles = []  # List of (start_ms, end_ms, text) tuples
        self.auto_pause_enabled = False
        self.next_pause_time = None
        self.pause_offset = 50  # Pause 50ms before subtitle ends (just for timing precision)
        self.current_subtitle_text = ""
        self.subtitle_display_index = -1
        self.paused_subtitles = set()  # Track which subtitles we've already paused for
        
        # Initialize TTS with a queue for thread safety
        self.tts_queue = queue.Queue()
        self.tts_thread = threading.Thread(target=self._tts_worker, daemon=True)
        self.tts_thread.start()
        
        self.setup_ui()
        
        # Set window handle after UI is created
        self.root.update_idletasks()  # Ensure window is created
        if sys.platform.startswith('win'):
            self.player.set_hwnd(self.video_frame.winfo_id())
        elif sys.platform.startswith('darwin'):
            self.player.set_nsobject(self.video_frame.winfo_id())
        else:
            self.player.set_xwindow(self.video_frame.winfo_id())
        
    def setup_ui(self):
        # Use grid for predictable layout
        self.root.grid_rowconfigure(1, weight=1)  # Video frame row expands
        self.root.grid_columnconfigure(0, weight=1)
        
        # Row 0: Control buttons
        control_frame = ttk.Frame(self.root)
        control_frame.grid(row=0, column=0, sticky="ew", padx=5, pady=5)
        
        self.open_button = ttk.Button(control_frame, text="Open Video", command=self.open_video)
        self.open_button.pack(side=tk.LEFT, padx=5)
        self.play_button = ttk.Button(control_frame, text="Play/Pause", command=self.play_pause)
        self.play_button.pack(side=tk.LEFT, padx=5)
        self.stop_button = ttk.Button(control_frame, text="Stop", command=self.stop)
        self.stop_button.pack(side=tk.LEFT, padx=5)
        
        # Row 1: Video frame (expands)
        self.video_frame = ttk.Frame(self.root, width=800, height=400)
        self.video_frame.grid(row=1, column=0, sticky="nsew", padx=5, pady=5)
        
        # Row 2: Subtitle display
        subtitle_frame = ttk.Frame(self.root)
        subtitle_frame.grid(row=2, column=0, sticky="ew", padx=5, pady=5)
        
        self.subtitle_display = ttk.Label(subtitle_frame, text="", font=font.Font(size=16), foreground="white", background="black")
        self.subtitle_display.pack(pady=5)
        
        self.subtitle_label = ttk.Label(subtitle_frame, text="", font=font.Font(size=14), foreground="blue")
        self.subtitle_label.pack(pady=5)
        
        # Text input field for typing practice
        self.typing_entry = ttk.Entry(subtitle_frame, font=font.Font(size=14), width=30)
        self.typing_entry.pack(pady=5)
        self.typing_entry.pack_forget()  # Initially hidden
        
        # Row 3: Progress bar
        self.progress_bar = ttk.Scale(self.root, from_=0, to=100, orient=tk.HORIZONTAL, command=self.on_seek)
        self.progress_bar.grid(row=3, column=0, sticky="ew", padx=5, pady=5)
        
        # Row 4: Time label
        self.time_label = ttk.Label(self.root, text="00:00 / 00:00")
        self.time_label.grid(row=4, column=0, pady=5)
        
        # Typing-related variables
        self.target_word = ""
        self.current_position = 0
        
        self.progress_bar.bind("<ButtonPress-1>", self.on_seek_start)
        self.progress_bar.bind("<ButtonRelease-1>", self.on_seek_end)
        
        self.root.protocol("WM_DELETE_WINDOW", self.on_close)
        
        self.update_time()
        
    def open_video(self, file_path=None):
        # Reset typing state if we were in the middle of typing
        if hasattr(self, 'typing_in_progress') and self.typing_in_progress:
            self.typing_in_progress = False
            self.typing_entry.pack_forget()
            self.typing_entry.unbind('<KeyRelease>')
            self.play_button.config(state="normal")
            self.subtitle_label.config(text="")
            
        if file_path is None:
            file_path = filedialog.askopenfilename(
                title="Select Video File",
                filetypes=[
                    ("Video files", "*.mp4 *.avi *.mkv *.mov *.wmv *.flv *.webm"),
                    ("All files", "*.*")
                ]
            )
        
        if file_path:
            # Store the path for use after extraction
            self.pending_video_path = file_path
            
            # Update UI to show loading status - make it very visible
            self.subtitle_label.config(
                text="⏳ EXTRACTING SUBTITLES... PLEASE WAIT (this may take 10-15 seconds)",
                font=font.Font(size=16, weight="bold"),
                foreground="red"
            )
            self.subtitle_display.config(text="Loading...", foreground="orange")
            
            # Hide buttons during extraction
            self.open_button.pack_forget()
            self.play_button.pack_forget()
            self.stop_button.pack_forget()
            
            # Run extraction in background thread
            thread = threading.Thread(target=self.extract_subtitles_async, args=(file_path,))
            thread.daemon = True
            thread.start()
    
    def extract_subtitles_async(self, file_path):
        """Extract subtitles in background thread"""
        self.extract_subtitles(file_path)
        
        # Schedule the video loading on the main thread
        self.root.after(0, self.finish_loading_video)
    
    def finish_loading_video(self):
        """Complete video loading after subtitle extraction (runs on main thread)"""
        if hasattr(self, 'pending_video_path'):
            # Now load the video
            self.current_media = self.instance.media_new(self.pending_video_path)
            self.player.set_media(self.current_media)
            
            # Clear status message and restore normal appearance
            self.subtitle_label.config(
                text="", 
                font=font.Font(size=14),
                foreground="blue"
            )
            self.subtitle_display.config(text="", foreground="white")
            
            # Show buttons again
            self.open_button.pack(side=tk.LEFT, padx=5)
            self.play_button.pack(side=tk.LEFT, padx=5)
            self.stop_button.pack(side=tk.LEFT, padx=5)
            
            # Enable auto-pause for typing practice
            self.auto_pause_enabled = True
            self.paused_subtitles.clear()  # Clear paused subtitles for new video
            
            print(f"Loaded video: {Path(self.pending_video_path).name} with {len(self.subtitles)} subtitles")
            delattr(self, 'pending_video_path')
            
            # Auto-play the video after loading
            self.player.play()
            # Disable VLC subtitles
            if self.player.video_get_spu() != -1:
                self.player.video_set_spu(-1)
            
    def play_pause(self):
        # Don't allow play/pause during typing practice
        if hasattr(self, 'typing_in_progress') and self.typing_in_progress:
            print("DEBUG: Play/pause blocked during typing practice")
            return
            
        if self.player.is_playing():
            self.player.pause()
        else:
            self.player.play()
            # Disable VLC subtitles on first play
            if self.player.video_get_spu() != -1:
                self.player.video_set_spu(-1)
            # Re-enable auto-pause if we have subtitles
            if hasattr(self, 'subtitles') and self.subtitles:
                self.auto_pause_enabled = True
            
    def stop(self):
        self.player.stop()
        
        # Reset typing state if we were in the middle of typing
        if hasattr(self, 'typing_in_progress') and self.typing_in_progress:
            self.typing_in_progress = False
            self.typing_entry.pack_forget()
            self.typing_entry.unbind('<KeyRelease>')
            self.play_button.config(state="normal")
            self.subtitle_label.config(text="")
            
        # Reset pause-related state
        self.next_pause_time = None
        # Don't disable auto_pause_enabled - let play button re-enable it
        
    def on_seek_start(self, event):
        """Called when user starts dragging the progress bar"""
        # Don't allow seeking during typing practice
        if hasattr(self, 'typing_in_progress') and self.typing_in_progress:
            print("DEBUG: Seeking blocked during typing practice")
            return
            
        self.is_user_seeking = True
        # Remember if we were playing before seeking
        self.was_playing_before_seek = self.player.is_playing()
        # Pause during seek
        if self.was_playing_before_seek:
            self.player.pause()
    
    def on_seek_end(self, event):
        """Called when user releases the progress bar"""
        if hasattr(self, 'typing_in_progress') and self.typing_in_progress:
            return
            
        self.is_user_seeking = False
        # Resume playback if we were playing before
        if hasattr(self, 'was_playing_before_seek') and self.was_playing_before_seek:
            self.player.play()
            delattr(self, 'was_playing_before_seek')
    
    def on_seek(self, value):
        if self.is_user_seeking and self.player.get_media():
            # Don't allow seeking during typing practice
            if hasattr(self, 'typing_in_progress') and self.typing_in_progress:
                print("DEBUG: Seeking blocked during typing practice")
                # Reset the progress bar to current position
                current_time = self.player.get_time()
                duration = self.player.get_length()
                if duration > 0:
                    progress = (current_time / duration) * 100
                    self.progress_bar.set(progress)
                return
                
            duration = self.player.get_length()
            if duration > 0:
                position = int(float(value) * duration / 100)
                self.player.set_time(position)
                # Clear pending pause info when scrubbing to new position
                self.next_pause_time = None
                if hasattr(self, 'next_pause_subtitle_index'):
                    delattr(self, 'next_pause_subtitle_index')
                
    def update_time(self):
        if self.player.get_media():
            current_time = self.player.get_time()
            duration = self.player.get_length()
            
            # Debug: Log every 10th call to avoid spam
            if not hasattr(self, 'update_counter'):
                self.update_counter = 0
            self.update_counter += 1
            if self.update_counter % 10 == 0:
                print(f"DEBUG: update_time called, current_time={current_time}ms")
            
            if duration > 0 and not self.is_user_seeking:
                progress = (current_time / duration) * 100
                self.progress_bar.set(progress)
                
            if duration > 0:
                current_str = self.format_time(current_time)
                duration_str = self.format_time(duration)
                self.time_label.config(text=f"{current_str} / {duration_str}")
            
            # Update subtitle display
            self.update_subtitle_display(current_time)
            
            # Check for subtitle auto-pause (but not while scrubbing)
            if (self.auto_pause_enabled and self.next_pause_time and 
                current_time >= self.next_pause_time and not self.is_user_seeking):
                self.pause_for_typing()
                
        self.root.after(100, self.update_time)
        
    def format_time(self, milliseconds):
        seconds = milliseconds // 1000
        minutes = seconds // 60
        seconds = seconds % 60
        return f"{minutes:02d}:{seconds:02d}"
        
    def pause_for_typing(self):
        self.player.pause()
        self.typing_in_progress = True  # Block play button
        
        # Mark this subtitle as paused
        if hasattr(self, 'next_pause_subtitle_index'):
            self.paused_subtitles.add(self.next_pause_subtitle_index)
            subtitle_index = self.next_pause_subtitle_index
        else:
            subtitle_index = self.subtitle_display_index
            
        self.next_pause_time = None
        
        # Get current subtitle text and check if it has words
        if 0 <= subtitle_index < len(self.subtitles):
            start_ms, end_ms, text = self.subtitles[subtitle_index]
            
            # Check if subtitle has actual words to type
            clean_text = re.sub(r'\([^)]*\)', '', text).strip()
            words = re.findall(r'\b[a-zA-Z]+\b', clean_text)
            
            if not words:
                # No words - just continue without pausing
                print(f"DEBUG: Skipping subtitle #{subtitle_index} (no words): {text}")
                self.player.play()
                return
            
            current_time = self.player.get_time()
            print(f"DEBUG: Paused at {current_time}ms for subtitle #{subtitle_index}")
            print(f"DEBUG: Subtitle time: {start_ms}-{end_ms}ms, Text: {text[:50]}...")
            
            # Seek to midpoint of the subtitle interval
            midpoint = (start_ms + end_ms) // 2
            self.player.set_time(midpoint)
            print(f"DEBUG: Seeked to midpoint {midpoint}ms")
            
            # Store the interval for replay on continue
            self.replay_start = start_ms
            
            # Keep the subtitle visible during the pause
            self.subtitle_display.config(text=text)
            
            # Show the word to type - randomly select from available words
            if words:
                # Filter for words longer than 2 chars if available, otherwise use all words
                longer_words = [w for w in words if len(w) > 2]
                word_pool = longer_words if longer_words else words
                word_to_type = random.choice(word_pool)
            else:
                word_to_type = ""
            
            self.subtitle_label.config(text=f"Type this word: {word_to_type}")
            print(f"DEBUG: Selected word: {word_to_type} from pool: {word_pool if words else []}")
            
            # Speak the word using TTS
            if word_to_type:
                self.speak_word(word_to_type)
            
            # Set up typing input
            self.target_word = word_to_type.upper()  # Store in uppercase for comparison
            self.current_position = 0
            self.typing_entry.delete(0, tk.END)  # Clear any previous text
            self.typing_entry.pack()  # Show the input field
            self.typing_entry.focus_set()  # Auto-focus for immediate typing
            
            # Bind the key validation
            self.typing_entry.bind('<KeyRelease>', self.validate_keystroke)
            
            # Also disable the play button
            self.play_button.config(state="disabled")
            
            # Start hint system - speak next letter after 5 seconds
            self.schedule_hint()
        
    def validate_keystroke(self, event):
        """Validate each keystroke and only accept correct letters"""
        if self.current_position >= len(self.target_word):
            return  # Already complete
        
        # Get the last typed character
        typed_char = event.char.upper()
        expected_char = self.target_word[self.current_position]
        
        # Check if it matches the expected character
        if typed_char == expected_char:
            # Update the entry to show only correct letters typed so far
            self.current_position += 1
            correct_text = self.target_word[:self.current_position]
            self.typing_entry.delete(0, tk.END)
            self.typing_entry.insert(0, correct_text)
            
            # Reset hint timer since user typed a correct letter
            self.schedule_hint()
            
            # Check if word is complete
            if self.current_position >= len(self.target_word):
                print(f"DEBUG: Word complete: {self.target_word}")
                self.cancel_hints()  # Cancel any pending hints
                self.typing_entry.unbind('<KeyRelease>')  # Unbind to prevent further input
                self.root.after(100, self.continue_playback)  # Small delay before continuing
        else:
            # Wrong key - remove it from the entry
            correct_text = self.target_word[:self.current_position]
            self.typing_entry.delete(0, tk.END)
            self.typing_entry.insert(0, correct_text)
    
    def continue_playback(self):
        if not self.player.is_playing():
            # Cancel any pending hints
            self.cancel_hints()
            
            # Hide the typing entry
            self.typing_entry.pack_forget()
            
            # Re-enable the play button and clear typing flag
            self.play_button.config(state="normal")
            self.typing_in_progress = False
            
            # Seek to start of the interval for replay
            if hasattr(self, 'replay_start'):
                self.player.set_time(self.replay_start)
                print(f"DEBUG: Replaying from {self.replay_start}ms")
                delattr(self, 'replay_start')  # Clear for next time
            
            # Reset pause detection to allow next subtitle to trigger
            self.next_pause_time = None
            self.auto_pause_enabled = True
            
            self.player.play()
            self.subtitle_label.config(text="")
            # Clear the subtitle display since we're moving on
            self.subtitle_display.config(text="")
    
    def extract_subtitles(self, video_path):
        """Extract subtitles from video file using ffmpeg"""
        try:
            # Extract subtitles to SRT format
            cmd = ['ffmpeg', '-i', video_path, '-map', '0:s:0', '-f', 'srt', '-']
            result = subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8')
            
            if result.returncode == 0:
                self.parse_srt(result.stdout)
                print(f"Extracted {len(self.subtitles)} subtitle entries")
            else:
                print("Failed to extract subtitles")
                
        except Exception as e:
            print(f"Error extracting subtitles: {e}")
    
    def parse_srt(self, srt_content):
        """Parse SRT content into list of (start_ms, end_ms, text) tuples"""
        self.subtitles = []
        
        # Split into subtitle blocks
        blocks = re.split(r'\n\n+', srt_content.strip())
        
        for block in blocks:
            lines = block.strip().split('\n')
            if len(lines) >= 3:
                # Parse timing line (e.g., "00:01:12,039 --> 00:01:14,074")
                timing_match = re.match(r'(\d{2}):(\d{2}):(\d{2}),(\d{3}) --> (\d{2}):(\d{2}):(\d{2}),(\d{3})', lines[1])
                if timing_match:
                    # Convert to milliseconds
                    start_ms = (int(timing_match.group(1)) * 3600000 +
                              int(timing_match.group(2)) * 60000 +
                              int(timing_match.group(3)) * 1000 +
                              int(timing_match.group(4)))
                    
                    end_ms = (int(timing_match.group(5)) * 3600000 +
                            int(timing_match.group(6)) * 60000 +
                            int(timing_match.group(7)) * 1000 +
                            int(timing_match.group(8)))
                    
                    # Join text lines
                    text = ' '.join(lines[2:])
                    self.subtitles.append((start_ms, end_ms, text))
    
    def update_subtitle_display(self, current_time):
        """Update the subtitle display based on current playback time"""
        # Find which subtitle should be displayed now
        for i, (start_ms, end_ms, text) in enumerate(self.subtitles):
            if start_ms <= current_time <= end_ms:
                # Always update display when we're in a subtitle's time range
                if i != self.subtitle_display_index:
                    self.subtitle_display_index = i
                    print(f"DEBUG: Showing subtitle #{i} at {current_time}ms: {text[:30]}...")
                
                # Always set the text, not just when index changes
                self.current_subtitle_text = text
                self.subtitle_display.config(text=text)
                
                # Check if we need to set up a pause for this subtitle
                # Do this EVERY update while in the subtitle, not just when it first appears
                if self.auto_pause_enabled and i not in self.paused_subtitles and self.next_pause_time is None:
                    self.next_pause_time = end_ms  # Pause at the end, no offset
                    self.next_pause_subtitle_index = i
                    print(f"DEBUG: Will pause at {self.next_pause_time}ms for subtitle #{i}")
                return
        
        # No subtitle should be displayed
        if self.subtitle_display_index != -1:
            print(f"DEBUG: Hiding subtitle #{self.subtitle_display_index} at {current_time}ms")
            self.subtitle_display_index = -1
            self.current_subtitle_text = ""
            self.subtitle_display.config(text="")
    
    def _tts_worker(self):
        """Worker thread for TTS - processes words from queue"""
        try:
            engine = pyttsx3.init()
            engine.setProperty('rate', 150)  # Speed of speech
            engine.setProperty('volume', 0.9)  # Volume level
            
            # Prime the engine with a dummy word to work around pyttsx3 bug
            engine.say("ready")
            engine.startLoop(False)
            engine.iterate()
            engine.endLoop()
            
        except Exception as e:
            print(f"ERROR: Failed to initialize TTS engine: {e}")
            sys.exit(1)
            
        while True:
            try:
                word = self.tts_queue.get(timeout=1.0)
                
                if word is None:  # Sentinel value to stop thread
                    break
                    
                engine.say(word)
                # Use startLoop/iterate/endLoop instead of runAndWait() for threading
                engine.startLoop(False)  # False = don't use internal event loop
                engine.iterate()
                engine.endLoop()
                
            except queue.Empty:
                continue
            except Exception as e:
                print(f"ERROR: TTS failed to speak '{word}': {e}")
                sys.exit(1)
    
    def speak_word(self, word):
        """Queue a word to be spoken by TTS"""
        self.tts_queue.put(word)
    
    def schedule_hint(self):
        """Schedule a hint to be spoken after 2 seconds"""
        if hasattr(self, 'hint_timer'):
            self.root.after_cancel(self.hint_timer)  # Cancel any existing timer
        
        # Schedule hint after 2 seconds (2000ms)
        self.hint_timer = self.root.after(2000, self.give_hint)
    
    def give_hint(self):
        """Speak the next letter the user needs to type"""
        if self.current_position < len(self.target_word):
            next_letter = self.target_word[self.current_position]
            hint_text = f"Type {next_letter}"
            self.speak_word(hint_text)
            
            # Schedule the next hint in 5 seconds
            self.schedule_hint()
    
    def cancel_hints(self):
        """Cancel any pending hints"""
        if hasattr(self, 'hint_timer'):
            self.root.after_cancel(self.hint_timer)
            delattr(self, 'hint_timer')
    
    def on_close(self):
        self.player.stop()
        self.tts_queue.put(None)  # Signal TTS thread to stop
        self.root.destroy()

def main():
    root = tk.Tk()
    player = VideoPlayer(root)
    root.mainloop()

if __name__ == "__main__":
    main()