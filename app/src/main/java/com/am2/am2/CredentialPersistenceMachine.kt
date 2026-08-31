package com.am2.am2

/** Storage operations required by the fail-closed remembered-token transaction. */
internal interface CredentialPersistenceBackend {
    fun setBlocked(): Boolean
    fun writeToken(record: StoredCredentialState): Boolean
    fun clearPlaintextCredential(): Boolean
    fun clearObsoleteCredentials(): Boolean
    fun verifyToken(record: StoredCredentialState): Boolean
    fun unblock(): Boolean
}

/**
 * The marker is the commit record: every interruption before the final step is
 * non-resumable, even when the encrypted record was already written.
 */
internal fun persistRememberedToken(
    backend: CredentialPersistenceBackend,
    record: StoredCredentialState,
): Boolean {
    if (!record.canResume || record.token.isNullOrEmpty() || record.password != null) return false
    if (!backend.setBlocked()) return false
    if (!backend.writeToken(record)) return false
    if (!backend.clearPlaintextCredential()) return false
    if (!backend.clearObsoleteCredentials()) return false
    if (!backend.verifyToken(record)) return false
    return backend.unblock()
}
