package io.github.aqua0801.droidlink.shared.pairing

import io.github.aqua0801.droidlink.shared.crypto.Fingerprint
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Content of the pairing QR code shown on the PC:
 *
 * ```
 * droidlink://pair?v=1&n=<pc name>&h=<host1,host2,...>&p=<port>&fp=<sha256 hex>&t=<token>
 * ```
 *
 * `fp` is what makes the first connection safe: the phone refuses any server whose
 * certificate does not match it, so the token can't be stolen by a look-alike host.
 */
data class PairingInfo(
    val serverName: String,
    /** Tried in order: LAN addresses, Tailscale MagicDNS name, Tailscale IPs. */
    val hosts: List<String>,
    val port: Int,
    val fingerprint: Fingerprint,
    val token: String,
) {
    fun toUri(): String = buildString {
        append("$SCHEME://pair?v=1")
        append("&n=").append(encode(serverName))
        append("&h=").append(hosts.joinToString(",") { encode(it) })
        append("&p=").append(port)
        append("&fp=").append(fingerprint.hex)
        append("&t=").append(encode(token))
    }

    companion object {
        const val SCHEME = "droidlink"

        fun parse(text: String): PairingInfo? {
            val prefix = "$SCHEME://pair?"
            val trimmed = text.trim()
            if (!trimmed.startsWith(prefix)) return null
            val params = trimmed.removePrefix(prefix).split('&').mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null else part.substring(0, i) to part.substring(i + 1)
            }.toMap()

            if (params["v"] != "1") return null
            val fingerprint = params["fp"]?.let { Fingerprint.parseOrNull(it) } ?: return null
            val port = params["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val token = params["t"]?.let(::decode)?.takeIf { it.isNotEmpty() } ?: return null
            val hosts = params["h"].orEmpty().split(',').map(::decode).filter { it.isNotBlank() }
            if (hosts.isEmpty()) return null
            val name = params["n"]?.let(::decode)?.takeIf { it.isNotBlank() } ?: "PC"
            return PairingInfo(name, hosts, port, fingerprint, token)
        }

        private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")
        private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
    }
}
