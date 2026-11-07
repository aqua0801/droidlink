package io.github.aqua0801.droidlink.shared.crypto

import java.net.InetSocketAddress
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * TLS 1.3 with mutual authentication and certificate pinning.
 *
 * Both sides present their self-signed [Identity] certificate. The trust decision is made
 * purely on the peer's [Fingerprint]: the phone pins the PC fingerprint it scanned from
 * the QR code, and the PC accepts any certificate at the TLS layer and then authorizes the
 * fingerprint at the application layer (trusted list, or a valid one-time pairing token).
 */
object Tls {
    const val PROTOCOL = "TLSv1.3"

    fun context(identity: Identity, trustManager: X509TrustManager): SSLContext =
        SSLContext.getInstance("TLS").apply {
            init(arrayOf(SingleIdentityKeyManager(identity)), arrayOf(trustManager), SecureRandom())
        }

    fun serverSocket(context: SSLContext, port: Int): SSLServerSocket =
        (context.serverSocketFactory.createServerSocket(port) as SSLServerSocket).apply {
            enabledProtocols = arrayOf(PROTOCOL)
            needClientAuth = true
        }

    fun connect(
        context: SSLContext,
        host: String,
        port: Int,
        connectTimeoutMs: Int,
        handshakeTimeoutMs: Int,
    ): SSLSocket {
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(host, port), connectTimeoutMs)
            raw.tcpNoDelay = true
            raw.soTimeout = handshakeTimeoutMs
            val socket = context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
            socket.enabledProtocols = arrayOf(PROTOCOL)
            socket.useClientMode = true
            socket.startHandshake()
            return socket
        } catch (e: Exception) {
            runCatching { raw.close() }
            throw e
        }
    }
}

/** Thrown from the trust manager when a peer's fingerprint is not the expected one. */
class PeerNotTrustedException(val fingerprint: Fingerprint) :
    CertificateException("untrusted peer certificate ${fingerprint.hex}")

fun Throwable.findPeerNotTrusted(): PeerNotTrustedException? =
    generateSequence(this) { it.cause }.filterIsInstance<PeerNotTrustedException>().firstOrNull()

/**
 * Trust is decided solely by [accept] on the leaf fingerprint. The server passes an
 * accept-all predicate on purpose and authorizes the fingerprint after the handshake
 * (see [io.github.aqua0801.droidlink.shared.link.LinkServer]); clients always pin.
 */
@Suppress("TrustAllX509TrustManager") // Android lint can't see through the predicate.
class PinnedTrustManager(private val accept: (Fingerprint) -> Boolean) : X509ExtendedTrustManager() {
    private fun verify(chain: Array<out X509Certificate>?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("peer presented no certificate")
        val fingerprint = Fingerprint.of(leaf)
        if (!accept(fingerprint)) throw PeerNotTrustedException(fingerprint)
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = verify(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = verify(chain)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = verify(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = verify(chain)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = verify(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = verify(chain)
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * Always offers our single identity, regardless of the issuer list the peer sends
 * (self-signed certs never match an issuer list, which would otherwise make the default
 * key managers send no client certificate at all).
 */
internal class SingleIdentityKeyManager(private val identity: Identity) : X509ExtendedKeyManager() {
    private val alias = "droidlink"

    private fun matches(keyType: String?) = keyType == null || keyType.startsWith("EC")

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
        if (matches(keyType)) arrayOf(alias) else null

    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? =
        if (keyType == null || keyType.any { matches(it) }) alias else null

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
        if (matches(keyType)) arrayOf(alias) else null

    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? =
        if (matches(keyType)) alias else null

    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String? =
        chooseClientAlias(keyType, issuers, null)

    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String? =
        chooseServerAlias(keyType, issuers, null)

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
        if (alias == this.alias) arrayOf(identity.certificate) else null

    override fun getPrivateKey(alias: String?): PrivateKey? =
        if (alias == this.alias) identity.privateKey else null
}
