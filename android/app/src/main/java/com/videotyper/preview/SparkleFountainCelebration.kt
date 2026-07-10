package com.videotyper.preview

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import kotlin.math.sin
import kotlin.random.Random

/**
 * "Sparkle Fountain" — a gentle champagne-fizz celebration that lifts softly off the scrub bar the
 * instant the 3rd star lands and the bar unlocks. A warm ribbon of light catches the bar's top edge,
 * a faint gold haze breathes upward, the three stars bloom once, and a few dozen tiny four-point
 * glints rise a short distance, twinkle, and wink out. Cohesive gold / champagne-white palette with
 * the faintest cool cyan-violet accents. Fully self-cleaning: renders nothing before start or after end.
 */
@Composable
fun SparkleFountainCelebration(play: Int, band: Band, modifier: Modifier) {
    val progress = remember { Animatable(1f) }
    LaunchedEffect(play) {
        if (play <= 0) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 2100, easing = LinearEasing))
    }
    val glints = remember(play) { sfBuildGlints(play) }
    val glyph = remember { Path() }
    val p = progress.value

    Canvas(modifier) {
        if (p <= 0f || p >= 1f) return@Canvas

        // Global closing envelope: guarantees everything melts to nothing well before p == 1.
        val fadeAll = if (p > 0.86f) (1f - p) / 0.14f else 1f

        val left = band.leftPx
        val right = band.rightPx
        val span = right - left
        if (span <= 0f || band.heightPx <= 0f) return@Canvas
        val barTop = band.centerYPx - band.heightPx * 0.5f
        val maxRise = band.heightPx * 7.5f
        val baseSize = band.heightPx * 0.30f
        val inset = span * 0.02f

        // 1) Rising gold haze — a whisper-faint veil that breathes off the bar and dissolves.
        run {
            val bloom = sfSin(sfClamp01(p / 0.55f) * SF_PI)
            if (bloom > 0.001f) {
                val mistTop = barTop - maxRise * 0.92f
                val mist = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, SF_GOLD.copy(alpha = 0.11f)),
                    startY = mistTop,
                    endY = barTop,
                )
                drawRect(
                    brush = mist,
                    topLeft = Offset(left, mistTop),
                    size = Size(span, barTop - mistTop),
                    alpha = bloom * fadeAll,
                )
            }
        }

        // 2) The three stars bloom once — a soft gold halo behind each slot, tying the reward to the bar.
        run {
            val bloom = sfSin(sfClamp01(p / 0.42f) * SF_PI)
            if (bloom > 0.001f) {
                for (i in 0..2) {
                    val sx = left + (i + 1) / 4f * span
                    val sy = band.centerYPx
                    val haloR = band.heightPx * (1.5f + 0.9f * sfClamp01(p / 0.42f))
                    val halo = Brush.radialGradient(
                        colors = listOf(
                            SF_GOLD.copy(alpha = 0.22f * bloom * fadeAll),
                            SF_WHITE.copy(alpha = 0.10f * bloom * fadeAll),
                            Color.Transparent,
                        ),
                        center = Offset(sx, sy),
                        radius = haloR,
                    )
                    drawCircle(brush = halo, radius = haloR, center = Offset(sx, sy))
                }
            }
        }

        // 3) A ribbon of light catches the top edge of the bar and pulses once.
        run {
            val edge = sfSin(sfClamp01(p / 0.5f) * SF_PI)
            if (edge > 0.001f) {
                val ribbon = Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        SF_GOLD.copy(alpha = 0.85f),
                        SF_WHITE,
                        SF_GOLD.copy(alpha = 0.85f),
                        Color.Transparent,
                    ),
                    start = Offset(left, barTop),
                    end = Offset(right, barTop),
                )
                drawLine(
                    brush = ribbon,
                    start = Offset(left + inset, barTop),
                    end = Offset(right - inset, barTop),
                    strokeWidth = band.heightPx * 0.16f,
                    cap = StrokeCap.Round,
                    alpha = edge * 0.7f * fadeAll,
                )
            }
        }

        // 4) The fountain — sparse, dainty four-point glints that lift, twinkle, and vanish.
        for (g in glints) {
            val lt = (p - g.t0) / g.life
            if (lt <= 0f || lt >= 1f) continue

            val rise = sfEaseOutQuad(lt)
            val startX = left + inset + g.xFrac * (span - 2f * inset)
            val startY = barTop - g.startVar * (band.heightPx * 0.35f)
            val sway = sfSin(lt * SF_TAU * 0.75f + g.swayPhase) * g.swayAmp * lt
            val x = startX + g.driftPx * rise + sway
            val y = startY - maxRise * g.riseFrac * rise

            val aIn = sfSmooth(0f, 0.16f, lt)
            val aOut = 1f - sfSmooth(0.6f, 1f, lt)
            val twinkle = 0.5f + 0.5f * sfSin(lt * SF_TAU * g.twFreq + g.twPhase)
            val alpha = aIn * aOut * (0.35f + 0.65f * twinkle) * fadeAll
            if (alpha <= 0.003f) continue

            val szEnv = 0.55f + 0.45f * sfSin(sfClamp01(lt) * SF_PI)
            val r = baseSize * g.sizeFrac * szEnv

            // soft halo
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(g.color.copy(alpha = alpha * 0.5f), Color.Transparent),
                    center = Offset(x, y),
                    radius = r * 3.4f,
                ),
                radius = r * 3.4f,
                center = Offset(x, y),
            )
            // four-point glint
            sfGlyphPath(glyph, x, y, r, 0.17f)
            drawPath(glyph, color = g.color, alpha = alpha)
            // bright core
            drawCircle(SF_CORE, radius = r * 0.30f, center = Offset(x, y), alpha = alpha)
        }
    }
}

