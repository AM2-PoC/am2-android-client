package com.am2.am2

import android.annotation.TargetApi
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * The credential store the platform's keystore backs, for handsets that have one.
 *
 * Kept in its own file because of what it imports. EncryptedSharedPreferences
 * arrives with minSdk 23 and this application supports 16, so on the oldest
 * handsets these classes are simply not there. Naming a missing class inside a
 * method the old runtime verifies is how an app crashes at load rather than at
 * the branch that would have used it -- so the type appears here, and nothing
 * on the legacy path ever mentions this file.
 *
 * CredentialStore decides which path runs. This one only knows how.
 */
@TargetApi(Build.VERSION_CODES.M)
internal object SecureCredentialStore {

    private const val FILE = "am2_credentials"

    /**
     * Null rather than a throw when the keystore will not open.
     *
     * A restored backup, a changed lock screen, a wiped keystore: the entry the
     * preferences were sealed with can be gone while the file remains, and
     * every read then fails. That is a handset that must sign in again, not a
     * handset that must crash, and the caller decides which.
     */
    fun open(context: Context): SharedPreferences? = try {
        // security-crypto 1.0.0, which is the stable release and the version
        // this module pins. MasterKey.Builder and the (context, file, key, ...)
        // overload belong to 1.1.0-alpha; taking an alpha into a fleet of
        // radios to save one line is not a trade worth making.
        val alias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            FILE,
            alias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        null
    }
}
