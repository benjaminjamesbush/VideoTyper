package com.videotyper.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.videotyper.game.GameController
import kotlinx.coroutines.launch

/**
 * Fraction of the keyboard's WIDTH taken up by its height. Every dimension below is a fraction of
 * width, so the whole keyboard scales with width and its proportions are invariant (2x width => 2x
 * everything). At 1080 px wide these reproduce the geometry traced against Gboard on the reference
 * phone (see tools keyboard_geometry.json). Height = 4*keyH + 3*rowGap.
 */
const val KEYBOARD_HEIGHT_FRACTION = 0.523519f

/** Width-relative layout constants (fractions of keyboard width). */
private object Kb {
    const val sideMargin = 0.021481f
    const val keyW = 0.082222f
    const val gap = 0.014979f
    const val keyH = 0.107130f
    const val rowGap = 0.031667f
    const val shiftW = 0.115370f
    const val enterLeft = 0.847685f
    val rowPitch = keyH + rowGap
    fun rowTop(n: Int) = n * rowPitch
}

private enum class KeyKind { LETTER, LANDMARK }

/** One key, positioned in width-fraction space. `char` is non-null only for functional letter keys. */
private data class KbKey(
    val xF: Float, val yF: Float, val wF: Float, val hF: Float,
    val label: String, val char: Char?, val kind: KeyKind,
)

/** The full QWERTY layout, built once from the fraction constants. */
private fun buildKeys(): List<KbKey> {
    val g = Kb.gap; val kw = Kb.keyW; val kh = Kb.keyH; val sm = Kb.sideMargin
    val keys = ArrayList<KbKey>(33)
    fun letter(x: Float, row: Int, c: Char) =
        keys.add(KbKey(x, Kb.rowTop(row), kw, kh, c.toString(), c, KeyKind.LETTER))
    fun landmark(x: Float, row: Int, w: Float, label: String) =
        keys.add(KbKey(x, Kb.rowTop(row), w, kh, label, null, KeyKind.LANDMARK))

    // row 0: q–p (10, left edge at the side margin)
    "qwertyuiop".forEachIndexed { i, c -> letter(sm + i * (kw + g), 0, c) }
    // row 1: a–l (9, centered)
    val l1 = (1f - (9 * kw + 8 * g)) / 2f
    "asdfghjkl".forEachIndexed { i, c -> letter(l1 + i * (kw + g), 1, c) }
    // row 2: z–m (7, centered) + shift / backspace landmarks
    val l2 = (1f - (7 * kw + 6 * g)) / 2f
    val r2x = FloatArray(7) { l2 + it * (kw + g) }
    "zxcvbnm".forEachIndexed { i, c -> letter(r2x[i], 2, c) }
    landmark(sm, 2, Kb.shiftW, "⇧")                       // shift: left = q-left
    landmark(1f - sm - Kb.shiftW, 2, Kb.shiftW, "⌫")      // backspace: right = p-right
    // row 3: comma (under z) | space (under x..n) | period (under m) | enter
    landmark(r2x[0], 3, kw, ",")
    landmark(r2x[6], 3, kw, ".")
    landmark(r2x[1], 3, (r2x[5] + kw) - r2x[1], "")            // space bar
    landmark(Kb.enterLeft, 3, (1f - sm) - Kb.enterLeft, "↵") // enter: right = p-right
    return keys
}

private val KbBackground = Color(0xFF15181D)
private val LetterBg = Color(0xFF3A3F47)
private val LetterText = Color(0xFFECECEC)
private val LandmarkBg = Color(0xFF2A2E35)
private val LandmarkText = Color(0xFFAAB0B8)
private val LitBg = Color(0xFFFFEB3B)
private val LitText = Color(0xFF141414)
private val GlowColor = Color(0xFFFFFFFF)

private data class Glow(val cx: Float, val cy: Float, val keyWpx: Float)

