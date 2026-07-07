package com.videotyper.game

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.videotyper.player.SchemeDataSourceFactory
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The VideoTyper game engine, ported from the desktop video_player.py.
 *
 * Flow: while a subtitle cue is on screen its chosen word is highlighted. When the cue ends,
 * playback pauses, we seek to the middle of the cue interval (so the frame matches the line),
 * the word is spoken via TTS and the child types it letter by letter on the system keyboard
 * (wrong letters are ignored, correct ones play the letter sound). On completion a reward
 * jingle plays, the whole line is replayed, and the movie continues to the next subtitle.
 *
 * The desktop app pre-extracted the entire SRT with ffmpeg; here we drive everything from
 * ExoPlayer's live subtitle cue callbacks, which works for local files and network streams alike.
 */
@UnstableApi
class GameController(context: Context, private val scope: CoroutineScope) : Player.Listener {

    companion object {
        private const val FIRST_HINT_DELAY_MS = 2_000L   // desktop: hint 2 s after pause/keystroke
        private const val REPEAT_HINT_DELAY_MS = 5_000L  // then every 5 s while stuck
        private const val REWARD_PAUSE_MS = 1_200L       // let the reward jingle play out
        private const val REPLAY_MATCH_WINDOW_MS = 3_000L
        private const val COMPLETED_CUE_MEMORY = 100

        // Anti-mash cooldown: a wrong key silently turns the whole screen flat gray and
        // ignores input; presses while gray extend it, repeats double it up to the max.
        private const val COOLDOWN_BASE_MS = 1_000L
        private const val COOLDOWN_MAX_MS = 8_000L
    }

    val audio = AudioFeedback(context, scope)

    val player: ExoPlayer = ExoPlayer.Builder(
        context,
        NextRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
    )
        .setTrackSelector(
            DefaultTrackSelector(context).also {
                it.parameters = it.buildUponParameters()
                    .setSelectUndeterminedTextLanguage(true)
                    .setPreferredTextLanguages("en", "eng")
                    .build()
            }
        )
        .setMediaSourceFactory(DefaultMediaSourceFactory(SchemeDataSourceFactory(context)))
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        .build()
        .also { it.addListener(this) }

    // ---- UI-observable state ----
    var subtitleText by mutableStateOf(""); private set
    var highlightWord by mutableStateOf<String?>(null); private set
    var typedCount by mutableStateOf(0); private set
    var isTyping by mutableStateOf(false); private set
    var isPlaying by mutableStateOf(false); private set
    var statusMessage by mutableStateOf<String?>(null); private set
    var hasMedia by mutableStateOf(false); private set
    var isCoolingDown by mutableStateOf(false); private set

    // ---- engine state ----
    private data class ActiveCue(val text: String, val startMs: Long, val word: String?, val alreadyDone: Boolean)
    private data class Round(val text: String, val word: String, val cueStartMs: Long)
    private data class CompletedCue(val text: String, val startMs: Long)

    private var activeCue: ActiveCue? = null
    private var round: Round? = null
    private val completedCues = ArrayDeque<CompletedCue>()
    private var hintJob: Job? = null
    private var resumeJob: Job? = null
    private var gameSeekInProgress = false
    private var cooldownJob: Job? = null
    private var cooldownLenMs = 0L
    private var wrongStreak = 0

    // ---------------------------------------------------------------- media control

    fun openUri(uri: Uri) {
        resetGameState()
        completedCues.clear()
        statusMessage = null
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.play()
        hasMedia = true
    }

    fun playPause() {
        if (isTyping) return // desktop blocks play/pause during a typing round
        if (player.isPlaying) player.pause() else player.play()
    }

    fun stop() {
        resetGameState()
        player.pause()
        player.seekTo(0)
    }

    /** User-initiated scrub. Blocked during typing rounds (the slider is disabled then too). */
    fun seekTo(positionMs: Long) {
        if (isTyping) return
        activeCue = null
        clearSubtitleDisplay()
        player.seekTo(positionMs)
    }

    fun release() {
        resetGameState()
        player.release()
        audio.release()
    }

    // ---------------------------------------------------------------- player events

    override fun onCues(cueGroup: CueGroup) {
        val text = cueGroup.cues
            .joinToString(" ") { it.text?.toString() ?: "" }
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
        handleCueText(text)
    }

    override fun onIsPlayingChanged(playing: Boolean) {
        isPlaying = playing
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) {
            // A cue that runs to the very end of the movie still gets its typing round.
            handleCueText("")
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        statusMessage = "Playback error: ${error.errorCodeName}"
    }

    override fun onTracksChanged(tracks: Tracks) {
        val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        if (textGroups.isEmpty()) {
            if (hasMedia && tracks.groups.isNotEmpty()) {
                statusMessage = "No subtitle track in this video — typing game disabled"
            }
            return
        }
        statusMessage = null
        // Preferred-language selection missed (e.g. track tagged with another language):
        // force-select the first text track so the game always has cues.
        if (textGroups.none { it.isSelected }) {
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setOverrideForType(TrackSelectionOverride(textGroups.first().mediaTrackGroup, 0))
                .build()
        }
    }

    // ---------------------------------------------------------------- game logic

