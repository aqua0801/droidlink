package io.github.aqua0801.droidlink.shared.link

import io.github.aqua0801.droidlink.shared.crypto.Fingerprint
import io.github.aqua0801.droidlink.shared.protocol.Bye
import io.github.aqua0801.droidlink.shared.protocol.Message
import io.github.aqua0801.droidlink.shared.protocol.Ping
import io.github.aqua0801.droidlink.shared.protocol.Pong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

typealias MessageHandler = suspend (Message) -> Unit

/**
 * An authenticated link to one peer. Created by [LinkServer] or [LinkClient]; call [start]
 * with a handler to begin receiving. Ping/pong and bye are handled internally.
 */
class LinkSession internal constructor(
    private val connection: Connection,
    val peerName: String,
    val peerPlatform: String,
    /** True when this connection completed a pairing (first contact). */
    val newlyPaired: Boolean,
    parentScope: CoroutineScope,
    private val pingIntervalMs: Long?,
) {
    val peerFingerprint: Fingerprint get() = connection.peerFingerprint
    val remoteAddress: String get() = connection.remoteAddress

    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job + Dispatchers.IO)
    private val closed = CompletableDeferred<Unit>()
    private val started = AtomicBoolean(false)
    private val pingCounter = AtomicLong()

    val isOpen: Boolean get() = !closed.isCompleted

    init {
        // Blocking socket reads ignore coroutine cancellation, so closing the socket is
        // what actually unblocks the reader when the session (or its parent) is cancelled.
        scope.launch {
            try {
                awaitCancellation()
            } finally {
                connection.close()
            }
        }
        job.invokeOnCompletion { closed.complete(Unit) }
    }

    fun start(handler: MessageHandler) {
        check(started.compareAndSet(false, true)) { "session already started" }
        scope.launch {
            try {
                while (isActive) {
                    when (val message = connection.receive() ?: break) {
                        is Ping -> send(Pong(message.nonce))
                        is Pong -> Unit
                        is Bye -> break
                        else -> try {
                            handler(message)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            System.err.println("DroidLink: handler failed for ${message::class.simpleName}: $e")
                        }
                    }
                }
            } catch (_: IOException) {
                // socket closed or timed out
            } finally {
                close()
            }
        }
        pingIntervalMs?.let { interval ->
            scope.launch {
                while (isActive) {
                    delay(interval)
                    try {
                        send(Ping(pingCounter.incrementAndGet()))
                    } catch (_: IOException) {
                        close()
                    }
                }
            }
        }
    }

    /** @throws IOException if the link is broken. */
    suspend fun send(message: Message) {
        if (!isOpen) throw IOException("session closed")
        connection.send(message)
    }

    fun close() {
        connection.close()
        job.cancel()
    }

    suspend fun closeGracefully(reason: String? = null) {
        withTimeoutOrNull(1_000) { runCatching { send(Bye(reason)) } }
        close()
    }

    suspend fun awaitClosed() = closed.await()
}
