package com.am2.am2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {
    @Test
    fun authorizedSessionReconnectsAfterAbnormalClose() {
        assertTrue(ReconnectPolicy.shouldReconnect(
            isAuthorized = true, transportWanted = false, closeCode = 1006))
    }

    @Test
    fun authorizedSessionReconnectsAfterNormalServerClose() {
        assertTrue(ReconnectPolicy.shouldReconnect(
            isAuthorized = true, transportWanted = false, closeCode = 1000))
    }

    @Test
    fun loggedOutSessionNeverReconnects() {
        assertFalse(ReconnectPolicy.shouldReconnect(
            isAuthorized = false, transportWanted = false, closeCode = 1006))
        assertFalse(ReconnectPolicy.shouldReconnect(
            isAuthorized = false, transportWanted = false, closeCode = 1000))
    }

    @Test
    fun aScreenWaitingForTheRelayReconnectsWithoutASession() {
        // The login screen, after the phone has slept. There is nothing to
        // resume and the socket is still needed.
        assertTrue(ReconnectPolicy.shouldReconnect(
            isAuthorized = false, transportWanted = true, closeCode = 1006))
        assertTrue(ReconnectPolicy.shouldReconnect(
            isAuthorized = false, transportWanted = true, closeCode = 1000))
    }

    @Test
    fun nothingWantingItAndNothingToResumeStaysDown() {
        assertFalse(ReconnectPolicy.shouldReconnect(
            isAuthorized = false, transportWanted = false, closeCode = 1001))
    }
}
