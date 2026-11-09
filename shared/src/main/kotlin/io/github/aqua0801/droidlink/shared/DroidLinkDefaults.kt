package io.github.aqua0801.droidlink.shared

object DroidLinkDefaults {
    /** TCP port the desktop app listens on. */
    const val PORT = 47100

    /** DNS-SD service type advertised by the desktop app on the LAN. */
    const val MDNS_SERVICE_TYPE = "_droidlink._tcp"

    /** TXT record key carrying the desktop's certificate fingerprint. */
    const val MDNS_TXT_FINGERPRINT = "fp"

    const val HANDSHAKE_TIMEOUT_MS = 10_000
    const val SESSION_READ_TIMEOUT_MS = 45_000
    const val PING_INTERVAL_MS = 15_000L
    const val PAIRING_TOKEN_TTL_MS = 5 * 60_000L

    /** Larger clipboard texts are not synced (UTF-16 chars; keeps frames well under the limit). */
    const val CLIPBOARD_MAX_CHARS = 1_000_000

    /** File data per FILE_CHUNK frame; small enough that control messages interleave promptly. */
    const val FILE_CHUNK_BYTES = 64 * 1024

    /** How long a sender waits for the receiver to accept, or to confirm the digest. */
    const val FILE_RESPONSE_TIMEOUT_MS = 60_000L
}
