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
}
