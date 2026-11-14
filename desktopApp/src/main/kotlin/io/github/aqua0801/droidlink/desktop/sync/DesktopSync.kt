package io.github.aqua0801.droidlink.desktop.sync

import io.github.aqua0801.droidlink.shared.crypto.Fingerprint
import io.github.aqua0801.droidlink.shared.link.LinkSession
import io.github.aqua0801.droidlink.shared.protocol.FileKind
import io.github.aqua0801.droidlink.shared.protocol.Message
import io.github.aqua0801.droidlink.shared.protocol.SyncDelete
import io.github.aqua0801.droidlink.shared.protocol.SyncDeleted
import io.github.aqua0801.droidlink.shared.protocol.SyncDone
import io.github.aqua0801.droidlink.shared.protocol.SyncList
import io.github.aqua0801.droidlink.shared.protocol.SyncListRequest
import io.github.aqua0801.droidlink.shared.sync.SyncPlan
import io.github.aqua0801.droidlink.shared.transfer.FileSource
import io.github.aqua0801.droidlink.shared.transfer.FileTransfers
import io.github.aqua0801.droidlink.shared.transfer.TransferStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.URLConnection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class SyncResult(val updated: Int, val unchanged: Int, val deleted: Int, val failed: Int)

enum class SyncFailure { FOLDER_MISSING, PHONE_NOT_READY, NO_REPLY, DISCONNECTED }

sealed interface SyncStatus {
    /** [total] is the number of files that need sending; 0 while still comparing. */
    data class Running(val done: Int, val total: Int) : SyncStatus
    data class Finished(val result: SyncResult, val at: Long) : SyncStatus
    data class Failed(val reason: SyncFailure, val at: Long) : SyncStatus
}

/**
 * Pushes the PC sync folder to phones (see the protocol notes next to SyncEntry). One run
 * per device at a time; files are sent one after another so progress is easy to follow
 * and a large folder does not open hundreds of transfers at once.
 */
class DesktopSync(
    private val scope: CoroutineScope,
    private val files: FileTransfers,
    private val scanner: SyncFolderScanner,
    private val folder: () -> File,
    private val mirror: () -> Boolean,
) {
    private val _status = MutableStateFlow<Map<Fingerprint, SyncStatus>>(emptyMap())
    val status: StateFlow<Map<Fingerprint, SyncStatus>> = _status.asStateFlow()

    private val jobs = ConcurrentHashMap<Fingerprint, Job>()
    private val replies = ConcurrentHashMap<Long, CompletableDeferred<Message>>()
    private val ids = AtomicLong(System.currentTimeMillis())

    fun isRunning(fingerprint: Fingerprint): Boolean = jobs[fingerprint]?.isActive == true

    fun start(fingerprint: Fingerprint, session: LinkSession) {
        if (isRunning(fingerprint)) return
        jobs[fingerprint] = scope.launch(Dispatchers.IO) {
            val outcome = try {
                run(fingerprint, session)
            } catch (_: IOException) {
                SyncStatus.Failed(SyncFailure.DISCONNECTED, System.currentTimeMillis())
            }
            set(fingerprint, outcome)
        }
    }

    /** Returns true if [message] was a sync reply. */
    fun onMessage(message: Message): Boolean {
        val id = when (message) {
            is SyncList -> message.requestId
            is SyncDeleted -> message.requestId
            else -> return false
        }
        replies.remove(id)?.complete(message)
        return true
    }

    private suspend fun run(fingerprint: Fingerprint, session: LinkSession): SyncStatus {
        set(fingerprint, SyncStatus.Running(0, 0))
        val root = folder()
        if (!root.isDirectory) return failed(SyncFailure.FOLDER_MISSING)
        val local = scanner.scan(root)

        val requestId = ids.incrementAndGet()
        val list = request<SyncList>(requestId, LIST_TIMEOUT_MS) { session.send(SyncListRequest(requestId)) }
            ?: return failed(SyncFailure.NO_REPLY)
        if (!list.ready) return failed(SyncFailure.PHONE_NOT_READY)

        val plan = SyncPlan.of(local.map { it.entry }, list.entries, mirror())
        val byPath = local.associateBy { it.entry.path }
        var updated = 0
        var failed = 0
        set(fingerprint, SyncStatus.Running(0, plan.send.size))
        for ((index, entry) in plan.send.withIndex()) {
            val file = byPath.getValue(entry.path).file
            val id = files.send(
                session,
                FileSource(file.name, entry.size, URLConnection.guessContentTypeFromName(file.name), FileKind.SYNC, entry.path) {
                    file.inputStream()
                },
            )
            val result = files.transfers.first { list -> list.any { it.id == id && !it.isActive } }.first { it.id == id }
            if (result.status == TransferStatus.DONE) updated++ else failed++
            // A closed session fails every remaining transfer at once; stop instead.
            if (result.status != TransferStatus.DONE && !session.isOpen) return failed(SyncFailure.DISCONNECTED)
            set(fingerprint, SyncStatus.Running(index + 1, plan.send.size))
        }

        var deleted = 0
        if (plan.delete.isNotEmpty()) {
            val deleteId = ids.incrementAndGet()
            val reply = request<SyncDeleted>(deleteId, DELETE_TIMEOUT_MS) { session.send(SyncDelete(deleteId, plan.delete)) }
            if (reply != null) {
                deleted = reply.deleted
                failed += reply.failed
            } else {
                failed += plan.delete.size
            }
        }

        session.send(SyncDone(requestId, updated, plan.unchanged, deleted, failed))
        return SyncStatus.Finished(SyncResult(updated, plan.unchanged, deleted, failed), System.currentTimeMillis())
    }

    private suspend inline fun <reified T : Message> request(id: Long, timeoutMs: Long, send: () -> Unit): T? {
        val reply = CompletableDeferred<Message>()
        replies[id] = reply
        try {
            send()
            return withTimeoutOrNull(timeoutMs) { reply.await() } as? T
        } finally {
            replies.remove(id)
        }
    }

    private fun failed(reason: SyncFailure) = SyncStatus.Failed(reason, System.currentTimeMillis())

    private fun set(fingerprint: Fingerprint, status: SyncStatus) {
        _status.update { it + (fingerprint to status) }
    }

    private companion object {
        /** The phone may have to hash files it has not seen before. */
        const val LIST_TIMEOUT_MS = 5 * 60_000L
        const val DELETE_TIMEOUT_MS = 60_000L
    }
}
