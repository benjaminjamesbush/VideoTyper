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
        
        self.progress_bar.bind("<ButtonPress-1>", lambda e: setattr(self, 'is_user_seeking', True))
        self.progress_bar.bind("<ButtonRelease-1>", lambda e: setattr(self, 'is_user_seeking', False))
        
        self.root.protocol("WM_DELETE_WINDOW", self.on_close)
        
        self.update_time()
        
    def open_video(self, file_path=None):
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
            
    def play_pause(self):
        if self.player.is_playing():
            self.player.pause()
        else:
            self.player.play()
            # Disable VLC subtitles on first play
            if self.player.video_get_spu() != -1:
                self.player.video_set_spu(-1)
            
    def stop(self):
        self.player.stop()
        
    def on_seek(self, value):
        if self.is_user_seeking and self.player.get_media():
            duration = self.player.get_length()
            if duration > 0:
                position = int(float(value) * duration / 100)
                self.player.set_time(position)
                
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
            
            # Check for subtitle auto-pause
            if self.auto_pause_enabled and self.next_pause_time and current_time >= self.next_pause_time:
                self.pause_for_typing()
                
        self.root.after(100, self.update_time)
        
    def format_time(self, milliseconds):
        seconds = milliseconds // 1000
        minutes = seconds // 60
        seconds = seconds % 60
        return f"{minutes:02d}:{seconds:02d}"
        
    def pause_for_typing(self):
        self.player.pause()
        
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
            
            # Set up typing input
            self.target_word = word_to_type.upper()  # Store in uppercase for comparison
            self.current_position = 0
            self.typing_entry.delete(0, tk.END)  # Clear any previous text
            self.typing_entry.pack()  # Show the input field
            self.typing_entry.focus_set()  # Auto-focus for immediate typing
            
            # Bind the key validation
            self.typing_entry.bind('<KeyRelease>', self.validate_keystroke)
        
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
            
            # Check if word is complete
            if self.current_position >= len(self.target_word):
                print(f"DEBUG: Word complete: {self.target_word}")
                self.typing_entry.unbind('<KeyRelease>')  # Unbind to prevent further input
                self.root.after(100, self.continue_playback)  # Small delay before continuing
        else:
            # Wrong key - remove it from the entry
            correct_text = self.target_word[:self.current_position]
            self.typing_entry.delete(0, tk.END)
            self.typing_entry.insert(0, correct_text)
    
    def continue_playback(self):
        if not self.player.is_playing():
            # Hide the typing entry
            self.typing_entry.pack_forget()
            
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
    
    def on_close(self):
        self.player.stop()
        self.root.destroy()

def main():
    root = tk.Tk()
    player = VideoPlayer(root)
    root.mainloop()

if __name__ == "__main__":
    main()