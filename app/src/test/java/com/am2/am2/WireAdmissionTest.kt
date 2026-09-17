package com.am2.am2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WireAdmissionTest {

    @Test
    fun `video is admitted only while the socket is close to drained`() {
        assertTrue(WireAdmission.shouldAdmitVideo(0))
        assertTrue(WireAdmission.shouldAdmitVideo(WireAdmission.VIDEO_QUEUE_BUDGET_BYTES - 1))
    }

    @Test
    fun `video is refused at the budget, not merely above it`() {

        assertFalse(WireAdmission.shouldAdmitVideo(WireAdmission.VIDEO_QUEUE_BUDGET_BYTES))
        assertFalse(WireAdmission.shouldAdmitVideo(WireAdmission.VIDEO_QUEUE_BUDGET_BYTES + 1))
    }

    @Test
    fun `no backlog can make video admissible again`() {

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

        assertTrue(WireAdmission.VIDEO_PRESSURE_BYTES < WireAdmission.VIDEO_QUEUE_BUDGET_BYTES)
        assertEquals(WireAdmission.Pressure.HEAVY,
            WireAdmission.videoPressure(WireAdmission.VIDEO_QUEUE_BUDGET_BYTES - 1))
    }
}
