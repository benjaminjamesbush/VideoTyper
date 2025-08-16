import tkinter as tk
from tkinter import ttk, filedialog, font
import vlc
import sys
import os
from pathlib import Path
import time

class VideoPlayer:
    def __init__(self, root):
        self.root = root
        self.root.title("VideoTyper - Basic Player")
        self.root.geometry("800x600")
        
        self.instance = vlc.Instance()
        self.player = self.instance.media_player_new()
        self.current_media = None
        self.is_user_seeking = False
        self.subtitle_end_times = []
        self.current_subtitle_index = 0
        self.auto_pause_enabled = False
        self.next_pause_time = None
        
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
        control_frame = ttk.Frame(self.root)
        control_frame.pack(side=tk.TOP, fill=tk.X, padx=5, pady=5)
        
        ttk.Button(control_frame, text="Open Video", command=self.open_video).pack(side=tk.LEFT, padx=5)
        ttk.Button(control_frame, text="Play/Pause", command=self.play_pause).pack(side=tk.LEFT, padx=5)
        ttk.Button(control_frame, text="Stop", command=self.stop).pack(side=tk.LEFT, padx=5)
        
        # Subtitle controls
        ttk.Label(control_frame, text="Subtitles:").pack(side=tk.LEFT, padx=(20, 5))
        self.subtitle_combo = ttk.Combobox(control_frame, state="readonly", width=20)
        self.subtitle_combo.pack(side=tk.LEFT, padx=5)
        self.subtitle_combo.bind("<<ComboboxSelected>>", self.on_subtitle_change)
        
        self.video_frame = ttk.Frame(self.root, width=800, height=400)
        self.video_frame.pack(fill=tk.BOTH, expand=True, padx=5, pady=5)
        
        # Subtitle display frame
        subtitle_frame = ttk.Frame(self.root)
        subtitle_frame.pack(fill=tk.X, padx=5, pady=5)
        
        self.subtitle_label = ttk.Label(subtitle_frame, text="", font=font.Font(size=14), foreground="blue")
        self.subtitle_label.pack(pady=10)
        
        # Continue button (initially hidden)
        self.continue_button = ttk.Button(subtitle_frame, text="Continue (Enter)", command=self.continue_playback, state="disabled")
        self.continue_button.pack(pady=5)
        
        # Bind Enter key to continue
        self.root.bind('<Return>', lambda e: self.continue_playback())
        
        self.time_label = ttk.Label(self.root, text="00:00 / 00:00")
        self.time_label.pack(side=tk.BOTTOM, pady=5)
        
        self.progress_bar = ttk.Scale(self.root, from_=0, to=100, orient=tk.HORIZONTAL, command=self.on_seek)
        self.progress_bar.pack(side=tk.BOTTOM, fill=tk.X, padx=5, pady=5)
        
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
            self.current_media = self.instance.media_new(file_path)
            self.player.set_media(self.current_media)
            
            # Start playing briefly to load track info, then pause
            self.player.play()
            self.root.after(500, self.load_subtitle_tracks)
            
            # Enable auto-pause for typing practice
            self.auto_pause_enabled = True
            self.current_subtitle_index = 0
            
            print(f"Loaded video: {Path(file_path).name}")
            
    def play_pause(self):
        if self.player.is_playing():
            self.player.pause()
        else:
            self.player.play()
            
    def stop(self):
        self.player.stop()
        
    def load_subtitle_tracks(self):
        # Get subtitle tracks
        track_count = self.player.video_get_spu_count()
        
        subtitle_options = ["None"]
        subtitle_values = [-1]  # -1 means no subtitles
        
        for i in range(track_count):
            desc = self.player.video_get_spu_description()
            if desc:
                for track_id, track_name in desc:
                    if track_id >= 0:  # Skip the "Disable" option which is -1
                        subtitle_options.append(track_name.decode('utf-8') if isinstance(track_name, bytes) else str(track_name))
                        subtitle_values.append(track_id)
                break
        
        # Update combobox
        self.subtitle_combo['values'] = subtitle_options
        self.subtitle_values = subtitle_values
        
        # Select first subtitle track if available (after "None")
        if len(subtitle_options) > 1:
            self.subtitle_combo.current(1)
            self.player.video_set_spu(subtitle_values[1])
        else:
            self.subtitle_combo.current(0)
            
        # Pause after loading tracks
        self.player.pause()
        
        # Initialize pause timing
        self.set_next_pause_time()
        
    def on_subtitle_change(self, event):
        selection = self.subtitle_combo.current()
        if selection >= 0 and hasattr(self, 'subtitle_values'):
            track_id = self.subtitle_values[selection]
            self.player.video_set_spu(track_id)
            print(f"Changed subtitle track to: {self.subtitle_combo.get()} (ID: {track_id})")
        
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
            
            if duration > 0 and not self.is_user_seeking:
                progress = (current_time / duration) * 100
                self.progress_bar.set(progress)
                
            if duration > 0:
                current_str = self.format_time(current_time)
                duration_str = self.format_time(duration)
                self.time_label.config(text=f"{current_str} / {duration_str}")
            
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
        self.continue_button.config(state="normal")
        self.next_pause_time = None
        
        # Get current subtitle text
        current_sub = self.get_current_subtitle()
        if current_sub:
            self.subtitle_label.config(text=f"Type this word: {current_sub}")
        
    def continue_playback(self):
        if not self.player.is_playing():
            self.player.play()
            self.continue_button.config(state="disabled")
            self.subtitle_label.config(text="")
            
            # Set next pause time
            self.set_next_pause_time()
    
    def get_current_subtitle(self):
        # For now, return a placeholder - this will be enhanced to get actual subtitle text
        return "[subtitle word]"
    
    def set_next_pause_time(self):
        # For now, pause every 10 seconds as a demo
        # This will be enhanced to use actual subtitle timings
        if self.auto_pause_enabled:
            current_time = self.player.get_time()
            self.next_pause_time = current_time + 10000  # 10 seconds in milliseconds
    
    def on_close(self):
        self.player.stop()
        self.root.destroy()

def main():
    root = tk.Tk()
    player = VideoPlayer(root)
    root.mainloop()

if __name__ == "__main__":
    main()