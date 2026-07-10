package com.videotyper.ui

import android.app.Activity
import android.graphics.ColorMatrixColorFilter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.videotyper.R
import com.videotyper.game.GameController
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private val HighlightYellow = Color(0xFFFFEB3B)
private val FlashRed = Color(0xFFE53935)
private val StarGold = Color(0xFFFFD740)
private val StarDim = Color(0x55FFFFFF)
private val TrackActive = Color(0xFFD0BCFF)
private val TrackInactive = Color(0x59FFFFFF)

// Vertical space kept for the subtitle strip + scrub bar + controls (everything between the video
// and the keyboard), so the video can never grow large enough to push them off-screen on near-square
// foldable displays. The keyboard's own height is reserved separately (it scales with width).
private val BottomUiReserve = 180.dp

@UnstableApi
@Composable
fun PlayerScreen(
    controller: GameController,
    onMenuClick: () -> Unit,
) {
    // Layout, top to bottom: video (full width, 16:9, but capped), subtitle strip, scrub bar,
    // controls, then the app's own on-screen keyboard pinned at the very bottom. The keyboard is a
    // permanent fixture (typing is ~90% of the app) with a fixed height that scales with width, so
    // nothing reflows when a typing round starts.
    //
    // The video height is capped so the bottom UI + keyboard always get their space first: on a tall
    // phone a full-width 16:9 video fits with room to spare; on a near-square foldable it's shortened
    // (letterboxed) rather than hiding the controls.
    //
    // Anti-mash cooldown: the whole UI (video + keyboard included) drains to grayscale — still fully
    // visible, just colorless — with no sound, message, or animation while gray.

    // Keep the screen awake only while the movie is actually playing, so the phone can sleep normally
    // when paused/idle (touch input keeps it awake during a typing round). Cleared on leaving the player.
    val activity = LocalContext.current as? Activity
    LaunchedEffect(controller.isPlaying, activity) {
        val w = activity?.window ?: return@LaunchedEffect
        if (controller.isPlaying) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    DisposableEffect(activity) {
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .grayscale(controller.isCoolingDown)
            // Any tap/drag anywhere counts as activity (resets the idle-to-reward timer). Observed on
            // the Initial pass without consuming, so it never steals input from the keyboard/slider.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    controller.onUserActivity()
                }
            }
    ) {
        val keyboardHeight = maxWidth * KEYBOARD_HEIGHT_FRACTION
        val videoHeight = minOf(maxWidth * 9f / 16f, maxHeight - BottomUiReserve - keyboardHeight)
            .coerceAtLeast(120.dp)
        // Secret debug gesture: 5 taps on the video within 3 s empties the star board (skips the
        // 5-minute reward wait), so the practice transition can be tested without waiting.
        val videoTaps = remember { ArrayList<Long>() }
        // Where the star/scrub band sits (root coords), so the unlock celebration can anchor to the
        // bar yet draw over the whole screen (rising off the bar into the video above).
        var bandBounds by remember { mutableStateOf<Rect?>(null) }
        Column(Modifier.fillMaxSize()) {
            VideoSurface(
                controller,
                Modifier.fillMaxWidth().height(videoHeight)
                    .pointerInput(Unit) {
                        detectTapGestures {
                            val now = System.currentTimeMillis()
                            videoTaps.add(now)
                            videoTaps.removeAll { now - it > 3000 }
                            if (videoTaps.size >= 5) { videoTaps.clear(); controller.debugForcePractice() }
                        }
                    }
                    // Swipe up/down over the video to raise/lower the movie volume. One full-height
                    // swipe = 100% (so the 100%..2000% range takes several deliberate swipes).
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = { controller.commitVolume() },
                            onVerticalDrag = { _, dragAmount ->
                                if (size.height > 0) controller.nudgeVolume(-dragAmount / size.height * 100f)
                            }
                        )
                    }
            )
            SubtitleStrip(controller)
            controller.statusMessage?.let {
                Text(
                    it,
                    color = FlashRed,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            StarScrubBand(controller, onBandBounds = { bandBounds = it })
            ControlsRow(controller, onMenuClick)
            CustomKeyboard(controller, Modifier.fillMaxWidth().height(keyboardHeight))
        }

        // Scrub-bar unlock celebration: a full-screen overlay anchored to the band, cycling through
        // the shuffled celebrations (one per unlock). Draws nothing until the first unlock and after
        // each one finishes. Touches pass through (a Canvas doesn't consume them), so typing is fine.
        bandBounds?.let { bb ->
            CelebrationHost(
                id = controller.celebrationId,
                play = controller.scrubUnlockTick,
                band = Band(bb.left, bb.right, bb.center.y, bb.height * 0.13f),
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Transient volume readout while swiping the video to adjust the movie volume.
        var showVolume by remember { mutableStateOf(false) }
        var prevVol by remember { mutableIntStateOf(controller.volumePercent) }
        LaunchedEffect(controller.volumePercent) {
            if (controller.volumePercent != prevVol) {
                prevVol = controller.volumePercent
                showVolume = true
                delay(900)
                showVolume = false
            }
        }
        if (showVolume) {
            Box(Modifier.fillMaxWidth().padding(top = 100.dp), contentAlignment = Alignment.TopCenter) {
                Row(
                    modifier = Modifier.clip(CircleShape).background(Color(0xCC000000))
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("🔊", fontSize = 22.sp)
                    Text("${controller.volumePercent}%", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Loading a freshly opened video: black takeover with a spinner until its cue timeline is
        // extracted, so playback never starts mid-load (and practice jumps always have the timeline).
        if (controller.isLoading) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    // Swallow all touches so nothing underneath (video debug gesture, controls) reacts
                    // while loading — otherwise a stray tap could start practice before playback begins.
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) { awaitPointerEvent().changes.forEach { it.consume() } }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = HighlightYellow)
                    Spacer(Modifier.height(20.dp))
                    Text("Loading…", color = Color.White, fontSize = 18.sp)
                }
            }
        }

        // "It's time to practice typing!" — black takeover while the prompt is spoken (video hidden).
        if (controller.showPracticePrompt) {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Text(
                    "It's time to practice typing!",
                    color = HighlightYellow,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                )
            }
        }
    }
}

