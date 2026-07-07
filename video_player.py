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
import pygame
import numpy as np

# Anti-mash cooldown: a wrong key silently turns the whole screen flat gray and ignores
# all input; every press while gray extends it, and repeats double it (up to the max).
COOLDOWN_BASE_MS = 1000
COOLDOWN_MAX_MS = 8000

class VideoPlayer:
    def __init__(self, root):
        self.root = root
        self.root.title("VideoTyper - Basic Player")
        self.root.geometry("800x800")  # Made taller for keyboard
        
        self.instance = vlc.Instance()
        self.player = self.instance.media_player_new()
        # Set movie volume to 200% (max for VLC)
        self.player.audio_set_volume(200)
        self.current_media = None
        self.is_user_seeking = False
        self.subtitles = []  # List of (start_ms, end_ms, text) tuples
        self.auto_pause_enabled = False
        self.next_pause_time = None
        self.pause_offset = 50  # Pause 50ms before subtitle ends (just for timing precision)
        self.current_subtitle_text = ""
        self.combined_canvas_index = -1
        self.paused_subtitles = set()  # Track which subtitles we've already paused for
        self.flash_state = False  # For flashing next letter indicator
        self.flash_timer = None  # Timer for flashing effect
        self.cooldown_until = 0   # Epoch ms; all typing input is ignored until this time
        self.cooldown_len = 0     # Current cooldown length in ms (escalates per wrong key)
        self.cooldown_streak = 0  # Consecutive wrong evaluations (reset by a correct letter)
        
        # Initialize TTS with a queue for thread safety
        self.tts_queue = queue.Queue()
        self.tts_thread = threading.Thread(target=self._tts_worker, daemon=True)
        self.tts_thread.start()
        
        # Initialize pygame mixer for letter sounds
        pygame.mixer.init(frequency=22050, size=-16, channels=2, buffer=512)
        self.letter_sounds = {}
        self.load_letter_sounds()
        
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
        control_frame.grid(row=0, column=0, sticky="ew", padx=5, pady=2)
        
        self.open_button = ttk.Button(control_frame, text="Open Video", command=self.open_video)
        self.open_button.pack(side=tk.LEFT, padx=5)
        self.play_button = ttk.Button(control_frame, text="Play/Pause", command=self.play_pause)
        self.play_button.pack(side=tk.LEFT, padx=5)
        self.stop_button = ttk.Button(control_frame, text="Stop", command=self.stop)
        self.stop_button.pack(side=tk.LEFT, padx=5)
        
        # Row 1: Video frame (expands)
        self.video_frame = ttk.Frame(self.root, width=800, height=400)
        self.video_frame.grid(row=1, column=0, sticky="nsew", padx=5, pady=0)
        
        # Row 2: Combined subtitle and keyboard display
        combined_frame = ttk.Frame(self.root)
        combined_frame.grid(row=2, column=0, sticky="ew", padx=5, pady=0)
        
        # Create single canvas for both subtitle and keyboard
        # Height: 60 (subtitle) + 160 (keyboard) = 220
        self.combined_canvas = tk.Canvas(combined_frame, width=800, height=220, 
                                        background="black", highlightthickness=0)
        self.combined_canvas.pack(pady=0, fill=tk.X)
        
        # Store fonts for reuse
        self.subtitle_font = font.Font(size=48)
        self.subtitle_font_bold = font.Font(size=48, weight="bold")
        
        # Store canvas item IDs for flashing effect
        self.flash_rect_id = None
        self.flash_text_id = None
        
        # Create keyboard display on the combined canvas
        self.keyboard = self.create_keyboard_on_canvas()
        # Hide keyboard initially
        self.keyboard.hide()
        
        # No visible input field - we'll capture keystrokes directly
        
        # Row 3: Progress bar (moved to bottom)
        self.progress_bar = ttk.Scale(self.root, from_=0, to=100, orient=tk.HORIZONTAL, command=self.on_seek)
        self.progress_bar.grid(row=3, column=0, sticky="ew", padx=5, pady=2)
        
        # Row 4: Time label (at bottom with progress bar)
        self.time_label = ttk.Label(self.root, text="00:00 / 00:00")
        self.time_label.grid(row=4, column=0, pady=2)
        
        # Typing-related variables
        self.target_word = ""
        self.current_position = 0
        
        # Full-window flat gray sheet shown while the anti-mash cooldown is active.
        # Deliberately featureless: no text, no animation, no sound.
        self.gray_overlay = tk.Frame(self.root, background="#7a7a7a")

        self.progress_bar.bind("<ButtonPress-1>", self.on_seek_start)
        self.progress_bar.bind("<ButtonRelease-1>", self.on_seek_end)
        
        self.root.protocol("WM_DELETE_WINDOW", self.on_close)
        
        self.update_time()
    
    def create_keyboard_on_canvas(self):
        """Create keyboard display directly on the combined canvas"""
        
        class KeyboardDisplay:
            def __init__(self, canvas):
                # Use the provided canvas instead of creating new one
                self.canvas = canvas
                
                # Key dimensions
                self.key_width = 40
                self.key_height = 40
                self.key_spacing = 5
                self.row_spacing = 10
                
                # Calculate centered starting positions for each row
                # Row 1 has 12 keys: 12*40 + 11*5 = 535px total width
                # Center in 800px canvas: (800-535)/2 = 132.5px
                self.row1_x = 133
                self.row2_x = 153  # Half key offset (20px from row1)
                self.row3_x = 173  # Full key offset (40px from row1)
                # Position keyboard in lower part of canvas (subtitle is 0-60, keyboard starts at 70)
                self.base_y = 70
                
                # Define keyboard layout
                self.row1_keys = ['Q', 'W', 'E', 'R', 'T', 'Y', 'U', 'I', 'O', 'P', '[', ']']
                self.row2_keys = ['A', 'S', 'D', 'F', 'G', 'H', 'J', 'K', 'L', ';', "'"]
                self.row3_keys = ['Z', 'X', 'C', 'V', 'B', 'N', 'M', ',', '.', '/']
                
                # Store key rectangles for highlighting
                self.key_rects = {}
                self.key_labels = {}
                
                # Font for key labels
                self.key_font = font.Font(family="Arial", size=16, weight="bold")
                
                # Draw the keyboard
                self.draw_keyboard()
                self.visible = True
                
            def hide(self):
                """Hide all keyboard elements"""
                self.canvas.delete("keyboard")
                self.visible = False
                
            def show(self):
                """Show keyboard elements"""
                if not self.visible:
                    self.draw_keyboard()
                    self.visible = True
                
            def reposition_keyboard_to_letter(self, letter_x_position, letter_char):
                """Reposition keyboard to align a specific key with the subtitle letter position"""
                if not letter_char:
                    return
                    
                letter_char = letter_char.upper()
                
                # Find which row and position the letter is in
                key_row = None
                key_index = None
                row_offset = 0
                
                if letter_char in self.row1_keys:
                    key_row = 1
                    key_index = self.row1_keys.index(letter_char)
                    row_offset = 0
                elif letter_char in self.row2_keys:
                    key_row = 2
                    key_index = self.row2_keys.index(letter_char)
                    row_offset = 20  # Half key offset
                elif letter_char in self.row3_keys:
                    key_row = 3
                    key_index = self.row3_keys.index(letter_char)
                    row_offset = 40  # Full key offset
                else:
                    # Letter not found in keyboard, fall back to centering
                    return
                
                # Calculate where this key would be relative to keyboard start
                # We want the center of the key to align with the center of the letter
                key_x_offset = row_offset + key_index * (self.key_width + self.key_spacing) + self.key_width // 2
                
                # Calculate where keyboard should start to align this key with the letter
                keyboard_start_x = letter_x_position - key_x_offset
                
                # Store current highlighted key and flash state before clearing
                current_highlighted_key = getattr(self, 'current_highlighted_key', None)
                current_flash_state = getattr(self, 'current_flash_state', False)
                
                # Clear existing keyboard
                self.canvas.delete("keyboard")
                self.key_rects.clear()
                self.key_labels.clear()
                
                # Calculate keyboard total width (12 keys * 40 + 11 spaces * 5 = 535px)
                keyboard_width = 535
                canvas_width = self.canvas.winfo_width()
                if canvas_width <= 1:
                    canvas_width = 800  # Default
                
                # Constrain to canvas bounds
                min_x = 10
                max_x = canvas_width - keyboard_width - 10
                new_x = max(min_x, min(keyboard_start_x, max_x))
                
                # Update row positions
                self.row1_x = new_x
                self.row2_x = new_x + 20  # Half key offset
                self.row3_x = new_x + 40  # Full key offset
                
                # Redraw keyboard at new position
                self.draw_keyboard()
                
                # Restore highlight state if there was one
                if current_highlighted_key:
                    self.highlight_key(current_highlighted_key)
                    if current_flash_state:
                        self.flash_key(current_highlighted_key, current_flash_state)
            
            def reposition_keyboard(self, word_x_position):
                """Reposition keyboard to align with word position"""
                # Store current highlighted key and flash state before clearing
                current_highlighted_key = getattr(self, 'current_highlighted_key', None)
                current_flash_state = getattr(self, 'current_flash_state', False)
                
                # Clear existing keyboard
                self.canvas.delete("keyboard")
                self.key_rects.clear()
                self.key_labels.clear()
                
                # Calculate keyboard total width (12 keys * 40 + 11 spaces * 5 = 535px)
                keyboard_width = 535
                canvas_width = self.canvas.winfo_width()
                if canvas_width <= 1:
                    canvas_width = 800  # Default
                
                # Center keyboard under the word, but keep within canvas bounds
                ideal_x = word_x_position - keyboard_width // 2
                
                # Constrain to canvas bounds
                min_x = 10
                max_x = canvas_width - keyboard_width - 10
                new_x = max(min_x, min(ideal_x, max_x))
                
                # Update row positions
                self.row1_x = new_x
                self.row2_x = new_x + 20  # Half key offset
                self.row3_x = new_x + 40  # Full key offset
                
                # Redraw keyboard at new position
                self.draw_keyboard()
                
                # Restore highlight state if there was one
                if current_highlighted_key:
                    self.highlight_key(current_highlighted_key)
                    if current_flash_state:
                        self.flash_key(current_highlighted_key, current_flash_state)
                
            def draw_keyboard(self):
                """Draw all keys on the canvas"""
                # Draw row 1 (Q-P, [, ])
                y = self.base_y
                for i, key in enumerate(self.row1_keys):
                    x = self.row1_x + i * (self.key_width + self.key_spacing)
                    self.draw_key(x, y, key)
                
                # Draw row 2 (A-L, ;, ')
                y = self.base_y + self.key_height + self.row_spacing
                for i, key in enumerate(self.row2_keys):
                    x = self.row2_x + i * (self.key_width + self.key_spacing)
                    self.draw_key(x, y, key)
                
                # Draw row 3 (Z-M, comma, period, slash)
                y = self.base_y + 2 * (self.key_height + self.row_spacing)
                for i, key in enumerate(self.row3_keys):
                    x = self.row3_x + i * (self.key_width + self.key_spacing)
                    self.draw_key(x, y, key)
            
            def draw_key(self, x, y, key_char):
                """Draw a single key at the specified position"""
                # Draw key rectangle (white outline on black background)
                rect = self.canvas.create_rectangle(
                    x, y, x + self.key_width, y + self.key_height,
                    fill="black", outline="white", width=2, tags="keyboard"
                )
                
                # Draw key label (white text)
                label = self.canvas.create_text(
                    x + self.key_width // 2, y + self.key_height // 2,
                    text=key_char, font=self.key_font, fill="white", tags="keyboard"
                )
                
                # Store references for later highlighting
                self.key_rects[key_char.upper()] = rect
                self.key_labels[key_char.upper()] = label
            
            def highlight_key(self, key_char):
                """Highlight a specific key"""
                key_char = key_char.upper()
                
                # Store the currently highlighted key
                self.current_highlighted_key = key_char if key_char else None
                
                # Reset all keys to default (black with white outline)
                for key in self.key_rects:
                    self.canvas.itemconfig(self.key_rects[key], fill="black", outline="white", width=2)
                    self.canvas.itemconfig(self.key_labels[key], fill="white")
                
                # Highlight the specified key
                if key_char in self.key_rects:
                    self.canvas.itemconfig(self.key_rects[key_char], fill="yellow", outline="red", width=3)
                    self.canvas.itemconfig(self.key_labels[key_char], fill="red")
                    
            def flash_key(self, key_char, flash_state):
                """Flash a key between two color states"""
                key_char = key_char.upper()
                
                # Store the flash state
                self.current_flash_state = flash_state
                
                if key_char in self.key_rects:
                    if flash_state:
                        # Red background, yellow text
                        self.canvas.itemconfig(self.key_rects[key_char], fill="red", outline="yellow", width=3)
                        self.canvas.itemconfig(self.key_labels[key_char], fill="yellow")
                    else:
                        # Yellow background, red text
                        self.canvas.itemconfig(self.key_rects[key_char], fill="yellow", outline="red", width=3)
                        self.canvas.itemconfig(self.key_labels[key_char], fill="red")
        
        return KeyboardDisplay(self.combined_canvas)
        
    def open_video(self, file_path=None):
        # Reset typing state if we were in the middle of typing
        if hasattr(self, 'typing_in_progress') and self.typing_in_progress:
            self.typing_in_progress = False
            self.root.unbind('<KeyPress>')
            self.play_button.config(state="normal")
            
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
            self.set_subtitle_text("⏳ EXTRACTING SUBTITLES... PLEASE WAIT (this may take 10-15 seconds)")
            self.set_subtitle_text("Loading...")
            
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
            self.set_subtitle_text("")
            
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
        self.reset_cooldown()

        # Reset typing state if we were in the middle of typing
        if hasattr(self, 'typing_in_progress') and self.typing_in_progress:
            self.typing_in_progress = False
            self.root.unbind('<KeyPress>')
            self.play_button.config(state="normal")
            
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
        # End of anti-mash cooldown: silently restore the UI
        if self.cooldown_until and self.now_ms() >= self.cooldown_until:
            self.cooldown_until = 0
            self.end_cooldown_display()

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
        self.reset_cooldown()  # Each word starts with a clean anti-mash slate
        
        # Mark this subtitle as paused
        if hasattr(self, 'next_pause_subtitle_index'):
            self.paused_subtitles.add(self.next_pause_subtitle_index)
            subtitle_index = self.next_pause_subtitle_index
        else:
            subtitle_index = self.combined_canvas_index
            
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
            
            # Show keyboard when paused for typing
            self.keyboard.show()
            
            # Keep the subtitle visible during the pause
            self.set_subtitle_text(text)
            
            # Use the pre-selected word for this subtitle if available
            if hasattr(self, 'subtitle_words') and subtitle_index in self.subtitle_words and self.subtitle_words[subtitle_index]:
                word_to_type = self.subtitle_words[subtitle_index]
            else:
                # Fallback to selecting a word if not pre-selected
                if words:
                    # Filter for words longer than 2 chars and no apostrophes
                    # Avoid fragments like "couldn" from "couldn't"
                    clean_words = [w for w in words if "'" not in text[max(0, text.find(w)-1):text.find(w)+len(w)+1]]
                    longer_words = [w for w in clean_words if len(w) > 2]
                    word_pool = longer_words if longer_words else clean_words if clean_words else words
                    word_to_type = random.choice(word_pool)
                else:
                    word_to_type = ""
            
            # Store the word to highlight for continuous updates
            self.highlight_word = word_to_type if word_to_type else None
            
            # Apply initial highlighting with first letter marked and start flashing
            if self.highlight_word:
                self.set_subtitle_text(text, self.highlight_word, self.current_position)
                self.start_flash_timer()  # Start the flashing effect
            
            print(f"DEBUG: Using pre-selected word: {word_to_type}" if (hasattr(self, 'subtitle_words') and subtitle_index in self.subtitle_words) else f"DEBUG: Selected word: {word_to_type}")
            
            # Speak the word using TTS
            if word_to_type:
                self.speak_word(word_to_type)
            
            # Set up typing input
            self.target_word = word_to_type.upper()  # Store in uppercase for comparison
            self.current_position = 0
            
            # Highlight the first key on keyboard
            if self.target_word:
                self.keyboard.highlight_key(self.target_word[0])
            
            # Bind keyboard events directly to root window
            self.root.bind('<KeyPress>', self.validate_keystroke)
            self.root.focus_set()  # Ensure window has focus
            
            # Also disable the play button
            self.play_button.config(state="disabled")
            
            # Start hint system - speak next letter after 5 seconds
            self.schedule_hint()
        
    def validate_keystroke(self, event):
        """Validate each keystroke and only accept correct letters"""
        # Anti-mash cooldown: while the screen is gray, every keypress silently extends it
        if self.now_ms() < self.cooldown_until:
            self.enter_cooldown(extend_only=True)
            return

        if self.current_position >= len(self.target_word):
            return  # Already complete

        # Ignore modifier/navigation keys (they produce no printable character)
        if not event.char or not event.char.isprintable():
            return

        # Get the last typed character
        typed_char = event.char.upper()
        expected_char = self.target_word[self.current_position]

        # Check if it matches the expected character
        if typed_char == expected_char:
            self.cooldown_streak = 0  # Honest typing de-escalates future cooldowns
            # Advance position
            self.current_position += 1
            
            # Update subtitle display to show next letter with current flash state
            if hasattr(self, 'current_subtitle_text') and self.current_subtitle_text:
                flash_state = self.flash_state if hasattr(self, 'flash_state') else False
                self.set_subtitle_text(self.current_subtitle_text, self.highlight_word, 
                                     self.current_position, flash_state)
            
            # Update keyboard to highlight next key
            if self.current_position < len(self.target_word):
                next_char = self.target_word[self.current_position]
                self.keyboard.highlight_key(next_char)
            
            # Speak the letter that was just typed
            print(f"DEBUG: Speaking typed letter: {expected_char}")
            self.speak_letter(expected_char)
            
            # Reset hint timer since user typed a correct letter
            self.schedule_hint()
            
            # Check if word is complete
            if self.current_position >= len(self.target_word):
                print(f"DEBUG: Word complete: {self.target_word}")
                self.cancel_hints()  # Cancel any pending hints
                self.root.unbind('<KeyPress>')  # Unbind to prevent further input
                # Clear keyboard highlighting and hide it
                self.keyboard.highlight_key('')  # Empty string will reset all keys
                self.keyboard.hide()
                # Play reward sound
                self.play_reward_sound()
                # Delay slightly longer to let reward sound play
                self.root.after(1200, self.continue_playback)  # 1.2 seconds for the beeps
        else:
            # Wrong key: no sound, no message - the whole screen just goes flat gray
            self.enter_cooldown()
    
    def continue_playback(self):
        if not self.player.is_playing():
            # Cancel any pending hints
            self.cancel_hints()
            self.reset_cooldown()
            
            # Re-enable the play button and clear typing flag
            self.play_button.config(state="normal")
            self.typing_in_progress = False
            self.highlight_word = None  # Clear the highlighting
            self.stop_flash_timer()  # Stop the flashing effect
            
            # Clear the subtitle display since we're moving on
            self.set_subtitle_text("")
            
            # Seek to start of the interval for replay
            if hasattr(self, 'replay_start'):
                self.player.set_time(self.replay_start)
                print(f"DEBUG: Replaying from {self.replay_start}ms")
                delattr(self, 'replay_start')  # Clear for next time
            
            # Reset pause detection to allow next subtitle to trigger
            self.next_pause_time = None
            self.auto_pause_enabled = True
            
            self.player.play()
            # Clear the subtitle display since we're moving on
            self.set_subtitle_text("")
    
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
                if i != self.combined_canvas_index:
                    self.combined_canvas_index = i
                    print(f"DEBUG: Showing subtitle #{i} at {current_time}ms: {text[:30]}...")
                    
                    # Pre-select word for highlighting when subtitle first appears
                    if self.auto_pause_enabled and i not in self.paused_subtitles:
                        # Extract words from the subtitle text
                        import re
                        words = re.findall(r'\b[a-zA-Z]+\b', text)
                        
                        if words:
                            # Filter out words that precede a colon (like speaker names)
                            filtered_words = []
                            for w in words:
                                # Check if this word is followed by a colon
                                word_pos = text.find(w)
                                if word_pos != -1:
                                    # Look for colon after the word (allowing for spaces)
                                    after_word = text[word_pos + len(w):word_pos + len(w) + 5]
                                    if ':' not in after_word.strip():
                                        filtered_words.append(w)
                            
                            # Filter for words longer than 2 chars and no apostrophes
                            clean_words = [w for w in filtered_words if "'" not in text[max(0, text.find(w)-1):text.find(w)+len(w)+1]]
                            longer_words = [w for w in clean_words if len(w) > 2]
                            word_pool = longer_words if longer_words else clean_words if clean_words else filtered_words if filtered_words else words
                            
                            # Select and store the word for THIS subtitle using its index as key
                            import random
                            if not hasattr(self, 'subtitle_words'):
                                self.subtitle_words = {}
                            self.subtitle_words[i] = random.choice(word_pool)
                            print(f"DEBUG: Pre-selected word '{self.subtitle_words[i]}' for subtitle #{i}")
                        else:
                            if not hasattr(self, 'subtitle_words'):
                                self.subtitle_words = {}
                            self.subtitle_words[i] = None
                
                # Always set the text, not just when index changes
                self.current_subtitle_text = text
                
                # Apply highlighting if we have a pre-selected word for this subtitle (not paused yet)
                # BUT ONLY if the subtitle has actual words (not just sounds like "(GASPS)")
                if hasattr(self, 'subtitle_words') and i in self.subtitle_words and self.subtitle_words[i] and i not in self.paused_subtitles:
                    # Check if subtitle has actual words to type (not just parenthetical sounds)
                    import re
                    clean_text = re.sub(r'\([^)]*\)', '', text).strip()
                    words = re.findall(r'\b[a-zA-Z]+\b', clean_text)
                    if words:  # Only highlight if there are actual words
                        # Show word highlighted but not in typing mode yet
                        self.set_subtitle_text(text, self.subtitle_words[i], None, False)
                    else:
                        # No typeable words, just show plain text
                        self.set_subtitle_text(text)
                # Apply highlighting if we're in typing mode (during pause)
                elif hasattr(self, 'highlight_word') and self.highlight_word:
                    # Pass current position and flash state if actively typing
                    pos = self.current_position if hasattr(self, 'current_position') else None
                    flash = self.flash_state if hasattr(self, 'flash_state') else False
                    self.set_subtitle_text(text, self.highlight_word, pos, flash)
                else:
                    self.set_subtitle_text(text)
                
                # Check if we need to set up a pause for this subtitle
                # Do this EVERY update while in the subtitle, not just when it first appears
                if self.auto_pause_enabled and i not in self.paused_subtitles and self.next_pause_time is None:
                    self.next_pause_time = end_ms  # Pause at the end, no offset
                    self.next_pause_subtitle_index = i
                    print(f"DEBUG: Will pause at {self.next_pause_time}ms for subtitle #{i}")
                return
        
        # No subtitle should be displayed
        if self.combined_canvas_index != -1:
            print(f"DEBUG: Hiding subtitle #{self.combined_canvas_index} at {current_time}ms")
            self.combined_canvas_index = -1
            self.current_subtitle_text = ""
            self.set_subtitle_text("")
    
    def set_subtitle_text(self, text, highlight_word=None, next_letter_pos=None, flash_red=False):
        """Update subtitle display with optional word highlighting and next letter indicator"""
        # Clear only subtitle items (tagged as "subtitle"), not keyboard
        self.combined_canvas.delete("subtitle")
        
        # Reset flash IDs
        self.flash_rect_id = None
        self.flash_text_id = None
        
        # Replace newlines with spaces to keep everything on one line and trim
        if text:
            text = text.replace('\n', ' ').replace('\r', ' ').strip()
        else:
            return
        
        # Get canvas dimensions
        canvas_width = self.combined_canvas.winfo_width()
        if canvas_width <= 1:
            # Canvas not yet rendered, use a default
            canvas_width = 700
        center_x = canvas_width // 2
        center_y = 30  # Center vertically in 60px height
        
        if highlight_word and text:
            # Find the word to highlight
            import re
            pattern = r'\b' + re.escape(highlight_word) + r'\b'
            matches = list(re.finditer(pattern, text, re.IGNORECASE))
            
            if matches:
                match = matches[0]  # Use first match
                
                # Split text into parts
                before_text = text[:match.start()]
                word_text = highlight_word.upper()
                after_text = text[match.end():]
                
                # Measure total width to center properly
                # Create temporary text to measure
                temp_id = self.combined_canvas.create_text(0, 0, text=before_text + word_text + after_text, 
                                                           font=self.subtitle_font, anchor="w")
                bbox = self.combined_canvas.bbox(temp_id)
                total_width = bbox[2] - bbox[0] if bbox else 0
                self.combined_canvas.delete(temp_id)
                
                # Calculate starting position for centered text
                start_x = (canvas_width - total_width) // 2
                current_x = start_x
                
                # Draw text before highlighted word
                if before_text:
                    self.combined_canvas.create_text(current_x, center_y, text=before_text,
                                                     font=self.subtitle_font, fill="white", anchor="w", tags="subtitle")
                    # Measure width of before text
                    temp_id = self.combined_canvas.create_text(0, 0, text=before_text, 
                                                               font=self.subtitle_font, anchor="w")
                    bbox = self.combined_canvas.bbox(temp_id)
                    before_width = bbox[2] - bbox[0] if bbox else 0
                    self.combined_canvas.delete(temp_id)
                    current_x += before_width
                
                # Store word position for keyboard alignment
                word_start_x = current_x
                
                # Draw highlighted word with next letter indicator
                if next_letter_pos is not None and next_letter_pos < len(word_text):
                    # Draw completed part
                    if next_letter_pos > 0:
                        completed_text = word_text[:next_letter_pos]
                        self.combined_canvas.create_text(current_x, center_y, text=completed_text,
                                                         font=self.subtitle_font_bold, fill="yellow", anchor="w", tags="subtitle")
                        # Measure completed width
                        temp_id = self.combined_canvas.create_text(0, 0, text=completed_text,
                                                                   font=self.subtitle_font_bold, anchor="w")
                        bbox = self.combined_canvas.bbox(temp_id)
                        completed_width = bbox[2] - bbox[0] if bbox else 0
                        self.combined_canvas.delete(temp_id)
                        current_x += completed_width
                    
                    # Draw next letter with background
                    next_letter = word_text[next_letter_pos]
                    # Create the letter to measure it
                    temp_id = self.combined_canvas.create_text(current_x, center_y, text=next_letter,
                                                               font=self.subtitle_font_bold, fill="yellow", anchor="w")
                    bbox = self.combined_canvas.bbox(temp_id)
                    self.combined_canvas.delete(temp_id)  # Delete the temporary measurement text
                    
                    # Store the CENTER position of the next letter for keyboard alignment
                    next_letter_x = current_x + (bbox[2] - bbox[0]) / 2 if bbox else current_x
                    
                    # Create background rectangle (store ID for flashing)
                    bg_color = "red" if flash_red else "yellow"
                    self.flash_rect_id = self.combined_canvas.create_rectangle(bbox[0], bbox[1], bbox[2], bbox[3],
                                                                               fill=bg_color, outline="", tags="subtitle")
                    
                    # Recreate letter on top (store ID for flashing)
                    text_color = "yellow" if flash_red else "red"
                    self.flash_text_id = self.combined_canvas.create_text(current_x, center_y, text=next_letter,
                                                                          font=self.subtitle_font_bold, fill=text_color, anchor="w", tags="subtitle")
                    
                    next_letter_width = bbox[2] - bbox[0] if bbox else 0
                    current_x += next_letter_width
                    
                    # Draw remaining letters
                    if next_letter_pos + 1 < len(word_text):
                        remaining_text = word_text[next_letter_pos + 1:]
                        self.combined_canvas.create_text(current_x, center_y, text=remaining_text,
                                                         font=self.subtitle_font_bold, fill="yellow", anchor="w", tags="subtitle")
                        # Measure remaining width
                        temp_id = self.combined_canvas.create_text(0, 0, text=remaining_text,
                                                                   font=self.subtitle_font_bold, anchor="w")
                        bbox = self.combined_canvas.bbox(temp_id)
                        remaining_width = bbox[2] - bbox[0] if bbox else 0
                        self.combined_canvas.delete(temp_id)
                        current_x += remaining_width
                else:
                    # Draw entire word highlighted
                    self.combined_canvas.create_text(current_x, center_y, text=word_text,
                                                     font=self.subtitle_font_bold, fill="yellow", anchor="w", tags="subtitle")
                    # Measure word width
                    temp_id = self.combined_canvas.create_text(0, 0, text=word_text,
                                                               font=self.subtitle_font_bold, anchor="w")
                    bbox = self.combined_canvas.bbox(temp_id)
                    word_width = bbox[2] - bbox[0] if bbox else 0
                    self.combined_canvas.delete(temp_id)
                    current_x += word_width
                
                # Draw text after highlighted word
                if after_text:
                    self.combined_canvas.create_text(current_x, center_y, text=after_text,
                                                     font=self.subtitle_font, fill="white", anchor="w", tags="subtitle")
                
                # Reposition keyboard to align highlighted letter with subtitle letter
                if hasattr(self, 'keyboard') and next_letter_pos is not None and next_letter_pos < len(word_text):
                    # Use the stored position of the next letter
                    self.keyboard.reposition_keyboard_to_letter(next_letter_x, next_letter)
            else:
                # No match found, just show regular text
                self.combined_canvas.create_text(center_x, center_y, text=text,
                                                 font=self.subtitle_font, fill="white", anchor="center", tags="subtitle")
        else:
            # No highlighting, just show centered text
            self.combined_canvas.create_text(center_x, center_y, text=text,
                                             font=self.subtitle_font, fill="white", anchor="center", tags="subtitle")
    
    def load_letter_sounds(self):
        """Load all letter WAV files into memory"""
        sound_dir = Path("letter_sounds")
        if not sound_dir.exists():
            print("Warning: letter_sounds directory not found")
            return
        
        for letter in "ABCDEFGHIJKLMNOPQRSTUVWXYZ":
            wav_file = sound_dir / f"{letter}.wav"
            if wav_file.exists():
                try:
                    self.letter_sounds[letter.upper()] = pygame.mixer.Sound(str(wav_file))
                except Exception as e:
                    print(f"Error loading {letter}.wav: {e}")
    
    def play_letter_sound(self, letter):
        """Play the sound for a given letter"""
        letter_upper = letter.upper()
        if letter_upper in self.letter_sounds:
            try:
                self.letter_sounds[letter_upper].play()
            except Exception as e:
                print(f"Error playing letter sound {letter}: {e}")
    
    def play_reward_sound(self):
        """Generate and play 5 beeps with random pitches"""
        def play_beeps():
            sample_rate = 22050
            duration = 0.15  # 150ms per beep
            
            for i in range(5):
                # Random frequency between 400Hz and 800Hz
                frequency = random.randint(400, 800)
                
                # Generate sine wave
                frames = int(sample_rate * duration)
                arr = np.zeros((frames, 2), dtype=np.int16)
                max_amplitude = 7000  # Lower volume
                
                for j in range(frames):
                    t = float(j) / sample_rate
                    # Apply fade in/out to avoid clicks
                    envelope = 1.0
                    if j < frames * 0.1:  # Fade in
                        envelope = j / (frames * 0.1)
                    elif j > frames * 0.9:  # Fade out
                        envelope = (frames - j) / (frames * 0.1)
                    
                    value = int(max_amplitude * envelope * np.sin(2 * np.pi * frequency * t))
                    arr[j] = [value, value]  # Stereo
                
                # Create and play sound immediately
                sound = pygame.sndarray.make_sound(arr)
                sound.play()
                time.sleep(0.10)  # 100ms - overlap beeps for continuous sound
        
        # Play in separate thread to not block UI
        threading.Thread(target=play_beeps, daemon=True).start()
    
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
                    
                print(f"DEBUG: TTS processing: '{word}'")
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
    
    def speak_letter(self, letter):
        """Play the sound for the typed letter"""
        self.play_letter_sound(letter)
    
    def now_ms(self):
        return time.time() * 1000

    def enter_cooldown(self, extend_only=False):
        """Turn the whole screen flat gray and ignore input. Silent by design: any sound or
        animation here would be entertaining enough to become the reward for mashing."""
        if not extend_only:
            self.cooldown_streak += 1
            self.cooldown_len = min(COOLDOWN_BASE_MS * (2 ** (self.cooldown_streak - 1)), COOLDOWN_MAX_MS)
        self.cooldown_until = self.now_ms() + self.cooldown_len
        self.gray_overlay.place(x=0, y=0, relwidth=1, relheight=1)
        self.gray_overlay.lift()

    def end_cooldown_display(self):
        self.gray_overlay.place_forget()

    def reset_cooldown(self):
        self.cooldown_until = 0
        self.cooldown_len = 0
        self.cooldown_streak = 0
        self.end_cooldown_display()

    def start_flash_timer(self):
        """Start the flashing effect for the next letter indicator"""
        self.stop_flash_timer()  # Cancel any existing timer
        self.flash_next_letter()
    
    def stop_flash_timer(self):
        """Stop the flashing effect"""
        if self.flash_timer:
            self.root.after_cancel(self.flash_timer)
            self.flash_timer = None
            self.flash_state = False
    
    def flash_next_letter(self):
        """Toggle the flash state and update display"""
        if hasattr(self, 'typing_in_progress') and self.typing_in_progress:
            self.flash_state = not self.flash_state
            
            # Update the canvas items directly for flashing effect
            if self.flash_rect_id and self.flash_text_id:
                if self.flash_state:
                    # Red background, yellow text
                    self.combined_canvas.itemconfig(self.flash_rect_id, fill="red")
                    self.combined_canvas.itemconfig(self.flash_text_id, fill="yellow")
                else:
                    # Yellow background, red text
                    self.combined_canvas.itemconfig(self.flash_rect_id, fill="yellow")
                    self.combined_canvas.itemconfig(self.flash_text_id, fill="red")
            
            # Also flash the keyboard key
            if hasattr(self, 'target_word') and self.current_position < len(self.target_word):
                next_char = self.target_word[self.current_position]
                self.keyboard.flash_key(next_char, self.flash_state)
            
            # Schedule next flash in 500ms
            self.flash_timer = self.root.after(500, self.flash_next_letter)
    
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