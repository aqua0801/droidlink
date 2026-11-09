package io.github.aqua0801.droidlink.shared

import io.github.aqua0801.droidlink.shared.crypto.Fingerprint
import io.github.aqua0801.droidlink.shared.crypto.Identity
import io.github.aqua0801.droidlink.shared.link.LinkClient
import io.github.aqua0801.droidlink.shared.link.LinkServer
import io.github.aqua0801.droidlink.shared.link.LinkSession
import io.github.aqua0801.droidlink.shared.link.PeerTrust
import io.github.aqua0801.droidlink.shared.link.SessionListener
import io.github.aqua0801.droidlink.shared.pairing.PairingTokens
import io.github.aqua0801.droidlink.shared.protocol.TransferError
import io.github.aqua0801.droidlink.shared.transfer.Direction
import io.github.aqua0801.droidlink.shared.transfer.FileNames
import io.github.aqua0801.droidlink.shared.transfer.FileSink
import io.github.aqua0801.droidlink.shared.transfer.FileSource
import io.github.aqua0801.droidlink.shared.transfer.FileTransfers
import io.github.aqua0801.droidlink.shared.transfer.IncomingFiles
import io.github.aqua0801.droidlink.shared.transfer.Transfer
import io.github.aqua0801.droidlink.shared.transfer.TransferStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileTransferTest {
    /** Keeps committed files in memory; records aborted names. */
    private class MemoryIncoming(private val decline: Boolean = false) : IncomingFiles {
        val committed = ConcurrentHashMap<String, ByteArray>()
        val aborted = ConcurrentHashMap.newKeySet<String>()

        override fun open(name: String, size: Long, mime: String?): FileSink {
            if (decline) throw IllegalStateException("no space")
            val buffer = ByteArrayOutputStream()
            return object : FileSink {
                override val output = buffer
                override fun commit(): String {
                    committed[name] = buffer.toByteArray()
                    return "mem:$name"
                }
                override fun abort() {
                    aborted += name
                }
            }
        }
    }

    private class Trust : PeerTrust {
        override fun isTrusted(fingerprint: Fingerprint) = true
        override fun addTrusted(fingerprint: Fingerprint, name: String) = Unit
    }

    private class Peers(
        val scope: CoroutineScope,
        val pcFiles: FileTransfers,
        val phoneFiles: FileTransfers,
        val pcSession: LinkSession,
        val phoneSession: LinkSession,
        val server: LinkServer,
    )

    private fun connect(pcIncoming: IncomingFiles, phoneIncoming: IncomingFiles, block: suspend Peers.() -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val pcFiles = FileTransfers(scope, pcIncoming)
            val phoneFiles = FileTransfers(scope, phoneIncoming)
            val serverSession = CompletableDeferred<LinkSession>()
            val serverId = Identity.generate("pc")
            val server = LinkServer(
                identity = serverId,
                serverName = "PC",
                trust = Trust(),
                pairing = PairingTokens(),
                listener = SessionListener { session ->
                    pcFiles.attach(session)
                    serverSession.complete(session)
                    return@SessionListener { message -> pcFiles.onMessage(session, message) }
                },
                scope = scope,
                requestedPort = 0,
            )
            server.start()
            val phone = LinkClient(Identity.generate("phone"), "Phone", "test", "0")
            val phoneSession = phone.connect("127.0.0.1", server.port, serverId.fingerprint, scope)
            phoneFiles.attach(phoneSession)
            phoneSession.start { message -> phoneFiles.onMessage(phoneSession, message) }
            Peers(scope, pcFiles, phoneFiles, withTimeout(5_000) { serverSession.await() }, phoneSession, server).block()
            server.stop()
        } finally {
            scope.cancel()
        }
    }

    private suspend fun FileTransfers.awaitFinished(id: Long): Transfer =
        withTimeout(15_000) { transfers.first { list -> list.any { it.id == id && !it.isActive } } }.first { it.id == id }

    private fun bytes(n: Int) = Random(n).nextBytes(n)

    private fun source(name: String, data: ByteArray) =
        FileSource(name, data.size.toLong(), "application/octet-stream") { ByteArrayInputStream(data) }

    @Test
    fun sendsBothDirectionsAndVerifies() {
        val pcIn = MemoryIncoming()
        val phoneIn = MemoryIncoming()
        connect(pcIn, phoneIn) {
            val big = bytes(1_500_000) // spans many chunks, including a partial last one
            val toPc = phoneFiles.send(phoneSession, source("photo.jpg", big))
            val empty = pcFiles.send(pcSession, source("empty.txt", ByteArray(0)))

            val sent = phoneFiles.awaitFinished(toPc)
            assertEquals(TransferStatus.DONE, sent.status)
            assertEquals(Direction.SEND, sent.direction)
            val received = pcFiles.awaitFinished(toPc)
            assertEquals(TransferStatus.DONE, received.status)
            assertEquals("mem:photo.jpg", received.location)
            assertContentEquals(big, pcIn.committed["photo.jpg"])

            assertEquals(TransferStatus.DONE, pcFiles.awaitFinished(empty).status)
            assertEquals(0, phoneIn.committed["empty.txt"]?.size)
        }
    }

    @Test
    fun receiverStorageFailureIsReportedToSender() {
        connect(MemoryIncoming(decline = true), MemoryIncoming()) {
            val id = phoneFiles.send(phoneSession, source("a.bin", bytes(10)))
            val result = phoneFiles.awaitFinished(id)
            assertEquals(TransferStatus.FAILED, result.status)
            assertEquals(TransferError.STORAGE, result.error)
        }
    }

    @Test
    fun receiverCancelStopsSenderAndRemovesPartialFile() {
        val pcIn = MemoryIncoming()
        val gate = CountDownLatch(1)
        // Delivers one chunk, then stalls until the test has cancelled on the receiving side.
        val stalling = object : InputStream() {
            private val data = bytes(400_000)
            private var pos = 0
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= data.size) return -1
                if (pos > 0) gate.await(10, TimeUnit.SECONDS)
                val n = minOf(len, data.size - pos)
                System.arraycopy(data, pos, b, off, n)
                pos += n
                return n
            }
        }
        connect(pcIn, MemoryIncoming()) {
            val id = phoneFiles.send(phoneSession, FileSource("big.bin", 400_000, null) { stalling })
            withTimeout(10_000) { pcFiles.transfers.first { l -> l.any { it.id == id && it.status == TransferStatus.ACTIVE } } }
            pcFiles.cancel(id)
            assertEquals(TransferStatus.CANCELLED, pcFiles.awaitFinished(id).status)
            gate.countDown()
            assertEquals(TransferStatus.CANCELLED, phoneFiles.awaitFinished(id).status)
            assertTrue("big.bin" in pcIn.aborted)
            assertTrue(!pcIn.committed.containsKey("big.bin"))
        }
    }

    @Test
    fun disconnectFailsTransferAndRemovesPartialFile() {
        val pcIn = MemoryIncoming()
        val gate = CountDownLatch(1)
        val stalling = object : InputStream() {
            private var first = true
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (!first) {
                    gate.await(10, TimeUnit.SECONDS)
                    return -1
                }
                first = false
                return len
            }
        }
        connect(pcIn, MemoryIncoming()) {
            val id = phoneFiles.send(phoneSession, FileSource("x.bin", 1_000_000, null) { stalling })
            withTimeout(10_000) { pcFiles.transfers.first { l -> l.any { it.id == id && it.status == TransferStatus.ACTIVE } } }
            phoneSession.close()
            val received = pcFiles.awaitFinished(id)
            assertEquals(TransferStatus.FAILED, received.status)
            assertEquals(TransferError.CONNECTION_LOST, received.error)
            assertTrue("x.bin" in pcIn.aborted)
            gate.countDown()
        }
    }

    @Test
    fun sanitizesPeerFileNames() {
        assertEquals("passwd", FileNames.sanitize("../../etc/passwd"))
        assertEquals("evil.exe", FileNames.sanitize("C:\\Windows\\evil.exe"))
        assertEquals("ab.txt", FileNames.sanitize("a<>:\"|?*b.txt"))
        assertEquals("_CON.txt", FileNames.sanitize("CON.txt"))
        assertEquals("file", FileNames.sanitize(".."))
        assertEquals(".env", FileNames.sanitize(".env"))
        assertEquals("照片 2026.jpg", FileNames.sanitize("照片 2026.jpg"))
        val long = FileNames.sanitize("x".repeat(500) + ".pdf")
        assertEquals(200, long.length)
        assertTrue(long.endsWith(".pdf"))
        assertEquals("report (2).pdf", FileNames.numbered("report.pdf", 2))
        assertEquals(".env (1)", FileNames.numbered(".env", 1))
    }
}
