package com.am2.am2

/**
 * The slider the operator moves, and the amplitude VOX compares against.
 *
 * These are inverses: a fuller bar means VOX keys on a quieter voice, which is
 * a *smaller* threshold. Getting that backwards would leave the control working
 * in the wrong direction — a setting that appears to respond, and makes the
 * radio deafer the further right you push it.
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

    private const val SPAN = MAX_THRESHOLD - MIN_THRESHOLD

    /** Slider position to the amplitude VOX compares each frame against. */
    fun thresholdFor(progress: Int): Int =
        MAX_THRESHOLD - (progress.coerceIn(0, MAX_PROGRESS) * SPAN / MAX_PROGRESS)

    /** The stored amplitude back to a slider position. */
    fun progressFor(threshold: Int): Int =
        (MAX_THRESHOLD - threshold.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)) * MAX_PROGRESS / SPAN
}
