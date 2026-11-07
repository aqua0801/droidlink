package io.github.aqua0801.droidlink.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class PairedPc(
    val fingerprint: String,
    val name: String,
    /** Hosts from the pairing QR code (LAN IPs, MagicDNS name, Tailscale IPs). */
    val hosts: List<String>,
    val port: Int,
    /** User-entered host, e.g. a Tailscale MagicDNS name. */
    val manualHost: String? = null,
    /** Last host that worked; tried first. */
    val lastHost: String? = null,
)

class PairedPcStore(context: Context) {
    private val prefs = context.getSharedPreferences("paired_pcs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(PairedPc.serializer())

    private val _pcs = MutableStateFlow(load())
    val pcs: StateFlow<List<PairedPc>> = _pcs.asStateFlow()

    /** The PC to reconnect to automatically (e.g. after the service is restarted). */
    var autoConnectFingerprint: String?
        get() = prefs.getString(KEY_AUTO_CONNECT, null)
        set(value) = prefs.edit { putString(KEY_AUTO_CONNECT, value) }

    fun get(fingerprint: String): PairedPc? = _pcs.value.firstOrNull { it.fingerprint == fingerprint }

    @Synchronized
    fun upsert(pc: PairedPc) = commit(_pcs.value.filterNot { it.fingerprint == pc.fingerprint } + pc)

    @Synchronized
    fun update(fingerprint: String, transform: (PairedPc) -> PairedPc) =
        commit(_pcs.value.map { if (it.fingerprint == fingerprint) transform(it) else it })

    @Synchronized
    fun remove(fingerprint: String) {
        commit(_pcs.value.filterNot { it.fingerprint == fingerprint })
        if (autoConnectFingerprint == fingerprint) autoConnectFingerprint = null
    }

    private fun commit(list: List<PairedPc>) {
        _pcs.value = list
        prefs.edit { putString(KEY_LIST, json.encodeToString(serializer, list)) }
    }

    private fun load(): List<PairedPc> =
        prefs.getString(KEY_LIST, null)
            ?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
            .orEmpty()

    private companion object {
        const val KEY_LIST = "list"
        const val KEY_AUTO_CONNECT = "auto_connect"
    }
}
