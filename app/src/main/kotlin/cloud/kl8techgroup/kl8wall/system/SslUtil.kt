package cloud.kl8techgroup.kl8wall.system

import android.util.Log
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Shared SSL/TLS utility to configure custom trust managers and enforce TLS 1.2 on older Android versions.
 */
object SslUtil {
    private const val TAG = "SslUtil"

    val trustManager: X509TrustManager by lazy {
        try {
            // 1. Load default trust manager
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?)
            val defaultTm = tmf.trustManagers.firstOrNull { it is X509TrustManager } as? X509TrustManager

            // 2. Load ISRG Root X1 trust manager (Let's Encrypt Root CA)
            val certPem = """
                -----BEGIN CERTIFICATE-----
                MIIFazCCA1OgAwIBAgIRAIIQz7DSQONZRGPgu2OCiwAwDQYJKoZIhvcNAQELBQAw
                TzELMAkGA1UEBhMCVVMxKTAnBgNVBAoTIEludGVybmV0IFNlY3VyaXR5IFJlc2Vh
                cmNoIEdyb3VwMRUwEwYDVQQDEwxJU1JHIFJvb3QgWDEwHhcNMTUwNjA0MTEwNDM4
                WhcNMzUwNjA0MTEwNDM4WjBPMQswCQYDVQQGEwJVUzEpMCcGA1UEChMgSW50ZXJu
                ZXQgU2VjdXJpdHkgUmVzZWFyY2ggR3JvdXAxFTATBgNVBAMTDElTUkcgUm9vdCBY
                MTCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBAK3oJHP0FDfzm54rVygc
                h77ct984kIxuPOZXoHj3dcKi/vVqbvYATyjb3miGbESTtrFj/RQSa78f0uoxmyF+
                0TM8ukj13Xnfs7j/EvEhmkvBioZxaUpmZmyPfjxwv60pIgbz5MDmgK7iS4+3mX6U
                A5/TR5d8mUgjU+g4rk8Kb4Mu0UlXjIB0ttov0DiNewNwIRt18jA8+o+u3dpjq+sW
                T8KOEUt+zwvo/7V3LvSye0rgTBIlDHCNAymg4VMk7BPZ7hm/ELNKjD+Jo2FR3qyH
                B5T0Y3HsLuJvW5iB4YlcNHlsdu87kGJ55tukmi8mxdAQ4Q7e2RCOFvu396j3x+UC
                B5iPNgiV5+I3lg02dZ77DnKxHZu8A/lJBdiB3QW0KtZB6awBdpUKD9jf1b0SHzUv
                KBds0pjBqAlkd25HN7rOrFleaJ1/ctaJxQZBKT5ZPt0m9STJEadao0xAH0ahmbWn
                OlFuhjuefXKnEgV4We0+UXgVCwOPjdAvBbI+e0ocS3MFEvzG6uBQE3xDk3SzynTn
                jh8BCNAw1FtxNrQHusEwMFxIt4I7mKZ9YIqioymCzLq9gwQbooMDQaHWBfEbwrbw
                qHyGO0aoSCqI3Haadr8faqU9GY/rOPNk3sgrDQoo//fb4hVC1CLQJ13hef4Y53CI
                rU7m2Ys6xt0nUW7/vGT1M0NPAgMBAAGjQjBAMA4GA1UdDwEB/wQEAwIBBjAPBgNV
                HRMBAf8EBTADAQH/MB0GA1UdDgQWBBR5tFnme7bl5AFzgAiIyBpY9umbbjANBgkq
                hkiG9w0BAQsFAAOCAgEAVR9YqbyyqFDQDLHYGmkgJykIrGF1XIpu+ILlaS/V9lZL
                ubhzEFnTIZd+50xx+7LSYK05qAvqFyFWhfFQDlnrzuBZ6brJFe+GnY+EgPbk6ZGQ
                3BebYhtF8GaV0nxvwuo77x/Py9auJ/GpsMiu/X1+mvoiBOv/2X/qkSsisRcOj/KK
                NFtY2PwByVS5uCbMiogziUwthDyC3+6WVwW6LLv3xLfHTjuCvjHIInNzktHCgKQ5
                ORAzI4JMPJ+GslWYHb4phowim57iaztXOoJwTdwJx4nLCgdNbOhdjsnvzqvHu7Ur
                TkXWStAmzOVyyghqpZXjFaH3pO3JLF+l+/+sKAIuvtd7u+Nxe5AW0wdeRlN8NwdC
                jNPElpzVmbUq4JUagEiuTDkHzsxHpFKVK7q4+63SM1N95R1NbdWhscdCb+ZAJzVc
                oyi3B43njTOQ5yOf+1CceWxG1bQVs5ZufpsMljq4Ui0/1lvh+wjChP4kqKOJ2qxq
                4RgqsahDYVvTH9w7jXbyLeiNdd8XM2w9U/t7y0Ff/9yi0GE44Za4rF2LN9d11TPA
                mRGunUHBcnWEvgJBQl9nJEiU0Zsnvgc/ubhPgXRR4Xq37Z0j4r7g1SgEEzwxA57d
                emyPxgcYxn/eR44/KJ4EBs+lVDR3veyJm+kXQ99b21/+jh5Xos1AnX5iItreGCc=
                -----END CERTIFICATE-----
            """.trimIndent()
            val cf = CertificateFactory.getInstance("X.509")
            val cert = cf.generateCertificate(ByteArrayInputStream(certPem.toByteArray()))
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("isrg_root_x1", cert)
            }
            val customTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            customTmf.init(keyStore)
            val customTm = customTmf.trustManagers.firstOrNull { it is X509TrustManager } as? X509TrustManager

