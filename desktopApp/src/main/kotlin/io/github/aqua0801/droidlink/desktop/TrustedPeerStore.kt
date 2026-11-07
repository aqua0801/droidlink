package io.github.aqua0801.droidlink.desktop

import io.github.aqua0801.droidlink.shared.crypto.Fingerprint
import io.github.aqua0801.droidlink.shared.link.PeerTrust
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

@Serializable
data class TrustedPeer(val fingerprint: String, val name: String, val pairedAt: Long)

/** Phones allowed to connect without a pairing token, persisted as JSON. */
class TrustedPeerStore(private val file: File) : PeerTrust {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }
    private val serializer = ListSerializer(TrustedPeer.serializer())

    private val _peers = MutableStateFlow(load())
    val peers: StateFlow<List<TrustedPeer>> = _peers.asStateFlow()

    override fun isTrusted(fingerprint: Fingerprint): Boolean =
        _peers.value.any { it.fingerprint == fingerprint.hex }

    @Synchronized
    override fun addTrusted(fingerprint: Fingerprint, name: String) {
        val next = _peers.value.filterNot { it.fingerprint == fingerprint.hex } +
            TrustedPeer(fingerprint.hex, name, System.currentTimeMillis())
        _peers.value = next
        save(next)
    }

    @Synchronized
    fun remove(fingerprint: Fingerprint) {
        val next = _peers.value.filterNot { it.fingerprint == fingerprint.hex }
        _peers.value = next
        save(next)
    }

    private fun load(): List<TrustedPeer> =
        runCatching { if (file.isFile) json.decodeFromString(serializer, file.readText()) else emptyList() }
            .getOrElse { emptyList() }

    private fun save(peers: List<TrustedPeer>) {
        file.writeTextAtomically(json.encodeToString(serializer, peers))
    }
}
