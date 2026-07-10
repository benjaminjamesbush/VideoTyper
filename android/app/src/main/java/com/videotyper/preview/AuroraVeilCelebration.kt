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
import androidx.compose.ui.graphics.StrokeCap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

/**
 * Aurora Veil — a calm, cinematic celebration for the scrub-bar unlock.
 *
 * Soft translucent curtains of cyan-violet-gold light rise off the bar and undulate slowly, while a
 * single warm highlight washes once from left to right, lighting each of the three stars in turn as
 * it passes. The whole veil then drifts upward and dissolves to nothing. Nothing is drawn before the
 * animation starts (p <= 0) or after it ends (p >= 1), so the overlay is guaranteed to clear itself.
 */
@Composable
fun AuroraVeilCelebration(play: Int, band: Band, modifier: Modifier) {
    val progress = remember { Animatable(1f) } // start "finished" so nothing draws initially
    LaunchedEffect(play) {
        if (play <= 0) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 2200, easing = LinearEasing))
    }
    val p = progress.value

    Canvas(modifier) {
        if (p <= 0f || p >= 1f) return@Canvas // draw NOTHING before start / after finish

        val left = band.leftPx
        val width = band.rightPx - band.leftPx
        if (width <= 0f) return@Canvas
        val cy = band.centerYPx
        val maxRise = width * 0.26f

        // --- Timeline envelopes ---------------------------------------------------------------
        val riseIn = auroraSmooth(0f, 0.30f, p)          // curtains grow up
        val fade = 1f - auroraSmooth(0.62f, 1f, p)       // then dissolve out
        val env = riseIn * fade                          // overall visibility
        val t = p * 6.5f                                 // slow undulation clock
        val colorDrift = p * 0.22f                       // palette gently drifts across the bar
        val sweep = auroraSmooth(0.08f, 0.80f, p)        // single warm wash, left -> right
        val ascend = (1f - fade) * 30f                   // veil rises as it dissolves

        // --- Base glow hugging the bar --------------------------------------------------------
        val glowH = band.heightPx * 4.2f
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                0.5f to AuroraGold.copy(alpha = 0.13f * env),
                1f to Color.Transparent,
                startY = cy - glowH,
                endY = cy + glowH,
            ),
            topLeft = Offset(left, cy - glowH),
            size = Size(width, glowH * 2f),
        )

        // --- The aurora curtains --------------------------------------------------------------
        val n = (width / 9f).toInt().coerceIn(64, 140)
        val lineW = (width / n) * 1.7f
        val yBot = cy + band.heightPx * 0.15f - ascend * 0.25f
        for (i in 0 until n) {
            val fx = i / (n - 1f)
            val x = left + fx * width

            // Layered slow sines -> undulating curtain top edge.
            val wave = 0.55f * sin(fx * 4.2f + t * 4.3f) +
                0.30f * sin(fx * 7.9f - t * 2.7f + 1.3f) +
                0.15f * sin(fx * 12.5f + t * 5.9f + 2.4f)
            val waveN = wave * 0.5f + 0.5f
            val h = maxRise * (0.42f + 0.58f * waveN) * riseIn
            val yTop = cy - h - ascend

            // Colour sweeps across the bar through the cohesive palette.
            var col = auroraPalette(fx * 0.9f + colorDrift)

            // Warm shimmer where the single wash currently is.
            val d = abs(fx - sweep)
            val shim = exp(-(d * d) / (2f * 0.11f * 0.11f))
            col = auroraMix(col, AuroraWarmWhite, shim * 0.55f)

            val a = (0.40f * env) * (0.55f + 0.45f * waveN) * (1f + shim * 0.6f)
            val botCol = auroraMix(col, AuroraWarmWhite, 0.28f).copy(alpha = a)
            val midCol = col.copy(alpha = a * 0.5f)
            val topCol = col.copy(alpha = 0f)

            drawLine(
                brush = Brush.verticalGradient(
                    0f to topCol,
                    0.62f to midCol,
                    1f to botCol,
                    startY = yTop,
                    endY = yBot,
                ),
                start = Offset(x, yBot),
                end = Offset(x, yTop),
                strokeWidth = lineW,
                cap = StrokeCap.Round,
            )
        }

        // --- Slim luminous core along the bar itself ------------------------------------------
        drawLine(
            brush = Brush.linearGradient(
                listOf(
                    AuroraCyan.copy(alpha = 0f),
                    AuroraCyan.copy(alpha = 0.30f * env),
                    AuroraViolet.copy(alpha = 0.30f * env),
                    AuroraGold.copy(alpha = 0.30f * env),
                    AuroraGold.copy(alpha = 0f),
                ),
                start = Offset(left, cy),
                end = Offset(band.rightPx, cy),
            ),
            start = Offset(left, cy),
            end = Offset(band.rightPx, cy),
            strokeWidth = band.heightPx * 0.8f,
            cap = StrokeCap.Round,
        )

        // --- The travelling warm wash ---------------------------------------------------------
        val hx = left + sweep * width
        val hlR = width * 0.18f
        drawCircle(
            brush = Brush.radialGradient(
                0f to AuroraWarmWhite.copy(alpha = 0.50f * env),
                0.35f to AuroraGold.copy(alpha = 0.20f * env),
                1f to Color.Transparent,
                center = Offset(hx, cy),
                radius = hlR,
            ),
            radius = hlR,
            center = Offset(hx, cy),
        )

        // --- Each earned star lights up as the wash passes over it ----------------------------
        for (i in 0..2) {
            val starFx = (i + 1) / 4f
            val sx = left + starFx * width
            val dp = abs(sweep - starFx)
            val pop = exp(-(dp * dp) / (2f * 0.09f * 0.09f))
            val ha = env * (0.14f + 0.55f * pop)
            if (ha <= 0.001f) continue
            val hr = band.heightPx * (1.7f + 1.6f * pop)
            drawCircle(
                brush = Brush.radialGradient(
                    0f to AuroraWarmWhite.copy(alpha = ha),
                    0.4f to AuroraGold.copy(alpha = ha * 0.5f),
                    1f to Color.Transparent,
                    center = Offset(sx, cy),
                    radius = hr,
                ),
                radius = hr,
                center = Offset(sx, cy),
            )
        }
    }
}