    private fun handleCueText(newText: String) {
        if (round != null) return // during a typing round the display is frozen on the round's line

        val previous = activeCue
        if (newText == previous?.text) return

        if (newText.isEmpty()) {
            activeCue = null
            clearSubtitleDisplay()
        } else {
            val positionMs = player.currentPosition
            val alreadyDone = wasRecentlyCompleted(newText, positionMs)
            val word = if (!alreadyDone && WordSelector.hasTypeableWords(newText)) {
                WordSelector.selectWord(newText)
            } else null
            activeCue = ActiveCue(newText, positionMs, word, alreadyDone)
            subtitleText = newText
            highlightWord = word
            typedCount = 0
        }

        // The cue that just ended is what the child types.
        if (previous?.word != null && !previous.alreadyDone) {
            startRound(previous, endMs = player.currentPosition)
        }
    }

    private fun startRound(cue: ActiveCue, endMs: Long) {
        val word = cue.word ?: return
        player.pause()
        round = Round(cue.text, word, cue.startMs)
        activeCue = null

        // Show the frame from the middle of the line while the child types.
        gameSeekInProgress = true
        player.seekTo((cue.startMs + endMs) / 2)

        subtitleText = cue.text
        highlightWord = word
        typedCount = 0
        isTyping = true
        cancelCooldown() // each word starts with a clean anti-mash slate

        audio.speak(word)
        scheduleHint(FIRST_HINT_DELAY_MS)
    }

    /**
     * Feed of characters from the on-screen keyboard. A wrong key silently triggers the
     * anti-mash cooldown (whole screen gray, input ignored); multi-character insertions
     * (gesture typing, suggestion taps) are treated as mashing outright. Deliberately no
     * sound or animation on the failure path — any reaction would reward mashing.
     */
    fun onTyped(input: String) {
        val current = round ?: return
        if (!isTyping) return
        if (isCoolingDown) {
            startCooldown(escalate = false) // any press while gray extends the gray
            return
        }
        if (input.length > 1) {
            startCooldown(escalate = true)
            return
        }
        val typed = input.single()
        val expected = current.word[typedCount].uppercaseChar()
        if (typed.uppercaseChar() == expected) {
            wrongStreak = 0 // honest typing de-escalates future cooldowns
            audio.playLetter(expected)
            typedCount += 1
            if (typedCount >= current.word.length) {
                completeRound(current)
            } else {
                scheduleHint(FIRST_HINT_DELAY_MS) // correct letter resets the hint timer
            }
        } else {
            startCooldown(escalate = true)
        }
    }

    private fun startCooldown(escalate: Boolean) {
        if (escalate) {
            wrongStreak += 1
            cooldownLenMs = (COOLDOWN_BASE_MS shl (wrongStreak - 1).coerceAtMost(3))
                .coerceAtMost(COOLDOWN_MAX_MS)
        }
        if (!isCoolingDown) {
            // Gray = fully unresponsive: cut speech off mid-utterance too
            audio.stopSpeaking()
        }
        isCoolingDown = true
        cooldownJob?.cancel()
        cooldownJob = scope.launch {
            delay(cooldownLenMs)
            isCoolingDown = false
            // Herald the return of interactivity with an immediate vocal prompt,
            // then fall back to the regular hint cadence.
            val current = round
            if (current != null && isTyping && typedCount < current.word.length) {
                audio.speak("Type ${current.word[typedCount].uppercaseChar()}")
                scheduleHint(REPEAT_HINT_DELAY_MS)
            }
        }
    }

    private fun cancelCooldown() {
        cooldownJob?.cancel()
        cooldownJob = null
        isCoolingDown = false
        cooldownLenMs = 0
        wrongStreak = 0
    }

    private fun completeRound(current: Round) {
        cancelHints()
        isTyping = false
        rememberCompleted(current.text, current.cueStartMs)
        audio.playReward()

        resumeJob = scope.launch {
            delay(REWARD_PAUSE_MS)
            round = null
            clearSubtitleDisplay()
            // Replay the whole line as a reward, then playback simply continues.
            gameSeekInProgress = true
            player.seekTo(current.cueStartMs)
            player.play()
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            if (gameSeekInProgress) {
                gameSeekInProgress = false
            } else if (round == null) {
                // Seek from outside the game (e.g. accessibility): forget the pending cue.
                activeCue = null
            }
        }
    }

    private fun scheduleHint(initialDelayMs: Long) {
        cancelHints()
        hintJob = scope.launch {
            delay(initialDelayMs)
            while (true) {
                val current = round ?: break
                if (!isTyping || typedCount >= current.word.length) break
                // Vocal prompts are suspended while the anti-mash grayscale is active —
                // a voice reacting to mashing would itself become the reward.
                if (isCoolingDown) {
                    delay(250)
                    continue
                }
                audio.speak("Type ${current.word[typedCount].uppercaseChar()}")
                delay(REPEAT_HINT_DELAY_MS)
            }
        }
    }

    private fun cancelHints() {
        hintJob?.cancel()
        hintJob = null
    }

    private fun wasRecentlyCompleted(text: String, positionMs: Long): Boolean =
        completedCues.any {
            it.text == text && kotlin.math.abs(it.startMs - positionMs) < REPLAY_MATCH_WINDOW_MS
        }

    private fun rememberCompleted(text: String, startMs: Long) {
        completedCues.addLast(CompletedCue(text, startMs))
        while (completedCues.size > COMPLETED_CUE_MEMORY) completedCues.removeFirst()
    }

    private fun clearSubtitleDisplay() {
        subtitleText = ""
        highlightWord = null
        typedCount = 0
    }

    private fun resetGameState() {
        cancelHints()
        cancelCooldown()
        resumeJob?.cancel()
        resumeJob = null
        round = null
        activeCue = null
        isTyping = false
        gameSeekInProgress = false
        clearSubtitleDisplay()
    }
}
