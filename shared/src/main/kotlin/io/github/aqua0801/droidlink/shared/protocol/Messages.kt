package io.github.aqua0801.droidlink.shared.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val PROTOCOL_VERSION = 1

/**
 * Every control message exchanged over a link. Encoded as JSON with a `t` discriminator.
 * Unknown message types are skipped by the receiver, so new types can be added without
 * breaking older peers.
 */
@Serializable
sealed class Message

// ---- Handshake -------------------------------------------------------------------------

/** First message sent by the client (phone) after the TLS handshake. */
@Serializable
@SerialName("hello")
data class Hello(
    val deviceName: String,
    val platform: String,
    val protocolVersion: Int,
    val appVersion: String,
    /** One-time pairing token from the QR code; only present when pairing. */
    val pairToken: String? = null,
) : Message()

/** Server accepted the client. */
@Serializable
@SerialName("welcome")
data class Welcome(
    val serverName: String,
    val platform: String,
    val protocolVersion: Int,
    val newlyPaired: Boolean,
) : Message()

/** Server refused the client; the connection is closed right after. */
@Serializable
@SerialName("rejected")
data class Rejected(val reason: RejectReason, val detail: String? = null) : Message()

@Serializable
enum class RejectReason { NOT_PAIRED, BAD_TOKEN, PROTOCOL_MISMATCH }

// ---- Session housekeeping --------------------------------------------------------------

@Serializable
@SerialName("ping")
data class Ping(val nonce: Long) : Message()

@Serializable
@SerialName("pong")
data class Pong(val nonce: Long) : Message()

@Serializable
@SerialName("bye")
data class Bye(val reason: String? = null) : Message()

// ---- Features --------------------------------------------------------------------------

@Serializable
@SerialName("battery")
data class BatteryStatus(
    /** 0..100, or -1 if unknown. */
    val level: Int,
    val charging: Boolean,
    val plugged: PowerSource,
    val full: Boolean,
    val temperatureC: Float? = null,
    val voltageMv: Int? = null,
) : Message()

@Serializable
enum class PowerSource { NONE, AC, USB, WIRELESS, DOCK, UNKNOWN }

/** Plain-text clipboard content, sent in either direction. */
@Serializable
@SerialName("clip")
data class ClipboardText(val text: String) : Message()

// ---- File transfer (either direction; see the transfer package) ------------------------

/** Sender proposes a file. Data follows as FILE_CHUNK frames once accepted. */
@Serializable
@SerialName("file.offer")
data class FileOffer(
    val id: Long,
    val name: String,
    val size: Long,
    val mime: String? = null,
    /** Omitted on the wire for ordinary files, so older peers still understand the offer. */
    val kind: FileKind = FileKind.FILE,
    /** For [FileKind.SYNC]: location inside the sync folder, `/`-separated (see SyncPaths). */
    val path: String? = null,
) : Message()

/** Why a file is being sent; the receiver may store or present it differently. */
@Serializable
enum class FileKind {
    FILE,
    SCREENSHOT,

    /** Part of a sync-folder push; replaces the file at [FileOffer.path]. */
    SYNC,
}

// ---- Sync folder -----------------------------------------------------------------------
// The PC folder is the source of truth. A sync run, always started on the PC:
//   PC -> sync.list.request, phone -> sync.list (what it has),
//   PC sends missing or different files as FileKind.SYNC transfers,
//   optionally PC -> sync.delete / phone -> sync.deleted for files the PC no longer has,
//   PC -> sync.done with the totals.

/** One file in a sync folder. [path] is relative to the folder root, `/`-separated. */
@Serializable
data class SyncEntry(val path: String, val size: Long, val sha256: String)

@Serializable
@SerialName("sync.list.request")
data class SyncListRequest(val requestId: Long) : Message()

/** [ready] is false when the phone has no sync folder set up yet. */
@Serializable
@SerialName("sync.list")
data class SyncList(val requestId: Long, val ready: Boolean, val entries: List<SyncEntry> = emptyList()) : Message()

@Serializable
@SerialName("sync.delete")
data class SyncDelete(val requestId: Long, val paths: List<String>) : Message()

@Serializable
@SerialName("sync.deleted")
data class SyncDeleted(val requestId: Long, val deleted: Int, val failed: Int) : Message()

@Serializable
@SerialName("sync.done")
data class SyncDone(val requestId: Long, val updated: Int, val unchanged: Int, val deleted: Int, val failed: Int) : Message()

@Serializable
@SerialName("file.accept")
data class FileAccept(val id: Long) : Message()

@Serializable
@SerialName("file.reject")
data class FileReject(val id: Long, val reason: TransferError) : Message()

/** Sender finished streaming; the receiver verifies the digest and answers with [FileResult]. */
@Serializable
@SerialName("file.complete")
data class FileComplete(val id: Long, val sha256: String) : Message()

@Serializable
@SerialName("file.result")
data class FileResult(val id: Long, val ok: Boolean, val error: TransferError? = null) : Message()

/** Either side aborts the transfer. */
@Serializable
@SerialName("file.cancel")
data class FileCancel(val id: Long) : Message()

@Serializable
enum class TransferError { DECLINED, STORAGE, TIMEOUT, CONNECTION_LOST, CHECKSUM_MISMATCH, CANCELLED, IO }
