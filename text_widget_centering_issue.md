# Text Widget Centering Issue Analysis

## Problem Description
When using tkinter's Text widget with `justify='center'` and applying highlighting tags to individual characters, the highlight background extends to include the centering padding on the left side of the text. This creates an undesirable visual effect where the first highlighted character appears to have a large block of highlight extending to the left margin.

## Root Cause
The Text widget's `justify='center'` implementation treats the padding/whitespace used for centering as part of the character position. When a tag with a background color is applied to the first character, it includes this invisible padding in the highlighted region.

## Failed Approaches
1. **Removing justify from highlight tags** - Caused the text to become left-justified when highlighted
2. **Manual centering with calculated spaces** - Imprecise and font-dependent
3. **Applying center tag after content** - No effect, padding still included in highlight

## Successful Solution: Canvas Widget
The Canvas widget provides precise control over text positioning and highlighting without the padding issues:
- Text can be positioned exactly where needed using coordinates
- Background highlights can be drawn as separate rectangles behind the text
- Each piece of text (highlighted vs non-highlighted) can be positioned independently

## Implementation Plan for video_player.py

### Replace Text Widget with Canvas
1. Replace the `self.subtitle_display` Text widget with a Canvas widget
2. When displaying subtitles:
   - Measure the full text width using a temporary canvas text object
   - Calculate the starting X position to center the text: `start_x = (canvas_width - text_width) // 2`
   - For text with highlighting:
     - Create the highlighted portion at `start_x` with background rectangle
     - Measure the highlighted portion width
     - Create the rest of the text at `start_x + highlighted_width`
   - For text without highlighting:
     - Create single text object centered using `anchor="center"`

### Specific Implementation Steps
1. **Replace Text widget creation**:
   - Change from Text to Canvas widget
   - Maintain similar height and background color

2. **Update `set_subtitle_text` method**:
   - Clear canvas with `delete("all")`
   - Get canvas width (with fallback if not yet rendered)
   - For highlighted text:
     - Create temporary text to measure total width
     - Calculate center position
     - Draw highlighted letter with background rectangle
     - Draw remaining text positioned after highlighted portion
   - Handle the flashing effect by updating rectangle and text colors

3. **Background highlight for next letter**:
   - Measure the exact bounds of the character using `bbox()`
   - Create rectangle with those exact bounds
   - Redraw text on top of rectangle

4. **Flashing implementation**:
   - Store canvas item IDs for the rectangle and text
   - Update their colors using `itemconfig()` during flash cycles