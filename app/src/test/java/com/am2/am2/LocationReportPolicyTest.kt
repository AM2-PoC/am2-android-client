package com.am2.am2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The map was not wrong about where anyone was. It was wrong about whether
 * anyone was still there, because a unit that stopped moving stopped speaking.
 */
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
        // The panel has nothing at all for this unit yet, and there is no
        // previous position to measure against.
        assertTrue(LocationReportPolicy.shouldSend(distanceMeters = 0f, force = true))
        assertTrue(LocationReportPolicy.shouldSend(distanceMeters = 0f, hasPreviousFix = false))
    }

    @Test
    fun `the threshold stays outside a handset's own error`() {
        // Below roughly this, the marker wanders while the unit stands still,
        // which reads worse than lag.
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
        /*
         * The panel's own threshold, restated here so the two cannot drift
         * apart silently. A heartbeat at or past it means a stationary unit
         * flickers between fresh and delayed on the map for no reason.
         */
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