// ---- Cohesive palette: cyan / violet / gold / warm white --------------------------------------
private val AuroraCyan = Color(0xFF5EE6DA)
private val AuroraViolet = Color(0xFF9E7BFF)
private val AuroraGold = Color(0xFFFFD07A)
private val AuroraWarmWhite = Color(0xFFFFF3DC)

/** Smootherstep-style clamp between two edges. */
private fun auroraSmooth(edge0: Float, edge1: Float, x: Float): Float {
    if (edge1 == edge0) return if (x < edge0) 0f else 1f
    val v = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return v * v * (3f - 2f * v)
}

/** Linear blend of two colours, channel-wise (alpha included). */
private fun auroraMix(a: Color, b: Color, t: Float): Color {
    val u = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * u,
        green = a.green + (b.green - a.green) * u,
        blue = a.blue + (b.blue - a.blue) * u,
        alpha = a.alpha + (b.alpha - a.alpha) * u,
    )
}

/** Cyclic cyan -> violet -> gold -> cyan palette, wrapping on the fractional part of [uIn]. */
private fun auroraPalette(uIn: Float): Color {
    val u = uIn - floor(uIn)
    return when {
        u < 1f / 3f -> auroraMix(AuroraCyan, AuroraViolet, u * 3f)
        u < 2f / 3f -> auroraMix(AuroraViolet, AuroraGold, (u - 1f / 3f) * 3f)
        else -> auroraMix(AuroraGold, AuroraCyan, (u - 2f / 3f) * 3f)
    }
}
