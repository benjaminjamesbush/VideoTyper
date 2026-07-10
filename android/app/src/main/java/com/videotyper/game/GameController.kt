package com.videotyper.game

import android.content.Context
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.SystemClock
import android.util.Log
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
import com.videotyper.data.SubtitleIndex
import com.videotyper.player.SchemeDataSourceFactory
import com.videotyper.ui.CELEBRATION_COUNT
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.random.Random

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
        private const val RESUME_REWIND_MS = 500L        // small run-up before the resume point to smooth the start/stop
        private const val REPLAY_MATCH_WINDOW_MS = 3_000L
        private const val COMPLETED_CUE_MEMORY = 100

        // Anti-mash cooldown: a wrong key silently turns the whole screen flat gray and
        // ignores input; presses while gray extend it, repeats double it up to the max.
        private const val COOLDOWN_BASE_MS = 1_000L
        private const val COOLDOWN_MAX_MS = 8_000L

        // Star / token board: type words to earn stars; 3 stars unlock the scrub bar for a while.
        private const val STARS_NEEDED = 3
        private const val REWARD_MS = 5 * 60 * 1000L      // scrub-bar unlocked window
        private const val IDLE_MS = 5 * 60 * 1000L        // no input this long -> auto-fill to 3 stars
        private const val IDLE_CHECK_MS = 15_000L         // how often the idle monitor checks
        private const val PROMPT_FALLBACK_MS = 9_000L     // jump anyway if the spoken prompt never signals done
        private const val TWINKLE_MS = 820L               // 3rd-star twinkle length; unlock lands after it
        private const val SUBTITLE_INDEX_WAIT_MS = 8_000L         // await the async cue-timeline pull before a jump
        private const val SUBTITLE_INDEX_LOAD_TIMEOUT_MS = 45_000L // give up loading a video's timeline after this
        private const val TAG = "VTStar"

        // Movie volume boost: the movies are mastered quiet vs the game SFX, so we amplify their audio
        // above unity via a LoudnessEnhancer. Adjustable 100%..2000% by swiping the video.
        private const val VOLUME_MIN = 100f
        private const val VOLUME_MAX = 2000f
        private const val VOLUME_DEFAULT = 200
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("videotyper", Context.MODE_PRIVATE)

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
    var isLoading by mutableStateOf(false); private set   // preparing a freshly opened video (extracting its cue timeline)
    var loadProgress by mutableStateOf(0f); private set   // 0..1 extraction progress for the loading bar

    // ---- star / token board ----
    var stars by mutableStateOf(0); private set                 // 0..STARS_NEEDED
    var scrubUnlocked by mutableStateOf(false); private set     // reward: scrub bar available
    var showPracticePrompt by mutableStateOf(false); private set // black "It's time to practice" screen
    var starEarnTick by mutableStateOf(0); private set          // ++ when a star is earned (UI burst)
    var lastStarIndex by mutableStateOf(-1); private set        // slot 0..2 just earned (-1 = auto-filled)
    var scrubUnlockTick by mutableStateOf(0); private set       // ++ when the scrub bar unlocks (UI plays a celebration)
    var celebrationId by mutableStateOf(0); private set         // which unlock celebration to play this time

    // Unlock celebrations are shuffled once per app launch, then cycled through on each unlock.
    private val celebrationOrder = (0 until CELEBRATION_COUNT).toList().shuffled()
    private var celebrationCursor = 0

    // Movie volume boost, adjustable by swiping the video (100%..500%, persisted across sessions).
    var volumePercent by mutableStateOf(prefs.getInt("volume_percent", VOLUME_DEFAULT)); private set
    private var volumeGainPercent = volumePercent.toFloat()
    private var loudnessEnhancer: LoudnessEnhancer? = null

    // ---- engine state ----
    private data class ActiveCue(val text: String, val startMs: Long, val word: String?, val alreadyDone: Boolean)
    private data class Round(val text: String, val word: String, val cueStartMs: Long, val cueEndMs: Long)
    private data class CompletedCue(val text: String, val startMs: Long, val endMs: Long)

    private var activeCue: ActiveCue? = null
    private var round: Round? = null
    private val completedCues = ArrayDeque<CompletedCue>()
    private var hintJob: Job? = null
    private var resumeJob: Job? = null
    private var gameSeekInProgress = false
    private var cooldownJob: Job? = null
    private var cooldownLenMs = 0L
    private var wrongStreak = 0
    private var suspended = false // app not in the foreground (screen off / navigated away)
    private var openToken = 0     // ++ on every open/stop/leave; a stale extraction coroutine checks it before autoplaying

    // Star board / reward timers + the subtitle timeline used to pick a safe practice jump.
    private var rewardJob: Job? = null
    private var idleJob: Job? = null
    private var promptJob: Job? = null
    private var lastActivityMs = 0L
    private var typeableCueStartsMs: List<Long> = emptyList()
    private var subtitleIndexJob: Job? = null

    // ---------------------------------------------------------------- media control

    // Fired once when the just-opened media's tracks resolve, with whether it has a text subtitle
    // track. Used to reject subtitle-less videos at import time (they can't drive the typing game).
    private var subtitleCheck: ((Boolean) -> Unit)? = null

    fun openUri(uri: Uri, onSubtitleCheck: ((Boolean) -> Unit)? = null) {
        val token = ++openToken
        resetGameState()
        resetStarState()
        completedCues.clear()
        statusMessage = null
        subtitleCheck = onSubtitleCheck
        typeableCueStartsMs = emptyList()
        isLoading = true
        loadProgress = 0f
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        hasMedia = true

        // Pull the full typeable-cue timeline BEFORE playback (off the main thread), showing a loading
        // screen meanwhile, so: (a) practice jumps always have the timeline, and (b) the child never
        // starts mid-load. Only once it's ready do we start the movie and the opening scrub reward.
        subtitleIndexJob = scope.launch {
            val idx = withTimeoutOrNull(SUBTITLE_INDEX_LOAD_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    SubtitleIndex.typeableCueStartsMs(appContext, uri.toString()) { p -> loadProgress = p }
                }
            } ?: emptyList()
            if (token != openToken) return@launch  // superseded by another open, or the video was rejected/left
            typeableCueStartsMs = idx
            Log.d(TAG, "subtitle index loaded: ${idx.size} typeable cues")
            isLoading = false
            player.play()
            // A freshly opened video starts with a full board -> the scrub-bar reward, then practice.
            fillStarsAndEnterReward(auto = true)
            startIdleMonitor()
        }
    }

    fun playPause() {
        if (isTyping) return // no skipping a typing round via Play
        if (player.isPlaying) player.pause() else player.play()
    }

    fun stop() {
        openToken++          // invalidate any in-flight open so its extraction won't autoplay
        resetGameState()
        resetStarState()
        player.pause()
        player.seekTo(0)
    }

    /** Leave gameplay for the menu: end any typing round and pause. */
    fun leaveGame() {
        openToken++          // invalidate any in-flight open so its extraction won't autoplay
        resetGameState()
        resetStarState()
        player.pause()
    }

    /**
     * App went to the background (screen off, home, another app). Silence the game immediately —
     * cut any TTS mid-utterance and stop the hint loop — so "type X" prompts don't keep talking
     * out of sight. The round itself is preserved; [onEnterForeground] resumes it. The movie is
     * paused by MainActivity.onStop.
     */
    fun onEnterBackground() {
        suspended = true
        cancelHints()
        audio.stopSpeaking()
    }

    /** App returned to the foreground: resume the hint cadence if a typing round is still open. */
    fun onEnterForeground() {
        if (!suspended) return
        suspended = false
        val current = round
        if (isTyping && current != null && !isCoolingDown && typedCount < current.word.length) {
            scheduleHint(FIRST_HINT_DELAY_MS)
        }
    }

    // ---------------------------------------------------------------- star / token board

    /** Any tap/drag; resets the idle-to-reward countdown. Called from the player's pointer input. */
    fun onUserActivity() { lastActivityMs = SystemClock.uptimeMillis() }

    /** Debug (secret gesture): skip the reward wait — same as the 5-minute timer firing. */
    fun debugForcePractice() = endReward()

    private fun noteActivity() { lastActivityMs = SystemClock.uptimeMillis() }

    private fun fillStarsAndEnterReward(auto: Boolean) {
        stars = STARS_NEEDED
        lastStarIndex = -1 // auto-fill: no per-star burst
        enterReward()
    }

    /** Unlock the scrub bar for REWARD_MS, then bounce back to practice. */
    private fun enterReward() {
        scrubUnlocked = true
        // Pick the next celebration in the shuffled cycle, then bump the tick so the UI plays it.
        celebrationId = celebrationOrder[celebrationCursor % celebrationOrder.size]
        celebrationCursor += 1
        scrubUnlockTick += 1
        audio.playUnlock()
        restartRewardTimer()
    }

    /** (Re)start the 5-minute countdown to the practice prompt. */
    private fun restartRewardTimer() {
        rewardJob?.cancel()
        rewardJob = scope.launch {
            delay(REWARD_MS)
            endReward()
        }
    }

    /** Reward window over: black "time to practice" prompt spoken 3x, then jump into the film. */
    private fun endReward() {
        rewardJob = null
        // If the child is already mid typing round, they're already practicing — don't interrupt with
        // the announcement or a jump. Just silently end the reward (empty the board, relock the bar).
        if (isTyping) {
            scrubUnlocked = false
            stars = 0
            lastStarIndex = -1
            return
        }
        resetGameState()           // end any in-progress round; freeze cues while the prompt shows
        scrubUnlocked = false
        player.pause()
        audio.stopSpeaking()

        var jumped = false
        val doJump = {
            if (!jumped) {
                jumped = true
                promptJob?.cancel(); promptJob = null
                showPracticePrompt = false
                jumpToPracticeStart()
            }
        }
        if (suspended) {
            // Screen off / backgrounded: the reward still times out, but never announce off-screen.
            // Skip the spoken prompt (and the black takeover) and jump straight to a practice line;
            // playback stays paused until the app is foregrounded again (see jumpToPracticeStart).
            doJump()
        } else {
            showPracticePrompt = true
            audio.speakThrice("It's time to practice typing!") { doJump() }
            // Safety net so we always jump even if TTS never signals done.
            promptJob = scope.launch { delay(PROMPT_FALLBACK_MS); doJump() }
        }
    }

    private fun jumpToPracticeStart() {
        stars = 0
        lastStarIndex = -1
        noteActivity()
        scope.launch {
            // The cue timeline loads asynchronously after a video opens; right after opening it may
            // not be ready yet. Wait for it (bounded) so the jump lands on a real subtitle line
            // instead of silently no-op'ing and just playing on from wherever we were.
            if (typeableCueStartsMs.isEmpty()) {
                withTimeoutOrNull(SUBTITLE_INDEX_WAIT_MS) { subtitleIndexJob?.join() }
            }
            val target = pickJumpTarget()
            Log.d(TAG, "practice jump: index=${typeableCueStartsMs.size} target=$target")
            if (target != null) {
                gameSeekInProgress = true
                activeCue = null
                clearSubtitleDisplay()
                player.seekTo(target)
            }
            // Don't resume audio while backgrounded; like any screen-off pause, it plays on return.
            if (!suspended) player.play()
        }
    }

    /**
     * A random typeable cue that still has >= STARS_NEEDED opportunities at or after it (so three
     * stars are always earnable). Null if the timeline isn't available or is too short — the caller
     * then just resumes from where it is.
     */
    private fun pickJumpTarget(): Long? {
        val cues = typeableCueStartsMs
        if (cues.isEmpty()) return null
        if (cues.size < STARS_NEEDED) return cues.first()
        return cues[Random.nextInt(0, cues.size - STARS_NEEDED + 1)]
    }

    private fun startIdleMonitor() {
        idleJob?.cancel()
        lastActivityMs = SystemClock.uptimeMillis()
        idleJob = scope.launch {
            while (true) {
                delay(IDLE_CHECK_MS)
                if (stars < STARS_NEEDED && !suspended &&   // don't auto-reward (a sound) while backgrounded
                    SystemClock.uptimeMillis() - lastActivityMs >= IDLE_MS
                ) {
                    fillStarsAndEnterReward(auto = true)
                    lastActivityMs = SystemClock.uptimeMillis() // don't immediately re-fire
                }
            }
        }
    }

    private fun resetStarState() {
        rewardJob?.cancel(); rewardJob = null
        idleJob?.cancel(); idleJob = null
        promptJob?.cancel(); promptJob = null
        stars = 0
        lastStarIndex = -1
        scrubUnlocked = false
        showPracticePrompt = false
    }

    /**
     * User-initiated scrub, allowed at any time — including mid typing round. Scrubbing away from a
     * word abandons that round (ending the typing state and cutting off its speech) and resumes
     * playback from the new position, so the movie plays on from wherever the child lands.
     */
    fun seekTo(positionMs: Long) {
        noteActivity()
        val wasTyping = isTyping
        if (wasTyping) {
            resetGameState()      // end the round: cancel hints/cooldown/replay, clear the frozen line
            audio.stopSpeaking()
        } else {
            activeCue = null
            clearSubtitleDisplay()
        }
        player.seekTo(positionMs)
        if (wasTyping) player.play()  // the round had force-paused the movie; carry on playing
    }

    fun release() {
        resetGameState()
        resetStarState()
        loudnessEnhancer?.release()
        loudnessEnhancer = null
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

    override fun onAudioSessionIdChanged(audioSessionId: Int) {
        // (Re)attach the loudness booster to the player's current audio session and apply the gain.
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) return
        try {
            loudnessEnhancer = LoudnessEnhancer(audioSessionId).apply {
                setTargetGain(gainMillibels(volumeGainPercent))
                enabled = true
            }
        } catch (_: Exception) {
            // Some devices/sessions can reject the effect; playback just falls back to unity gain.
        }
    }

    /** Nudge the movie volume by [deltaPercent] (from a video swipe); clamps to 100%..500%. */
    fun nudgeVolume(deltaPercent: Float) {
        volumeGainPercent = (volumeGainPercent + deltaPercent).coerceIn(VOLUME_MIN, VOLUME_MAX)
        volumePercent = volumeGainPercent.roundToInt()
        try { loudnessEnhancer?.setTargetGain(gainMillibels(volumeGainPercent)) } catch (_: Exception) {}
    }

    /** Persist the volume once a swipe finishes (kept out of the per-frame drag path). */
    fun commitVolume() {
        prefs.edit().putInt("volume_percent", volumePercent).apply()
    }

    /** Convert a percent gain (100 = unity) to LoudnessEnhancer target gain in millibels. */
    private fun gainMillibels(percent: Float): Int =
        (2000.0 * log10((percent / 100.0).coerceAtLeast(0.01))).roundToInt()

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) {
            // A cue that runs to the very end of the movie still gets its typing round.
            handleCueText("")
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        statusMessage = "Playback error: ${error.errorCodeName}"
        // A load failure can't be a valid import either.
        subtitleCheck?.invoke(false)
        subtitleCheck = null
    }

    override fun onTracksChanged(tracks: Tracks) {
        val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        // One-time import validation: once the tracks resolve (audio/video present), report whether a
        // text subtitle track exists so the caller can reject subtitle-less videos.
        if (tracks.groups.isNotEmpty()) {
            subtitleCheck?.invoke(textGroups.isNotEmpty())
            subtitleCheck = null
        }
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
        round = Round(cue.text, word, cue.startMs, endMs)
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
    /** @return true only when the input was the expected letter (so the keyboard can reward it). */
    fun onTyped(input: String): Boolean {
        val current = round ?: return false
        if (!isTyping) return false
        if (isCoolingDown) {
            startCooldown(escalate = false) // any press while gray extends the gray
            return false
        }
        if (input.length > 1) {
            startCooldown(escalate = true)
            return false
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
            return true
        } else {
            startCooldown(escalate = true)
            return false
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
            if (current != null && isTyping && !suspended && typedCount < current.word.length) {
                audio.speakLetterHint(current.word[typedCount])
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
        rememberCompleted(current.text, current.cueStartMs, current.cueEndMs)
        noteActivity()

        if (stars < STARS_NEEDED) {
            // Earn a star: twinkle (replaces the jingle while stars are still being earned) + burst.
            stars += 1
            lastStarIndex = stars - 1
            starEarnTick += 1
            audio.playTwinkle()
            // Third star: let its twinkle ring out first, THEN unlock (sound + burst after it).
            if (stars >= STARS_NEEDED) scope.launch { delay(TWINKLE_MS); if (stars >= STARS_NEEDED) enterReward() }
        } else {
            // Board already full (reward mode): no star to earn — instead, completing a word resets
            // the 5-minute countdown to practice, so continued typing keeps the scrub time alive.
            audio.playReward()
            if (scrubUnlocked) restartRewardTimer()
        }

        resumeJob = scope.launch {
            delay(REWARD_PAUSE_MS)
            round = null
            clearSubtitleDisplay()
            // Resume with a tiny run-up before the line's end (just to smooth the pause→play seam)
            // rather than replaying the whole line, which made movie progress sleepy. Clamp so a
            // line shorter than the rewind (shouldn't happen) can't seek before its start or negative.
            gameSeekInProgress = true
            val resumeAt = (current.cueEndMs - RESUME_REWIND_MS)
                .coerceAtLeast(current.cueStartMs)
                .coerceAtLeast(0L)
            player.seekTo(resumeAt)
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
                if (!isTyping || suspended || typedCount >= current.word.length) break
                // Vocal prompts are suspended while the anti-mash grayscale is active —
                // a voice reacting to mashing would itself become the reward.
                if (isCoolingDown) {
                    delay(250)
                    continue
                }
                audio.speakLetterHint(current.word[typedCount])
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
            it.text == text &&
                positionMs > it.startMs - REPLAY_MATCH_WINDOW_MS &&
                positionMs < it.endMs + REPLAY_MATCH_WINDOW_MS
        }

    private fun rememberCompleted(text: String, startMs: Long, endMs: Long) {
        completedCues.addLast(CompletedCue(text, startMs, endMs))
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
        isLoading = false
        gameSeekInProgress = false
        clearSubtitleDisplay()
    }
}
