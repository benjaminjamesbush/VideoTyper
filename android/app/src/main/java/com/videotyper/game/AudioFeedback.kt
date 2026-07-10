package com.videotyper.game

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.SoundPool
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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

    /** Speak [text] three times back-to-back, then invoke [onAllDone] (on the game scope). */
    fun speakThrice(text: String, onAllDone: () -> Unit) {
        val t = tts
        if (t == null || !ttsReady) { onAllDone(); return }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onError(utteranceId: String?) { if (utteranceId == LAST) scope.launch { onAllDone() } }
            override fun onDone(utteranceId: String?) { if (utteranceId == LAST) scope.launch { onAllDone() } }
        })
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vt_prompt_1")
        t.speak(text, TextToSpeech.QUEUE_ADD, null, "vt_prompt_2")
        t.speak(text, TextToSpeech.QUEUE_ADD, null, LAST)
    }

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

            playPcm(mix, sampleRate)
        }
    }

    /** The "you earned a star" twinkle: a 5-note ascending run capped with a bright major chord. */
    fun playTwinkle() {
        scope.launch(Dispatchers.Default) {
            val sr = 22050
            val notes = intArrayOf(1047, 1319, 1568, 2093, 2637)   // C6 E6 G6 C7 E7 — rising
            val noteFrames = sr * 80 / 1000
            val stepFrames = sr * 62 / 1000
            val runEnd = stepFrames * (notes.size - 1) + noteFrames
            val chord = intArrayOf(2093, 2637, 3136)               // C7 E7 G7 — capping chord
            val chordFrames = sr * 450 / 1000
            val mix = FloatArray(runEnd + chordFrames)
            notes.forEachIndexed { i, f ->
                val s = stepFrames * i
                for (j in 0 until noteFrames) {
                    val env = if (j < noteFrames * 0.05) j / (noteFrames * 0.05)
                              else 1.0 - (j - noteFrames * 0.05) / (noteFrames * 0.95)
                    mix[s + j] += (0.15 * env * sin(2 * PI * f * j / sr)).toFloat()
                    mix[s + j] += (0.05 * env * sin(2 * PI * f * 2 * j / sr)).toFloat()
                }
            }
            for (j in 0 until chordFrames) {
                val env = if (j < chordFrames * 0.02) j / (chordFrames * 0.02) else 1.0 - j.toDouble() / chordFrames
                for (f in chord) {
                    mix[runEnd + j] += (0.12 * env * sin(2 * PI * f * j / sr)).toFloat()
                    mix[runEnd + j] += (0.04 * env * sin(2 * PI * f * 2 * j / sr)).toFloat()
                }
            }
            playPcm(mix, sr)
        }
    }

    /**
     * Power-up for unlocking the scrub bar: a rising sweep into a big sustained triumphant chord with
     * a sparkling arpeggio tail — a ~2.6s fanfare to match the fireworks celebration.
     */
    fun playUnlock() {
        scope.launch(Dispatchers.Default) {
            val sr = 22050
            val sweepFrames = sr * 400 / 1000
            val chordFrames = sr * 2200 / 1000
            val mix = FloatArray(sweepFrames + chordFrames)
            for (j in 0 until sweepFrames) {
                val t = j.toDouble() / sweepFrames
                val f = 350 + 1600 * t                                   // glide up
                val env = if (t < 0.1) t / 0.1 else 1.0
                mix[j] += (0.16 * env * sin(2 * PI * f * j / sr)).toFloat()
            }
            val chord = intArrayOf(523, 659, 784, 1047, 1319)           // C5 E5 G5 C6 E6 — spread major
            for (j in 0 until chordFrames) {
                val env = if (j < chordFrames * 0.02) j / (chordFrames * 0.02) else 1.0 - j.toDouble() / chordFrames
                for (f in chord) mix[sweepFrames + j] += (0.085 * env * sin(2 * PI * f * j / sr)).toFloat()
            }
            // Sparkle arpeggio riding over the chord.
            val sparkle = intArrayOf(1568, 2093, 2637, 3136, 2637, 2093) // G6 C7 E7 G7 E7 C7
            val noteFrames = sr * 130 / 1000
            sparkle.forEachIndexed { i, f ->
                val s = sweepFrames + sr * 300 / 1000 + i * (sr * 200 / 1000)
                for (j in 0 until noteFrames) {
                    if (s + j >= mix.size) break
                    val env = if (j < noteFrames * 0.05) j / (noteFrames * 0.05) else 1.0 - j.toDouble() / noteFrames
                    mix[s + j] += (0.10 * env * sin(2 * PI * f * j / sr)).toFloat()
                }
            }
            playPcm(mix, sr)
        }
    }

    private suspend fun playPcm(mix: FloatArray, sampleRate: Int) {
        val pcm = ShortArray(mix.size) {
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
        delay(mix.size * 1000L / sampleRate + 100)
        track.release()
    }

    fun release() {
        soundPool.release()
        tts?.stop()
        tts?.shutdown()
    }

    companion object {
        private const val LAST = "vt_prompt_last"

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
