package com.am2.am2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audio must never be the thing that waits.
 *
 * These are the arithmetic of the drop policy, checked directly rather than
 * inferred from the socket, because the failure they prevent — audio delay that
 * grows for as long as someone keeps talking — is invisible until a user is
 * already complaining about it.
 */
class WireAdmissionTest {

    @Test
    fun `video is admitted only while the socket is close to drained`() {
        assertTrue(WireAdmission.shouldAdmitVideo(0))
        assertTrue(WireAdmission.shouldAdmitVideo(WireAdmission.VIDEO_QUEUE_BUDGET_BYTES - 1))
    }

    @Test
    fun `video is refused at the budget, not merely above it`() {
        // An off-by-one here is a frame's worth of audio delay, every time.
        assertFalse(WireAdmission.shouldAdmitVideo(WireAdmission.VIDEO_QUEUE_BUDGET_BYTES))
        assertFalse(WireAdmission.shouldAdmitVideo(WireAdmission.VIDEO_QUEUE_BUDGET_BYTES + 1))
    }

    @Test
    fun `no backlog can make video admissible again`() {
        // Monotonic: more queued must never mean more permissive. A policy that
        // reopened under load would be worse than none.
        var previous = true
        var queued = 0L
        while (queued <= WireAdmission.VIDEO_QUEUE_BUDGET_BYTES * 4) {
            val admitted = WireAdmission.shouldAdmitVideo(queued)
            assertFalse("admission reopened at $queued bytes", admitted && !previous)
            previous = admitted
            queued += 512
        }
    }

    @Test
    fun `the budget is about one frame, not a buffer`() {
        // Big enough to keep a clear link busy, small enough that at most one
        // video frame sits ahead of an audio frame. A budget in the hundreds of
        // kilobytes would be a queue by another name.
        assertTrue(WireAdmission.VIDEO_QUEUE_BUDGET_BYTES in 8_000..64_000)
    }

    @Test
    fun `pressure rises with the backlog and never falls back`() {
        assertEquals(WireAdmission.Pressure.CLEAR, WireAdmission.videoPressure(0))
        assertEquals(WireAdmission.Pressure.HEAVY,
            WireAdmission.videoPressure(WireAdmission.VIDEO_PRESSURE_BYTES))
        assertEquals(WireAdmission.Pressure.BLOCKED,
            WireAdmission.videoPressure(WireAdmission.VIDEO_QUEUE_BUDGET_BYTES))
        assertEquals(WireAdmission.Pressure.BLOCKED,
            WireAdmission.videoPressure(WireAdmission.VIDEO_QUEUE_BUDGET_BYTES * 10))
    }

    @Test
    fun `video lowers its cost before it is refused outright`() {
        // There has to be a range where video degrades rather than disappears,
        // or a weak uplink turns the picture on and off instead of softening it.
        assertTrue(WireAdmission.VIDEO_PRESSURE_BYTES < WireAdmission.VIDEO_QUEUE_BUDGET_BYTES)
        assertEquals(WireAdmission.Pressure.HEAVY,
            WireAdmission.videoPressure(WireAdmission.VIDEO_QUEUE_BUDGET_BYTES - 1))
    }
}
