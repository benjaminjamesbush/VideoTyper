package com.videotyper.ui

import android.graphics.ColorMatrixColorFilter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
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

private val HighlightYellow = Color(0xFFFFEB3B)
private val FlashRed = Color(0xFFE53935)

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
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .grayscale(controller.isCoolingDown)
    ) {
        val keyboardHeight = maxWidth * KEYBOARD_HEIGHT_FRACTION
        val videoHeight = minOf(maxWidth * 9f / 16f, maxHeight - BottomUiReserve - keyboardHeight)
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
            CustomKeyboard(controller, Modifier.fillMaxWidth().height(keyboardHeight))
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

    // Just the scrub bar — no runtime label (not useful here, and it cost vertical space). Height
    // is trimmed from the Material default so the bar stays compact. Scrubbing is allowed at any
    // time, including during a typing round (which it abandons — see GameController.seekTo).
    Slider(
        value = if (dragValue >= 0f) dragValue
        else if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
        onValueChange = { dragValue = it },
        onValueChangeFinished = {
            if (dragValue >= 0f && durationMs > 0) {
                controller.seekTo((dragValue * durationMs).toLong())
            }
            dragValue = -1f
        },
        enabled = controller.hasMedia,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(28.dp)
    )
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
