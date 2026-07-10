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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * "Silk Ribbon" scrub-bar unlock celebration.
 *
 * A sparse fan of 6 satin streamers is launched from the scrub bar the instant the 3rd star lands.
 * Each ribbon arcs UP into the darker video area, catches the light with a gentle 3-D twist, then
 * flutters and settles gracefully back down onto the bar. A brief warm bloom along the bar and three
 * soft glints on the stars ground the whole gesture to the bar itself — the thing the child unlocked.
 *
 * Palette: warm gold + cream, with a single soft cyan and soft violet accent centered in the fan
 * (2-4 cohesive colors, warm-dominant). Everything is a pure function of the animation progress `p`,
 * and the Canvas draws NOTHING for p <= 0 or p >= 1, so the overlay self-cleans with zero residue.
 */
@Composable
fun SilkRibbonCelebration(play: Int, band: Band, modifier: Modifier) {
    val progress = remember { Animatable(1f) }   // start "finished" -> nothing drawn initially
    LaunchedEffect(play) {
        if (play <= 0) return@LaunchedEffect
        progress.snapTo(0f)
        // Linear time so the projectile-arc physics (rise, hang, gentle fall) read naturally.
        progress.animateTo(1f, tween(durationMillis = 2000, easing = LinearEasing))
    }
    val ribbons = remember(play, band) { silkBuildRibbons(band, play) }
    val p = progress.value

    Canvas(modifier) {
        if (p <= 0f || p >= 1f) return@Canvas   // draw nothing before start or after finish

        // Master envelope: quick fade-in, hold, graceful fade-out fully complete by p = 1.
        val env = silkStep(0f, 0.07f, p) * (1f - silkStep(0.72f, 1f, p))

        silkDrawBarBloom(band, p)     // warm light rising off the bar (grounds the effect)
        silkDrawStarGlints(band, p)   // three soft glints on the stars that powered the unlock
        for (r in ribbons) silkDrawRibbon(r, band, p, env)
    }
}

// ---- Ribbon model ------------------------------------------------------------------------------

private class SilkRibbon(
    val x0: Float,
    val driftX: Float,
    val riseH: Float,
    val swayAmp: Float,
    val swayFreq: Float,
    val swayPhase: Float,
    val twistFreq: Float,
    val twistPhase: Float,
    val twistSpin: Float,
    val baseHalf: Float,
    val tail: Float,
    val delay: Float,
    val color: Color,
)

private const val SILK_PI = 3.1415927f
private const val SILK_TWO_PI = 6.2831853f

private val SILK_GOLD = Color(0xFFFFD27A)
private val SILK_CREAM = Color(0xFFFFF1CF)
private val SILK_CYAN = Color(0xFF9FE7E0)
private val SILK_VIOLET = Color(0xFFC7ABFF)

private fun silkBuildRibbons(band: Band, seed: Int): List<SilkRibbon> {
    val w = band.rightPx - band.leftPx
    val rnd = Random(seed * 9176 + 17)
    // A symmetric fan across the bar: centre streamers rise highest & straightest, edges fan outward.
    val fracs = floatArrayOf(0.18f, 0.32f, 0.44f, 0.56f, 0.68f, 0.82f)
    val cols = listOf(SILK_GOLD, SILK_CREAM, SILK_CYAN, SILK_VIOLET, SILK_CREAM, SILK_GOLD)
    val hMax = (w * 0.34f).coerceIn(150f, 360f)
    val hMin = hMax * 0.6f
    val half = (w * 0.013f).coerceIn(7f, 15f)
    val swayBase = (w * 0.018f).coerceIn(8f, 24f)
    return fracs.indices.map { i ->
        val f = fracs[i]
        val centerFactor = 1f - abs(f - 0.5f) * 2f     // 1 at centre, 0 at edges
        SilkRibbon(
            x0 = band.leftPx + f * w,
            driftX = (f - 0.5f) * w * 0.24f,            // outward drift, larger toward the edges
            riseH = hMin + (hMax - hMin) * centerFactor,
            swayAmp = swayBase * (0.8f + rnd.nextFloat() * 0.5f),
            swayFreq = 1.6f + rnd.nextFloat() * 1.0f,
            swayPhase = rnd.nextFloat() * SILK_TWO_PI,
            twistFreq = 1.0f + rnd.nextFloat() * 0.8f,
            twistPhase = rnd.nextFloat() * SILK_TWO_PI,
            twistSpin = 2.2f + rnd.nextFloat() * 1.6f,
            baseHalf = half * (0.85f + rnd.nextFloat() * 0.35f),
            tail = 0.26f + rnd.nextFloat() * 0.06f,
            delay = i * 0.012f + rnd.nextFloat() * 0.02f,
            color = cols[i],
        )
    }
}

// Smoothstep in [edge0, edge1].
private fun silkStep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

// Height profile of a ribbon head over its local time in [0,1]: fast launch, brief hang, gentle fall.
// Returns a non-negative value that is 0 at t=0 and t=1, peaking near t≈0.38, so ribbons never go
// below the bar and always settle back onto it.
private fun silkRise(t: Float): Float {
    val tc = t.coerceIn(0f, 1f)
    return sin(SILK_PI * tc.pow(0.72f))
}

private fun silkPointX(r: SilkRibbon, t: Float): Float {
    val tc = t.coerceIn(0f, 1f)
    val flutter = r.swayAmp * sin(r.swayFreq * tc * SILK_TWO_PI + r.swayPhase) * silkStep(0.02f, 0.34f, tc)
    return r.x0 + r.driftX * tc + flutter
}

