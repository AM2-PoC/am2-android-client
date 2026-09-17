package com.am2.am2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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

        assertEquals(VoxSensitivity.MAX_PROGRESS, VoxSensitivity.progressFor(0))
        assertEquals(0, VoxSensitivity.progressFor(Int.MAX_VALUE))
    }

    @Test
    fun `the default sits on the bar within one step of itself`() {

        val shown = VoxSensitivity.progressFor(VoxSensitivity.DEFAULT_THRESHOLD)
        val step = (VoxSensitivity.MAX_THRESHOLD - VoxSensitivity.MIN_THRESHOLD) / VoxSensitivity.MAX_PROGRESS
        val drift = Math.abs(VoxSensitivity.thresholdFor(shown) - VoxSensitivity.DEFAULT_THRESHOLD)
        assertTrue("the default lands $drift away from itself, more than one step of $step", drift <= step)
    }

    @Test
    fun `most of the bar travels toward the sensitivity an operator asks for`() {

        val default = VoxSensitivity.progressFor(VoxSensitivity.DEFAULT_THRESHOLD)
        assertTrue(
            "the default sits at $default, leaving ${VoxSensitivity.MAX_PROGRESS - default} " +
                "steps toward a quieter voice and $default away from one",
            default in 40..60,
        )
    }

    @Test
    fun `the steps are proportional, because loudness is`() {

        val loudEnd = VoxSensitivity.thresholdFor(0) - VoxSensitivity.thresholdFor(1)
        val quietEnd = VoxSensitivity.thresholdFor(VoxSensitivity.MAX_PROGRESS - 1) -
            VoxSensitivity.thresholdFor(VoxSensitivity.MAX_PROGRESS)
        assertTrue(
            "one step is $loudEnd at the loud end and $quietEnd at the quiet end; " +
                "equal steps mean the bar is still linear",
            loudEnd > quietEnd * 4,
        )
    }

    @Test
    fun `the floor is where it was, because nothing has measured below it`() {

        assertEquals(500, VoxSensitivity.MIN_THRESHOLD)
    }
}
