package com.videotyper.preview

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
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.exp

/**
 * RadiantSweep — a premium "power-on" light sweep for the scrub-bar unlock.
 *
 * A soft bright gleam glides left-to-right along the bar. Behind it the bar charges
 * gold (a horizontal gradient wavefront); as the gleam reaches each of the 3 stars in
 * turn they flare with a clean 6-point shine. When the sweep completes the whole bar is
 * lit, a soft glow blooms upward into the video, one clean ring pulses off the bar, and
 * the three stars twinkle together — then everything eases back to nothing.
 *
 * Palette is restrained: gold, warm-white, and a whisper of soft cosmic cyan.
 * The animation is fully a function of p in (0,1); at p<=0 and p>=1 it draws NOTHING,
 * so the overlay is guaranteed clear before it starts and after it finishes.
 */
@Composable
fun RadiantSweepCelebration(play: Int, band: Band, modifier: Modifier) {
    val progress = remember { Animatable(1f) } // start "finished" so nothing draws initially
    LaunchedEffect(play) {
        if (play <= 0) return@LaunchedEffect
        progress.snapTo(0f)
        // Shaping is done internally from a linear clock for full control over overlapping phases.
        progress.animateTo(1f, tween(durationMillis = 1900, easing = LinearEasing))
    }
    val p = progress.value

    Canvas(modifier) {
        if (p <= 0f || p >= 1f) return@Canvas // draw NOTHING before start or after finish

        // Master tail fade — insurance that the last sliver of the animation eases cleanly to zero.
        val master = 1f - radiantSweepSmoother(((p - 0.90f) / 0.10f).coerceIn(0f, 1f))
        if (master <= 0f) return@Canvas

        val left = band.leftPx
        val right = band.rightPx
        val cy = band.centerYPx
        val h = band.heightPx.coerceAtLeast(1f)
        val barW = (right - left).coerceAtLeast(1f)
        val corner = h * 0.5f
        val halfH = h * 0.5f

        // Cohesive palette.
        val gold = Color(0xFFFFD740)
        val warm = Color(0xFFFFF4D6) // warm white
        val hot = Color(0xFFFFFFFF)  // hot core
        val cyan = Color(0xFF9FE8FF) // soft cosmic accent

        // Phase split: the sweep occupies the first 55%, the settle/pulse the rest.
        val phaseA = 0.55f
        val aT = (p / phaseA).coerceIn(0f, 1f)
        val pulseT = ((p - phaseA) / (1f - phaseA)).coerceIn(0f, 1f)
        val settled = p >= phaseA

        // Sweep position (enters just left of the bar, exits just right so every star is crossed).
        val gleamHalf = barW * 0.14f
        val sweepStart = left - gleamHalf
        val sweepEnd = right + gleamHalf
        val se = radiantSweepSmoother(aT)
        val sweepX = sweepStart + (sweepEnd - sweepStart) * se
        val stopPos = ((sweepX - left) / barW).coerceIn(0f, 1f)

        // ---- Layer 1: upward bloom off the bar (wide as the bar, rising a few bar-heights up) ----
        val bloomEnv = radiantSweepBump(p, 0.60f, 0.17f) * master
        if (bloomEnv > 0.004f) {
            val bloomTop = cy - halfH - h * 6f
            val bloomBottom = cy + halfH
            val bloomBrush = Brush.verticalGradient(
                0f to Color.Transparent,
                0.30f to cyan.copy(alpha = 0.05f * bloomEnv),
                0.72f to gold.copy(alpha = 0.15f * bloomEnv),
                1f to warm.copy(alpha = 0.22f * bloomEnv),
                startY = bloomTop,
                endY = bloomBottom,
            )
            drawRect(bloomBrush, topLeft = Offset(left, bloomTop), size = Size(barW, bloomBottom - bloomTop))
        }

        // ---- Layer 2: bar charge fill ----
        if (!settled) {
            // Gradient reveal: charged gold up to the moving wavefront, transparent ahead of it.
            val ca = 0.50f * master
            val a = (stopPos - 0.05f).coerceIn(0.0002f, 0.9990f)
            val b = stopPos.coerceIn(a + 0.0004f, 0.9994f)
            val c = (stopPos + 0.06f).coerceIn(b + 0.0004f, 1f)
            val fillBrush = Brush.horizontalGradient(
                0f to gold.copy(alpha = ca),
                a to gold.copy(alpha = ca),
                b to warm.copy(alpha = (ca * 1.4f).coerceAtMost(1f)),
                c to Color.Transparent,
                1f to Color.Transparent,
                startX = left,
                endX = right,
            )
            drawRoundRect(
                fillBrush,
                topLeft = Offset(left, cy - halfH),
                size = Size(barW, h),
                cornerRadius = CornerRadius(corner, corner),
            )
        } else {
            // Fully charged; ease the fill back down as the pulse plays out.
            val ca = 0.50f * (1f - pulseT) * master
            if (ca > 0.004f) {
                drawRoundRect(
                    gold.copy(alpha = ca),
                    topLeft = Offset(left, cy - halfH),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(corner, corner),
                )
            }
        }

        // ---- Layer 3: completion flash (quick "power-on" pop across the whole bar) ----
        if (settled) {
            val flashA = 0.34f * radiantSweepBump(pulseT, 0f, 0.14f) * master
            if (flashA > 0.004f) {
                drawRoundRect(
                    warm.copy(alpha = flashA),
                    topLeft = Offset(left, cy - halfH),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(corner, corner),
                )
            }
        }

        // ---- Layer 4: one clean pulse ring rising off the bar ----
        if (settled) {
            val inflate = h * 2.4f * radiantSweepSmoother(pulseT)
            val fade = (1f - pulseT).coerceIn(0f, 1f)
            val ringA = 0.55f * fade * fade * master
            if (ringA > 0.004f) {
                val rw = (h * 0.16f).coerceAtLeast(2f)
                drawRoundRect(
                    gold.copy(alpha = ringA),
                    topLeft = Offset(left - inflate, cy - halfH - inflate),
                    size = Size(barW + inflate * 2f, h + inflate * 2f),
                    cornerRadius = CornerRadius(corner + inflate, corner + inflate),
                    style = Stroke(width = rw),
                )
            }
        }

        // ---- Layer 5: per-star glow, shine and final twinkle ----
        val twinkle = radiantSweepBump(pulseT, 0.14f, 0.13f)
        for (i in 0..2) {
            val sx = left + (i + 1) / 4f * barW
            val dx = sweepX - sx

            // Held "lit" aura once the gleam has passed the star (persists through the settle, then eases out).
            val held = (dx / (barW * 0.04f)).coerceIn(0f, 1f)
            val heldA = held * 0.42f * (1f - 0.8f * pulseT) * master
            if (heldA > 0.01f) {
                val auraR = h * 1.9f
                drawCircle(
                    Brush.radialGradient(
                        listOf(gold.copy(alpha = heldA), Color.Transparent),
                        center = Offset(sx, cy),
                        radius = auraR,
                    ),
                    radius = auraR,
                    center = Offset(sx, cy),
                )
            }

            // Momentary shine: as the gleam sweeps across the star, plus a synchronized twinkle at the end.
            val pass = radiantSweepGauss(dx, barW * 0.09f)
            val shine = (pass + (if (settled) twinkle else 0f)).coerceIn(0f, 1f) * master
            if (shine > 0.01f) {
                val len = h * (1.4f + 3.0f * shine)
                val lw = (h * 0.11f).coerceAtLeast(1.5f)
                val rayBright = warm.copy(alpha = 0.90f * shine)
                val rayFaint = warm.copy(alpha = 0.48f * shine)
                // Vertical shine (rises further up into the video than it drops below the bar).
                drawLine(rayBright, Offset(sx, cy - len * 1.25f), Offset(sx, cy + len * 0.55f), strokeWidth = lw, cap = StrokeCap.Round)
                // Horizontal shine.
                drawLine(rayBright, Offset(sx - len, cy), Offset(sx + len, cy), strokeWidth = lw, cap = StrokeCap.Round)
                // Soft diagonals for a 6-point sparkle.
                val d = len * 0.42f * 0.7071f
                drawLine(rayFaint, Offset(sx - d, cy - d), Offset(sx + d, cy + d), strokeWidth = lw * 0.7f, cap = StrokeCap.Round)
                drawLine(rayFaint, Offset(sx - d, cy + d), Offset(sx + d, cy - d), strokeWidth = lw * 0.7f, cap = StrokeCap.Round)
                // Gold halo ring + hot core.
                drawCircle(gold.copy(alpha = 0.50f * shine), radius = h * (0.55f + 0.7f * shine), center = Offset(sx, cy), style = Stroke(width = lw * 0.8f))
                drawCircle(hot.copy(alpha = 0.95f * shine), radius = h * (0.30f + 0.5f * shine), center = Offset(sx, cy))
            }
        }

        // ---- Layer 6: the gleam itself (the shine head) — brightest, on top; vanishes once settled ----
        val gleamEnv = (aT / 0.10f).coerceIn(0f, 1f) * ((1f - aT) / 0.14f).coerceIn(0f, 1f) * master
        if (gleamEnv > 0.01f) {
            val haloR = h * 2.6f
            drawCircle(
                Brush.radialGradient(
                    listOf(gold.copy(alpha = 0.35f * gleamEnv), Color.Transparent),
                    center = Offset(sweepX, cy),
                    radius = haloR,
                ),
                radius = haloR,
                center = Offset(sweepX, cy),
            )
            // Tall soft shine streak rising off the bar.
            val streakTop = cy - h * 3.4f
            val streakBottom = cy + h * 1.4f
            val streakW = h * 0.55f
            val streakBrush = Brush.verticalGradient(
                0f to Color.Transparent,
                0.30f to warm.copy(alpha = 0.50f * gleamEnv),
                0.71f to hot.copy(alpha = 0.85f * gleamEnv),
                0.85f to warm.copy(alpha = 0.35f * gleamEnv),
                1f to Color.Transparent,
                startY = streakTop,
                endY = streakBottom,
            )
            drawRoundRect(
                streakBrush,
                topLeft = Offset(sweepX - streakW / 2f, streakTop),
                size = Size(streakW, streakBottom - streakTop),
                cornerRadius = CornerRadius(streakW / 2f, streakW / 2f),
            )
            // Hot core on the bar line + a leading cyan whisper.
            drawCircle(hot.copy(alpha = 0.90f * gleamEnv), radius = h * 0.5f, center = Offset(sweepX, cy))
            drawCircle(cyan.copy(alpha = 0.25f * gleamEnv), radius = h * 0.9f, center = Offset(sweepX + gleamHalf * 0.45f, cy))
        }
    }
}

/** Smootherstep (Ken Perlin) — zero-velocity at both ends, for silky sweep/pulse motion. */
private fun radiantSweepSmoother(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * t * (t * (t * 6f - 15f) + 10f)
}

/** Time-domain Gaussian bump centred at [center] with the given [width]. */
private fun radiantSweepBump(x: Float, center: Float, width: Float): Float {
    val z = (x - center) / width
    return exp(-(z * z))
}

/** Space-domain Gaussian falloff of distance [dist] with the given [width]. */
private fun radiantSweepGauss(dist: Float, width: Float): Float {
    val z = dist / width
    return exp(-(z * z) / 2f)
}
