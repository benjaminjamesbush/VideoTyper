package com.videotyper.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import kotlin.math.pow

/**
 * HaloResonanceCelebration
 *
 * When the scrub bar unlocks, it "breathes out" three clean luminous halo-rings that
 * echo the bar's own rounded-stadium silhouette. The three stars pulse first, the bar
 * itself glows, then staggered rings expand outward and drift gently up into the video,
 * cooling from warm gold to a soft cosmic cyan as they dissolve. Palette is limited to
 * warm white, gold, and a single cyan accent. Everything is a pure function of the
 * animation progress and the whole thing is guaranteed to render nothing once finished.
 */
@Composable
fun HaloResonanceCelebration(play: Int, band: Band, modifier: Modifier) {
    val progress = remember { Animatable(1f) } // start "finished" so nothing draws initially
    LaunchedEffect(play) {
        if (play <= 0) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 1900, easing = LinearEasing))
    }
    val p = progress.value

    Canvas(modifier) {
        if (p <= 0f || p >= 1f) return@Canvas // draw NOTHING before start or after finish

        val left = band.leftPx
        val right = band.rightPx
        val cy = band.centerYPx
        val bh = band.heightPx
        val cx = (left + right) / 2f
        val halfW = (right - left) / 2f

        // --- Cohesive palette: warm white -> gold -> soft cyan ------------------
        val warm = Color(0xFFFFF6E0)
        val gold = Color(0xFFFFCA5A)
        val cyan = Color(0xFF8FD9FF)

        // --- 1. Bar activation glow: a soft warm bloom hugging the bar ----------
        val glowA = haloClamp01(p / 0.05f) * haloClamp01((0.50f - p) / 0.40f)
        if (glowA > 0.002f) {
            val gw = (right - left) + 48f
            val gh = bh * 4.5f
            val brush = Brush.radialGradient(
                colors = listOf(
                    warm.copy(alpha = glowA * 0.55f),
                    gold.copy(alpha = glowA * 0.30f),
                    Color.Transparent
                ),
                center = Offset(cx, cy),
                radius = (halfW * 1.05f).coerceAtLeast(gh)
            )
            drawRoundRect(
                brush = brush,
                topLeft = Offset(cx - gw / 2f, cy - gh / 2f),
                size = Size(gw, gh),
                cornerRadius = CornerRadius(gh / 2f, gh / 2f)
            )
        }

        // --- 2. The three stars flare, then settle -----------------------------
        val starA = haloClamp01(p / 0.04f) * haloClamp01((0.32f - p) / 0.28f)
        if (starA > 0.002f) {
            val sr = 8f + p * 26f
            for (i in 0..2) {
                val sx = left + (i + 1) / 4f * (right - left)
                val brush = Brush.radialGradient(
                    colors = listOf(
                        warm.copy(alpha = starA * 0.90f),
                        gold.copy(alpha = starA * 0.45f),
                        Color.Transparent
                    ),
                    center = Offset(sx, cy),
                    radius = sr
                )
                drawCircle(brush = brush, radius = sr, center = Offset(sx, cy))
                drawCircle(warm.copy(alpha = starA * 0.85f), radius = 2.2f, center = Offset(sx, cy))
            }
        }

        // --- 3. A brief bright line sweeps along the bar (the "charge") ---------
        val lineA = haloClamp01(p / 0.03f) * haloClamp01((0.24f - p) / 0.20f)
        if (lineA > 0.002f) {
            val inset = halfW * 0.03f
            drawLine(
                color = gold.copy(alpha = lineA * 0.35f),
                start = Offset(left + inset, cy),
                end = Offset(right - inset, cy),
                strokeWidth = 6f,
                cap = StrokeCap.Round
            )
            drawLine(
                color = warm.copy(alpha = lineA * 0.90f),
                start = Offset(left + inset, cy),
                end = Offset(right - inset, cy),
                strokeWidth = 2.2f,
                cap = StrokeCap.Round
            )
        }

        // --- 4. Hero: three staggered halo-rings expand & drift upward ----------
        val baseRx = halfW * 0.86f
        val baseRy = bh * 0.65f
        val growX = halfW * 0.30f + 36f
        val growY = 96f
        val liftMax = 84f

        for (i in 0..2) {
            val start = i * 0.16f
            val span = 0.68f
            val lp = (p - start) / span
            if (lp <= 0f || lp >= 1f) continue

            val e = haloEaseOut(lp)
            val a = haloClamp01(lp / 0.12f) * (1f - lp).pow(1.35f)
            if (a <= 0.003f) continue

            val rx = baseRx + e * growX
            val ry = baseRy + e * growY
            val ringCy = cy - e * liftMax
            val cool = lerp(gold, cyan, (lp * 0.7f).coerceIn(0f, 1f))

            haloDrawRing(cx, ringCy, rx, ry, glowColor = cool, coreColor = warm, alpha = a)
        }
    }
}

/** Draws one halo-ring as three concentric strokes to fake a soft glow (no blur API). */
private fun DrawScope.haloDrawRing(
    cx: Float,
    cy: Float,
    rx: Float,
    ry: Float,
    glowColor: Color,
    coreColor: Color,
    alpha: Float
) {
    val topLeft = Offset(cx - rx, cy - ry)
    val size = Size(rx * 2f, ry * 2f)
    val r = ry.coerceAtMost(rx)
    val corner = CornerRadius(r, r)

    // wide soft halo
    drawRoundRect(
        color = glowColor.copy(alpha = alpha * 0.12f),
        topLeft = topLeft, size = size, cornerRadius = corner,
        style = Stroke(width = 10f)
    )
    // mid body
    drawRoundRect(
        color = glowColor.copy(alpha = alpha * 0.40f),
        topLeft = topLeft, size = size, cornerRadius = corner,
        style = Stroke(width = 3.5f)
    )
    // bright thin core
    drawRoundRect(
        color = coreColor.copy(alpha = alpha * 0.95f),
        topLeft = topLeft, size = size, cornerRadius = corner,
        style = Stroke(width = 1.4f)
    )
}

private fun haloClamp01(x: Float): Float = if (x < 0f) 0f else if (x > 1f) 1f else x

private fun haloEaseOut(t: Float): Float = 1f - (1f - t) * (1f - t)
