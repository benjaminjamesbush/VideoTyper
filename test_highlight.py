import tkinter as tk
from tkinter import font

def test_highlighting():
    root = tk.Tk()
    root.title("Text Highlighting Test")
    root.geometry("800x200")
    root.configure(bg='black')
    
    # Create Text widget
    text_widget = tk.Text(root, height=1, wrap=tk.NONE, 
                          font=font.Font(size=48), foreground="white", 
                          background="gray30", relief=tk.RAISED, borderwidth=2)
    text_widget.pack(pady=50, padx=50, fill=tk.X)
    
    # Configure tags
    text_widget.tag_configure("center", justify='center')
    text_widget.tag_configure("highlight", foreground="yellow", background="blue",
                             font=font.Font(size=48, weight="bold"))
    
    # Insert text with first letter highlighted
    text = "hello world"
    
    # Method 1: Insert with tags
    text_widget.insert(tk.END, text[0].upper(), "highlight")  # First letter highlighted
    text_widget.insert(tk.END, text[1:])  # Rest of text
    
    # Apply center to entire line
    text_widget.tag_add("center", "1.0", "end")
    
    # Disable editing
    text_widget.config(state='disabled')
    
    # Create comparison label
    label = tk.Label(root, text="Label (for comparison): " + text, 
                    font=font.Font(size=24), fg="gray", bg="black")
    label.pack()
    
    root.mainloop()

if __name__ == "__main__":
    test_highlighting()