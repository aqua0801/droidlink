package io.github.aqua0801.droidlink.shared.link

import io.github.aqua0801.droidlink.shared.DroidLinkDefaults
import io.github.aqua0801.droidlink.shared.crypto.Fingerprint
import io.github.aqua0801.droidlink.shared.crypto.Identity
import io.github.aqua0801.droidlink.shared.crypto.PinnedTrustManager
import io.github.aqua0801.droidlink.shared.crypto.Tls
import io.github.aqua0801.droidlink.shared.crypto.findPeerNotTrusted
import io.github.aqua0801.droidlink.shared.protocol.Hello
import io.github.aqua0801.droidlink.shared.protocol.MirrorReady
import io.github.aqua0801.droidlink.shared.protocol.MirrorStreamHello
import io.github.aqua0801.droidlink.shared.protocol.PROTOCOL_VERSION
import io.github.aqua0801.droidlink.shared.protocol.RejectReason
import io.github.aqua0801.droidlink.shared.protocol.Rejected
import io.github.aqua0801.droidlink.shared.protocol.Welcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.IOException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/** The server answered with [Rejected]. */
class LinkRejectedException(val reason: RejectReason) : IOException("rejected by server: $reason")

/** The host answered, but its certificate is not the pinned one (wrong machine or MITM). */
class ServerIdentityMismatchException(val actual: Fingerprint) :
    IOException("server identity mismatch: ${actual.hex}")

/** Phone side: dials a known desktop and authenticates it by pinned fingerprint. */
class LinkClient(
    private val identity: Identity,
    private val deviceName: String,
    private val platform: String,
    private val appVersion: String,
) {
    /**
     * Connects, verifies the server certificate against [expectedServer], and sends Hello.
     * The returned session is not started yet; call [LinkSession.start].
     *
     * @param scope parent scope for the session's coroutines.
     */
    suspend fun connect(
        host: String,
        port: Int,
        expectedServer: Fingerprint,
        scope: CoroutineScope,
        pairToken: String? = null,
        connectTimeoutMs: Int = 4_000,
    ): LinkSession = withContext(Dispatchers.IO) {
        val context = Tls.context(identity, PinnedTrustManager { it == expectedServer })
        val socket = try {
            Tls.connect(context, host, port, connectTimeoutMs, DroidLinkDefaults.HANDSHAKE_TIMEOUT_MS)
        } catch (e: SSLException) {
            val mismatch = e.findPeerNotTrusted()
            if (mismatch != null) throw ServerIdentityMismatchException(mismatch.fingerprint)
            throw e
        }

        try {
            val connection = Connection(socket)
            connection.send(Hello(deviceName, platform, PROTOCOL_VERSION, appVersion, pairToken))
            when (val reply = connection.receive()) {
                is Welcome -> {
                    connection.readTimeoutMs = DroidLinkDefaults.SESSION_READ_TIMEOUT_MS
                    LinkSession(
                        connection = connection,
                        peerName = reply.serverName,
                        peerPlatform = reply.platform,
                        newlyPaired = reply.newlyPaired,
                        parentScope = scope,
                        pingIntervalMs = DroidLinkDefaults.PING_INTERVAL_MS,
                    )
                }
                is Rejected -> throw LinkRejectedException(reply.reason)
                null -> throw EOFException("server closed the connection")
                else -> throw IOException("unexpected reply: ${reply::class.simpleName}")
            }
        } catch (e: Throwable) {
            runCatching { socket.close() }
            throw e
        }
    }

    /**
     * Opens an extra connection for a raw stream: sends [hello] as the first frame and waits
     * for [MirrorReady]. The returned socket has left the framed protocol; the caller owns it.
     */
    suspend fun openStream(
        host: String,
        port: Int,
        expectedServer: Fingerprint,
        hello: MirrorStreamHello,
        connectTimeoutMs: Int = 4_000,
    ): SSLSocket = withContext(Dispatchers.IO) {
        val context = Tls.context(identity, PinnedTrustManager { it == expectedServer })
        val socket = Tls.connect(context, host, port, connectTimeoutMs, DroidLinkDefaults.HANDSHAKE_TIMEOUT_MS)
        try {
            val connection = Connection(socket)
            connection.send(hello)
            val reply = connection.receive()
            if (reply !is MirrorReady) throw IOException("stream refused: ${reply?.let { it::class.simpleName }}")
            socket.soTimeout = 0
            connection.releaseSocket()
        } catch (e: Throwable) {
            runCatching { socket.close() }
            throw e
        }
    }
}