/** Draws the content through a saturation-0 color filter when enabled. */
private fun Modifier.grayscale(enabled: Boolean): Modifier =
    if (!enabled) this
    else drawWithCache {
        val paint = Paint().apply {
            colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
        }
        onDrawWithContent {
            drawIntoCanvas { canvas ->
                canvas.saveLayer(Rect(0f, 0f, size.width, size.height), paint)
                drawContent()
                canvas.restore()
            }
        }
    }

@UnstableApi
@Composable
private fun VideoSurface(controller: GameController, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            // Inflated from XML because surface_type (TextureView) is inflation-only;
            // TextureView is what lets the grayscale layer paint reach the video pixels.
            (LayoutInflater.from(ctx).inflate(R.layout.player_view, null) as PlayerView).apply {
                player = controller.player
                // The game draws its own subtitles in the strip below the video.
                subtitleView?.visibility = ViewGroup.GONE
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        update = { playerView ->
            if (controller.isCoolingDown) {
                if (playerView.layerType != View.LAYER_TYPE_HARDWARE) {
                    val grayPaint = android.graphics.Paint().apply {
                        colorFilter = ColorMatrixColorFilter(
                            android.graphics.ColorMatrix().apply { setSaturation(0f) }
                        )
                    }
                    playerView.setLayerType(View.LAYER_TYPE_HARDWARE, grayPaint)
                }
            } else if (playerView.layerType != View.LAYER_TYPE_NONE) {
                playerView.setLayerType(View.LAYER_TYPE_NONE, null)
            }
        }
    )
}

