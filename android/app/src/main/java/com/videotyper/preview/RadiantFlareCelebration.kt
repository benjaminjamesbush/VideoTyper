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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Radiant Flare — a single cinematic lens-flare bloom that unlocks off the scrub bar.
 *
 * A soft warm-white core blooms at the bar centre while a tall gold beam rises into the video and a
 * thin anamorphic streak runs the length of the bar; a restrained upward fan of gold spikes and two
 * faint cyan lens ghosts complete the flare. The three stars twinkle in sequence left-to-right. The
 * whole thing shoots out fast, holds a beat, then gracefully recedes to nothing — the master envelope
 * reaches exactly 0 at p = 1 and the Canvas draws nothing at p <= 0 or p >= 1, so it fully self-cleans.
 */
@Composable
fun RadiantFlareCelebration(play: Int, band: Band, modifier: Modifier) {
    val progress = remember { Animatable(1f) }
    LaunchedEffect(play) {
        if (play <= 0) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 1900, easing = LinearEasing))
    }
    val p = progress.value
    Canvas(modifier) {
        if (p <= 0f || p >= 1f) return@Canvas

        val cx = (band.leftPx + band.rightPx) / 2f
        val cy = band.centerYPx
        val span = (band.rightPx - band.leftPx)
        if (span <= 1f) return@Canvas

        // ---- Envelopes ---------------------------------------------------------------------------
        // Fast attack, brief hold, then a long graceful decay that reaches exactly 0 at p = 1.
        val attack = flareSmooth(0f, 0.16f, p)
        val decay = 1f - flareSmooth(0.30f, 1f, p)
        val env = attack * decay                                   // master alpha
        val growE = flareEaseOut(flareSmooth(0f, 0.22f, p))        // rays shoot out
        val contract = 1f - 0.28f * flareSmooth(0.32f, 1f, p)      // then gently recede
        val reach = growE * contract
        val decayExpand = flareSmooth(0.35f, 1f, p)                // glow swells as it dims
        val rot = 0.085f * flareEaseOut(p)                         // subtle blooming turn (~5 deg)

        // ---- Soft gold under-glow hugging the bar ------------------------------------------------
        val glowBrush = Brush.linearGradient(
            0f to Color.Transparent,
            0.5f to FlareGold.copy(alpha = 0.30f * env),
            1f to Color.Transparent,
            start = Offset(band.leftPx, cy),
            end = Offset(band.rightPx, cy),
        )
        drawLine(
            glowBrush, Offset(band.leftPx, cy), Offset(band.rightPx, cy),
            strokeWidth = (band.heightPx.coerceAtLeast(6f)) * 1.6f, cap = StrokeCap.Round,
        )

        // ---- Faint lens ghosts along the horizontal axis -----------------------------------------
        val horizLen = span * 0.47f * reach
        for (side in intArrayOf(-1, 1)) {
            val gx = cx + side * horizLen * 0.58f
            val gr = span * 0.028f
            if (gr > 0.5f) {
                drawCircle(
                    Brush.radialGradient(
                        0f to FlareCyan.copy(alpha = 0.16f * env),
                        1f to Color.Transparent,
                        center = Offset(gx, cy), radius = gr,
                    ),
                    radius = gr, center = Offset(gx, cy),
                )
            }
        }

        // ---- Radiant spikes fanning from the bar centre ------------------------------------------
        for (spike in RADIANT_SPIKES) {
            val a = spike.angle + if (spike.rotates) rot else 0f
            drawRadiantRay(
                cx, cy, a, span * spike.lenFactor * reach, spike.halfWidth,
                spike.core, spike.tip, env * spike.baseAlpha,
            )
        }

        // ---- Central bloom -----------------------------------------------------------------------
        val bloomR = span * 0.09f * (0.5f + 0.7f * growE + 0.4f * decayExpand)
        if (bloomR > 0.5f) {
            drawCircle(
                Brush.radialGradient(
                    0f to FlareWarmWhite.copy(alpha = 0.90f * env),
                    0.24f to FlareGold.copy(alpha = 0.55f * env),
                    0.58f to FlareDeepGold.copy(alpha = 0.22f * env),
                    0.85f to FlareViolet.copy(alpha = 0.10f * env),
                    1f to Color.Transparent,
                    center = Offset(cx, cy), radius = bloomR,
                ),
                radius = bloomR, center = Offset(cx, cy),
            )
        }
        val coreR = span * 0.018f * (0.5f + 0.5f * growE)
        if (coreR > 0.4f) {
            drawCircle(FlareWarmWhite.copy(alpha = env), radius = coreR, center = Offset(cx, cy))
        }

        // ---- Crisp anamorphic streak line across the bar -----------------------------------------
        val lineBrush = Brush.linearGradient(
            0f to Color.Transparent,
            0.5f to FlareWarmWhite.copy(alpha = env),
            1f to Color.Transparent,
            start = Offset(band.leftPx, cy),
            end = Offset(band.rightPx, cy),
        )
        drawLine(
            lineBrush, Offset(band.leftPx, cy), Offset(band.rightPx, cy),
            strokeWidth = 2.5f, cap = StrokeCap.Round,
        )

        // ---- Sequential twinkle on each of the 3 stars -------------------------------------------
        for (i in 0..2) {
            val starX = band.leftPx + (i + 1) / 4f * span
            val starEnv = flareSmooth(0.06f + i * 0.05f, 0.24f + i * 0.05f, p) * decay
            drawRadiantTwinkle(starX, cy, span * 0.075f * reach, starEnv, span)
        }
    }
}

