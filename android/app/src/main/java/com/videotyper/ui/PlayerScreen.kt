package com.videotyper.ui

import android.graphics.ColorMatrixColorFilter
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

@UnstableApi
@Composable
fun PlayerScreen(
    controller: GameController,
    lastOpened: String?,
    onOpened: (String) -> Unit,
) {
    // Anti-mash cooldown: the whole screen (video included) drains to grayscale. The UI
    // stays fully visible — clearly alive, just colorless — with no sound, no message, and
    // nothing animating (the letter flash freezes and hints pause while gray).
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .imePadding()
            .grayscale(controller.isCoolingDown)
    ) {
        VideoSurface(controller, Modifier.fillMaxWidth().weight(1f))
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
        SeekBar(controller)
        ControlsRow(controller, lastOpened, onOpened)
        HiddenTypingField(controller)
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
    lastOpened: String?,
    onOpened: (String) -> Unit,
) {
    var showUrlDialog by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onOpened(uri.toString())
            controller.openUri(uri)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = { filePicker.launch(arrayOf("video/*")) },
            enabled = !controller.isTyping,
            modifier = Modifier.weight(1f)
        ) { Text("Open") }
        OutlinedButton(
            onClick = { showUrlDialog = true },
            enabled = !controller.isTyping,
            modifier = Modifier.weight(1f)
        ) { Text("Network") }
        Button(
            onClick = { controller.playPause() },
            enabled = controller.hasMedia && !controller.isTyping,
            modifier = Modifier.weight(1f)
        ) { Text(if (controller.isPlaying) "Pause" else "Play") }
        OutlinedButton(
            onClick = { controller.stop() },
            enabled = controller.hasMedia && !controller.isTyping,
            modifier = Modifier.weight(1f)
        ) { Text("Stop") }
    }

    if (showUrlDialog) {
        var url by remember {
            mutableStateOf(lastOpened?.takeIf { it.startsWith("smb://") || it.startsWith("http") } ?: "smb://")
        }
        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            title = { Text("Open network video") },
            text = {
                Column {
                    Text(
                        "smb://user:pass@server/share/movie.mkv or an http(s) URL",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showUrlDialog = false
                    val trimmed = url.trim()
                    if (trimmed.isNotEmpty() && trimmed != "smb://") {
                        onOpened(trimmed)
                        controller.openUri(android.net.Uri.parse(trimmed))
                    }
                }) { Text("Play") }
            },
            dismissButton = {
                TextButton(onClick = { showUrlDialog = false }) { Text("Cancel") }
            }
        )
    }
}

/**
 * Invisible 1dp text field that summons the keyboard during typing rounds. Its text is cleared
 * after every change, so each change carries just the newly typed characters, fed to the game
 * (wrong letters get ignored there).
 *
 * Uses a Password keyboard type deliberately. A normal Text field lets the keyboard keep a
 * "composing region" (the pending, underlined text used for prediction/autocorrect) that is
 * separate from our field state and does NOT respect our reset-to-empty. Under fast mashing —
 * especially during the gray cooldown, where nothing on screen confirms input — that buffer
 * accumulates and is later re-presented as one big multi-character onValueChange, which the game
 * reads as gesture-typing and turns into an endless re-triggering cooldown (the "stuck after
 * gray" bug). Password type disables composing, suggestions, and swipe-typing entirely, so every
 * tap commits immediately as a single clean character and swipe-to-type can't cheat the word.
 */
@UnstableApi
@Composable
private fun HiddenTypingField(controller: GameController) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var fieldText by remember { mutableStateOf("") }

    BasicTextField(
        value = fieldText,
        onValueChange = { newValue ->
            if (newValue.isNotEmpty()) controller.onTyped(newValue)
            fieldText = ""
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

    LaunchedEffect(controller.isTyping) {
        if (controller.isTyping) {
            fieldText = ""
            focusRequester.requestFocus()
            // Give focus a beat to land before asking for the IME.
            delay(50)
            keyboard?.show()
        } else {
            keyboard?.hide()
        }
    }
}
