package com.am2.am2

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.Build
import android.util.Base64
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

@RunWith(AndroidJUnit4::class)
class TrustModeInstrumentedTest {
    @Test
    fun variantUsesExpectedTrustModeAndEndpoint() {
        val expectedEndpoint = when {
            BuildConfig.APPLICATION_ID.endsWith(".dev") -> "wss://dev-api.am2-poc.com"
            BuildConfig.APPLICATION_ID.endsWith(".staging") -> "wss://staging-apiapi.am2-poc.com"
            else -> "wss://apiapi.am2-poc.com"
        }
        assertEquals(expectedEndpoint, BuildConfig.WEBSOCKET_URL)
        if (BuildConfig.APPLICATION_ID != "com.am2.tik") {
            assertNotEquals("wss://apiapi.am2-poc.com", BuildConfig.WEBSOCKET_URL)
        }

        val expectedBundledCa = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N
        assertEquals(
            expectedBundledCa,
            TlsCompat.usesBundledCaForSdk(android.os.Build.VERSION.SDK_INT),
        )
    }

    @Test
    fun platformTlsCompatibilitySelectsExpectedRuntimePath() {
        val original = OkHttpClient.Builder()
        var systemTrustRequested = false
        var bundledTrustRequested = false

        val configured = TlsCompat.applyPlatformTlsCompatibility(
            builder = original,
            sdkInt = android.os.Build.VERSION.SDK_INT,
            systemTrustManager = {
                systemTrustRequested = true
                fixtureTrustManager()
            },
            bundledTrustManager = {
                bundledTrustRequested = true
                fixtureTrustManager()
            },
        )

        assertSame(original, configured)
        val expectedFallback = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N
        assertEquals(expectedFallback, systemTrustRequested)
        assertEquals(expectedFallback, bundledTrustRequested)
    }

    @Test
    fun httpsHandshakeUsesVariantTrustConfiguration() {
        val client = fixtureClient()
        client.newCall(Request.Builder().url(fixtureHttpsUrl()).build()).execute().use { response ->
            assertTrue("TLS handshake succeeded but server returned ${response.code()}", response.code() in 200..499)
        }
    }

    @Test
    fun wssHandshakeUsesVariantTrustConfiguration() {
        val completed = CountDownLatch(1)
        var failure: Throwable? = null
        val socket = fixtureClient().newWebSocket(
            Request.Builder().url(fixtureHttpsUrl().replaceFirst("https://", "wss://")).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.close(1000, "compatibility-check")
                    completed.countDown()
                }

                override fun onFailure(webSocket: WebSocket, throwable: Throwable, response: Response?) {
                    failure = throwable
                    completed.countDown()
                }
            },
        )
        assertTrue("WSS handshake timed out", completed.await(30, TimeUnit.SECONDS))
        socket.cancel()
        failure?.let { throw AssertionError("WSS handshake failed", it) }
    }

    @Test
    fun hostnameMismatchIsRejected() {
        try {
            fixtureClient(hostnameOverride = true).newCall(
                Request.Builder().url(
                    fixtureHttpsUrl().replace("10.0.2.2", "mismatch.am2.invalid"),
                ).build(),
            ).execute().close()
            throw AssertionError("TLS hostname mismatch was accepted")
        } catch (expected: SSLPeerUnverifiedException) {
            assertTrue(expected.message.orEmpty().isNotEmpty())
        }
    }

    @Test
    fun untrustedCertificateIsRejected() {
        try {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
                .newCall(Request.Builder().url(fixtureHttpsUrl()).build())
                .execute()
                .close()
            throw AssertionError("Untrusted fixture certificate was accepted")
        } catch (expected: SSLHandshakeException) {
            assertTrue(expected.message.orEmpty().isNotEmpty())
        }
    }

    private fun fixtureClient(hostnameOverride: Boolean = false): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .dns(Dns { hostname ->
                if (hostnameOverride && hostname == "mismatch.am2.invalid") {
                    listOf(InetAddress.getByName("10.0.2.2"))
                } else {
                    Dns.SYSTEM.lookup(hostname)
                }
            })
        return TlsCompat.applyTrustManagers(
            builder,
            listOf(fixtureTrustManager()),
            Build.VERSION.SDK_INT,
        ).build()
    }

    private fun fixtureHttpsUrl(): String {
        return InstrumentationRegistry.getArguments().getString("am2CiHttpsUrl")
            ?: throw AssertionError("am2CiHttpsUrl instrumentation argument is required")
    }

    private fun fixtureTrustManager(): X509TrustManager {
        val encoded = InstrumentationRegistry.getArguments().getString("am2CiCaBase64")
            ?: throw AssertionError("am2CiCaBase64 instrumentation argument is required")
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(
            ByteArrayInputStream(Base64.decode(encoded, Base64.DEFAULT)),
        )
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)
        keyStore.setCertificateEntry("am2-ci", certificate)
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }
}