// ---- Palette (gold family + warm white, with restrained cyan / violet cosmic accents) ------------
private val FlareWarmWhite = Color(0xFFFFFDF5)
private val FlareGold = Color(0xFFFFD54F)
private val FlareDeepGold = Color(0xFFFFB300)
private val FlareCyan = Color(0xFF9BE7FF)
private val FlareViolet = Color(0xFFC3B4FF)

private class RadiantSpike(
    val angle: Float,
    val lenFactor: Float,
    val halfWidth: Float,
    val core: Color,
    val tip: Color,
    val baseAlpha: Float,
    val rotates: Boolean,
)

// Upward-biased fan: a tall beam up into the video, the anamorphic horizontal streak, four diagonals,
// and a fine upper fan. Down is deliberately stubby (that side is the controls). "Up" is -90 deg.
private val RADIANT_SPIKES = listOf(
    RadiantSpike(flareDeg(-90f), 0.40f, 3.4f, FlareWarmWhite, FlareViolet, 0.95f, false),
    RadiantSpike(flareDeg(0f), 0.47f, 2.6f, FlareWarmWhite, FlareCyan, 0.80f, false),
    RadiantSpike(flareDeg(180f), 0.47f, 2.6f, FlareWarmWhite, FlareCyan, 0.80f, false),
    RadiantSpike(flareDeg(90f), 0.12f, 2.4f, FlareGold, FlareDeepGold, 0.45f, false),
    RadiantSpike(flareDeg(-45f), 0.30f, 2.2f, FlareGold, FlareDeepGold, 0.60f, true),
    RadiantSpike(flareDeg(-135f), 0.30f, 2.2f, FlareGold, FlareDeepGold, 0.60f, true),
    RadiantSpike(flareDeg(45f), 0.15f, 1.9f, FlareGold, FlareDeepGold, 0.40f, true),
    RadiantSpike(flareDeg(135f), 0.15f, 1.9f, FlareGold, FlareDeepGold, 0.40f, true),
    RadiantSpike(flareDeg(-22f), 0.24f, 1.4f, FlareGold, FlareCyan, 0.34f, true),
    RadiantSpike(flareDeg(-68f), 0.26f, 1.4f, FlareGold, FlareViolet, 0.34f, true),
    RadiantSpike(flareDeg(-112f), 0.26f, 1.4f, FlareGold, FlareViolet, 0.34f, true),
    RadiantSpike(flareDeg(-158f), 0.24f, 1.4f, FlareGold, FlareCyan, 0.34f, true),
)

private fun flareDeg(deg: Float): Float = (deg * PI / 180.0).toFloat()

private fun flareSmooth(edge0: Float, edge1: Float, x: Float): Float {
    if (edge0 == edge1) return if (x < edge0) 0f else 1f
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun flareEaseOut(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    val inv = 1f - t
    return 1f - inv * inv * inv
}

/** A single tapered light ray: wide-ish at the origin, converging to a point, fading to transparent. */
private fun DrawScope.drawRadiantRay(
    ox: Float, oy: Float, angle: Float, length: Float, halfWidth: Float,
    core: Color, tip: Color, intensity: Float,
) {
    if (intensity <= 0.004f || length < 1.5f) return
    val dx = cos(angle)
    val dy = sin(angle)
    val tx = ox + dx * length
    val ty = oy + dy * length
    val px = -dy * halfWidth
    val py = dx * halfWidth
    val path = Path().apply {
        moveTo(ox + px, oy + py)
        lineTo(ox - px, oy - py)
        lineTo(tx, ty)
        close()
    }
    val brush = Brush.linearGradient(
        0f to core.copy(alpha = intensity),
        0.42f to core.copy(alpha = intensity * 0.45f),
        1f to tip.copy(alpha = 0f),
        start = Offset(ox, oy),
        end = Offset(tx, ty),
    )
    drawPath(path, brush)
}

/** A small 4-point twinkle plus soft bloom for a single star. */
private fun DrawScope.drawRadiantTwinkle(x: Float, y: Float, arm: Float, intensity: Float, span: Float) {
    if (intensity <= 0.004f) return
    val hw = 1.5f
    drawRadiantRay(x, y, flareDeg(-90f), arm, hw, FlareWarmWhite, FlareCyan, intensity)
    drawRadiantRay(x, y, flareDeg(90f), arm * 0.7f, hw, FlareWarmWhite, FlareGold, intensity * 0.8f)
    drawRadiantRay(x, y, flareDeg(0f), arm * 0.85f, hw, FlareWarmWhite, FlareCyan, intensity * 0.9f)
    drawRadiantRay(x, y, flareDeg(180f), arm * 0.85f, hw, FlareWarmWhite, FlareCyan, intensity * 0.9f)
    val r = span * 0.035f * (0.6f + 0.6f * intensity)
    if (r > 0.4f) {
        drawCircle(
            Brush.radialGradient(
                0f to FlareWarmWhite.copy(alpha = 0.85f * intensity),
                0.4f to FlareGold.copy(alpha = 0.40f * intensity),
                1f to Color.Transparent,
                center = Offset(x, y), radius = r,
            ),
            radius = r, center = Offset(x, y),
        )
    }
    drawCircle(FlareWarmWhite.copy(alpha = intensity), radius = span * 0.008f, center = Offset(x, y))
}
