package com.videotyper.preview

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/**
 * A standalone harness for judging candidate scrub-bar unlock celebrations on-device. It reproduces
 * the player's dark video area + star/scrub band, and plays a chosen candidate as a full-screen
 * overlay anchored to the band. Launch it directly: `am start -n com.videotyper/.preview.PreviewActivity`.
 */
class PreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(color = Color.Black) { AnimationPreviewScreen() }
            }
        }
    }
}

private data class Candidate(
    val name: String,
    val concept: String,
    val render: @Composable (play: Int, band: Band, modifier: Modifier) -> Unit,
)

@Composable
private fun AnimationPreviewScreen() {
    var selected by remember { mutableStateOf<Int?>(null) }
    var playToken by remember { mutableIntStateOf(0) }
    var bandRect by remember { mutableStateOf<Rect?>(null) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            // Mock "video" — a dark region so effects rising off the bar have context.
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(
                        Brush.verticalGradient(listOf(Color(0xFF0A0A12), Color(0xFF05050A)))
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (selected == null) "Tap a celebration below" else previewCandidates[selected!!].name,
                    color = Color(0x33FFFFFF), fontSize = 15.sp,
                )
            }

            // The star/scrub band (matches the real player), reported to the overlay in root coords.
            MockStarBand(Modifier.onGloballyPositioned { bandRect = it.boundsInRoot() })

            // Controls: concept line + one button per candidate + replay.
            Column(Modifier.fillMaxWidth().background(Color(0xFF101014))) {
                Text(
                    selected?.let { previewCandidates[it].concept } ?: "Preview harness — pick a candidate to see it play on the bar.",
                    color = Color(0xCCFFFFFF), fontSize = 13.sp,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                )
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(vertical = 6.dp),
                ) {
                    itemsIndexed(previewCandidates) { i, c ->
                        val isSel = selected == i
                        Button(
                            onClick = { selected = i; playToken++ },
                            modifier = Modifier.fillMaxWidth().height(46.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSel) Color(0xFF5B4B8A) else Color(0xFF26263A)
                            ),
                        ) {
                            Text("${i + 1}.  ${c.name}", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (previewCandidates.isEmpty()) {
                        item { Text("No candidates yet", color = Color(0x88FFFFFF)) }
                    }
                    item {
                        OutlinedButton(
                            onClick = { if (selected != null) playToken++ },
                            modifier = Modifier.fillMaxWidth().height(46.dp).padding(top = 6.dp),
                        ) { Text(if (selected == null) "↻ Replay" else "↻ Replay “${previewCandidates[selected!!].name}”", color = Color.White) }
                    }
                }
            }
        }

        // The selected candidate plays here, as a full-screen overlay anchored to the band.
        val r = bandRect
        val sel = selected
        if (r != null && sel != null) {
            val band = Band(
                leftPx = r.left,
                rightPx = r.right,
                centerYPx = r.center.y,
                heightPx = r.height * 0.13f,
            )
            previewCandidates[sel].render(playToken, band, Modifier.fillMaxSize())
        }
    }
}

/** The star/scrub band, matching the real player: 3 gold stars on a thin scrub track. */
@Composable
private fun MockStarBand(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(56.dp).background(Color.Black)) {
        Canvas(Modifier.fillMaxSize()) {
            val cy = size.height / 2f
            // Thin scrub track through the stars.
            drawRoundRectTrack(cy)
            val outerR = size.height * 0.44f
            for (i in 0..2) {
                val cx = (i + 1) / 4f * size.width
                val path = previewStarPath(cx, cy, outerR)
                drawPath(path, Color(0xFFFFD54F))
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRoundRectTrack(cy: Float) {
    val h = 7.dp.toPx()
    drawLine(
        Color(0xFF6E6E6E),
        Offset(12.dp.toPx(), cy),
        Offset(size.width - 12.dp.toPx(), cy),
        strokeWidth = h,
        cap = androidx.compose.ui.graphics.StrokeCap.Round,
    )
}

/** 5-point star path centered at (cx, cy), top point up — same as the real band. */
private fun previewStarPath(cx: Float, cy: Float, outerR: Float): Path {
    val innerR = outerR * 0.42f
    val p = Path()
    for (k in 0 until 10) {
        val r = if (k % 2 == 0) outerR else innerR
        val a = Math.toRadians(-90.0 + k * 36.0)
        val x = cx + (r * cos(a)).toFloat()
        val y = cy + (r * sin(a)).toFloat()
        if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    return p
}

// ---- Candidate registry (generated from the animation-director workflow) ----------------------
private val previewCandidates: List<Candidate> = listOf(
    Candidate("Radiant Sweep", "A premium light-sweep glides along the bar charging it gold, lighting each star in turn, then blooms upward and pulses once as the three stars twinkle.", { p, b, m -> RadiantSweepCelebration(p, b, m) }),
    Candidate("Halo Resonance", "The unlocked bar breathes out three clean gold-to-cyan halo rings that echo the bar's own rounded shape, rising and dissolving into the video above.", { p, b, m -> HaloResonanceCelebration(p, b, m) }),
    Candidate("Sparkle Fountain", "A dainty champagne-fizz of tiny gold and warm-white glints lifts softly off the bar, twinkles, and winks out — with the three stars blooming once.", { p, b, m -> SparkleFountainCelebration(p, b, m) }),
    Candidate("Golden Ignition", "A warm golden charge sweeps left-to-right along the bar, firing the 3 stars in a 1-2-3 flare, then the bar flashes fully powered-on as a gold-into-violet halo blooms up.", { p, b, m -> GoldenIgnitionCelebration(p, b, m) }),
    Candidate("Silk Ribbon", "A sparse fan of six satin streamers arcs up off the bar, catches the light with a soft twist, then flutters gracefully back down onto the bar.", { p, b, m -> SilkRibbonCelebration(p, b, m) }),
    Candidate("Aurora Veil", "Soft translucent cyan-violet-gold aurora curtains rise off the bar and undulate while a warm highlight washes across lighting each star, then drift up and dissolve.", { p, b, m -> AuroraVeilCelebration(p, b, m) }),
    Candidate("Radiant Flare", "A single cinematic lens-flare blooms off the bar — a warm-white core, a tall gold beam rising into the video, an anamorphic streak, and the three stars twinkling.", { p, b, m -> RadiantFlareCelebration(p, b, m) }),
)
