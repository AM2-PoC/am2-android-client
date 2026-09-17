package com.am2.am2

import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

class TlsCompatConnectionSpecTest {
    private val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun specsFor(sdkInt: Int): List<ConnectionSpec> =
        TlsCompat.applyTrustManagers(OkHttpClient.Builder(), listOf(trustManager), sdkInt)
            .build()
            .connectionSpecs()

    @Test fun plainHttpStaysReachableOnJellyBean() {
        assertTrue(
            "API 16 cannot make a cleartext request; the ip-api fallback is dead",
            specsFor(16).contains(ConnectionSpec.CLEARTEXT),
        )
    }

    @Test fun plainHttpStaysReachableAcrossTheLegacyRange() {

        for (sdkInt in listOf(19, 21, 23)) {
            assertTrue(
                "API $sdkInt cannot make a cleartext request",
                specsFor(sdkInt).contains(ConnectionSpec.CLEARTEXT),
            )
        }
    }

    @Test fun modernTlsIsStillOffered() {

        assertTrue(
            "the legacy path no longer offers TLS 1.2",
            specsFor(16).contains(ConnectionSpec.MODERN_TLS),
        )
    }

    @Test fun obsoleteTlsIsNeverOffered() {
        val specs = specsFor(16)
        assertTrue(
            "TLS 1.0/1.1 was re-enabled while restoring cleartext",
            !specs.contains(ConnectionSpec.COMPATIBLE_TLS),
        )
    }
}
