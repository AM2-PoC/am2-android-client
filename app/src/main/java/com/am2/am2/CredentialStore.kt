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
        get() = !username.isNullOrEmpty() &&
            (!token.isNullOrEmpty() || !password.isNullOrEmpty())
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
            val state = StoredCredentialState(
                username = store.getString(USER, null),
                password = store.getString(PASS, null),
                token = store.getString(TOKEN, null),
            )
            if (state.canResume) return state
        }
        if (encrypted == null && secureUnavailable(context)) {
            return StoredCredentialState(null, null, null)
        }

        val fallback = plain(context)
        val plainState = StoredCredentialState(
            username = fallback.getString(USER, null),
            password = fallback.getString(PASS, null),
            token = fallback.getString(TOKEN, null),
        )
        if (plainState.canResume) return plainState

        readLegacyFile(context)?.let { (user, pass) ->
            return StoredCredentialState(user, pass, null)
        }
        return StoredCredentialState(null, null, null)
    }

    /** Read one complete record, migrate it, and delete every obsolete copy. */
    @Synchronized
    fun state(context: Context): StoredCredentialState {
        if (contexts(context).any { plain(it).getBoolean(SESSION_BLOCKED, false) }) {
            return StoredCredentialState(null, null, null)
        }
        if (secureUnavailable(canonical(context))) {
            return StoredCredentialState(null, null, null)
        }
        val source = contexts(context)
            .asSequence()
            .map(::readRecord)
            .firstOrNull { it.canResume }
            ?: StoredCredentialState(null, null, null)

        if (source.canResume && needsMigration(context, source)) {
            if (writeCanonical(context, source)) clearObsolete(context)
        }
        return source
    }


    @Synchronized
    fun save(context: Context, user: String, pass: String): Boolean {
        val saved = writeCanonical(context, StoredCredentialState(user, pass, null))
        if (saved) {
            clearObsolete(context)
        }
        return saved
    }

    @Synchronized
    fun saveToken(context: Context, token: String, username: String): Boolean {
        val saved = writeCanonical(context, StoredCredentialState(username, null, token))
        if (saved) {
            clearObsolete(context)
        }
        return saved
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

    private fun writeCanonical(context: Context, state: StoredCredentialState): Boolean {
        val target = canonical(context)
        val store = secure(target)
        if (store != null) {
            val edit = store.edit()
                .putString(USER, state.username)
                .remove(PASS)
                .remove(TOKEN)
            state.password?.let { edit.putString(PASS, it) }
            state.token?.let { edit.putString(TOKEN, it) }
            if (!edit.commit()) return false
            return plain(target).edit()
                .remove(USER).remove(PASS).remove(TOKEN).remove(SESSION_BLOCKED).commit()
        } else {
            val edit = plain(target).edit()
                .putString(USER, state.username)
                .remove(PASS)
                .remove(TOKEN)
                .remove(SESSION_BLOCKED)
            state.password?.let { edit.putString(PASS, it) }
            state.token?.let { edit.putString(TOKEN, it) }
            return edit.commit()
        }
    }

    private fun clearObsolete(context: Context) {
        contexts(context).drop(1).forEach { old ->
            secure(old)?.edit()?.remove(USER)?.remove(PASS)?.remove(TOKEN)?.commit()
            plain(old).edit()
                .remove(USER).remove(PASS).remove(TOKEN).remove(SESSION_BLOCKED).commit()
        }
        contexts(context).forEach(::deleteLegacyFile)
    }

    private fun needsMigration(context: Context, state: StoredCredentialState): Boolean {
        val target = canonical(context)
        if (readRecord(target) != state) return true
        return contexts(context).drop(1).any { old ->
            readRecord(old).canResume || readLegacyFile(old) != null
        } || readLegacyFile(target) != null
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
