package com.am2.am2

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TrustModeInstrumentedTest {
    @Test
    fun variantUsesExpectedTrustModeAndEndpoint() {
        val expectedEndpoint = when {
            BuildConfig.APPLICATION_ID.endsWith(".dev") -> "wss://dev-api.am2-poc.com"
            BuildConfig.APPLICATION_ID.endsWith(".staging") -> "wss://staging-api.am2-poc.com"
            else -> "wss://apiapi.am2-poc.com"
        }
        assertEquals(expectedEndpoint, BuildConfig.WEBSOCKET_URL)
        if (BuildConfig.APPLICATION_ID != "com.am2.tik") {
            assertNotEquals("wss://apiapi.am2-poc.com", BuildConfig.WEBSOCKET_URL)
        }

        val expectedBundledCa = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N
        assertEquals(expectedBundledCa, BuildConfig.BUNDLED_CA_ENABLED)
    }

    @Test
    fun httpsHandshakeUsesVariantTrustConfiguration() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val client = TlsCompat.applyBundledCaForOldAndroid(
            app,
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
        ).build()
        client.newCall(Request.Builder().url("https://valid-isrgrootx1.letsencrypt.org/").build()).execute().use { response ->
            assertTrue("TLS handshake succeeded but server returned ${response.code()}", response.code() in 200..499)
        }
    }
}
