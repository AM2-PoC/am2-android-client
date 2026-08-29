package com.am2.am2

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.am2.am2.logging.SafeLog
import java.io.File

/**
 * Where an operator's password lives, and where it stops living.
 *
 * It was in two places, both readable by anything that could read the files:
 * a cleartext `password` in SharedPreferences, and `filesDir/cred.txt` holding
 * `username|password`. The second was written as a "backup" of the first,
 * which doubled the exposure and halved nothing.
 *
 * minSdk here is 16 and EncryptedSharedPreferences needs 23, so this splits the
 * way TLS already does in this application: the modern path gets the platform
 * keystore, the legacy path keeps what it had, and the boundary is written down
 * rather than implied. Below 23 there is no keystore able to hold a symmetric
 * key, and pretending otherwise would be worse than saying so.
 *
 * What does not split: cred.txt is deleted on every handset at every API level.
 * It is redundant with the preferences and strictly the worse of the two, and
 * handsets already carry one -- so merely not writing it again would leave
 * every existing device exactly as exposed as before.
 *
 * The real answer is not to hold a reusable password at all. A token the relay
 * issues can be revoked when a handset is lost; a password cannot, and it is
 * the same password everywhere else the operator uses it. That change reaches
 * the relay and is deliberately not this one.
 */
object CredentialStore {

    private const val USER = "username"
    private const val PASS = "password"
    private const val TOKEN = "device_token"
    private const val LEGACY_FILE = "cred.txt"

    /** Only ever consulted through this, so the version check has one home. */
    private fun secure(context: Context): SharedPreferences? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            SecureCredentialStore.open(context)
        } else {
            null
        }

    private fun plain(context: Context): SharedPreferences =
        context.getSharedPreferences(WebSocketManager.PREFS_NAME, Context.MODE_PRIVATE)

    fun save(context: Context, user: String, pass: String) {
        val store = secure(context)
        if (store != null) {
            store.edit().putString(USER, user).putString(PASS, pass).apply()
            // The cleartext copies this replaces, not merely stopped.
            plain(context).edit().remove(USER).remove(PASS).apply()
        } else {
            plain(context).edit().putString(USER, user).putString(PASS, pass).apply()
        }
        deleteLegacyFile(context)
    }

    /**
     * The credentials, wherever this handset last put them.
     *
     * A handset upgrading into this code has them in cleartext, so the first
     * read adopts what it finds and writes it back through save() -- which
     * seals it where it can be sealed and removes the copies either way.
     * Nobody signs in again because of this change.
     */
    fun load(context: Context): Pair<String, String>? {
        secure(context)?.let { store ->
            val user = store.getString(USER, null)
            val pass = store.getString(PASS, null)
            if (!user.isNullOrEmpty() && !pass.isNullOrEmpty()) return user to pass
        }

        val prefs = plain(context)
        var user = prefs.getString(USER, null)
        var pass = prefs.getString(PASS, null)

        if (user.isNullOrEmpty() || pass.isNullOrEmpty()) {
            readLegacyFile(context)?.let { (u, p) -> user = u; pass = p }
        }

        val foundUser = user
        val foundPass = pass
        if (foundUser.isNullOrEmpty() || foundPass.isNullOrEmpty()) {
            deleteLegacyFile(context)
            return null
        }

        save(context, foundUser, foundPass)
        return foundUser to foundPass
    }

    /**
     * The credential this handset keeps once it has one.
     *
     * A device token, issued by the relay and revocable there: a lost handset
     * is a row an admin deletes. The password it replaces was the operator's
     * own, worked from any device, and could only be withdrawn by changing it
     * for the person. Sealed on API 23 and above like everything else here, and
     * on the handsets below that it is still worth having -- unencrypted, but
     * revocable, which the password never was.
     */
    fun saveToken(context: Context, token: String) {
        val store = secure(context)
        if (store != null) {
            store.edit().putString(TOKEN, token).apply()
        } else {
            plain(context).edit().putString(TOKEN, token).apply()
        }
        // The password has done its one job. Nothing on this handset needs it
        // again, and keeping it would mean this change had removed nothing.
        forgetPassword(context)
    }

    fun token(context: Context): String? =
        secure(context)?.getString(TOKEN, null)
            ?: plain(context).getString(TOKEN, null)

    /** After a revocation: the token is worthless and the password is gone. */
    fun clearToken(context: Context) {
        secure(context)?.edit()?.remove(TOKEN)?.apply()
        plain(context).edit().remove(TOKEN).apply()
    }

    private fun forgetPassword(context: Context) {
        secure(context)?.edit()?.remove(PASS)?.apply()
        plain(context).edit().remove(PASS).apply()
        deleteLegacyFile(context)
    }

    fun clear(context: Context) {
        clearToken(context)
        secure(context)?.edit()?.remove(USER)?.remove(PASS)?.apply()
        plain(context).edit().remove(USER).remove(PASS).apply()
        deleteLegacyFile(context)
    }

    private fun readLegacyFile(context: Context): Pair<String, String>? = try {
        val file = File(context.filesDir, LEGACY_FILE)
        if (!file.exists()) null else {
            val parts = file.readText().split("|")
            if (parts.size >= 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) {
                parts[0] to parts[1]
            } else null
        }
    } catch (e: Exception) {
        null
    }

    private fun deleteLegacyFile(context: Context) {
        try {
            val file = File(context.filesDir, LEGACY_FILE)
            if (file.exists() && !file.delete()) {
                SafeLog.w("CredentialStore", "the legacy credential file could not be removed")
            }
        } catch (e: Exception) {
            SafeLog.w("CredentialStore", "the legacy credential file could not be removed")
        }
    }
}
