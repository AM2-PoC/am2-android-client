package com.am2.am2

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.am2.am2.logging.SafeLog
import java.io.File

data class StoredCredentialState(
    val username: String?,
    val password: String?,
    val token: String?,
) {
    val canResume: Boolean
        get() = !username.isNullOrEmpty() && !token.isNullOrEmpty()
}

/**
 * One coherent persisted login. The canonical store is credential-protected;
 * old device-protected and plain copies are adopted once and removed.
 */
object CredentialStore {
    private const val USER = "username"
    private const val PASS = "password"
    private const val TOKEN = "device_token"
    private const val SESSION_BLOCKED = "session_blocked"
    private const val LEGACY_FILE = "cred.txt"

    private fun secure(context: Context): SharedPreferences? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            SecureCredentialStore.open(context)
        } else {
            null
        }

    private fun plain(context: Context): SharedPreferences =
        context.getSharedPreferences(WebSocketManager.PREFS_NAME, Context.MODE_PRIVATE)

    private fun contexts(context: Context): List<Context> {
        val application = context.applicationContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return listOf(application)

        val protected = application.createDeviceProtectedStorageContext()
        return listOf(application, protected).distinctBy { it.filesDir.absolutePath }
    }

    private fun canonical(context: Context): Context = contexts(context).first()

    private fun secureFile(context: Context): File =
        File(context.filesDir.parentFile, "shared_prefs/am2_credentials.xml")

    private fun secureBackupFile(context: Context): File =
        File(context.filesDir.parentFile, "shared_prefs/am2_credentials.xml.bak")

    private fun secureUnavailable(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            secure(context) == null &&
            (secureFile(context).exists() || secureBackupFile(context).exists())

    private fun readRecord(context: Context): StoredCredentialState {
        val encrypted = secure(context)
        encrypted?.let { store ->
            val state = try {
                StoredCredentialState(
                    username = store.getString(USER, null),
                    password = null,
                    token = store.getString(TOKEN, null),
                )
            } catch (_: Exception) {
                return StoredCredentialState(null, null, null)
            }
            if (state.canResume) return state
        }
        if (encrypted == null && secureUnavailable(context)) {
            return StoredCredentialState(null, null, null)
        }

        val fallback = plain(context)
        val plainState = try {
            StoredCredentialState(
                username = fallback.getString(USER, null),
                password = null,
                token = fallback.getString(TOKEN, null),
            )
        } catch (_: Exception) {
            return StoredCredentialState(null, null, null)
        }
        if (plainState.canResume) return plainState

        readLegacyFile(context)?.let { (user, _) ->
            // Legacy password material is removed below; it is never a
            // resumable credential. Keep only the identity for the login UI.
            return StoredCredentialState(user, null, null)
        }
        return StoredCredentialState(null, null, null)
    }

    /** Read one complete record, migrate it, and delete every obsolete copy. */
    @Synchronized
    fun state(context: Context): StoredCredentialState {
        if (contexts(context).any { plain(it).getBoolean(SESSION_BLOCKED, false) }) {
            return StoredCredentialState(null, null, null)
        }
        val target = canonical(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && secure(target) == null) {
            // Modern Android has no plaintext credential mode. Delete every
            // readable/plain copy and the unreadable encrypted file while
            // retaining SESSION_BLOCKED, so old password material does not
            // merely become dormant on disk.
            clear(context)
            return StoredCredentialState(null, null, null)
        }
        val source = contexts(context)
            .asSequence()
            .map(::readRecord)
            .firstOrNull { it.canResume }
            ?: StoredCredentialState(null, null, null)
        val hasPasswordMaterial = contexts(context).any(::hasPasswordMaterial)
        if (!source.canResume && hasPasswordMaterial) {
            clear(context)
            return StoredCredentialState(null, null, null)
        }

        if (source.canResume && (hasPasswordMaterial || needsMigration(context, source))) {
            return if (saveToken(context, source.token!!, source.username!!)) {
                source
            } else {
                StoredCredentialState(null, null, null)
            }
        }
        return source
    }


    @Synchronized
    fun saveToken(context: Context, token: String, username: String): Boolean {
        val record = StoredCredentialState(username, null, token)
        return persistRememberedToken(AndroidPersistenceBackend(context), record)
    }


    @Synchronized
    fun clearToken(context: Context): Boolean {
        var cleared = true
        contexts(context).forEach { candidate ->
            cleared = plain(candidate).edit().putBoolean(SESSION_BLOCKED, true).commit() && cleared
            val encrypted = secure(candidate)
            if (encrypted != null) {
                cleared = encrypted.edit().remove(TOKEN).commit() && cleared
            } else if (secureUnavailable(candidate)) {
                cleared = deleteSecureFiles(candidate) && cleared
            }
            cleared = plain(candidate).edit().remove(TOKEN).commit() && cleared
        }
        return cleared
    }

    /** Prevent any rejected persisted credential from resuming after restart. */
    @Synchronized
    fun blockSession(context: Context): Boolean {
        var blocked = true
        contexts(context).forEach { candidate ->
            blocked = plain(candidate).edit()
                .putBoolean(SESSION_BLOCKED, true).commit() && blocked
        }
        return blocked
    }

    /** Logout is durable before the caller may terminate the process. */
    @Synchronized
    fun clear(context: Context): Boolean {
        var cleared = true
        contexts(context).forEach { candidate ->
            cleared = plain(candidate).edit().putBoolean(SESSION_BLOCKED, true).commit() && cleared
            val encrypted = secure(candidate)
            if (encrypted != null) {
                cleared = encrypted.edit()
                    .remove(USER).remove(PASS).remove(TOKEN).commit() && cleared
            } else if (secureUnavailable(candidate)) {
                cleared = deleteSecureFiles(candidate) && cleared
            }
            cleared = plain(candidate).edit()
                .remove(USER).remove(PASS).remove(TOKEN).commit() && cleared
            cleared = deleteLegacyFile(candidate) && cleared
        }
        return cleared
    }

    private class AndroidPersistenceBackend(private val context: Context) :
        CredentialPersistenceBackend {
        private val target = canonical(context)

        override fun setBlocked(): Boolean = blockSession(context)

        override fun writeToken(record: StoredCredentialState): Boolean =
            writeCanonicalBlocked(target, record)

        override fun clearPlaintextCredential(): Boolean = contexts(context).all { candidate ->
            val edit = plain(candidate).edit().remove(PASS)
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ||
                candidate.filesDir.absolutePath != target.filesDir.absolutePath
            ) {
                edit.remove(USER).remove(TOKEN)
            }
            edit.commit()
        }

        override fun clearObsoleteCredentials(): Boolean {
            var cleared = true
            contexts(context).drop(1).forEach { old ->
                secure(old)?.let { store ->
                    cleared = store.edit().remove(USER).remove(PASS).remove(TOKEN).commit() && cleared
                }
                cleared = deleteSecureFiles(old) && cleared
            }
            contexts(context).forEach { candidate ->
                cleared = deleteLegacyFile(candidate) && cleared
            }
            return cleared
        }

        override fun verifyToken(record: StoredCredentialState): Boolean = try {
            readCanonicalToken(target) == record
        } catch (_: Exception) {
            false
        }

        override fun unblock(): Boolean = contexts(context).all { candidate ->
            plain(candidate).edit().remove(SESSION_BLOCKED).commit()
        }
    }

    private fun writeCanonicalBlocked(
        target: Context,
        state: StoredCredentialState,
    ): Boolean {
        val store = secure(target)
        if (store != null) {
            return store.edit()
                .putString(USER, state.username)
                .remove(PASS)
                .putString(TOKEN, state.token)
                .commit()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) return false
        return plain(target).edit()
            .putString(USER, state.username)
            .remove(PASS)
            .putString(TOKEN, state.token)
            .commit()
    }

    private fun readCanonicalToken(target: Context): StoredCredentialState? = try {
        val store = secure(target)
        if (store != null) {
            StoredCredentialState(
                store.getString(USER, null),
                null,
                store.getString(TOKEN, null),
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            null
        } else {
            StoredCredentialState(
                plain(target).getString(USER, null),
                null,
                plain(target).getString(TOKEN, null),
            )
        }
    } catch (_: Exception) {
        null
    }

    private fun needsMigration(context: Context, state: StoredCredentialState): Boolean {
        val target = canonical(context)
        if (readCanonicalToken(target) != state) return true
        return contexts(context).drop(1).any { old ->
            readRecord(old).canResume || readLegacyFile(old) != null
        } || readLegacyFile(target) != null
    }

    private fun hasPasswordMaterial(context: Context): Boolean {
        val plainHasPassword = try {
            plain(context).contains(PASS)
        } catch (_: Exception) {
            true
        }
        val encryptedHasPassword = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val store = secure(context)
            if (store != null) {
                try {
                    store.contains(PASS)
                } catch (_: Exception) {
                    true
                }
            } else {
                secureFile(context).exists() || secureBackupFile(context).exists()
            }
        } else {
            false
        }
        return plainHasPassword || encryptedHasPassword ||
            File(context.filesDir, LEGACY_FILE).exists()
    }

    private fun deleteSecureFiles(context: Context): Boolean {
        var removed = true
        for (file in listOf(secureFile(context), secureBackupFile(context))) {
            if (file.exists() && !file.delete()) removed = false
        }
        return removed
    }

    private fun readLegacyFile(context: Context): Pair<String, String>? = try {
        val file = File(context.filesDir, LEGACY_FILE)
        if (!file.exists()) null else {
            val parts = file.readText().split("|")
            if (parts.size >= 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) {
                parts[0] to parts[1]
            } else null
        }
    } catch (_: Exception) {
        null
    }

    private fun deleteLegacyFile(context: Context): Boolean {
        try {
            val file = File(context.filesDir, LEGACY_FILE)
            if (file.exists() && !file.delete()) {
                SafeLog.w("CredentialStore", "the legacy credential file could not be removed")
                return false
            }
        } catch (_: Exception) {
            SafeLog.w("CredentialStore", "the legacy credential file could not be removed")
            return false
        }
        return true
    }
}
