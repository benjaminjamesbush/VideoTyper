import tkinter as tk
from tkinter import ttk, filedialog
import vlc
import sys
import os
from pathlib import Path

class VideoPlayer:
    def __init__(self, root):
        self.root = root
        self.root.title("VideoTyper - Basic Player")
        self.root.geometry("800x600")
        
        self.instance = vlc.Instance()
        self.player = self.instance.media_player_new()
        self.current_media = None
        self.is_user_seeking = False
        
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
        
        self.video_frame = ttk.Frame(self.root, width=800, height=500)
        self.video_frame.pack(fill=tk.BOTH, expand=True, padx=5, pady=5)
        
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
            
            print(f"Loaded video: {Path(file_path).name}")
            
    def play_pause(self):
        if self.player.is_playing():
            self.player.pause()
        else:
            self.player.play()
            
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
            
            if duration > 0 and not self.is_user_seeking:
                progress = (current_time / duration) * 100
                self.progress_bar.set(progress)
                
            if duration > 0:
                current_str = self.format_time(current_time)
                duration_str = self.format_time(duration)
                self.time_label.config(text=f"{current_str} / {duration_str}")
                
        self.root.after(100, self.update_time)
        
    def format_time(self, milliseconds):
        seconds = milliseconds // 1000
        minutes = seconds // 60
        seconds = seconds % 60
        return f"{minutes:02d}:{seconds:02d}"
        
    def on_close(self):
        self.player.stop()
        self.root.destroy()

def main():
    root = tk.Tk()
    player = VideoPlayer(root)
    root.mainloop()

if __name__ == "__main__":
    main()