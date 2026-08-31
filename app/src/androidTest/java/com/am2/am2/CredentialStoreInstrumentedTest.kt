package com.am2.am2

import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CredentialStoreInstrumentedTest {
    private lateinit var context: Context

    @Before
    fun resetCredentialStorage() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        CredentialStore.clear(context)
        context.getSharedPreferences(WebSocketManager.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
                .getSharedPreferences(WebSocketManager.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
    }

    @After
    fun removeCredentialStorage() {
        CredentialStore.clear(context)
    }

    @Test
    fun aSavedTokenResumesButNoPasswordIsWrittenToPlainPreferences() {
        assertTrue(CredentialStore.saveToken(context, "token-1", "UNIT01"))

        val restored = CredentialStore.state(context)
        assertTrue(restored.canResume)
        assertNull(restored.password)
        val plain = context.getSharedPreferences(WebSocketManager.PREFS_NAME, Context.MODE_PRIVATE)
        assertFalse(plain.contains("password"))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            assertFalse(plain.contains("username"))
            assertFalse(plain.contains("device_token"))
        }
    }

    @Test
    fun aLegacyPasswordIsDeletedAndCannotResume() {
        val plain = context.getSharedPreferences(WebSocketManager.PREFS_NAME, Context.MODE_PRIVATE)
        plain.edit()
            .putString("username", "UNIT01")
            .putString("password", "operator-secret")
            .commit()

        assertFalse(CredentialStore.state(context).canResume)
        assertFalse(plain.contains("password"))
        assertTrue(plain.getBoolean("session_blocked", false))
    }

    @Test
    fun logoutBlocksAndRemovesThePersistedToken() {
        assertTrue(CredentialStore.saveToken(context, "token-2", "UNIT01"))
        assertTrue(CredentialStore.clear(context))

        assertFalse(CredentialStore.state(context).canResume)
        assertTrue(
            context.getSharedPreferences(WebSocketManager.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean("session_blocked", false),
        )
    }

    @Test
    fun unreadableEncryptedCredentialFailsClosed() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
        assertTrue(CredentialStore.saveToken(context, "token-3", "UNIT01"))

        // Removing the keystore alias makes the encrypted preferences
        // unreadable while leaving the credential file in place.
        val alias = androidx.security.crypto.MasterKeys.getOrCreate(
            androidx.security.crypto.MasterKeys.AES256_GCM_SPEC,
        )
        val plain = context.getSharedPreferences(WebSocketManager.PREFS_NAME, Context.MODE_PRIVATE)
        plain.edit().putString("password", "legacy-secret").commit()
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        keyStore.deleteEntry(alias)

        assertFalse(CredentialStore.state(context).canResume)
        assertFalse(plain.contains("password"))
        assertTrue(plain.getBoolean("session_blocked", false))
    }
}
