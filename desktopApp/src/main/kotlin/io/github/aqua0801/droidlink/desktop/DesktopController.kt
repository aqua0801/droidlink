package io.github.aqua0801.droidlink.desktop

import androidx.compose.ui.graphics.ImageBitmap
import io.github.aqua0801.droidlink.desktop.net.MdnsAdvertiser
import io.github.aqua0801.droidlink.desktop.net.NetworkInfo
import io.github.aqua0801.droidlink.desktop.platform.SystemClipboard
import io.github.aqua0801.droidlink.desktop.platform.TrayNotifier
import io.github.aqua0801.droidlink.desktop.resources.Res
import io.github.aqua0801.droidlink.desktop.resources.notify_critical_body
import io.github.aqua0801.droidlink.desktop.resources.notify_critical_title
import io.github.aqua0801.droidlink.desktop.resources.notify_full_body
import io.github.aqua0801.droidlink.desktop.resources.notify_full_title
import io.github.aqua0801.droidlink.desktop.resources.notify_low_body
import io.github.aqua0801.droidlink.desktop.resources.notify_low_title
import io.github.aqua0801.droidlink.desktop.resources.notify_paired_body
import io.github.aqua0801.droidlink.desktop.resources.notify_paired_title
import io.github.aqua0801.droidlink.shared.crypto.Fingerprint
import io.github.aqua0801.droidlink.shared.crypto.Identity
import io.github.aqua0801.droidlink.shared.crypto.IdentityFile
import io.github.aqua0801.droidlink.shared.link.LinkServer
import io.github.aqua0801.droidlink.shared.link.LinkSession
import io.github.aqua0801.droidlink.shared.link.MessageHandler
import io.github.aqua0801.droidlink.shared.pairing.PairingInfo
import io.github.aqua0801.droidlink.shared.pairing.PairingTokens
import io.github.aqua0801.droidlink.shared.protocol.BatteryStatus
import io.github.aqua0801.droidlink.shared.protocol.ClipboardText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

sealed interface ServerStatus {
    data object Starting : ServerStatus
    data class Running(val port: Int) : ServerStatus
    data class Failed(val message: String) : ServerStatus
}

data class ConnectedDevice(
    val fingerprint: Fingerprint,
    val name: String,
    val platform: String,
    val address: String,
    val battery: BatteryStatus? = null,
)

class PairingOffer(val info: PairingInfo, val qr: ImageBitmap, val expiresAt: Long)

