package io.github.aqua0801.droidlink.shared

import io.github.aqua0801.droidlink.shared.crypto.Fingerprint
import io.github.aqua0801.droidlink.shared.crypto.Identity
import io.github.aqua0801.droidlink.shared.link.LinkClient
import io.github.aqua0801.droidlink.shared.link.LinkServer
import io.github.aqua0801.droidlink.shared.link.PeerTrust
import io.github.aqua0801.droidlink.shared.link.SessionListener
import io.github.aqua0801.droidlink.shared.link.StreamListener
import io.github.aqua0801.droidlink.shared.pairing.PairingTokens
import io.github.aqua0801.droidlink.shared.protocol.MirrorReady
import io.github.aqua0801.droidlink.shared.protocol.MirrorStreamHello
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StreamConnectionTest {
    private class SetTrust(trusted: List<Fingerprint>) : PeerTrust {
        private val peers = trusted.map { it.hex }.toMutableSet()
        override fun isTrusted(fingerprint: Fingerprint) = fingerprint.hex in peers
        override fun addTrusted(fingerprint: Fingerprint, name: String) {
            peers += fingerprint.hex
        }
    }

    @Test
    fun trustedPeerGetsARawStream() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val serverId = Identity.generate("pc")
            val phoneId = Identity.generate("phone")
            val payload = ByteArray(100_000) { (it % 251).toByte() }
            val received = CompletableFuture<Pair<String, ByteArray>>()

            val server = LinkServer(
                identity = serverId,
                serverName = "PC",
                trust = SetTrust(listOf(phoneId.fingerprint)),
                pairing = PairingTokens(),
                listener = SessionListener { { } },
                scope = scope,
                requestedPort = 0,
                streams = StreamListener { connection, hello ->
                    scope.launch {
                        connection.send(MirrorReady())
                        val socket = connection.releaseSocket()
                        val bytes = ByteArray(payload.size).also { DataInputStream(socket.inputStream).readFully(it) }
                        received.complete(hello.token to bytes)
                        socket.close()
                    }
                },
            )
            server.start()

            val phone = LinkClient(phoneId, "Phone", "test", "0")
            val socket = phone.openStream("127.0.0.1", server.port, serverId.fingerprint, MirrorStreamHello("t-1", "Phone"))
            withContext(Dispatchers.IO) {
                socket.outputStream.write(payload)
                socket.outputStream.flush()
            }
            val (token, bytes) = withContext(Dispatchers.IO) { received.get(10, TimeUnit.SECONDS) }
            assertEquals("t-1", token)
            assertContentEquals(payload, bytes)
            socket.close()

            // An unpaired device cannot open a stream.
            val stranger = LinkClient(Identity.generate("stranger"), "X", "test", "0")
            assertFailsWith<IOException> {
                stranger.openStream("127.0.0.1", server.port, serverId.fingerprint, MirrorStreamHello("t-2", "X"))
            }
            server.stop()
        } finally {
            scope.cancel()
        }
    }
}
