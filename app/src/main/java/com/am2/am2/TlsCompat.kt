package com.am2.am2

import android.content.Context
import android.os.Build
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object TlsCompat {

    fun applyBundledCaForOldAndroid(
        context: Context,
        builder: OkHttpClient.Builder
    ): OkHttpClient.Builder {
        if (!BuildConfig.BUNDLED_CA_ENABLED || Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return builder
        }

        val systemTrustManager = createSystemTrustManager()
        val bundledTrustManager = createBundledTrustManager(context)

        return applyTrustManagers(
            builder,
            listOf(systemTrustManager, bundledTrustManager),
        )
    }

    internal fun applyTrustManagers(
        builder: OkHttpClient.Builder,
        trustManagers: List<X509TrustManager>,
    ): OkHttpClient.Builder {
        val compositeTrustManager = CompositeX509TrustManager(trustManagers)

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(compositeTrustManager), null)
        val socketFactory = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            LegacyTls12SocketFactory(sslContext.socketFactory)
        } else {
            sslContext.socketFactory
        }

        return builder
            // OkHttp 3.12's default COMPATIBLE_TLS fallback is TLS 1.0-only.
            // Keep the modern spec so API 16/19 can negotiate TLS 1.2 where
            // the platform supports it; never re-enable TLS 1.0/1.1.
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
            .sslSocketFactory(
                socketFactory,
                compositeTrustManager
            )
    }

    private class LegacyTls12SocketFactory(
        private val delegate: SSLSocketFactory,
    ) : SSLSocketFactory() {
        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        override fun createSocket(): Socket = applyTlsConfiguration(delegate.createSocket())

        override fun createSocket(
            socket: Socket,
            host: String,
            port: Int,
            autoClose: Boolean,
        ): Socket = applyTlsConfiguration(delegate.createSocket(socket, host, port, autoClose))

        override fun createSocket(host: String, port: Int): Socket =
            applyTlsConfiguration(delegate.createSocket(host, port))

        override fun createSocket(
            host: String,
            port: Int,
            localHost: InetAddress,
            localPort: Int,
        ): Socket = applyTlsConfiguration(delegate.createSocket(host, port, localHost, localPort))

        override fun createSocket(host: InetAddress, port: Int): Socket =
            applyTlsConfiguration(delegate.createSocket(host, port))

        override fun createSocket(
            address: InetAddress,
            port: Int,
            localAddress: InetAddress,
            localPort: Int,
        ): Socket = applyTlsConfiguration(
            delegate.createSocket(address, port, localAddress, localPort),
        )

        private fun applyTlsConfiguration(socket: Socket): Socket {
            if (socket is SSLSocket && "TLSv1.2" in socket.supportedProtocols) {
                socket.enabledProtocols = arrayOf("TLSv1.2")
            }
            return socket
        }
    }

    private fun createSystemTrustManager(): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm()
        )
        tmf.init(null as KeyStore?)
        return tmf.trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()
    }

    private fun createBundledTrustManager(context: Context): X509TrustManager {
        val certificateFactory = CertificateFactory.getInstance("X.509")

        val caCertificate = context.resources.openRawResource(R.raw.isrg_root_x1).use {
            certificateFactory.generateCertificate(it)
        }

        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)
        keyStore.setCertificateEntry("isrg_root_x1", caCertificate)

        val tmf = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm()
        )
        tmf.init(keyStore)

        return tmf.trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()
    }

    private class CompositeX509TrustManager(
        private val trustManagers: List<X509TrustManager>
    ) : X509TrustManager {

        override fun checkClientTrusted(
            chain: Array<out X509Certificate>,
            authType: String
        ) {
            var lastException: CertificateException? = null

            for (trustManager in trustManagers) {
                try {
                    trustManager.checkClientTrusted(chain, authType)
                    return
                } catch (e: CertificateException) {
                    lastException = e
                }
            }

            throw lastException ?: CertificateException("Client certificate is not trusted")
        }

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>,
            authType: String
        ) {
            var lastException: CertificateException? = null

            for (trustManager in trustManagers) {
                try {
                    trustManager.checkServerTrusted(chain, authType)
                    return
                } catch (e: CertificateException) {
                    lastException = e
                }
            }

            throw lastException ?: CertificateException("Server certificate is not trusted")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> {
            return trustManagers
                .flatMap { it.acceptedIssuers.toList() }
                .toTypedArray()
        }
    }
}