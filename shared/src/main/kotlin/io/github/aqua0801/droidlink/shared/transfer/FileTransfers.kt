package io.github.aqua0801.droidlink.shared.transfer

import io.github.aqua0801.droidlink.shared.DroidLinkDefaults
import io.github.aqua0801.droidlink.shared.link.LinkSession
import io.github.aqua0801.droidlink.shared.protocol.FileAccept
import io.github.aqua0801.droidlink.shared.protocol.FileCancel
import io.github.aqua0801.droidlink.shared.protocol.FileComplete
import io.github.aqua0801.droidlink.shared.protocol.FileOffer
import io.github.aqua0801.droidlink.shared.protocol.FileReject
import io.github.aqua0801.droidlink.shared.protocol.FileResult
import io.github.aqua0801.droidlink.shared.protocol.FrameType
import io.github.aqua0801.droidlink.shared.protocol.Message
import io.github.aqua0801.droidlink.shared.protocol.TransferError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * File transfer over [LinkSession]s, in both directions. Shared by the phone and the PC;
 * each platform supplies [IncomingFiles] to decide where received files are written.
 *
 * Protocol: sender sends [FileOffer]; receiver answers [FileAccept] or [FileReject]; data
 * follows as FILE_CHUNK frames (`[i64 id][bytes]`); sender sends [FileComplete] with the
 * SHA-256; receiver verifies and answers [FileResult]. Either side may send [FileCancel].
 * Partial files are removed on failure, cancellation or disconnect.
 *
 * Usage per session: call [attach] before `session.start`, and pass every message to
 * [onMessage] first (it returns true for transfer messages).
 */
