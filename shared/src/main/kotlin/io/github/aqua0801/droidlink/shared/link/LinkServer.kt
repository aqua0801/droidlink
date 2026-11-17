package io.github.aqua0801.droidlink.shared.link

import io.github.aqua0801.droidlink.shared.DroidLinkDefaults
import io.github.aqua0801.droidlink.shared.crypto.Fingerprint
import io.github.aqua0801.droidlink.shared.crypto.Identity
import io.github.aqua0801.droidlink.shared.crypto.PinnedTrustManager
import io.github.aqua0801.droidlink.shared.crypto.Tls
import io.github.aqua0801.droidlink.shared.pairing.PairingTokens
import io.github.aqua0801.droidlink.shared.protocol.Hello
import io.github.aqua0801.droidlink.shared.protocol.MirrorStreamHello
import io.github.aqua0801.droidlink.shared.protocol.PROTOCOL_VERSION
import io.github.aqua0801.droidlink.shared.protocol.RejectReason
import io.github.aqua0801.droidlink.shared.protocol.Rejected
import io.github.aqua0801.droidlink.shared.protocol.Welcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/** Which client fingerprints may connect without a pairing token. */
interface PeerTrust {
    fun isTrusted(fingerprint: Fingerprint): Boolean
    fun addTrusted(fingerprint: Fingerprint, name: String)
}

/** Receives extra connections that carry a raw stream (see [MirrorStreamHello]). */
fun interface StreamListener {
    /** Called on an IO thread with an authenticated connection from a trusted peer; must not block long. */
    fun onStream(connection: Connection, hello: MirrorStreamHello)
}

fun interface SessionListener {
    /** Called for every authenticated session; returns the handler for its messages. */
    fun onSessionOpened(session: LinkSession): MessageHandler
}

/** Desktop side: accepts phones, authorizes them, and hands out [LinkSession]s. */
class LinkServer(
    private val identity: Identity,
    private val serverName: String,
    private val trust: PeerTrust,
    private val pairing: PairingTokens,
    private val listener: SessionListener,
    private val scope: CoroutineScope,
    private val requestedPort: Int = DroidLinkDefaults.PORT,
    private val platform: String = "desktop",
    private val streams: StreamListener? = null,
) {
    @Volatile
    private var serverSocket: SSLServerSocket? = null

    /** The bound port (useful when [requestedPort] is 0). */
    val port: Int get() = serverSocket?.localPort ?: requestedPort

    /** Binds the port synchronously (throws if it is taken), then accepts in the background. */
    fun start() {
        val context = Tls.context(identity, PinnedTrustManager { true })
        val socket = Tls.serverSocket(context, requestedPort)
        serverSocket = socket
        scope.launch(Dispatchers.IO) {
            while (isActive && !socket.isClosed) {
                val client = try {
                    socket.accept() as SSLSocket
                } catch (e: IOException) {
                    if (socket.isClosed) break else continue
                }
                launch { handle(client) }
            }
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
    }

    private suspend fun handle(socket: SSLSocket) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = DroidLinkDefaults.HANDSHAKE_TIMEOUT_MS
            socket.startHandshake()
            val connection = Connection(socket)

            val first = connection.receive()
            if (first is MirrorStreamHello) {
                // Stream connections never pair; the peer must already be trusted.
                if (streams == null || !trust.isTrusted(connection.peerFingerprint)) {
                    connection.close()
                } else {
                    connection.readTimeoutMs = 0
                    streams.onStream(connection, first)
                }
                return
            }
            val hello = first as? Hello ?: run {
                connection.close()
                return
            }
            if (hello.protocolVersion != PROTOCOL_VERSION) {
                reject(connection, RejectReason.PROTOCOL_MISMATCH)
                return
            }

            val fingerprint = connection.peerFingerprint
            val tokenAccepted = hello.pairToken?.let { pairing.consume(it) } == true
            val newlyPaired = when {
                tokenAccepted -> {
                    trust.addTrusted(fingerprint, hello.deviceName)
                    true
                }
                trust.isTrusted(fingerprint) -> false
                else -> {
                    reject(connection, if (hello.pairToken != null) RejectReason.BAD_TOKEN else RejectReason.NOT_PAIRED)
                    return
                }
            }

            connection.send(Welcome(serverName, platform, PROTOCOL_VERSION, newlyPaired))
            connection.readTimeoutMs = DroidLinkDefaults.SESSION_READ_TIMEOUT_MS
            val session = LinkSession(
                connection = connection,
                peerName = hello.deviceName,
                peerPlatform = hello.platform,
                newlyPaired = newlyPaired,
                parentScope = scope,
                pingIntervalMs = null, // the client pings; we answer
            )
            session.start(listener.onSessionOpened(session))
        } catch (e: CancellationException) {
            runCatching { socket.close() }
            throw e
        } catch (e: Exception) {
            runCatching { socket.close() }
        }
    }

    private suspend fun reject(connection: Connection, reason: RejectReason) {
        runCatching { connection.send(Rejected(reason)) }
        connection.close()
    }
}
