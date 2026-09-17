package com.am2.am2

object LocationReportPolicy {

    const val MIN_MOVE_METERS = 25f

    const val HEARTBEAT_MS = 45_000L
    const val PANEL_FRESH_MS = 60_000L

    fun shouldSend(
        distanceMeters: Float,
        force: Boolean = false,
        hasPreviousFix: Boolean = true,
    ): Boolean {
        if (force) return true
        if (!hasPreviousFix) return true
        return distanceMeters >= MIN_MOVE_METERS
    }
}