class FileTransfers(
    private val scope: CoroutineScope,
    private val incoming: IncomingFiles,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _transfers = MutableStateFlow<List<Transfer>>(emptyList())
    val transfers: StateFlow<List<Transfer>> = _transfers.asStateFlow()

    private val _finished = MutableSharedFlow<Transfer>(extraBufferCapacity = 32)
    /** Emits each transfer once when it reaches DONE, FAILED or CANCELLED. */
    val finished: SharedFlow<Transfer> = _finished.asSharedFlow()

    private class Outcome(val error: TransferError?)

    private class Outgoing(val session: LinkSession) {
        val accepted = CompletableDeferred<Outcome>()
        val result = CompletableDeferred<Outcome>()

        @Volatile
        var cancelled = false
    }

    private class Receiving(val session: LinkSession, val sink: FileSink, val size: Long) {
        val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
        var received = 0L
        var closed = false
    }

    private val outgoing = ConcurrentHashMap<Long, Outgoing>()
    private val receiving = ConcurrentHashMap<Long, Receiving>()
    private val lastPublish = ConcurrentHashMap<Long, Long>()
    private val random = SecureRandom()

    // --- wiring -------------------------------------------------------------------------

    fun attach(session: LinkSession) {
        session.binaryHandler = { type, payload ->
            if (type == FrameType.FILE_CHUNK) onChunk(session, payload)
        }
        scope.launch {
            session.awaitClosed()
            onSessionClosed(session)
        }
    }

    /** Returns true if [message] belonged to file transfer and was handled. */
    suspend fun onMessage(session: LinkSession, message: Message): Boolean {
        when (message) {
            is FileOffer -> onOffer(session, message)
            is FileAccept -> outgoingFor(session, message.id)?.accepted?.complete(Outcome(null))
            is FileReject -> outgoingFor(session, message.id)?.accepted?.complete(Outcome(message.reason))
            is FileComplete -> onComplete(session, message)
            is FileResult -> outgoingFor(session, message.id)?.result
                ?.complete(Outcome(if (message.ok) null else message.error ?: TransferError.IO))
            is FileCancel -> onPeerCancel(session, message.id)
            else -> return false
        }
        return true
    }

    // --- sending ------------------------------------------------------------------------

    /** Starts sending [source] over [session] in the background. Returns the transfer id. */
    fun send(session: LinkSession, source: FileSource): Long {
        val id = newId()
        val out = Outgoing(session)
        outgoing[id] = out
        add(Transfer(id, Direction.SEND, source.name, source.size, 0, TransferStatus.WAITING, session.peerName, kind = source.kind))

        scope.launch(Dispatchers.IO) {
            val error = try {
                runSend(id, out, session, source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (out.cancelled) TransferError.CANCELLED else TransferError.CONNECTION_LOST
            } catch (e: Exception) {
                TransferError.IO
            }
            outgoing.remove(id)
            finish(id, error, location = null)
        }
        return id
    }

    private suspend fun runSend(id: Long, out: Outgoing, session: LinkSession, source: FileSource): TransferError? {
        session.send(FileOffer(id, source.name, source.size, source.mime, source.kind))
        val answer = withTimeoutOrNull(DroidLinkDefaults.FILE_RESPONSE_TIMEOUT_MS) { out.accepted.await() }
            ?: return abortSend(id, session, TransferError.TIMEOUT)
        answer.error?.let { return it }

        update(id) { it.copy(status = TransferStatus.ACTIVE) }
        val digest = MessageDigest.getInstance("SHA-256")
        val chunk = DroidLinkDefaults.FILE_CHUNK_BYTES
        val buffer = ByteArray(8 + chunk)
        var sent = 0L

        source.open().use { input ->
            while (true) {
                if (out.cancelled) return TransferError.CANCELLED
                val n = input.read(buffer, 8, chunk)
                if (n < 0) break
                if (n == 0) continue
                ByteBuffer.wrap(buffer, 0, 8).putLong(id)
                digest.update(buffer, 8, n)
                // sendBinary writes the frame before returning, so a full buffer can be reused.
                session.sendBinary(FrameType.FILE_CHUNK, if (n == chunk) buffer else buffer.copyOf(8 + n))
                sent += n
                progress(id, sent)
            }
        }
        if (sent != source.size) return abortSend(id, session, TransferError.IO) // file changed while sending

        session.send(FileComplete(id, digest.digest().toHex()))
        val result = withTimeoutOrNull(DroidLinkDefaults.FILE_RESPONSE_TIMEOUT_MS) { out.result.await() }
            ?: return TransferError.TIMEOUT
        return result.error
    }

    private suspend fun abortSend(id: Long, session: LinkSession, error: TransferError): TransferError {
        runCatching { session.send(FileCancel(id)) }
        return error
    }

    // --- receiving ----------------------------------------------------------------------

    private suspend fun onOffer(session: LinkSession, offer: FileOffer) {
        val name = FileNames.sanitize(offer.name)
        val sink = try {
            if (offer.size < 0) throw IOException("negative size")
            incoming.open(name, offer.size, offer.mime, offer.kind)
        } catch (e: Exception) {
            add(Transfer(offer.id, Direction.RECEIVE, name, offer.size, 0, TransferStatus.FAILED, session.peerName, TransferError.STORAGE, kind = offer.kind))
            _transfers.value.firstOrNull { it.id == offer.id }?.let { _finished.tryEmit(it) }
            runCatching { session.send(FileReject(offer.id, TransferError.STORAGE)) }
            return
        }
        receiving[offer.id] = Receiving(session, sink, offer.size)
        add(Transfer(offer.id, Direction.RECEIVE, name, offer.size, 0, TransferStatus.ACTIVE, session.peerName, kind = offer.kind))
        try {
            session.send(FileAccept(offer.id))
        } catch (_: IOException) {
            failReceive(offer.id, TransferError.CONNECTION_LOST, notifyPeer = false)
        }
    }

    private suspend fun onChunk(session: LinkSession, payload: ByteArray) {
        if (payload.size < 8) return
        val id = ByteBuffer.wrap(payload, 0, 8).long
        val r = receiving[id]?.takeIf { it.session === session } ?: return
        val length = payload.size - 8
        val written = synchronized(r) {
            if (r.closed) return
            if (r.received + length > r.size) {
                false
            } else {
                try {
                    r.sink.output.write(payload, 8, length)
                    r.digest.update(payload, 8, length)
                    r.received += length
                    true
                } catch (_: IOException) {
                    null
                }
            }
        }
        when (written) {
            true -> progress(id, r.received)
            false -> failReceive(id, TransferError.IO, notifyPeer = true) // more data than offered
            null -> failReceive(id, TransferError.STORAGE, notifyPeer = true)
        }
    }

    private suspend fun onComplete(session: LinkSession, complete: FileComplete) {
        val r = receiving[complete.id]?.takeIf { it.session === session }
        if (r == null) {
            // Already cancelled or failed here; tell the sender so it doesn't wait.
            runCatching { session.send(FileResult(complete.id, ok = false, error = TransferError.CANCELLED)) }
            return
        }
        receiving.remove(complete.id)
        val actual = synchronized(r) {
            r.closed = true
            r.digest.digest().toHex()
        }
        if (r.received != r.size || !actual.equals(complete.sha256, ignoreCase = true)) {
            r.sink.abort()
            runCatching { session.send(FileResult(complete.id, ok = false, error = TransferError.CHECKSUM_MISMATCH)) }
            finish(complete.id, TransferError.CHECKSUM_MISMATCH, location = null)
            return
        }
        val location = try {
            r.sink.commit()
        } catch (_: Exception) {
            r.sink.abort()
            null
        }
        if (location == null) {
            runCatching { session.send(FileResult(complete.id, ok = false, error = TransferError.STORAGE)) }
            finish(complete.id, TransferError.STORAGE, location = null)
        } else {
            runCatching { session.send(FileResult(complete.id, ok = true)) }
            finish(complete.id, null, location)
        }
    }

    private suspend fun failReceive(id: Long, error: TransferError, notifyPeer: Boolean) {
        val r = receiving.remove(id) ?: return
        synchronized(r) {
            r.closed = true
            r.sink.abort()
        }
        if (notifyPeer) runCatching { r.session.send(FileCancel(id)) }
        finish(id, error, location = null)
    }

    // --- cancellation and disconnect ----------------------------------------------------

    /** Cancels a transfer in either direction and tells the peer. */
    fun cancel(id: Long) {
        outgoing[id]?.let { out ->
            out.cancelled = true
            out.accepted.complete(Outcome(TransferError.CANCELLED))
            out.result.complete(Outcome(TransferError.CANCELLED))
            scope.launch { runCatching { out.session.send(FileCancel(id)) } }
        }
        if (receiving.containsKey(id)) {
            scope.launch { failReceive(id, TransferError.CANCELLED, notifyPeer = true) }
        }
    }

    private suspend fun onPeerCancel(session: LinkSession, id: Long) {
        outgoingFor(session, id)?.let { out ->
            out.cancelled = true
            out.accepted.complete(Outcome(TransferError.CANCELLED))
            out.result.complete(Outcome(TransferError.CANCELLED))
        }
        if (receiving[id]?.session === session) failReceive(id, TransferError.CANCELLED, notifyPeer = false)
    }

    private suspend fun onSessionClosed(session: LinkSession) {
        receiving.filterValues { it.session === session }.keys.forEach {
            failReceive(it, TransferError.CONNECTION_LOST, notifyPeer = false)
        }
        outgoing.values.filter { it.session === session }.forEach { out ->
            out.accepted.complete(Outcome(TransferError.CONNECTION_LOST))
            out.result.complete(Outcome(TransferError.CONNECTION_LOST))
        }
    }

    /** Removes finished transfers from [transfers]. */
    fun clearFinished() {
        _transfers.update { list -> list.filter { it.isActive } }
    }

    // --- state helpers ------------------------------------------------------------------

    private fun outgoingFor(session: LinkSession, id: Long): Outgoing? =
        outgoing[id]?.takeIf { it.session === session }

    private fun newId(): Long {
        while (true) {
            val id = random.nextLong() and Long.MAX_VALUE
            if (id != 0L && !outgoing.containsKey(id) && !receiving.containsKey(id)) return id
        }
    }

    private fun add(transfer: Transfer) {
        _transfers.update { list -> list.filterNot { it.id == transfer.id } + transfer }
    }

    private fun update(id: Long, transform: (Transfer) -> Transfer) {
        _transfers.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    /** Publishes progress at most every [PROGRESS_INTERVAL_MS] per transfer. */
    private fun progress(id: Long, transferred: Long) {
        val now = clock()
        val last = lastPublish[id] ?: 0L
        if (now - last < PROGRESS_INTERVAL_MS) return
        lastPublish[id] = now
        update(id) { it.copy(transferred = transferred) }
    }

    private fun finish(id: Long, error: TransferError?, location: String?) {
        lastPublish.remove(id)
        update(id) {
            when (error) {
                null -> it.copy(status = TransferStatus.DONE, transferred = it.size, location = location)
                TransferError.CANCELLED -> it.copy(status = TransferStatus.CANCELLED, error = error)
                else -> it.copy(status = TransferStatus.FAILED, error = error)
            }
        }
        _transfers.value.firstOrNull { it.id == id }?.let { _finished.tryEmit(it) }
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 100L

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
