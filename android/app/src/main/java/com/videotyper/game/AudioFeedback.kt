package com.videotyper.game

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.SoundPool
import android.speech.tts.TextToSpeech
import com.videotyper.R
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * All game audio: per-letter voice clips (the letter_sounds WAVs from the desktop app),
 * text-to-speech for words and hints, and the generated reward beeps.
 */
class AudioFeedback(context: Context, private val scope: CoroutineScope) {

    private val soundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .build()

    private val letterRes = mapOf(
        'A' to R.raw.letter_a, 'B' to R.raw.letter_b, 'C' to R.raw.letter_c,
        'D' to R.raw.letter_d, 'E' to R.raw.letter_e, 'F' to R.raw.letter_f,
        'G' to R.raw.letter_g, 'H' to R.raw.letter_h, 'I' to R.raw.letter_i,
        'J' to R.raw.letter_j, 'K' to R.raw.letter_k, 'L' to R.raw.letter_l,
        'M' to R.raw.letter_m, 'N' to R.raw.letter_n, 'O' to R.raw.letter_o,
        'P' to R.raw.letter_p, 'Q' to R.raw.letter_q, 'R' to R.raw.letter_r,
        'S' to R.raw.letter_s, 'T' to R.raw.letter_t, 'U' to R.raw.letter_u,
        'V' to R.raw.letter_v, 'W' to R.raw.letter_w, 'X' to R.raw.letter_x,
        'Y' to R.raw.letter_y, 'Z' to R.raw.letter_z,
    )
    private val letterSounds: Map<Char, Int> =
        letterRes.mapValues { (_, res) -> soundPool.load(context, res, 1) }

    private var ttsReady = false
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                ttsReady = true
            }
        }
    }

    fun playLetter(letter: Char) {
        letterSounds[letter.uppercaseChar()]?.let {
            soundPool.play(it, 1f, 1f, 1, 0, 1f)
        }
    }

    fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "videotyper")
    }

    /** Speak the "type this letter" hint for [letter]. */
    fun speakLetterHint(letter: Char) = speak(letterHintText(letter))

    /** Cut off speech mid-utterance (used when the anti-mash gray state begins). */
    fun stopSpeaking() {
        if (ttsReady) tts?.stop()
    }

    /** Five overlapping beeps at random pitches (400-800 Hz), same recipe as the desktop app. */
    fun playReward() {
        scope.launch(Dispatchers.Default) {
            val sampleRate = 22050
            val beepFrames = sampleRate * 150 / 1000     // 150 ms per beep
            val stepFrames = sampleRate * 100 / 1000     // beeps start every 100 ms and overlap
            val totalFrames = stepFrames * 4 + beepFrames
            val mix = FloatArray(totalFrames)

            repeat(5) { i ->
                val frequency = Random.nextInt(400, 801)
                val start = stepFrames * i
                for (j in 0 until beepFrames) {
                    val envelope = when {
                        j < beepFrames * 0.1 -> j / (beepFrames * 0.1)
                        j > beepFrames * 0.9 -> (beepFrames - j) / (beepFrames * 0.1)
                        else -> 1.0
                    }
                    mix[start + j] += (0.21 * envelope * sin(2 * PI * frequency * j / sampleRate)).toFloat()
                }
            }

            val pcm = ShortArray(totalFrames) {
                (mix[it].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
            }

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            delay(totalFrames * 1000L / sampleRate + 100)
            track.release()
        }
    }

    fun release() {
        soundPool.release()
        tts?.stop()
        tts?.shutdown()
    }

    companion object {
        /** The exact string spoken for a letter hint ("Type X"), with Roman-numeral letters spelled
         *  as homophones so TTS doesn't read them as numbers ("Type I" -> "type one"). */
        fun letterHintText(letter: Char): String = "Type ${spokenLetter(letter)}"

        private fun spokenLetter(c: Char): String = when (c.uppercaseChar()) {
            'I' -> "eye"
            'V' -> "vee"
            'X' -> "ex"
            'L' -> "el"
            'C' -> "see"
            'D' -> "dee"
            'M' -> "em"
            else -> c.uppercaseChar().toString()
        }
    }
}
