package io.github.aqua0801.droidlink.shared.transfer

import io.github.aqua0801.droidlink.shared.protocol.FileKind
import io.github.aqua0801.droidlink.shared.protocol.TransferError
import java.io.InputStream
import java.io.OutputStream

enum class Direction { SEND, RECEIVE }

enum class TransferStatus { WAITING, ACTIVE, DONE, FAILED, CANCELLED }

/** Snapshot of one file transfer, for UI. */
data class Transfer(
    val id: Long,
    val direction: Direction,
    val name: String,
    val size: Long,
    val transferred: Long,
    val status: TransferStatus,
    val peerName: String,
    val error: TransferError? = null,
    /** Where a received file was saved (platform-specific: a path or a content URI). */
    val location: String? = null,
    val kind: FileKind = FileKind.FILE,
) {
    val isActive: Boolean get() = status == TransferStatus.WAITING || status == TransferStatus.ACTIVE

    val progress: Float
        get() = when {
            status == TransferStatus.DONE -> 1f
            size <= 0 -> 0f
            else -> (transferred.toFloat() / size).coerceIn(0f, 1f)
        }
}

/** A file to send. [open] is called once, on a background thread. */
class FileSource(
    val name: String,
    val size: Long,
    val mime: String?,
    val kind: FileKind = FileKind.FILE,
    private val opener: () -> InputStream,
) {
    fun open(): InputStream = opener()
}

/** Where incoming files go. Implemented per platform. */
interface IncomingFiles {
    /** Creates a destination for an offered file. Throw to decline (reported as STORAGE). */
    fun open(name: String, size: Long, mime: String?, kind: FileKind = FileKind.FILE): FileSink
}

/** A destination being written. Exactly one of [commit] or [abort] is called. */
interface FileSink {
    val output: OutputStream

    /** Closes the output, makes the file visible, and returns its location. */
    fun commit(): String

    /** Closes the output and removes the partial file. Must not throw. */
    fun abort()
}
