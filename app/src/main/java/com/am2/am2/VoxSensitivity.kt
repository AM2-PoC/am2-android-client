package com.am2.am2

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

object VoxSensitivity {

    /** Loud speech only. The bar at its left end. */
    const val MAX_THRESHOLD = 12000

    /** As quiet as VOX will go before room noise starts keying it. */
    const val MIN_THRESHOLD = 500

    /** Where an operator who has never touched the slider starts. */
    const val DEFAULT_THRESHOLD = 2200

    /** The slider's own range, which is also its resolution. */
    const val MAX_PROGRESS = 100

    /** The whole span, as the ratio the bar walks across in MAX_PROGRESS steps. */
    private val SPAN_RATIO = MIN_THRESHOLD.toDouble() / MAX_THRESHOLD

    /** Slider position to the amplitude VOX compares each frame against. */
    fun thresholdFor(progress: Int): Int {
        val position = progress.coerceIn(0, MAX_PROGRESS).toDouble() / MAX_PROGRESS
        return (MAX_THRESHOLD * SPAN_RATIO.pow(position)).roundToInt()
    }

    /** The stored amplitude back to a slider position. */
    fun progressFor(threshold: Int): Int {
        val bounded = threshold.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD).toDouble()
        return (MAX_PROGRESS * ln(bounded / MAX_THRESHOLD) / ln(SPAN_RATIO)).roundToInt()
    }
}
