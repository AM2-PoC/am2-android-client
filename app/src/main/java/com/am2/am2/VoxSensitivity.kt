package com.am2.am2

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The slider the operator moves, and the amplitude VOX compares against.
 *
 * These are inverses: a fuller bar means VOX keys on a quieter voice, which is
 * a *smaller* threshold. Getting that backwards would leave the control working
 * in the wrong direction — a setting that appears to respond, and makes the
 * radio deafer the further right you push it.
 *
 * The direction was right and the *curve* was wrong, which reads the same from
 * the operator's seat. Mapped linearly from 500 to 12000, the default of 2200
 * sat at position 85 of 100: eighty-five steps made VOX deafer and fifteen made
 * it keener. For a control whose only reported fault is "not sensitive enough",
 * almost all of its travel went the other way, and the fifteen steps that
 * remained were the whole answer.
 *
 * The steps are proportional now. Doubling a quiet sound and doubling a loud
 * one are the same perceptual step, so the bar moves by ratio rather than by
 * difference — which is also why a linear map spent most of itself between
 * "shout" and "loud speech", a range nobody speaks in. The same default now
 * sits near the middle with real travel on both sides.
 *
 * The floor is deliberately unchanged. Lowering it is the change that could
 * make VOX key on room noise, and no amplitude any handset has actually
 * reported has been recorded anywhere yet — the reachable range here is exactly
 * the range that was reachable before. Only its distribution across the bar
 * moved.
 *
 * Kept out of the settings screen and off AudioRecorder because both are
 * unreachable from a JVM test: AudioRecorder initialises MIN_BUFFER_SIZE from
 * `AudioRecord.getMinBufferSize`, and touching it under unit test throws. This
 * is arithmetic, so it belongs somewhere arithmetic can be checked.
 */
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
