import pyttsx3
import wave
import numpy as np
import os

def trim_silence(audio_data, threshold=0.01):
    """Remove leading and trailing silence from audio data"""
    # Convert bytes to numpy array
    audio_array = np.frombuffer(audio_data, dtype=np.int16)
    
    # Find indices where audio exceeds threshold
    non_silent = np.abs(audio_array) > threshold * np.max(np.abs(audio_array))
    non_silent_indices = np.where(non_silent)[0]
    
    if len(non_silent_indices) == 0:
        return audio_data
    
    # Get start and end of non-silent audio
    start = non_silent_indices[0]
    end = non_silent_indices[-1] + 1
    
    # Return trimmed audio as bytes
    return audio_array[start:end].tobytes()

def generate_letter_sounds():
    """Generate WAV files for each letter A-Z"""
    print("Starting letter sound generation...", flush=True)
    
    # Create sounds directory if it doesn't exist
    os.makedirs('letter_sounds', exist_ok=True)
    print("Created/verified letter_sounds directory", flush=True)
    
    # Initialize TTS engine
    print("Initializing TTS engine...", flush=True)
    try:
        engine = pyttsx3.init()
        engine.setProperty('rate', 150)
        engine.setProperty('volume', 1.0)
        print("TTS engine initialized successfully", flush=True)
    except Exception as e:
        print(f"ERROR: Failed to initialize TTS engine: {e}", flush=True)
        return
    
    # Generate sound for each letter
    letters = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'
    for i, letter in enumerate(letters):
        print(f"\n[{i+1}/26] Generating sound for letter: {letter}", flush=True)
        
        # Save to temporary file first
        temp_file = f'letter_sounds/temp_{letter}.wav'
        final_file = f'letter_sounds/{letter}.wav'
        
        # Generate the audio
        try:
            print(f"  - Generating audio...", flush=True)
            engine.save_to_file(letter, temp_file)
            engine.startLoop(False)
            engine.iterate()
            engine.endLoop()
            print(f"  - Audio generated, saved to temp file", flush=True)
        except Exception as e:
            print(f"  ERROR generating audio: {e}", flush=True)
            continue
        
        # Read the generated file
        try:
            print(f"  - Reading temp file...", flush=True)
            with wave.open(temp_file, 'rb') as wav_in:
                params = wav_in.getparams()
                audio_data = wav_in.readframes(params.nframes)
            print(f"  - Read {len(audio_data)} bytes of audio data", flush=True)
        except Exception as e:
            print(f"  ERROR reading temp file: {e}", flush=True)
            continue
        
        # Trim silence
        try:
            print(f"  - Trimming silence...", flush=True)
            trimmed_data = trim_silence(audio_data)
            print(f"  - Trimmed to {len(trimmed_data)} bytes", flush=True)
        except Exception as e:
            print(f"  ERROR trimming silence: {e}", flush=True)
            trimmed_data = audio_data
        
        # Write trimmed audio to final file
        try:
            print(f"  - Writing final file...", flush=True)
            with wave.open(final_file, 'wb') as wav_out:
                wav_out.setparams(params)
                wav_out.setnframes(len(trimmed_data) // params.sampwidth)
                wav_out.writeframes(trimmed_data)
            print(f"  - Saved: {final_file}", flush=True)
        except Exception as e:
            print(f"  ERROR writing final file: {e}", flush=True)
            continue
        
        # Remove temporary file
        try:
            os.remove(temp_file)
            print(f"  - Removed temp file", flush=True)
        except:
            pass
    
    print("\n\nAll letter sounds generated successfully!", flush=True)

if __name__ == "__main__":
    generate_letter_sounds()