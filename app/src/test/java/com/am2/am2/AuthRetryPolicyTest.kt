package com.am2.am2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRetryPolicyTest {
    @Test
    fun interactiveLoginFailureStaysAuthorizedForCorrection() {
        assertTrue(AuthRetryPolicy.keepAuthorizedSession(hadAuthenticatedSession = false))
    }

    @Test
    fun failedReauthenticationStopsAutomaticReconnectLoop() {
        assertFalse(AuthRetryPolicy.keepAuthorizedSession(hadAuthenticatedSession = true))
    }
}
