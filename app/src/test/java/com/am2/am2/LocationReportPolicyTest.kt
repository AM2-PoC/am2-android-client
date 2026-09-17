package com.am2.am2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationReportPolicyTest {

    @Test
    fun `a unit that has barely moved does not spend a message`() {
        assertFalse(LocationReportPolicy.shouldSend(distanceMeters = 5f))
        assertFalse(LocationReportPolicy.shouldSend(distanceMeters = 24f))
    }

    @Test
    fun `a unit that has moved far enough to matter reports`() {
        assertTrue(LocationReportPolicy.shouldSend(distanceMeters = 25f))
        assertTrue(LocationReportPolicy.shouldSend(distanceMeters = 120f))
    }

    @Test
    fun `the first fix after a login always goes`() {

        assertTrue(LocationReportPolicy.shouldSend(distanceMeters = 0f, force = true))
        assertTrue(LocationReportPolicy.shouldSend(distanceMeters = 0f, hasPreviousFix = false))
    }

    @Test
    fun `the threshold stays outside a handset's own error`() {

        assertTrue(
            "a threshold inside the fix's error budget makes a parked unit jitter",
            LocationReportPolicy.MIN_MOVE_METERS >= 20f,
        )
        assertTrue(
            "100m was the old value: about seventy seconds at walking pace",
            LocationReportPolicy.MIN_MOVE_METERS < 100f,
        )
    }

    @Test
    fun `a parked unit is confirmed while the panel still calls it fresh`() {

        assertTrue(
            "the heartbeat lands outside the window the panel calls fresh",
            LocationReportPolicy.HEARTBEAT_MS < LocationReportPolicy.PANEL_FRESH_MS,
        )
        assertTrue(
            "no margin for a late message",
            LocationReportPolicy.PANEL_FRESH_MS - LocationReportPolicy.HEARTBEAT_MS >= 10_000L,
        )
    }
}
