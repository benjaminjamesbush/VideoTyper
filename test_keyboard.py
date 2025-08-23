import tkinter as tk
from tkinter import font

class KeyboardDisplay:
    def __init__(self, root):
        self.root = root
        self.root.title("QWERTY Keyboard Display")
        self.root.geometry("650x350")
        self.root.configure(bg='black')
        
        # Create Canvas for keyboard
        self.canvas = tk.Canvas(root, width=620, height=200, bg='white', highlightthickness=1)
        self.canvas.pack(pady=20)
        
        # Key dimensions
        self.key_width = 40
        self.key_height = 40
        self.key_spacing = 5
        self.row_spacing = 10
        
        # Starting positions for each row
        self.row1_x = 20
        self.row2_x = 40  # Half key offset (20px)
        self.row3_x = 60  # Full key offset (40px)
        self.base_y = 20
        
        # Define keyboard layout
        self.row1_keys = ['Q', 'W', 'E', 'R', 'T', 'Y', 'U', 'I', 'O', 'P', '[', ']']
        self.row2_keys = ['A', 'S', 'D', 'F', 'G', 'H', 'J', 'K', 'L', ';', "'"]
        self.row3_keys = ['Z', 'X', 'C', 'V', 'B', 'N', 'M', ',', '.', '/']
        
        # Store key rectangles for highlighting
        self.key_rects = {}
        self.key_labels = {}
        
        # Font for key labels
        self.key_font = font.Font(family="Arial", size=18, weight="bold")
        
        # Draw the keyboard
        self.draw_keyboard()
        
        # Test highlighting
        self.highlight_key('H')
        
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
        # Draw key rectangle
        rect = self.canvas.create_rectangle(
            x, y, x + self.key_width, y + self.key_height,
            fill="white", outline="black", width=2
        )
        
        # Draw key label
        label = self.canvas.create_text(
            x + self.key_width // 2, y + self.key_height // 2,
            text=key_char, font=self.key_font, fill="black"
        )
        
        # Store references for later highlighting
        self.key_rects[key_char.upper()] = rect
        self.key_labels[key_char.upper()] = label
    
    def highlight_key(self, key_char):
        """Highlight a specific key"""
        key_char = key_char.upper()
        
        # Reset all keys to default
        for key in self.key_rects:
            self.canvas.itemconfig(self.key_rects[key], fill="white", outline="black", width=2)
            self.canvas.itemconfig(self.key_labels[key], fill="black")
        
        # Highlight the specified key
        if key_char in self.key_rects:
            self.canvas.itemconfig(self.key_rects[key_char], fill="yellow", outline="red", width=3)
            self.canvas.itemconfig(self.key_labels[key_char], fill="red")
    
    def flash_key(self, key_char):
        """Flash a key between highlighted and normal state"""
        self.flashing = True
        self.flash_state = False
        self.flash_key_char = key_char.upper()
        self.do_flash()
    
    def do_flash(self):
        """Perform the flash animation"""
        if not hasattr(self, 'flashing') or not self.flashing:
            return
        
        if self.flash_state:
            # Show highlighted
            self.highlight_key(self.flash_key_char)
        else:
            # Show normal
            if self.flash_key_char in self.key_rects:
                self.canvas.itemconfig(self.key_rects[self.flash_key_char], fill="white", outline="black", width=2)
                self.canvas.itemconfig(self.key_labels[self.flash_key_char], fill="black")
        
        self.flash_state = not self.flash_state
        self.root.after(500, self.do_flash)
    
    def stop_flash(self):
        """Stop flashing animation"""
        self.flashing = False

def test_keyboard():
    root = tk.Tk()
    keyboard = KeyboardDisplay(root)
    
    # Test frame for trying different keys - make it more visible
    test_frame = tk.Frame(root, bg='gray', height=50)
    test_frame.pack(pady=10, fill=tk.X, padx=20)
    test_frame.pack_propagate(False)  # Maintain height
    
    label = tk.Label(test_frame, text="Type a letter to highlight:", 
                    font=('Arial', 12), fg='black', bg='gray')
    label.pack(side=tk.LEFT, padx=5, pady=10)
    
    entry = tk.Entry(test_frame, font=('Arial', 14), width=20)
    entry.pack(side=tk.LEFT, padx=5, pady=10)
    
    def on_key_release(event):
        if event.widget.get():
            last_char = event.widget.get()[-1].upper()
            keyboard.highlight_key(last_char)
    
    entry.bind('<KeyRelease>', on_key_release)
    entry.focus()
    
    # Button to test flashing
    flash_button = tk.Button(test_frame, text="Flash 'T'", 
                           command=lambda: keyboard.flash_key('T'),
                           font=('Arial', 10))
    flash_button.pack(side=tk.LEFT, padx=5, pady=10)
    
    # Clear button
    clear_button = tk.Button(test_frame, text="Clear", 
                           command=lambda: entry.delete(0, tk.END),
                           font=('Arial', 10))
    clear_button.pack(side=tk.LEFT, padx=5, pady=10)
    
    root.mainloop()

if __name__ == "__main__":
    test_keyboard()