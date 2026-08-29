package com.am2.am2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoredCredentialStateTest {
    @Test
    fun usernameAndTokenRestoreAnAuthorizedSessionWithoutAPassword() {
        val state = StoredCredentialState(
            username = "UNIT01",
            password = null,
            token = "device-token",
        )

        assertTrue(state.canResume)
    }

    @Test
    fun usernameAndPasswordRemainCompatibleWithARelayThatIssuesNoToken() {
        val state = StoredCredentialState(
            username = "UNIT01",
            password = "password",
            token = null,
        )

        assertTrue(state.canResume)
    }

    @Test
    fun aCredentialWithoutAnIdentityCannotResume() {
        val state = StoredCredentialState(
            username = null,
            password = null,
            token = "device-token",
        )

        assertFalse(state.canResume)
    }
}
