package io.github.aqua0801.droidlink.desktop.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

object NetworkInfo {
    /** Adapters a phone can't reach anyway (WSL/Hyper-V/VM/Docker bridges); skipping them saves connect timeouts. */
    private val virtualAdapterHints = listOf("vethernet", "hyper-v", "virtualbox", "vmware", "wsl", "docker", "npcap", "loopback")

    private data class LocalAddress(val address: Inet4Address, val tailscale: Boolean)

    private fun localIpv4(): List<LocalAddress> =
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .filterNot { nif ->
                val label = "${nif.name} ${nif.displayName}".lowercase()
                virtualAdapterHints.any { it in label }
            }
            .flatMap { it.inetAddresses.toList().filterIsInstance<Inet4Address>() }
            .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
            .map { LocalAddress(it, isTailscale(it)) }

    /** Private LAN addresses, used for mDNS advertising. */
    fun lanAddresses(): List<Inet4Address> =
        localIpv4().filter { !it.tailscale && it.address.isSiteLocalAddress }.map { it.address }

    /** Addresses for the pairing QR code, in the order the phone should try them. */
    fun hostCandidates(): List<String> {
        val all = localIpv4()
        val lan = all.filter { !it.tailscale && it.address.isSiteLocalAddress }.map { it.address.hostAddress }
        val tailscaleIps = all.filter { it.tailscale }.map { it.address.hostAddress }
        return (lan + listOfNotNull(tailscaleDnsName()) + tailscaleIps).distinct()
    }

    /** Tailscale CGNAT range 100.64.0.0/10. */
    private fun isTailscale(address: Inet4Address): Boolean {
        val bytes = address.address
        return (bytes[0].toInt() and 0xff) == 100 && (bytes[1].toInt() and 0xc0) == 64
    }

    /** This machine's MagicDNS name via the Tailscale CLI, e.g. `my-pc.tail1234.ts.net`. */
    fun tailscaleDnsName(): String? = runCatching {
        val out = File.createTempFile("droidlink-ts", ".json")
        try {
            val process = ProcessBuilder("tailscale", "status", "--json")
                .redirectOutput(out)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return@runCatching null
            }
            Json.parseToJsonElement(out.readText())
                .jsonObject["Self"]?.jsonObject?.get("DNSName")?.jsonPrimitive?.content
                ?.trimEnd('.')
                ?.takeIf { it.isNotBlank() }
        } finally {
            out.delete()
        }
    }.getOrNull()
}
