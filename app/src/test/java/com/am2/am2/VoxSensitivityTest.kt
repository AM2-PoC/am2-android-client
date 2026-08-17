package com.am2.am2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A control that responds while pointing the wrong way is worse than none.
 *
 * Sensitivity and threshold run opposite to each other, and the conversion is
 * integer arithmetic in both directions, so a sign or an operand order that is
 * wrong still returns plausible numbers. Nothing about that shows up in source
 * review, and on a handset it reads as "VOX is just bad in this room" rather
 * than as a bug.
 */
class VoxSensitivityTest {

    @Test
    fun `a fuller bar means a quieter voice will key it`() {
        assertTrue(
            "sensitivity is inverted: pushing the slider right made VOX deafer",
            VoxSensitivity.thresholdFor(100) < VoxSensitivity.thresholdFor(0),
        )
    }

    @Test
    fun `the ends of the bar are the ends of the range`() {
        assertEquals(VoxSensitivity.MIN_THRESHOLD, VoxSensitivity.thresholdFor(100))
        assertEquals(VoxSensitivity.MAX_THRESHOLD, VoxSensitivity.thresholdFor(0))
    }

    @Test
    fun `every position on the bar survives the round trip`() {
        // Exhaustive, because it is only 101 values and because integer
        // division is exactly where a mapping like this loses a step: reopen
        // the settings screen and the slider would sit somewhere other than
        // where it was left.
        for (progress in 0..VoxSensitivity.MAX_PROGRESS) {
            val threshold = VoxSensitivity.thresholdFor(progress)
            assertEquals(
                "position $progress came back as a different position",
                progress,
                VoxSensitivity.progressFor(threshold),
            )
        }
    }

    @Test
    fun `a position off either end of the bar is clamped, not wrapped`() {
        assertEquals(VoxSensitivity.MAX_THRESHOLD, VoxSensitivity.thresholdFor(-5))
        assertEquals(VoxSensitivity.MIN_THRESHOLD, VoxSensitivity.thresholdFor(1000))
    }

    @Test
    fun `a stored threshold from outside the range still lands on the bar`() {
        // Nothing writes these today. A future default, or a hand-edited
        // preference, must not put the slider past its own ends.
        assertEquals(VoxSensitivity.MAX_PROGRESS, VoxSensitivity.progressFor(0))
        assertEquals(0, VoxSensitivity.progressFor(Int.MAX_VALUE))
    }

    @Test
    fun `the default sits on the bar within one step of itself`() {
        // 2200 is not on a step boundary, so it shows as the nearest position
        // and would be rewritten slightly if the operator touches the slider.
        // That is quantisation and it does not accumulate -- the bar is always
        // derived from the stored value, never from the last bar position --
        // but it should stay within one step.
        val shown = VoxSensitivity.progressFor(VoxSensitivity.DEFAULT_THRESHOLD)
        val step = (VoxSensitivity.MAX_THRESHOLD - VoxSensitivity.MIN_THRESHOLD) / VoxSensitivity.MAX_PROGRESS
        val drift = Math.abs(VoxSensitivity.thresholdFor(shown) - VoxSensitivity.DEFAULT_THRESHOLD)
        assertTrue("the default lands $drift away from itself, more than one step of $step", drift <= step)
    }
}