private fun silkPointY(r: SilkRibbon, band: Band, t: Float): Float {
    return band.centerYPx - r.riseH * silkRise(t)
}

private fun DrawScope.silkDrawRibbon(r: SilkRibbon, band: Band, p: Float, env: Float) {
    if (env <= 0.003f) return
    val tau = p - r.delay
    if (tau <= 0f) return

    val n = 24
    val sx = FloatArray(n)
    val sy = FloatArray(n)
    for (k in 0 until n) {
        val frac = k / (n - 1f)                 // 0 = tail (older), 1 = head (newest)
        val t = tau - r.tail * (1f - frac)      // clamped inside the point helpers -> emerges from bar
        sx[k] = silkPointX(r, t)
        sy[k] = silkPointY(r, band, t)
    }

    val head = Offset(sx[n - 1], sy[n - 1])
    val tail = Offset(sx[0], sy[0])
    val dxs = head.x - tail.x
    val dys = head.y - tail.y
    if (sqrt(dxs * dxs + dys * dys) < 1.5f) return   // too short -> skip (avoids degenerate gradient)

    val lx = FloatArray(n)
    val ly = FloatArray(n)
    val rx = FloatArray(n)
    val ry = FloatArray(n)
    for (k in 0 until n) {
        val frac = k / (n - 1f)
        val a = if (k > 0) k - 1 else k
        val b = if (k < n - 1) k + 1 else k
        var tx = sx[b] - sx[a]
        var ty = sy[b] - sy[a]
        val len = sqrt(tx * tx + ty * ty)
        if (len > 0.0001f) { tx /= len; ty /= len } else { tx = 1f; ty = 0f }
        val nx = -ty
        val ny = tx
        val tailTaper = silkStep(0f, 0.16f, frac)                 // soft point at the trailing tail
        val headTaper = 1f - 0.30f * silkStep(0.72f, 1f, frac)    // slightly slimmer head
        val twist = 0.55f + 0.45f * sin(r.twistFreq * frac * SILK_TWO_PI + r.twistPhase + tau * r.twistSpin)
        val hw = r.baseHalf * tailTaper * headTaper * twist
        lx[k] = sx[k] + nx * hw; ly[k] = sy[k] + ny * hw
        rx[k] = sx[k] - nx * hw; ry[k] = sy[k] - ny * hw
    }

    val path = Path()
    path.moveTo(lx[0], ly[0])
    for (k in 1 until n) path.lineTo(lx[k], ly[k])
    for (k in n - 1 downTo 0) path.lineTo(rx[k], ry[k])
    path.close()

    // Satin body: bright, saturated head fading to transparent at the trailing tail.
    val body = Brush.linearGradient(
        colors = listOf(
            r.color.copy(alpha = env),
            r.color.copy(alpha = env * 0.5f),
            r.color.copy(alpha = 0f),
        ),
        start = head,
        end = tail,
    )
    drawPath(path, body)

    // Soft specular sheen down the centre (the "silk" highlight), skipping the tapered ends.
    val sheen = Path()
    sheen.moveTo(sx[2], sy[2])
    for (k in 3..(n - 3)) sheen.lineTo(sx[k], sy[k])
    drawPath(
        sheen,
        color = SILK_CREAM.copy(alpha = env * 0.22f),
        style = Stroke(width = r.baseHalf * 0.55f, cap = StrokeCap.Round),
    )

    // A gentle glint at the leading tip, brightest while rising.
    val glintA = env * 0.65f * (1f - silkStep(0.55f, 1f, tau))
    if (glintA > 0.01f) {
        val gr = r.baseHalf * 2.4f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFFFFFFFF).copy(alpha = glintA),
                    r.color.copy(alpha = glintA * 0.45f),
                    Color(0x00FFFFFF),
                ),
                center = head,
                radius = gr,
            ),
            radius = gr,
            center = head,
        )
    }
}

// Warm bloom rising off the bar at the launch instant, gone well before the ribbons peak.
private fun DrawScope.silkDrawBarBloom(band: Band, p: Float) {
    val a = silkStep(0f, 0.06f, p) * (1f - silkStep(0.12f, 0.42f, p))
    if (a <= 0.01f) return
    val w = band.rightPx - band.leftPx
    val glowH = (w * 0.07f).coerceIn(40f, 130f)
    val top = band.centerYPx - glowH
    val bottom = band.centerYPx + band.heightPx * 0.5f
    val bloom = Brush.verticalGradient(
        colors = listOf(
            Color(0x00FFCF7A),
            SILK_GOLD.copy(alpha = a * 0.5f),
            Color(0x00FFCF7A),
        ),
        startY = top,
        endY = bottom,
    )
    drawRect(
        brush = bloom,
        topLeft = Offset(band.leftPx, top),
        size = Size(w, bottom - top),
    )
}

// Three soft glints on the stars, flashing at the launch instant then vanishing.
private fun DrawScope.silkDrawStarGlints(band: Band, p: Float) {
    val a = silkStep(0f, 0.05f, p) * (1f - silkStep(0.08f, 0.3f, p))
    if (a <= 0.01f) return
    val w = band.rightPx - band.leftPx
    val gr = (w * 0.022f).coerceIn(14f, 34f)
    for (i in 0..2) {
        val cx = band.leftPx + (i + 1) / 4f * w
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFFFFFFFF).copy(alpha = a),
                    SILK_GOLD.copy(alpha = a * 0.5f),
                    Color(0x00FFD27A),
                ),
                center = Offset(cx, band.centerYPx),
                radius = gr,
            ),
            radius = gr,
            center = Offset(cx, band.centerYPx),
        )
    }
}
