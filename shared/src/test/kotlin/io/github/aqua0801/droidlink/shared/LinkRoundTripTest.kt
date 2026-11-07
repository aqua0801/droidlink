package io.github.aqua0801.droidlink.shared

import io.github.aqua0801.droidlink.shared.crypto.Fingerprint
import io.github.aqua0801.droidlink.shared.crypto.Identity
import io.github.aqua0801.droidlink.shared.link.LinkClient
import io.github.aqua0801.droidlink.shared.link.LinkRejectedException
import io.github.aqua0801.droidlink.shared.link.LinkServer
import io.github.aqua0801.droidlink.shared.link.PeerTrust
import io.github.aqua0801.droidlink.shared.link.ServerIdentityMismatchException
import io.github.aqua0801.droidlink.shared.link.SessionListener
import io.github.aqua0801.droidlink.shared.pairing.PairingInfo
import io.github.aqua0801.droidlink.shared.pairing.PairingTokens
import io.github.aqua0801.droidlink.shared.protocol.BatteryStatus
import io.github.aqua0801.droidlink.shared.protocol.Message
import io.github.aqua0801.droidlink.shared.protocol.PowerSource
import io.github.aqua0801.droidlink.shared.protocol.RejectReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LinkRoundTripTest {
    private class MemoryTrust : PeerTrust {
        val peers = ConcurrentHashMap<String, String>()
        override fun isTrusted(fingerprint: Fingerprint) = peers.containsKey(fingerprint.hex)
        override fun addTrusted(fingerprint: Fingerprint, name: String) {
            peers[fingerprint.hex] = name
        }
    }

    @Test
    fun pairReconnectAndRejectStrangers() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val serverId = Identity.generate("test-server")
            val phoneId = Identity.generate("test-phone")
            val trust = MemoryTrust()
            val tokens = PairingTokens()
            val received = Channel<Message>(Channel.UNLIMITED)

            val server = LinkServer(
                identity = serverId,
                serverName = "TestPC",
                trust = trust,
                pairing = tokens,
                listener = SessionListener { { message -> received.send(message) } },
                scope = scope,
                requestedPort = 0,
            )
            server.start()
            val port = server.port
            val phone = LinkClient(phoneId, "TestPhone", "test", "0.0.0")

            // 1. Pair with a fresh token.
            val token = tokens.issue().value
            val first = phone.connect("127.0.0.1", port, serverId.fingerprint, scope, pairToken = token)
            assertTrue(first.newlyPaired)
            assertEquals("TestPC", first.peerName)
            assertTrue(trust.isTrusted(phoneId.fingerprint))
            first.start { }

            val battery = BatteryStatus(42, charging = true, plugged = PowerSource.USB, full = false, temperatureC = 31.5f)
            first.send(battery)
            assertEquals(battery, withTimeout(5_000) { received.receive() })
            first.close()
            first.awaitClosed()

            // 2. Reconnect without a token: trusted by fingerprint.
            val second = phone.connect("127.0.0.1", port, serverId.fingerprint, scope)
            assertFalse(second.newlyPaired)
            second.close()

            // 3. A stranger can't reuse the consumed token, nor connect without one.
            val stranger = LinkClient(Identity.generate("stranger"), "Stranger", "test", "0.0.0")
            val reused = runCatching { stranger.connect("127.0.0.1", port, serverId.fingerprint, scope, pairToken = token) }
                .exceptionOrNull()
            assertIs<LinkRejectedException>(reused)
            assertEquals(RejectReason.BAD_TOKEN, reused.reason)
            val unpaired = runCatching { stranger.connect("127.0.0.1", port, serverId.fingerprint, scope) }
                .exceptionOrNull()
            assertIs<LinkRejectedException>(unpaired)
            assertEquals(RejectReason.NOT_PAIRED, unpaired.reason)

            // 4. The phone refuses a server that doesn't match the pinned fingerprint.
            val wrongPin = Identity.generate("someone-else").fingerprint
            val mismatch = runCatching { phone.connect("127.0.0.1", port, wrongPin, scope) }.exceptionOrNull()
            assertIs<ServerIdentityMismatchException>(mismatch)
            assertEquals(serverId.fingerprint, mismatch.actual)

            server.stop()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun pairingUriRoundTrip() {
        val info = PairingInfo(
            serverName = "工作機 #1",
            hosts = listOf("192.168.1.20", "my-pc.tail1234.ts.net", "100.101.102.103"),
            port = 47100,
            fingerprint = Identity.generate("x").fingerprint,
            token = "abc-DEF_123",
        )
        assertEquals(info, PairingInfo.parse(info.toUri()))
        assertEquals(null, PairingInfo.parse("https://example.com"))
    }

    @Test
    fun identitySurvivesEncoding() {
        val identity = Identity.generate("roundtrip")
        val decoded = Identity.decode(identity.encode())
        assertEquals(identity.fingerprint, decoded.fingerprint)
        assertEquals(identity.privateKey, decoded.privateKey)
    }

    @Test
    fun pairingTokenIsSingleUseAndExpires() {
        var now = 0L
        val tokens = PairingTokens(ttlMs = 1_000, clock = { now })
        val a = tokens.issue()
        assertFalse(tokens.consume("wrong"))
        assertTrue(tokens.consume(a.value))
        assertFalse(tokens.consume(a.value))
        val b = tokens.issue()
        now = 2_000
        assertFalse(tokens.consume(b.value))
    }
}
