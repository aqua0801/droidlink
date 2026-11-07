package io.github.aqua0801.droidlink.link

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import io.github.aqua0801.droidlink.shared.DroidLinkDefaults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Finds DroidLink desktops on the local network via DNS-SD. Results are keyed by the
 * certificate fingerprint from the TXT record, so they map straight onto paired PCs.
 */
class NsdDiscovery(context: Context) {
    data class Endpoint(val host: String, val port: Int)

    private val nsd = context.getSystemService(NsdManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _found = MutableStateFlow<Map<String, Endpoint>>(emptyMap())
    /** fingerprint hex → endpoint */
    val found: StateFlow<Map<String, Endpoint>> = _found.asStateFlow()

    private val nameToFingerprint = ConcurrentHashMap<String, String>()
    private val resolveQueue = Channel<NsdServiceInfo>(Channel.UNLIMITED)
    private var listener: NsdManager.DiscoveryListener? = null
    private var worker: Job? = null

    @Synchronized
    fun start() {
        if (listener != null) return
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                synchronized(this@NsdDiscovery) { if (listener === this) listener = null }
            }
            override fun onServiceFound(info: NsdServiceInfo) {
                resolveQueue.trySend(info)
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                nameToFingerprint.remove(info.serviceName)?.let { fp -> _found.update { it - fp } }
            }
        }
        listener = discovery
        // The pre-API-34 resolver handles one request at a time, so resolve serially.
        worker = scope.launch {
            for (info in resolveQueue) {
                val (fingerprint, endpoint) = resolve(info) ?: continue
                nameToFingerprint[info.serviceName] = fingerprint
                _found.update { it + (fingerprint to endpoint) }
            }
        }
        nsd.discoverServices(DroidLinkDefaults.MDNS_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
    }

    @Synchronized
    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        worker?.cancel()
        worker = null
        nameToFingerprint.clear()
        _found.value = emptyMap()
    }

    @Suppress("DEPRECATION") // resolveService works through API 36; the replacement needs API 34.
    private suspend fun resolve(info: NsdServiceInfo): Pair<String, Endpoint>? = withTimeoutOrNull(5_000) {
        suspendCancellableCoroutine { cont ->
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val fingerprint = serviceInfo.attributes[DroidLinkDefaults.MDNS_TXT_FINGERPRINT]
                        ?.toString(Charsets.UTF_8)
                    val host = serviceInfo.host?.hostAddress
                    val result = if (fingerprint != null && host != null) {
                        fingerprint to Endpoint(host, serviceInfo.port)
                    } else {
                        null
                    }
                    if (cont.isActive) cont.resume(result)
                }
            })
        }
    }
}