private val SF_GOLD = Color(0xFFFFD27A)
private val SF_WHITE = Color(0xFFFFF6E2)
private val SF_CYAN = Color(0xFFB8E2FF)
private val SF_VIOLET = Color(0xFFCEBEFF)
private val SF_CORE = Color(0xFFFFFDF6)
private const val SF_PI = 3.1415927f
private const val SF_TAU = 6.2831855f

private class SfGlint(
    val xFrac: Float,
    val startVar: Float,
    val driftPx: Float,
    val riseFrac: Float,
    val t0: Float,
    val life: Float,
    val sizeFrac: Float,
    val twFreq: Float,
    val twPhase: Float,
    val swayAmp: Float,
    val swayPhase: Float,
    val color: Color,
)

private fun sfBuildGlints(play: Int): List<SfGlint> {
    val rnd = Random(play * 73856093 xor 0x9E3779)
    val count = 32
    return List(count) {
        val roll = rnd.nextFloat()
        val color = when {
            roll < 0.44f -> SF_GOLD
            roll < 0.84f -> SF_WHITE
            roll < 0.93f -> SF_CYAN
            else -> SF_VIOLET
        }
        SfGlint(
            xFrac = rnd.nextFloat(),
            startVar = rnd.nextFloat(),
            driftPx = (rnd.nextFloat() * 2f - 1f) * (8f + rnd.nextFloat() * 12f),
            riseFrac = 0.5f + rnd.nextFloat() * 0.5f,
            t0 = rnd.nextFloat() * 0.42f,
            life = 0.34f + rnd.nextFloat() * 0.16f,
            sizeFrac = 0.7f + rnd.nextFloat() * 0.6f,
            twFreq = 2.5f + rnd.nextFloat() * 3.5f,
            twPhase = rnd.nextFloat() * SF_TAU,
            swayAmp = 4f + rnd.nextFloat() * 9f,
            swayPhase = rnd.nextFloat() * SF_TAU,
            color = color,
        )
    }
}

/** Builds a sharp four-point sparkle (8 vertices) into [path], reused each frame. */
private fun sfGlyphPath(path: Path, cx: Float, cy: Float, r: Float, inner: Float) {
    val ir = r * inner
    path.reset()
    path.moveTo(cx, cy - r)
    path.lineTo(cx + ir, cy - ir)
    path.lineTo(cx + r, cy)
    path.lineTo(cx + ir, cy + ir)
    path.lineTo(cx, cy + r)
    path.lineTo(cx - ir, cy + ir)
    path.lineTo(cx - r, cy)
    path.lineTo(cx - ir, cy - ir)
    path.close()
}

private fun sfClamp01(x: Float): Float = if (x < 0f) 0f else if (x > 1f) 1f else x

private fun sfSmooth(edge0: Float, edge1: Float, x: Float): Float {
    val t = sfClamp01((x - edge0) / (edge1 - edge0))
    return t * t * (3f - 2f * t)
}

private fun sfEaseOutQuad(t: Float): Float {
    val u = 1f - t
    return 1f - u * u
}

private fun sfSin(a: Float): Float = sin(a.toDouble()).toFloat()