/** Owns the server, the trusted-device list, pairing and per-device state. UI reads the flows. */
class DesktopController(
    private val scope: CoroutineScope,
    private val notifier: TrayNotifier,
) {
    private val paths = AppPaths.default()

    val serverName: String = hostName()
    val identity: Identity = IdentityFile.loadOrCreate(paths.identityFile, "DroidLink PC $serverName")
    val trustedPeers = TrustedPeerStore(paths.trustedFile)
    val settings = SettingsStore(paths.settingsFile)

    private val tokens = PairingTokens()
    private val sessions = ConcurrentHashMap<Fingerprint, LinkSession>()
    private val batteryAlerts = ConcurrentHashMap<Fingerprint, BatteryAlertPolicy>()

    val clipboard = ClipboardSync(SystemClipboard.create(), scope, ::broadcastClipboard)

    private val _serverStatus = MutableStateFlow<ServerStatus>(ServerStatus.Starting)
    val serverStatus: StateFlow<ServerStatus> = _serverStatus.asStateFlow()

    private val _devices = MutableStateFlow<List<ConnectedDevice>>(emptyList())
    val devices: StateFlow<List<ConnectedDevice>> = _devices.asStateFlow()

    private val _pairing = MutableStateFlow<PairingOffer?>(null)
    val pairing: StateFlow<PairingOffer?> = _pairing.asStateFlow()

    private var server: LinkServer? = null
    private var mdns: MdnsAdvertiser? = null
    private var pairingExpiry: Job? = null

    fun start() {
        clipboard.start()
        scope.launch(Dispatchers.IO) {
            try {
                val linkServer = LinkServer(
                    identity = identity,
                    serverName = serverName,
                    trust = trustedPeers,
                    pairing = tokens,
                    listener = ::onSessionOpened,
                    scope = scope,
                )
                linkServer.start()
                server = linkServer
                _serverStatus.value = ServerStatus.Running(linkServer.port)
                mdns = MdnsAdvertiser(serverName, linkServer.port, identity.fingerprint).also { it.start() }
            } catch (e: Exception) {
                _serverStatus.value = ServerStatus.Failed(e.message ?: e.toString())
            }
        }
    }

    fun stop() {
        cancelPairing()
        clipboard.stop()
        sessions.values.forEach { it.close() }
        server?.stop()
        // JmDNS sends goodbye packets on close, which can take a few seconds; don't hang the exit on it.
        mdns?.let { advertiser ->
            Thread { advertiser.close() }.apply { isDaemon = true; start() }.join(1_500)
        }
    }

    fun startPairing() {
        val port = (serverStatus.value as? ServerStatus.Running)?.port ?: return
        scope.launch(Dispatchers.IO) {
            val token = tokens.issue()
            val hosts = NetworkInfo.hostCandidates().ifEmpty { listOf("127.0.0.1") }
            val info = PairingInfo(serverName, hosts, port, identity.fingerprint, token.value)
            val offer = PairingOffer(info, QrCode.render(info.toUri()), token.expiresAt)
            _pairing.value = offer
            pairingExpiry?.cancel()
            pairingExpiry = scope.launch {
                delay(token.expiresAt - System.currentTimeMillis())
                _pairing.compareAndSet(offer, null)
            }
        }
    }

    fun cancelPairing() {
        tokens.cancel()
        pairingExpiry?.cancel()
        _pairing.value = null
    }

    fun forget(fingerprintHex: String) {
        val fingerprint = Fingerprint.parseOrNull(fingerprintHex) ?: return
        trustedPeers.remove(fingerprint)
        sessions[fingerprint]?.close()
        batteryAlerts.remove(fingerprint)
    }

    private fun onSessionOpened(session: LinkSession): MessageHandler {
        val fingerprint = session.peerFingerprint
        // A phone that reconnects replaces its stale session.
        sessions.put(fingerprint, session)?.takeIf { it !== session }?.close()
        _devices.update { list ->
            list.filterNot { it.fingerprint == fingerprint } +
                ConnectedDevice(fingerprint, session.peerName, session.peerPlatform, session.remoteAddress)
        }
        if (session.newlyPaired) {
            cancelPairing()
            scope.launch {
                notifier.notify(getString(Res.string.notify_paired_title), getString(Res.string.notify_paired_body, session.peerName))
            }
        }
        scope.launch {
            session.awaitClosed()
            if (sessions.remove(fingerprint, session)) {
                _devices.update { list -> list.filterNot { it.fingerprint == fingerprint } }
            }
        }
        return { message ->
            when (message) {
                is BatteryStatus -> onBattery(fingerprint, session.peerName, message)
                is ClipboardText -> if (settings.settings.value.clipboardReceiveFromPhone) clipboard.applyRemote(message.text)
                else -> Unit
            }
        }
    }

    /** Sends PC clipboard text to every connected phone. Returns true if at least one got it. */
    private suspend fun broadcastClipboard(text: String): Boolean {
        if (!settings.settings.value.clipboardSendToPhone) return false
        var delivered = false
        for (session in sessions.values) {
            try {
                session.send(ClipboardText(text))
                delivered = true
            } catch (_: IOException) {
                // that session is going away; the others still get it
            }
        }
        return delivered
    }

    private suspend fun onBattery(fingerprint: Fingerprint, name: String, status: BatteryStatus) {
        _devices.update { list -> list.map { if (it.fingerprint == fingerprint) it.copy(battery = status) else it } }
        val policy = batteryAlerts.getOrPut(fingerprint) { BatteryAlertPolicy() }
        val level = "${status.level}%"
        for (alert in policy.evaluate(status)) {
            val (title, body) = when (alert) {
                BatteryAlertPolicy.Alert.FULL -> Res.string.notify_full_title to Res.string.notify_full_body
                BatteryAlertPolicy.Alert.LOW -> Res.string.notify_low_title to Res.string.notify_low_body
                BatteryAlertPolicy.Alert.CRITICAL -> Res.string.notify_critical_title to Res.string.notify_critical_body
            }
            notifier.notify(getString(title, name), getString(body, level))
        }
    }

    private companion object {
        fun hostName(): String =
            System.getenv("COMPUTERNAME")
                ?: System.getenv("HOSTNAME")
                ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
                ?: "PC"
    }
}
