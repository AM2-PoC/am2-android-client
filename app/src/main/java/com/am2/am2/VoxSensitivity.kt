package com.am2.am2

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

object VoxSensitivity {

    const val MAX_THRESHOLD = 12000

    const val MIN_THRESHOLD = 500

    const val DEFAULT_THRESHOLD = 2200

    const val MAX_PROGRESS = 100

    private val SPAN_RATIO = MIN_THRESHOLD.toDouble() / MAX_THRESHOLD

    fun thresholdFor(progress: Int): Int {
        val position = progress.coerceIn(0, MAX_PROGRESS).toDouble() / MAX_PROGRESS
        return (MAX_THRESHOLD * SPAN_RATIO.pow(position)).roundToInt()
    }

    fun progressFor(threshold: Int): Int {
        val bounded = threshold.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD).toDouble()
        return (MAX_PROGRESS * ln(bounded / MAX_THRESHOLD) / ln(SPAN_RATIO)).roundToInt()
    }
}
