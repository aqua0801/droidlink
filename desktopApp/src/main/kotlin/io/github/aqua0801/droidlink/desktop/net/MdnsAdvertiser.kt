package io.github.aqua0801.droidlink.desktop.net

import io.github.aqua0801.droidlink.shared.DroidLinkDefaults
import io.github.aqua0801.droidlink.shared.crypto.Fingerprint
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * Announces `_droidlink._tcp` on every LAN interface so paired phones on the same network
 * find the PC even after its DHCP address changes. The TXT record carries the certificate
 * fingerprint so a phone can tell which paired PC this is.
 */
class MdnsAdvertiser(
    private val instanceName: String,
    private val port: Int,
    private val fingerprint: Fingerprint,
) : Closeable {
    private val instances = CopyOnWriteArrayList<JmDNS>()

    fun start() {
        for (address in NetworkInfo.lanAddresses()) {
            runCatching {
                val jmdns = JmDNS.create(address, instanceName)
                val info = ServiceInfo.create(
                    "${DroidLinkDefaults.MDNS_SERVICE_TYPE}.local.",
                    instanceName,
                    port,
                    0,
                    0,
                    mapOf(DroidLinkDefaults.MDNS_TXT_FINGERPRINT to fingerprint.hex, "v" to "1"),
                )
                jmdns.registerService(info)
                instances += jmdns
            }.onFailure { System.err.println("DroidLink: mDNS on $address failed: $it") }
        }
    }

    override fun close() {
        instances.forEach { jmdns ->
            runCatching {
                jmdns.unregisterAllServices()
                jmdns.close()
            }
        }
        instances.clear()
    }
}
