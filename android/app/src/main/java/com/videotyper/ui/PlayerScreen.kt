package com.videotyper.ui

import android.graphics.ColorMatrixColorFilter
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.videotyper.R
import com.videotyper.game.GameController
import kotlinx.coroutines.delay

private val HighlightYellow = Color(0xFFFFEB3B)
private val FlashRed = Color(0xFFE53935)

// Vertical space kept for the subtitle strip + transport controls, so the video can never grow
// large enough to push them off-screen (the failure mode on near-square foldable displays). Sized
// to what that bottom UI actually needs (subtitle min ~110dp + seek/controls ~110dp), measured so
// the cap does NOT bind on a normal phone — there a full-width 16:9 video keeps its full size.
private val BottomUiReserve = 220.dp

@UnstableApi
@Composable
fun PlayerScreen(
    controller: GameController,
    onMenuClick: () -> Unit,
) {
    // The soft keyboard is a permanent fixture: typing is ~90% of the app, so it stays up for
    // the whole session rather than playing peek-a-boo per round. imePadding reserves space for
    // it, so everything lives in the band above the keyboard.
    //
    // The video is pinned at the top, full width, at a 16:9 height — but capped so the subtitle
    // and controls are always reserved space first (BottomUiReserve). On a tall phone the cap
    // never binds (16:9 fits easily); on a near-square foldable a full-width 16:9 video would
    // otherwise eat the whole height, so it's shortened (letterboxed) instead of hiding the
    // controls. Because the keyboard never hides, this band is a constant size and nothing
    // reflows during play.
    //
    // Anti-mash cooldown: the whole UI (video included) drains to grayscale — still fully
    // visible, just colorless — with no sound, message, or animation while gray.
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .imePadding()
            .grayscale(controller.isCoolingDown)
    ) {
        val videoHeight = minOf(maxWidth * 9f / 16f, maxHeight - BottomUiReserve)
            .coerceAtLeast(120.dp)
        Column(Modifier.fillMaxSize()) {
            VideoSurface(controller, Modifier.fillMaxWidth().height(videoHeight))
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
            SeekBar(controller)
            ControlsRow(controller, onMenuClick)
            HiddenTypingField(controller)
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
            .defaultMinSize(minHeight = 110.dp)
            .background(Color.Black)
            .padding(horizontal = 12.dp, vertical = 8.dp),
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

@UnstableApi
@Composable
private fun SeekBar(controller: GameController) {
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

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Slider(
            value = if (dragValue >= 0f) dragValue
            else if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
            onValueChange = { if (!controller.isTyping) dragValue = it },
            onValueChangeFinished = {
                if (dragValue >= 0f && durationMs > 0 && !controller.isTyping) {
                    controller.seekTo((dragValue * durationMs).toLong())
                }
                dragValue = -1f
            },
            enabled = controller.hasMedia && !controller.isTyping,
        )
        Text(
            text = "${formatTime(positionMs)} / ${formatTime(durationMs)}",
            color = Color.Gray,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun formatTime(ms: Long): String = DateUtils.formatElapsedTime(ms / 1000)

@UnstableApi
@Composable
private fun ControlsRow(
    controller: GameController,
    onMenuClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Menu and Stop are always available (Menu leaves to the chooser, Stop resets). Play is
        // disabled during a typing round — there is deliberately no way to skip the word.
        OutlinedButton(
            onClick = onMenuClick,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            modifier = Modifier.weight(1f)
        ) { Text("Menu") }
        Button(
            onClick = { controller.playPause() },
            enabled = !controller.isTyping,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            modifier = Modifier.weight(1f)
        ) { Text(if (controller.isPlaying) "Pause" else "Play") }
        OutlinedButton(
            onClick = { controller.stop() },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            modifier = Modifier.weight(1f)
        ) { Text("Stop") }
    }
}

/**
 * Invisible 1dp text field that keeps the soft keyboard up for the whole session and captures the
 * child's key presses. Typing is the primary interaction, so the keyboard is a permanent fixture:
 * this field holds focus continuously and the IME is re-shown if the system ever dismisses it
 * (back gesture, focus blip). The menu is a separate screen that doesn't compose this field, so
 * the keyboard is naturally absent there.
 *
 * Keystrokes are captured as a DELTA rather than by resetting the field to "" each change. A normal
 * Text field lets the keyboard keep a committed text state; the old reset-to-empty approach raced
 * that state under fast mashing — the IME, still believing the field held the previous letters,
 * sent cumulative multi-character values ("EL", "ELM", ...) which the game read as gesture-typing
 * and turned into an endlessly re-triggering cooldown (the "stuck after gray" bug), with phantom
 * values arriving even after the child stopped. Instead the field accumulates the round's
 * keystrokes and the game is fed only the characters appended since the last change (the suffix
 * past the common prefix). Because we always store back exactly what the IME sent, the two never
 * disagree, so no phantom input can form. The buffer is cleared only at round start, when no input
 * is in flight. Password keyboard type additionally disables composing/suggestions/swipe, so
 * deltas are clean committed characters and swipe-to-type can't cheat the whole word in one go.
 */
@OptIn(ExperimentalLayoutApi::class)
@UnstableApi
@Composable
private fun HiddenTypingField(controller: GameController) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var fieldText by remember { mutableStateOf("") }

    BasicTextField(
        value = fieldText,
        onValueChange = { newValue ->
            // Feed only the characters appended since last time (suffix past the common prefix),
            // so the game sees each newly typed letter exactly once and the field stays in sync
            // with the IME instead of being force-reset out from under it.
            var i = 0
            val old = fieldText
            while (i < old.length && i < newValue.length && old[i] == newValue[i]) i++
            val added = newValue.substring(i)
            if (added.isNotEmpty()) controller.onTyped(added)
            fieldText = newValue
        },
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
        ),
        modifier = Modifier
            .size(1.dp)
            .alpha(0f)
            .focusRequester(focusRequester)
    )

    // Fresh input buffer at the start of each typing round (no input in flight then).
    LaunchedEffect(controller.isTyping) {
        if (controller.isTyping) fieldText = ""
    }

    // Keep the keyboard permanently visible: hold focus and re-show it whenever it becomes hidden
    // (back gesture, focus blip). Re-asserting focus on each ime-visibility change also reclaims it
    // after returning from the menu screen.
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) {
        focusRequester.requestFocus()
        if (!imeVisible) {
            delay(50)  // let focus land before asking for the IME
            keyboard?.show()
        }
    }
}
