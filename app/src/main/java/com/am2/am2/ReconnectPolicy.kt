package com.am2.am2

/**
 * Whether a socket that just ended should be brought back.
 *
 * Two different questions were being answered by one flag. "Should this
 * handset resume its session" is about authorisation. "Should the transport be
 * up at all" is about whether anything is waiting to use it -- and the login
 * screen is waiting for it before any session exists.
 *
 * Gating both on authorisation meant the login screen lost its socket the
 * first time the phone slept and never got it back: it went on reporting
 * "Server Offline" against a relay that was up, and only recovered because
 * signing in calls connect() directly.
 */
internal object ReconnectPolicy {
    fun shouldReconnect(
        isAuthorized: Boolean,
        transportWanted: Boolean,
        closeCode: Int,
    ): Boolean {
        return isAuthorized || transportWanted
    }
}
