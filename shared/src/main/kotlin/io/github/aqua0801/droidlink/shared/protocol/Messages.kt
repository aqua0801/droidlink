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
