package com.videotyper.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.videotyper.game.GameController
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

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

// ---- cosmic explosion (fires only on an accepted correct letter) ----
private const val BOOM_DURATION_NS = 750_000_000L
private val CosmicPalette = listOf(
    Color(0xFF7C4DFF), Color(0xFF448AFF), Color(0xFF18FFFF),
    Color(0xFFE040FB), Color(0xFFFF4081), Color(0xFFFFD740), Color(0xFFFFFFFF),
)
private class Particle(val angle: Float, val speed: Float, val size: Float, val color: Color, val streak: Boolean)
private class Boom(val cx: Float, val cy: Float, val baseR: Float, val startNanos: Long, val particles: List<Particle>)

private fun makeBoom(cx: Float, cy: Float, baseR: Float): Boom {
    val n = 30
    val ps = ArrayList<Particle>(n)
    val twoPi = 2f * Math.PI.toFloat()
    for (i in 0 until n) {
        val angle = ((i + Random.nextFloat() - 0.5f) / n) * twoPi
        val speed = 0.5f + Random.nextFloat() * Random.nextFloat() * 1.7f  // biased small, a few fast
        val size = 0.05f + Random.nextFloat() * 0.12f
        val color = CosmicPalette[Random.nextInt(CosmicPalette.size)]
        val streak = Random.nextFloat() < 0.35f
        ps.add(Particle(angle, speed, size, color, streak))
    }
    return Boom(cx, cy, baseR, System.nanoTime(), ps)
}

private fun DrawScope.drawBoom(b: Boom, frameNanos: Long) {
    val t = ((frameNanos - b.startNanos).toFloat() / BOOM_DURATION_NS).coerceIn(0f, 1f)
    if (t >= 1f) return
    val ease = 1f - (1f - t) * (1f - t) * (1f - t)       // ease-out cubic
    val c = Offset(b.cx, b.cy)

    // white core flash — brightest at the instant of the tap, gone by a third of the way through
    val flashA = (1f - t * 3f).coerceAtLeast(0f) * 0.9f
    if (flashA > 0f) drawCircle(Color.White.copy(alpha = flashA), radius = b.baseR * (0.35f + 1.1f * t), center = c)

    // two expanding shockwave rings
    drawCircle(Color(0xFF9FE8FF).copy(alpha = (1f - t) * 0.5f), radius = b.baseR * (0.5f + 5.5f * ease),
        center = c, style = Stroke(width = b.baseR * 0.10f))
    drawCircle(Color(0xFFE0B3FF).copy(alpha = (1f - t) * 0.35f), radius = b.baseR * (0.3f + 3.6f * ease),
        center = c, style = Stroke(width = b.baseR * 0.06f))

    // radial particle burst (dots + streaks)
    for (p in b.particles) {
        val dist = b.baseR * (0.8f + p.speed * 5.5f * ease)
        val dx = cos(p.angle) * dist; val dy = sin(p.angle) * dist
        val pos = Offset(b.cx + dx, b.cy + dy)
        val a = (1f - t) * (1f - t)
        val col = p.color.copy(alpha = a)
        if (p.streak) {
            val back = Offset(b.cx + dx * 0.7f, b.cy + dy * 0.7f)
            drawLine(col, back, pos, strokeWidth = b.baseR * p.size * 1.3f, cap = StrokeCap.Round)
        } else {
            drawCircle(col, radius = b.baseR * p.size * (1f - 0.4f * t), center = pos)
        }
    }
}

private fun rewardHaptic(view: View) {
    val constant =
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
        else HapticFeedbackConstants.KEYBOARD_TAP
    view.performHapticFeedback(constant)
}

/**
 * The app's own on-screen keyboard, replacing the system IME. Laid out from [Kb] (an exact match of
 * Gboard on the reference phone, scaling to fill any width). Letter keys feed the same
 * [GameController.onTyped] path the IME used; shift/backspace/enter/comma/period/space are inert
 * landmarks kept for familiarity. During a typing round every letter in the target word lights up so
 * the child scans a smaller set. Any tap resolves to the NEAREST key (rectangle distance), so there
 * are no dead gaps — you can't miss.
 *
 * An accepted CORRECT letter is rewarded with a haptic and a cosmic explosion bursting from the key.
 * Wrong letters and landmark taps get nothing but the anti-mash grayscale — so the celebration only
 * ever rewards correct typing, never mashing.
 */
@UnstableApi
@Composable
fun CustomKeyboard(controller: GameController, modifier: Modifier = Modifier) {
    val view = LocalView.current
    val keys = remember { buildKeys() }
    val booms = remember { mutableStateListOf<Boom>() }
    var frameNanos by remember { mutableLongStateOf(0L) }

    // Animate only while explosions are alive (no idle frame loop).
    LaunchedEffect(booms.isNotEmpty()) {
        while (booms.isNotEmpty()) {
            withFrameNanos { frameNanos = it }
            booms.removeAll { frameNanos - it.startNanos > BOOM_DURATION_NS }
        }
    }

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

                        // Letters type; landmarks are inert but still count as non-progress input,
                        // so mashing them lands in the same boring grayscale (no reward loophole).
                        val correct = controller.onTyped(
                            if (hit.kind == KeyKind.LETTER) hit.char.toString() else " "
                        )
                        // Reward ONLY an accepted correct letter — never a wrong tap or a mash.
                        if (correct) {
                            rewardHaptic(view)
                            booms.add(
                                makeBoom(
                                    (hit.xF + hit.wF / 2f) * wpx,
                                    (hit.yF + hit.hF / 2f) * wpx,
                                    hit.wF * wpx,
                                )
                            )
                        }
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

            // Explosions, drawn above the keys (and allowed to burst beyond the keyboard).
            Canvas(Modifier.fillMaxSize()) {
                for (b in booms) drawBoom(b, frameNanos)
            }
        }
    }
}
