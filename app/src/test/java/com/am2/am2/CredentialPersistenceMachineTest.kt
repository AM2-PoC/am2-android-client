package com.am2.am2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialPersistenceMachineTest {
    private class MemoryBackend(
        var secureAvailable: Boolean = true,
        var failAt: Int? = null,
    ) : CredentialPersistenceBackend {
        var blocked = false
        var secureRecord: StoredCredentialState? = null
        var plainRecord: StoredCredentialState? = null
        var obsoleteCleared = false
        private var operation = 0

        private fun succeeds(): Boolean {
            operation += 1
            return failAt != operation
        }

        override fun setBlocked(): Boolean = succeeds().also { if (it) blocked = true }

        override fun writeToken(record: StoredCredentialState): Boolean {
            if (!secureAvailable || !succeeds()) return false
            secureRecord = record
            return true
        }

        override fun clearPlaintextCredential(): Boolean = succeeds().also {
            if (it) plainRecord = null
        }

        override fun clearObsoleteCredentials(): Boolean = succeeds().also {
            if (it) obsoleteCleared = true
        }

        override fun verifyToken(record: StoredCredentialState): Boolean =
            succeeds() && secureRecord == record

        override fun unblock(): Boolean = succeeds().also { if (it) blocked = false }
    }

    @Test
    fun modernSecureStoreFailureNeverFallsBackToPlaintext() {
        val backend = MemoryBackend(secureAvailable = false).apply {
            plainRecord = StoredCredentialState("old", "password", null)
        }

        assertFalse(
            persistRememberedToken(
                backend,
                StoredCredentialState("UNIT01", null, "token"),
            ),
        )
        assertTrue(backend.blocked)
        assertEquals("password", backend.plainRecord?.password)
        assertNull(backend.secureRecord)
    }

    @Test
    fun successfulTokenSaveUnblocksOnlyAfterVerificationAndCleanup() {
        val backend = MemoryBackend().apply {
            plainRecord = StoredCredentialState("UNIT01", "password", null)
        }
        val token = StoredCredentialState("UNIT01", null, "token")

        assertTrue(persistRememberedToken(backend, token))
        assertEquals(token, backend.secureRecord)
        assertNull(backend.plainRecord)
        assertTrue(backend.obsoleteCleared)
        assertFalse(backend.blocked)
    }

    @Test
    fun interruptionAtEveryPreCommitStepRemainsBlocked() {
        for (failure in 1..6) {
            val backend = MemoryBackend(failAt = failure)
            val result = persistRememberedToken(
                backend,
                StoredCredentialState("UNIT01", null, "token"),
            )

            assertFalse("failure at operation $failure must not commit", result)
            if (failure > 1) {
                assertTrue("failure at operation $failure resurrected the session", backend.blocked)
            }
        }
    }
}
