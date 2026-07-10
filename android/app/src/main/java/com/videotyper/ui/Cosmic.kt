package com.videotyper.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Reusable cosmic-explosion effect (white flash + two shockwave rings + dot/streak particles in a
 * cosmic palette). Used for the star-earned bursts and the screen-width burst around the scrub bar
 * when it unlocks. Fire a burst via [CosmicBursts.fire]; draw with [CosmicCanvas] over the content.
 */
private val Palette = listOf(
    Color(0xFF7C4DFF), Color(0xFF448AFF), Color(0xFF18FFFF),
    Color(0xFFE040FB), Color(0xFFFF4081), Color(0xFFFFD740), Color(0xFFFFFFFF),
)

internal class CosmicParticle(val angle: Float, val speed: Float, val size: Float, val color: Color, val streak: Boolean)
internal class CosmicBurst(
    val cx: Float, val cy: Float, val baseR: Float,
    val startNanos: Long, val durationNs: Long, val particles: List<CosmicParticle>,
)

class CosmicBursts {
    internal val list = mutableStateListOf<CosmicBurst>()

    /** Fire a burst at (cx,cy) px; baseR sets its scale, particleCount its density, durationMs its length. */
    fun fire(cx: Float, cy: Float, baseR: Float, particleCount: Int = 34, durationMs: Long = 800) {
        val twoPi = 2f * Math.PI.toFloat()
        val ps = ArrayList<CosmicParticle>(particleCount)
        for (i in 0 until particleCount) {
            val angle = ((i + Random.nextFloat() - 0.5f) / particleCount) * twoPi
            val speed = 0.5f + Random.nextFloat() * Random.nextFloat() * 1.8f
            val size = 0.04f + Random.nextFloat() * 0.11f
            ps.add(CosmicParticle(angle, speed, size, Palette[Random.nextInt(Palette.size)], Random.nextFloat() < 0.35f))
        }
        list.add(CosmicBurst(cx, cy, baseR, System.nanoTime(), durationMs * 1_000_000L, ps))
    }
}

@Composable
fun rememberCosmicBursts(): CosmicBursts = remember { CosmicBursts() }

@Composable
fun CosmicCanvas(bursts: CosmicBursts, modifier: Modifier = Modifier) {
    var frameNanos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(bursts.list.isNotEmpty()) {
        while (bursts.list.isNotEmpty()) {
            withFrameNanos { frameNanos = it }
            bursts.list.removeAll { frameNanos - it.startNanos > it.durationNs }
        }
    }
    Canvas(modifier) { for (b in bursts.list) drawBurst(b, frameNanos) }
}

private fun DrawScope.drawBurst(b: CosmicBurst, frameNanos: Long) {
    val t = ((frameNanos - b.startNanos).toFloat() / b.durationNs.toFloat()).coerceIn(0f, 1f)
    if (t >= 1f) return
    val ease = 1f - (1f - t) * (1f - t) * (1f - t)
    val c = Offset(b.cx, b.cy)

    val flashA = (1f - t * 3f).coerceAtLeast(0f) * 0.9f
    if (flashA > 0f) drawCircle(Color.White.copy(alpha = flashA), radius = b.baseR * (0.35f + 1.1f * t), center = c)

    drawCircle(Color(0xFF9FE8FF).copy(alpha = (1f - t) * 0.5f), radius = b.baseR * (0.5f + 5.5f * ease),
        center = c, style = Stroke(width = b.baseR * 0.10f))
    drawCircle(Color(0xFFE0B3FF).copy(alpha = (1f - t) * 0.35f), radius = b.baseR * (0.3f + 3.6f * ease),
        center = c, style = Stroke(width = b.baseR * 0.06f))

    for (p in b.particles) {
        val dist = b.baseR * (0.8f + p.speed * 5.5f * ease)
        val dx = cos(p.angle) * dist; val dy = sin(p.angle) * dist
        val pos = Offset(b.cx + dx, b.cy + dy)
        val a = (1f - t) * (1f - t)
        val col = p.color.copy(alpha = a)
        if (p.streak) {
            drawLine(col, Offset(b.cx + dx * 0.7f, b.cy + dy * 0.7f), pos,
                strokeWidth = b.baseR * p.size * 1.3f, cap = StrokeCap.Round)
        } else {
            drawCircle(col, radius = b.baseR * p.size * (1f - 0.4f * t), center = pos)
        }
    }
}