/**
 * The app's own on-screen keyboard, replacing the system IME. Laid out from [Kb] (an exact match of
 * Gboard on the reference phone, scaling to fill any width). Letter keys feed the same
 * [GameController.onTyped] path the IME used; shift/backspace/enter/comma/period/space are inert
 * landmarks kept for familiarity. During a typing round every letter in the target word lights up so
 * the child scans a smaller set. Any tap resolves to the NEAREST key (rectangle distance), so there
 * are no dead gaps — you can't miss. Each tap gives a haptic click and an expanding glow that reaches
 * well beyond the key. Both feedback effects are suppressed during the anti-mash grayscale.
 */
@UnstableApi
@Composable
fun CustomKeyboard(controller: GameController, modifier: Modifier = Modifier) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val keys = remember { buildKeys() }
    val glowAnim = remember { Animatable(0f) }
    var glow by remember { mutableStateOf<Glow?>(null) }

    // Which letters to light up: the target word's letters, only while a round is active.
    val litSet: Set<Char> =
        if (controller.isTyping) controller.highlightWord?.uppercase()?.toSet().orEmpty()
        else emptySet()

    BoxWithConstraints(modifier.background(KbBackground)) {
        val w = maxWidth                       // keyboard width (Dp); every fraction scales off this
        val corner = (w.value * 0.02f).dp

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(keys) {
                    awaitEachGesture {
                        // Fire on finger-down for a snappy key feel.
                        val down = awaitFirstDown()
                        val wpx = size.width.toFloat()
                        if (wpx <= 0f) return@awaitEachGesture
                        val fx = down.position.x / wpx
                        val fy = down.position.y / wpx
                        val hit = keys.minByOrNull { k ->
                            val dx = maxOf(k.xF - fx, 0f, fx - (k.xF + k.wF))
                            val dy = maxOf(k.yF - fy, 0f, fy - (k.yF + k.hF))
                            dx * dx + dy * dy
                        } ?: return@awaitEachGesture

                        // Feedback only when interactive (gray = fully unresponsive, no reward).
                        if (!controller.isCoolingDown) {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            glow = Glow((hit.xF + hit.wF / 2f) * wpx, (hit.yF + hit.hF / 2f) * wpx, hit.wF * wpx)
                            scope.launch { glowAnim.snapTo(0f); glowAnim.animateTo(1f, tween(320)) }
                        }
                        // Letters type; landmarks are inert but still count as non-progress input,
                        // so mashing them lands in the same boring grayscale (no reward loophole).
                        controller.onTyped(if (hit.kind == KeyKind.LETTER) hit.char.toString() else " ")
                    }
                }
        ) {
            for (key in keys) {
                val lit = key.kind == KeyKind.LETTER && key.char != null &&
                    key.char.uppercaseChar() in litSet
                val bg = if (lit) LitBg else if (key.kind == KeyKind.LANDMARK) LandmarkBg else LetterBg
                val fg = if (lit) LitText else if (key.kind == KeyKind.LANDMARK) LandmarkText else LetterText
                Box(
                    Modifier
                        .offset(x = w * key.xF, y = w * key.yF)
                        .size(width = w * key.wF, height = w * key.hF)
                        .clip(RoundedCornerShape(corner))
                        .background(bg),
                    contentAlignment = Alignment.Center,
                ) {
                    if (key.label.isNotEmpty()) {
                        Text(
                            key.label,
                            color = fg,
                            fontSize = (w.value * if (key.kind == KeyKind.LANDMARK) 0.045f else 0.052f).sp,
                            fontWeight = if (lit) FontWeight.Bold else FontWeight.Medium,
                        )
                    }
                }
            }

            // Tap glow, drawn above the keys so it flashes over and beyond the tapped key.
            Canvas(Modifier.fillMaxSize()) { drawGlow(glow, glowAnim.value) }
        }
    }
}

private fun DrawScope.drawGlow(glow: Glow?, progress: Float) {
    if (glow == null || progress >= 1f) return
    val radius = glow.keyWpx * (0.8f + 1.9f * progress)   // expands well past the key
    val alpha = (1f - progress) * 0.5f
    val center = Offset(glow.cx, glow.cy)
    drawCircle(
        brush = Brush.radialGradient(
            listOf(GlowColor.copy(alpha = alpha), Color.Transparent),
            center = center, radius = radius,
        ),
        radius = radius, center = center,
    )
}
