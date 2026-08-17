package com.am2.am2

import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * The legacy TLS path must not silently disable plain HTTP.
 *
 * `applyTrustManagers` runs on every device below API 24, and it replaces the
 * connection specs wholesale to keep TLS 1.2 reachable on Jelly Bean. Replacing
 * them also removed `ConnectionSpec.CLEARTEXT`, and OkHttp 3.12 refuses any
 * `http://` URL outright when that spec is absent -- `UnknownServiceException:
 * CLEARTEXT communication not enabled for client`.
 *
 * The app has exactly one such URL, the ip-api geolocation fallback, and its
 * call site catches broadly. So on API 16 through 23 the fallback stopped
 * working and said nothing: the very devices with the weakest location hardware
 * lost their last resort, on a build that reported success everywhere.
 *
 * This asserts the spec list rather than the request, because the request needs
 * a socket and the defect is entirely in what the builder was configured with.
 */
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
        // 16 through 23 all take this path. 19 and 21 straddle the socket
        // factory branch inside applyTrustManagers, so both are worth naming.
        for (sdkInt in listOf(19, 21, 23)) {
            assertTrue(
                "API $sdkInt cannot make a cleartext request",
                specsFor(sdkInt).contains(ConnectionSpec.CLEARTEXT),
            )
        }
    }

    @Test fun modernTlsIsStillOffered() {
        // The reason the specs were replaced at all: OkHttp 3.12's own fallback
        // is TLS 1.0-only, and that must not come back.
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
