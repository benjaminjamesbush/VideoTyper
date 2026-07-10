package com.videotyper.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The chosen scrub-bar unlock celebrations. Each is a self-contained, bar-anchored, self-clearing
 * Compose animation (see the individual *Celebration.kt files). [GameController] shuffles a play
 * order once per app launch and advances through it on each unlock; [CelebrationHost] renders the
 * one it picked. [play] should change on every unlock (we pass the unlock tick) to (re)trigger it.
 *
 * Keep [CELEBRATION_COUNT] in sync with the branches below (GameController shuffles ids 0 until it).
 */
const val CELEBRATION_COUNT = 5

@Composable
fun CelebrationHost(id: Int, play: Int, band: Band, modifier: Modifier) {
    when (id) {
        0 -> HaloResonanceCelebration(play, band, modifier)
        1 -> SparkleFountainCelebration(play, band, modifier)
        2 -> GoldenIgnitionCelebration(play, band, modifier)
        3 -> SilkRibbonCelebration(play, band, modifier)
        4 -> AuroraVeilCelebration(play, band, modifier)
    }
}
