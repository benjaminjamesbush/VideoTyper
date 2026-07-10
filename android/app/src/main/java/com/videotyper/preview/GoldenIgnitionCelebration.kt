package com.videotyper.preview

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Golden charge-up / power-on unlock celebration.
 *
 * A warm glow sweeps left-to-right along the scrub bar like a charging power rail. As the charge
 * front passes each of the 3 gold stars they flare in a crisp 1-2-3 sequence (glow bloom + ring
 * ping + a premium 4-point glint). When the front reaches the end, the whole bar flashes to a
 * steady "powered on" glow and a soft gold dome — tinted with a faint cosmic violet at its crown —
 * blooms upward off the bar and settles. Everything then fades cleanly to nothing.
 *
 * Palette (4 colors): warm white core, warm gold, deep amber, and a soft violet cosmic accent.
 * Entirely localized to the bar + its stars; nothing scatters across the screen.
 */
@Composable
fun GoldenIgnitionCelebration(play: Int, band: Band, modifier: Modifier) {
    val progress = remember { Animatable(1f) }
    LaunchedEffect(play) {
        if (play <= 0) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 1850, easing = LinearEasing))
    }
    val p = progress.value

    Canvas(modifier.fillMaxSize()) {
        if (p <= 0f || p >= 1f) return@Canvas

        // ---- Palette -----------------------------------------------------------------------
        val warmWhite = Color(0xFFFFF6DC)
        val gold = Color(0xFFFFC24B)
        val amber = Color(0xFFFF9E1B)
        val cosmic = Color(0xFFC3AEFF) // soft violet cosmic accent, used sparingly

        // ---- Geometry ----------------------------------------------------------------------
        val left = band.leftPx
        val right = band.rightPx
        val cy = band.centerYPx
        val bh = band.heightPx.coerceAtLeast(2f)
        val barW = (right - left).coerceAtLeast(1f)
        val cx = (left + right) / 2f
        val starR = bh * 3.2f
        val starX = List(3) { i -> left + (i + 1) / 4f * barW }
        val starPeak = floatArrayOf(0.10f, 0.17f, 0.24f)

        // ---- Master timing envelopes -------------------------------------------------------
        // Global exit fade so absolutely everything is gone before the hard cutoff at p == 1.
        val masterAlpha = 1f - ignitionSmoothstep(0.80f, 0.99f, p)
        if (masterAlpha <= 0f) return@Canvas

        // Charge front sweeping across the bar (0 at left .. 1 at right), reaching full ~p=0.31.
        val front = ignitionSmoothstep(0.02f, 0.31f, p)
        val frontX = left + front * barW
        // Crossfade from the "hot leading edge" sweep to an even, fully-powered glow.
        val settle = ignitionSmoothstep(0.27f, 0.44f, p)
        // Rail presence: rises in with the charge, holds through the powered hold, fades at the end.
        val railEnv = ignitionSmoothstep(0.0f, 0.26f, p) * (1f - ignitionSmoothstep(0.80f, 0.97f, p))
        // Soft dome blooming above the bar and settling.
        val domeEnv = ignitionBump(p, 0.26f, 0.52f, 0.95f)
        // Unified "click — fully powered" flash across the whole bar at the moment of completion.
        val climax = ignitionBump(p, 0.26f, 0.34f, 0.52f)

        // ---- 1) Dome halo blooming upward off the bar --------------------------------------
        if (domeEnv > 0.001f) {
            val domeH = barW * 0.17f * domeEnv
            if (domeH > 1f) {
                val topY = cy - domeH
                val dome = Path().apply {
                    moveTo(left, cy)
                    cubicTo(left + barW * 0.16f, cy, left + barW * 0.34f, topY, cx, topY)
                    cubicTo(right - barW * 0.34f, topY, right - barW * 0.16f, cy, right, cy)
                    close()
                }
                val domeBrush = Brush.verticalGradient(
                    0f to cosmic.copy(alpha = 0f),
                    0.22f to cosmic.copy(alpha = 0.05f),
                    0.58f to gold.copy(alpha = 0.15f),
                    1f to warmWhite.copy(alpha = 0.32f),
                    startY = topY,
                    endY = cy,
                )
                drawPath(dome, domeBrush, alpha = domeEnv * masterAlpha)

                // Bright heart of the halo, hovering just above the bar center.
                val coreR = barW * 0.15f
                val coreCenter = Offset(cx, cy - domeH * 0.34f)
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to warmWhite.copy(alpha = 0.42f),
                        0.4f to gold.copy(alpha = 0.20f),
                        1f to gold.copy(alpha = 0f),
                        center = coreCenter,
                        radius = coreR,
                    ),
                    radius = coreR,
                    center = coreCenter,
                    alpha = domeEnv * masterAlpha,
                )
            }
        }

        // ---- 2) The charging / powered rail ------------------------------------------------
        // Faint pre-glow so the not-yet-charged track reads as "waiting to light up".
        val preGlow = railEnv * (1f - settle) * 0.5f * masterAlpha
        if (preGlow > 0.001f) {
            drawLine(
                color = gold.copy(alpha = 0.10f),
                start = Offset(left, cy),
                end = Offset(right, cy),
                strokeWidth = bh * 1.2f,
                cap = StrokeCap.Round,
                alpha = preGlow,
            )
        }

        // Hot leading-edge sweep (dominant during the charge).
        val sweepA = railEnv * (1f - settle) * masterAlpha
        if (sweepA > 0.001f && frontX - left > 1f) {
            drawLine(
                brush = Brush.linearGradient(
                    0f to amber.copy(alpha = 0f),
                    0.55f to gold.copy(alpha = 0.30f),
                    1f to warmWhite.copy(alpha = 0.62f),
                    start = Offset(left, cy),
                    end = Offset(frontX, cy),
                ),
                start = Offset(left, cy),
                end = Offset(frontX, cy),
                strokeWidth = bh * 2.8f,
                cap = StrokeCap.Round,
                alpha = sweepA,
            )
            drawLine(
                brush = Brush.linearGradient(
                    0f to gold.copy(alpha = 0.15f),
                    0.75f to warmWhite.copy(alpha = 0.85f),
                    1f to warmWhite,
                    start = Offset(left, cy),
                    end = Offset(frontX, cy),
                ),
                start = Offset(left, cy),
                end = Offset(frontX, cy),
                strokeWidth = bh * 0.9f,
                cap = StrokeCap.Round,
                alpha = sweepA,
            )
        }

        // Even, fully-powered glow (dominant after the charge completes).
        val powerA = railEnv * settle * masterAlpha
        if (powerA > 0.001f) {
            drawLine(
                color = gold.copy(alpha = 0.30f),
                start = Offset(left, cy),
                end = Offset(right, cy),
                strokeWidth = bh * 2.8f,
                cap = StrokeCap.Round,
                alpha = powerA,
            )
            drawLine(
                color = warmWhite.copy(alpha = 0.80f),
                start = Offset(left, cy),
                end = Offset(right, cy),
                strokeWidth = bh * 0.9f,
                cap = StrokeCap.Round,
                alpha = powerA,
            )
        }

        // Unified completion flash across the whole bar.
        if (climax > 0.001f) {
            drawLine(
                color = gold.copy(alpha = 0.32f),
                start = Offset(left, cy),
                end = Offset(right, cy),
                strokeWidth = bh * 3.4f,
                cap = StrokeCap.Round,
                alpha = climax * masterAlpha,
            )
            drawLine(
                color = warmWhite.copy(alpha = 0.60f),
                start = Offset(left, cy),
                end = Offset(right, cy),
                strokeWidth = bh * 1.3f,
                cap = StrokeCap.Round,
                alpha = climax * masterAlpha,
            )
        }

        // Traveling spark at the charge front (only while charging).
        val sparkLife = (1f - ignitionSmoothstep(0.24f, 0.33f, p)) *
            ignitionSmoothstep(0f, 0.04f, p) * masterAlpha
        if (sparkLife > 0.001f) {
            val sparkR = bh * 2.6f
            drawCircle(
                brush = Brush.radialGradient(
                    0f to warmWhite.copy(alpha = 0.95f),
                    0.4f to gold.copy(alpha = 0.45f),
                    1f to gold.copy(alpha = 0f),
                    center = Offset(frontX, cy),
                    radius = sparkR,
                ),
                radius = sparkR,
                center = Offset(frontX, cy),
                alpha = sparkLife,
            )
        }

        // ---- 3) Per-star sequential flares (1 - 2 - 3) -------------------------------------
        for (i in 0..2) {
            val peak = starPeak[i]
            val sxi = starX[i]

            // Ring ping — an expanding thin ring that fades as it grows.
            val ringP = ignitionSmoothstep(peak - 0.01f, peak + 0.30f, p)
            if (ringP > 0f && ringP < 1f) {
                val ringR = starR * (0.5f + 2.6f * ringP)
                val ringA = (1f - ringP) * 0.55f * masterAlpha
                if (ringA > 0.001f) {
                    drawCircle(
                        color = gold.copy(alpha = ringA),
                        radius = ringR,
                        center = Offset(sxi, cy),
                        style = Stroke(width = bh * 0.45f * (1f - 0.5f * ringP)),
                    )
                }
            }

            val flare = ignitionBump(p, peak - 0.06f, peak, peak + 0.18f)
            if (flare > 0.001f) {
                // Radial glow bloom behind the star.
                val glowR = starR * (1.1f + 0.9f * flare)
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to warmWhite.copy(alpha = 0.90f),
                        0.3f to gold.copy(alpha = 0.55f),
                        0.65f to amber.copy(alpha = 0.18f),
                        1f to gold.copy(alpha = 0f),
                        center = Offset(sxi, cy),
                        radius = glowR,
                    ),
                    radius = glowR,
                    center = Offset(sxi, cy),
                    alpha = flare * masterAlpha,
                )

                // Bright warm-white flash of the star itself (scale pulse over the real gold star).
                val flashR = starR * (0.72f + 0.55f * flare)
                drawPath(
                    path = ignitionStarPath(sxi, cy, flashR),
                    color = warmWhite,
                    alpha = flare * 0.85f * masterAlpha,
                )
            }

            // Crisp 4-point glint at the peak of the flare — biased upward, off the bar.
            val glint = ignitionBump(p, peak - 0.02f, peak + 0.02f, peak + 0.15f)
            if (glint > 0.001f) {
                val len = starR * (1.5f + 1.1f * glint)
                val gw = bh * 0.30f
                val gA = glint * 0.9f * masterAlpha
                // Vertical shine (longer up into the video, short below the bar).
                drawLine(
                    color = warmWhite.copy(alpha = gA),
                    start = Offset(sxi, cy - len),
                    end = Offset(sxi, cy + len * 0.55f),
                    strokeWidth = gw,
                    cap = StrokeCap.Round,
                )
                // Horizontal shine.
                drawLine(
                    color = warmWhite.copy(alpha = gA),
                    start = Offset(sxi - len * 0.72f, cy),
                    end = Offset(sxi + len * 0.72f, cy),
                    strokeWidth = gw,
                    cap = StrokeCap.Round,
                )
                // Short diagonal cosmic accents.
                val dl = len * 0.42f
                val cA = glint * 0.5f * masterAlpha
                drawLine(
                    color = cosmic.copy(alpha = cA),
                    start = Offset(sxi - dl, cy - dl),
                    end = Offset(sxi + dl, cy + dl),
                    strokeWidth = gw * 0.6f,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = cosmic.copy(alpha = cA),
                    start = Offset(sxi - dl, cy + dl),
                    end = Offset(sxi + dl, cy - dl),
                    strokeWidth = gw * 0.6f,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

/** Hermite smoothstep from e0..e1, clamped to 0..1. */
private fun ignitionSmoothstep(e0: Float, e1: Float, x: Float): Float {
    if (e1 <= e0) return if (x < e0) 0f else 1f
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** Smooth 0 -> 1 -> 0 bump: rises across start..peak, decays across peak..end. */
private fun ignitionBump(x: Float, start: Float, peak: Float, end: Float): Float {
    if (x <= start || x >= end) return 0f
    return if (x < peak) ignitionSmoothstep(start, peak, x)
    else 1f - ignitionSmoothstep(peak, end, x)
}

/** 5-point star path centered at (cx, cy), top point up — matches the real scrub-bar stars. */
private fun ignitionStarPath(cx: Float, cy: Float, outerR: Float): Path {
    val innerR = outerR * 0.42f
    val path = Path()
    for (k in 0 until 10) {
        val r = if (k % 2 == 0) outerR else innerR
        val a = (-90.0 + k * 36.0) * PI / 180.0
        val x = cx + (r * cos(a)).toFloat()
        val y = cy + (r * sin(a)).toFloat()
        if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}