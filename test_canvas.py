import tkinter as tk
from tkinter import font

def test_canvas_approach():
    root = tk.Tk()
    root.title("Canvas Text Test")
    root.geometry("800x200")
    root.configure(bg='black')
    
    # Create Canvas widget
    canvas = tk.Canvas(root, height=100, bg='gray30', highlightthickness=2)
    canvas.pack(pady=50, padx=50, fill=tk.X)
    
    # Font setup
    text_font = font.Font(size=48)
    highlight_font = font.Font(size=48, weight="bold")
    
    # Text to display
    text = "hello world"
    
    # Get canvas dimensions after it's packed
    root.update_idletasks()
    canvas_width = canvas.winfo_width()
    # If canvas width is still 1, use requested width
    if canvas_width <= 1:
        canvas_width = 700  # 800 - 100 for padding
    center_x = canvas_width // 2
    center_y = 50
    
    # Simple approach - just create the text parts side by side
    # First, create the entire text to measure its width
    full_text = text[0].upper() + text[1:]
    
    # Create a temporary text to measure total width
    temp_id = canvas.create_text(0, 0, text=full_text, font=text_font, anchor="w")
    bbox = canvas.bbox(temp_id)
    total_width = bbox[2] - bbox[0]
    canvas.delete(temp_id)
    
    # Calculate starting x position to center the text
    start_x = (canvas_width - total_width) // 2
    
    # Create first letter with background
    first_letter = text[0].upper()
    
    # Create the letter temporarily to measure it
    temp_id = canvas.create_text(start_x, center_y, text=first_letter, 
                                 font=highlight_font, fill="yellow", anchor="w")
    bbox = canvas.bbox(temp_id)
    
    # Create blue background rectangle
    canvas.create_rectangle(bbox[0], bbox[1], bbox[2], bbox[3], 
                           fill="blue", outline="")
    
    # Recreate the letter on top of the rectangle
    canvas.create_text(start_x, center_y, text=first_letter, 
                      font=highlight_font, fill="yellow", anchor="w")
    
    first_letter_width = bbox[2] - bbox[0]
    
    # Create rest of text
    rest_text = text[1:]
    canvas.create_text(start_x + first_letter_width, center_y, text=rest_text, 
                       font=text_font, fill="white", anchor="w")
    
    # Add debug text
    canvas.create_text(center_x, 20, text=f"Canvas width: {canvas_width}, Text width: {total_width}, Start X: {start_x}", 
                       font=font.Font(size=10), fill="cyan", anchor="center")
    
    # Add label for comparison
    label = tk.Label(root, text="Label (for comparison): " + text, 
                    font=font.Font(size=24), fg="gray", bg="black")
    label.pack()
    
    root.mainloop()

if __name__ == "__main__":
    test_canvas_approach()