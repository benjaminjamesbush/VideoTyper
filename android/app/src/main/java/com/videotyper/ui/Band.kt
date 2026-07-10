package com.videotyper.ui

/**
 * Geometry of the scrub-bar band passed to every candidate celebration animation, in overlay pixels.
 * leftPx/rightPx are the bar's horizontal ends; centerYPx is the bar's vertical center line (the 3
 * stars sit centered on it); heightPx is the bar's thickness. y increases downward, so "up"/into the
 * video is smaller y. Star centers: x = leftPx + (i+1)/4f*(rightPx-leftPx), y = centerYPx, i in 0..2.
 */
data class Band(
    val leftPx: Float,
    val rightPx: Float,
    val centerYPx: Float,
    val heightPx: Float,
)