            if (defaultTm != null && customTm != null) {
                CustomTrustManager(defaultTm, customTm)
            } else {
                customTm ?: defaultTm ?: throw IllegalStateException("No trust manager available")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize custom trust manager", e)
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?)
            tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
        }
    }

    val tlsSocketFactory: SSLSocketFactory by lazy {
        try {
            TLSSocketFactory(trustManager)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize shared TLSSocketFactory", e)
            SSLSocketFactory.getDefault() as SSLSocketFactory
        }
    }

    class TLSSocketFactory(tm: X509TrustManager) : SSLSocketFactory() {
        private val delegate: SSLSocketFactory

        init {
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(tm), java.security.SecureRandom())
            }
            delegate = sslContext.socketFactory
        }

        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket {
            return enableTLS(delegate.createSocket(s, host, port, autoClose))
        }

        override fun createSocket(host: String?, port: Int): Socket {
            return enableTLS(delegate.createSocket(host, port))
        }

        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket {
            return enableTLS(delegate.createSocket(host, port, localHost, localPort))
        }

        override fun createSocket(host: InetAddress?, port: Int): Socket {
            return enableTLS(delegate.createSocket(host, port))
        }

        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket {
            return enableTLS(delegate.createSocket(address, port, localAddress, localPort))
        }

        private fun enableTLS(socket: Socket): Socket {
            if (socket is SSLSocket) {
                val supportedProtocols = socket.supportedProtocols
                val protocolsToEnable = ArrayList<String>()
                if (supportedProtocols.contains("TLSv1.2")) protocolsToEnable.add("TLSv1.2")
                if (supportedProtocols.contains("TLSv1.1")) protocolsToEnable.add("TLSv1.1")
                if (supportedProtocols.contains("TLSv1")) protocolsToEnable.add("TLSv1")

                if (protocolsToEnable.isNotEmpty()) {
                    socket.enabledProtocols = protocolsToEnable.toTypedArray()
                }
            }
            return socket
        }
    }

    class CustomTrustManager(
        private val defaultTrustManager: X509TrustManager,
        private val customTrustManager: X509TrustManager
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            defaultTrustManager.checkClientTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            try {
                defaultTrustManager.checkServerTrusted(chain, authType)
            } catch (e: Exception) {
                customTrustManager.checkServerTrusted(chain, authType)
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> {
            return defaultTrustManager.acceptedIssuers + customTrustManager.acceptedIssuers
        }
    }
}