@UnstableApi
@Composable
private fun SubtitleStrip(controller: GameController) {
    // Flash the next letter between yellow-on-red and red-on-yellow every 500 ms while typing.
    // The flash holds still during the anti-mash cooldown: a blinking letter is an animation,
    // and any animation during the gray state would reward mashing.
    val flashOn by produceState(false, controller.isTyping) {
        value = false
        while (controller.isTyping) {
            delay(500)
            if (!controller.isCoolingDown) value = !value
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 80.dp)
            .background(Color.Black)
            .padding(horizontal = 12.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = buildSubtitleString(
                text = controller.subtitleText,
                word = controller.highlightWord,
                typedCount = if (controller.isTyping) controller.typedCount else null,
                flashOn = flashOn
            ),
            color = Color.White,
            fontSize = 30.sp,
            lineHeight = 38.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Renders the subtitle line the way the desktop canvas did: the target word uppercase in bold
 * yellow, letters already typed in yellow, and the next letter to type on a flashing red/yellow
 * block. [typedCount] == null means "not in a typing round" (whole word highlighted).
 */
private fun buildSubtitleString(
    text: String,
    word: String?,
    typedCount: Int?,
    flashOn: Boolean,
): AnnotatedString = buildAnnotatedString {
    if (text.isEmpty()) return@buildAnnotatedString
    val match = word?.let {
        Regex("\\b${Regex.escape(it)}\\b", RegexOption.IGNORE_CASE).find(text)
    }
    if (word == null || match == null) {
        append(text)
        return@buildAnnotatedString
    }

    append(text.substring(0, match.range.first))
    val wordUpper = word.uppercase()
    val bold = SpanStyle(fontWeight = FontWeight.Bold, color = HighlightYellow)

    if (typedCount == null || typedCount >= wordUpper.length) {
        withStyle(bold) { append(wordUpper) }
    } else {
        withStyle(bold) { append(wordUpper.substring(0, typedCount)) }
        withStyle(
            SpanStyle(
                fontWeight = FontWeight.Bold,
                color = if (flashOn) HighlightYellow else FlashRed,
                background = if (flashOn) FlashRed else HighlightYellow,
            )
        ) { append(wordUpper[typedCount]) }
        withStyle(bold) { append(wordUpper.substring(typedCount + 1)) }
    }

    append(text.substring(match.range.last + 1))
}

private fun AnnotatedString.Builder.withStyle(style: SpanStyle, block: AnnotatedString.Builder.() -> Unit) {
    val start = length
    block()
    addStyle(style, start, length)
}

/**
 * The scrub bar's band, which is also the star board. The 3 star slots always sit here; the scrub
 * bar only appears while it's unlocked (reward) and runs straight THROUGH the stars — so the stars
 * visibly are the scrub bar's lock. A star earned pops a cosmic burst behind its slot; unlocking the
 * bar pops a screen-width burst around it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
private fun StarScrubBand(controller: GameController, onBandBounds: (Rect) -> Unit) {
    var durationMs by remember { mutableLongStateOf(0L) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var dragValue by remember { mutableFloatStateOf(-1f) }
    LaunchedEffect(Unit) {
        while (true) {
            val d = controller.player.duration
            durationMs = if (d > 0) d else 0L
            positionMs = controller.player.currentPosition.coerceAtLeast(0L)
            delay(200)
        }
    }

    val bursts = rememberCosmicBursts()
    var bandW by remember { mutableFloatStateOf(0f) }
    var bandH by remember { mutableFloatStateOf(0f) }

    // Star-earned burst behind the just-filled slot (slots land at 1/4, 2/4, 3/4 of the width).
    var prevStarTick by remember { mutableIntStateOf(controller.starEarnTick) }
    LaunchedEffect(controller.starEarnTick) {
        if (controller.starEarnTick != prevStarTick) {
            prevStarTick = controller.starEarnTick
            val i = controller.lastStarIndex
            if (bandW > 0f && i in 0..2) bursts.fire((i + 1) / 4f * bandW, bandH / 2f, bandW * 0.05f)
        }
    }
    // The scrub-bar unlock celebration is rendered as a full-screen overlay by the caller (so it can
    // rise off the bar into the video above without being clipped to this 56dp band).

    val sliderFrac = if (dragValue >= 0f) dragValue
        else if (durationMs > 0) positionMs.toFloat() / durationMs else 0f

    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .onSizeChanged { bandW = it.width.toFloat(); bandH = it.height.toFloat() }
            .onGloballyPositioned { onBandBounds(it.boundsInRoot()) }
    ) {
        // Scrub bar (drawn first = behind the stars) — only while unlocked. Custom track is ~half the
        // default thickness so the stars read clearly; the default thumb (playhead) is kept as-is.
        if (controller.scrubUnlocked) {
            Slider(
                value = sliderFrac,
                onValueChange = { dragValue = it; controller.onUserActivity() },
                onValueChangeFinished = {
                    if (dragValue >= 0f && durationMs > 0) controller.seekTo((dragValue * durationMs).toLong())
                    dragValue = -1f
                },
                enabled = controller.hasMedia,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).align(Alignment.Center),
                track = { _ ->
                    Box(Modifier.fillMaxWidth().height(7.dp)) {
                        Box(Modifier.fillMaxWidth().height(7.dp).clip(CircleShape).background(TrackInactive))
                        if (sliderFrac > 0f) {
                            Box(
                                Modifier.fillMaxWidth(sliderFrac.coerceIn(0.002f, 1f)).height(7.dp)
                                    .clip(CircleShape).background(TrackActive)
                            )
                        }
                    }
                },
            )
        }
        // Bursts behind the stars, over the bar.
        CosmicCanvas(bursts, Modifier.fillMaxSize())
        // Star slots drawn ON TOP, centered exactly on the band's mid-line (= the scrub track line),
        // so the bar passes through each star's center. Radius is bounded so nothing clips.
        Canvas(Modifier.fillMaxSize()) {
            val cy = size.height / 2f
            val outerR = size.height * 0.44f   // top point at 0.06*h, bottom points at ~0.36*h -> fits
            for (i in 0..2) {
                val cx = (i + 1) / 4f * size.width
                val path = starPath(cx, cy, outerR)
                if (i < controller.stars) drawPath(path, StarGold)
                else drawPath(path, StarDim, style = Stroke(width = outerR * 0.16f))
            }
        }
    }
}

/** A 5-point star path centered exactly at (cx, cy) with the given outer radius (top point up). */
private fun starPath(cx: Float, cy: Float, outerR: Float): Path {
    val innerR = outerR * 0.42f
    val p = Path()
    for (k in 0 until 10) {
        val r = if (k % 2 == 0) outerR else innerR
        val a = Math.toRadians(-90.0 + k * 36.0)
        val x = cx + (r * cos(a)).toFloat()
        val y = cy + (r * sin(a)).toFloat()
        if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    return p
}

@UnstableApi
@Composable
private fun ControlsRow(
    controller: GameController,
    onMenuClick: () -> Unit,
) {
    // A gap below the buttons so they don't crowd the keyboard. (Button height is Material's ~48dp
    // minimum; it only looked short on the Fold when the over-budget layout compressed it, which
    // the video-height cap now prevents.)
    val buttonPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Menu and Stop are always available (Menu leaves to the chooser, Stop resets). Play is
        // disabled during a typing round — there is deliberately no way to skip the word.
        OutlinedButton(
            onClick = onMenuClick,
            contentPadding = buttonPadding,
            modifier = Modifier.weight(1f)
        ) { Text("Menu") }
        Button(
            onClick = { controller.playPause() },
            enabled = !controller.isTyping,
            contentPadding = buttonPadding,
            modifier = Modifier.weight(1f)
        ) { Text(if (controller.isPlaying) "Pause" else "Play") }
        OutlinedButton(
            onClick = { controller.stop() },
            contentPadding = buttonPadding,
            modifier = Modifier.weight(1f)
        ) { Text("Stop") }
    }
}
