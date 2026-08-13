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
import java.net.URI
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TrustModeInstrumentedTest {
    @Test
    fun variantUsesExpectedTrustModeAndEndpoint() {
        val endpoint = URI(BuildConfig.WEBSOCKET_URL)
        assertEquals("wss", endpoint.scheme)
        assertTrue(endpoint.host.endsWith(".am2-poc.com"))
        assertNotEquals("apiapi.am2-poc.com", endpoint.host)

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
        val httpsUrl = BuildConfig.WEBSOCKET_URL
            .replaceFirst("wss://", "https://")
            .substringBefore("/ws") + "/"
        client.newCall(Request.Builder().url(httpsUrl).build()).execute().use { response ->
            assertTrue("TLS handshake succeeded but server returned ${response.code()}", response.code() in 200..499)
        }
    }
}
