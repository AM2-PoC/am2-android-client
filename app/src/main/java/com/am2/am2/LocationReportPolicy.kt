package com.am2.am2

/**
 * When a position is worth sending.
 *
 * Two questions, and they used to be answered in two different files by
 * accident. Android is asked for fixes with a displacement filter, and the
 * manager then dropped anything under a hundred metres from the last position
 * it had sent. Stacked, that meant a report reached the relay only after a
 * hundred metres of travel -- roughly seventy seconds at walking pace, and
 * never at all while parked.
 *
 * Live Track grades a unit by how old its position is, so a unit that had
 * simply stopped moving was painted as lost about five minutes later. Measured
 * on production: five of six online units were showing positions between seven
 * and thirty-one minutes old, every one of them in exactly the right place. The
 * map was not wrong about where anyone was; it was wrong about whether anyone
 * was still there.
 *
 * So the rule is both halves together: send when we have moved far enough to
 * matter, and otherwise say again where we already are, often enough that
 * silence means something is wrong rather than that nothing has changed.
 */
object LocationReportPolicy {

    /**
     * How far a unit must move before a new position earns a message.
     *
     * Not lower than this. A handset fix is commonly good to somewhere between
     * ten and fifty metres, and a threshold inside that error budget makes a
     * stationary marker wander around the map, which reads worse than lag.
     */
    const val MIN_MOVE_METERS = 25f

    /**
     * How often a unit that has not moved re-confirms its position.
     *
     * Coupled to the panel. get-users-ajax.php calls a position fresh under
     * sixty seconds old, delayed to five minutes, and stale after that; the
     * heartbeat has to land inside the fresh window with room for a late
     * message, or a parked unit flickers between fresh and delayed on the map.
     * Move one of these two numbers and the other has to move with it, which is
     * why the panel's threshold is written down here rather than left implied.
     */
    const val HEARTBEAT_MS = 45_000L
    const val PANEL_FRESH_MS = 60_000L

    /**
     * Whether a fix that has just arrived is worth a message.
     *
     * `force` is the first fix after a login: there is nothing to compare it
     * against and the panel has nothing at all, so it always goes.
     */
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
