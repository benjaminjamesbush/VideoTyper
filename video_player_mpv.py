import tkinter as tk
from tkinter import ttk, filedialog
import mpv
import sys
import os
from pathlib import Path

class VideoPlayerMPV:
    def __init__(self, root):
        self.root = root
        self.root.title("VideoTyper - MPV Player")
        self.root.geometry("800x600")
        self.is_user_seeking = False
        
        self.setup_ui()
        self.setup_player()
        
    def setup_ui(self):
        control_frame = ttk.Frame(self.root)
        control_frame.pack(side=tk.TOP, fill=tk.X, padx=5, pady=5)
        
        ttk.Button(control_frame, text="Open Video", command=self.open_video).pack(side=tk.LEFT, padx=5)
        ttk.Button(control_frame, text="Play/Pause", command=self.play_pause).pack(side=tk.LEFT, padx=5)
        ttk.Button(control_frame, text="Stop", command=self.stop).pack(side=tk.LEFT, padx=5)
        
        self.video_frame = ttk.Frame(self.root, width=800, height=500)
        self.video_frame.pack(fill=tk.BOTH, expand=True, padx=5, pady=5)
        
        self.time_label = ttk.Label(self.root, text="00:00 / 00:00")
        self.time_label.pack(side=tk.BOTTOM, pady=5)
        
        self.progress_bar = ttk.Scale(self.root, from_=0, to=100, orient=tk.HORIZONTAL, command=self.on_seek)
        self.progress_bar.pack(side=tk.BOTTOM, fill=tk.X, padx=5, pady=5)
        
        self.progress_bar.bind("<ButtonPress-1>", lambda e: setattr(self, 'is_user_seeking', True))
        self.progress_bar.bind("<ButtonRelease-1>", lambda e: setattr(self, 'is_user_seeking', False))
        
        self.root.protocol("WM_DELETE_WINDOW", self.on_close)
        
    def setup_player(self):
        self.player = mpv.MPV(
            wid=str(self.video_frame.winfo_id()),
            vo='gpu',
            log_handler=print,
            loglevel='error'
        )
        
        @self.player.property_observer('time-pos')
        def time_observer(_name, value):
            if value is not None:
                self.update_time_display()
                
        self.update_time_display()
        
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
            self.player.play(file_path)
            print(f"Loaded video: {Path(file_path).name}")
            
    def play_pause(self):
        self.player.cycle('pause')
            
    def stop(self):
        self.player.command('stop')
        
    def on_seek(self, value):
        if self.is_user_seeking and self.player.duration:
            position = (float(value) / 100) * self.player.duration
            self.player.seek(position, reference='absolute')
                
    def update_time_display(self):
        current = self.player.time_pos or 0
        duration = self.player.duration or 0
        
        if duration > 0 and not self.is_user_seeking:
            progress = (current / duration) * 100
            self.progress_bar.set(progress)
            
        if duration > 0:
            current_str = self.format_time(current)
            duration_str = self.format_time(duration)
            self.time_label.config(text=f"{current_str} / {duration_str}")
            
        self.root.after(100, self.update_time_display)
        
    def format_time(self, seconds):
        if seconds is None:
            return "00:00"
        minutes = int(seconds) // 60
        secs = int(seconds) % 60
        return f"{minutes:02d}:{secs:02d}"
        
    def on_close(self):
        self.player.quit()
        self.root.destroy()

def main():
    root = tk.Tk()
    player = VideoPlayerMPV(root)
    root.mainloop()

if __name__ == "__main__":
    main()