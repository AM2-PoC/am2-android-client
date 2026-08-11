package com.am2.am2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {
    @Test
    fun authorizedSessionReconnectsAfterAbnormalClose() {
        assertTrue(ReconnectPolicy.shouldReconnect(isAuthorized = true, closeCode = 1006))
    }

    @Test
    fun authorizedSessionReconnectsAfterNormalServerClose() {
        assertTrue(ReconnectPolicy.shouldReconnect(isAuthorized = true, closeCode = 1000))
    }

    @Test
    fun loggedOutSessionNeverReconnects() {
        assertFalse(ReconnectPolicy.shouldReconnect(isAuthorized = false, closeCode = 1006))
        assertFalse(ReconnectPolicy.shouldReconnect(isAuthorized = false, closeCode = 1000))
    }
}
