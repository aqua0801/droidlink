package io.github.aqua0801.droidlink.desktop

import androidx.compose.ui.graphics.ImageBitmap
import io.github.aqua0801.droidlink.desktop.adb.AgentController
import io.github.aqua0801.droidlink.desktop.net.MdnsAdvertiser
import io.github.aqua0801.droidlink.desktop.net.NetworkInfo
import io.github.aqua0801.droidlink.desktop.platform.TrayNotifier
import io.github.aqua0801.droidlink.desktop.resources.Res
import io.github.aqua0801.droidlink.desktop.resources.notify_full_body
import io.github.aqua0801.droidlink.desktop.resources.notify_full_title
import io.github.aqua0801.droidlink.desktop.resources.notify_low_body
import io.github.aqua0801.droidlink.desktop.resources.notify_low_title
import io.github.aqua0801.droidlink.desktop.resources.notify_paired_body
import io.github.aqua0801.droidlink.desktop.resources.notify_paired_title
import io.github.aqua0801.droidlink.desktop.resources.notify_received_body
import io.github.aqua0801.droidlink.desktop.resources.notify_received_title
import io.github.aqua0801.droidlink.desktop.transfer.DesktopIncomingFiles
import io.github.aqua0801.droidlink.shared.transfer.Direction
import io.github.aqua0801.droidlink.shared.transfer.FileSource
import io.github.aqua0801.droidlink.shared.transfer.FileTransfers
import io.github.aqua0801.droidlink.shared.transfer.TransferStatus
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
import io.github.aqua0801.droidlink.shared.protocol.FileKind
import io.github.aqua0801.droidlink.desktop.mirror.MirrorController
import io.github.aqua0801.droidlink.desktop.sync.DesktopSync
import io.github.aqua0801.droidlink.desktop.sync.SyncFolderScanner
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
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
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

/** A screenshot the phone just sent, shown in the preview popup. */
data class ScreenshotNotice(val id: Long, val file: File, val peerName: String, val receivedAt: Long)

/** Why files dropped on the window were not sent. */
enum class DropNotice { NO_DEVICE, MULTIPLE_DEVICES }

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

    /** Enhanced mode (ADB). Independent of the P2P link above. */
    val agent = AgentController(scope)

    /** Phone screen over ADB (scrcpy-server); see docs/screen-mirror.md. */
    val mirror = MirrorController(scope, File(paths.downloadsDir, DesktopIncomingFiles.SCREENSHOTS))

    /** Clipboard modes, hotkeys and routing between the PC, P2P phones and enhanced mode. */
    val clipboardRouting = ClipboardCoordinator(scope, settings, agent, notifier) { sessions.values }
    val clipboard: ClipboardSync get() = clipboardRouting.sync

    /** File transfer over the P2P link, both directions. */
    val downloadsDir: File get() = paths.downloadsDir
    val files = FileTransfers(scope, DesktopIncomingFiles(paths.downloadsDir))

    /** Folder push to phones; the PC folder is the source of truth. */
    val syncFolder: File get() = settings.settings.value.sync.folder?.let(::File) ?: paths.defaultSyncDir
    val sync = DesktopSync(
        scope = scope,
        files = files,
        scanner = SyncFolderScanner(paths.syncCacheFile),
        folder = { syncFolder },
        mirror = { settings.settings.value.sync.mirror },
    )

    fun startSync(fingerprint: Fingerprint) {
        val session = sessions[fingerprint] ?: return
        // The default folder is created on demand; a custom one must already exist.
        if (settings.settings.value.sync.folder == null) paths.defaultSyncDir.mkdirs()
        sync.start(fingerprint, session)
    }

    fun resetSyncFolder() {
        settings.update { it.copy(sync = it.sync.copy(folder = null)) }
    }

    private val _screenshot = MutableStateFlow<ScreenshotNotice?>(null)
    /** The latest screenshot to preview; a newer one replaces it. */
    val screenshot: StateFlow<ScreenshotNotice?> = _screenshot.asStateFlow()

    fun dismissScreenshot(id: Long) {
        _screenshot.update { if (it?.id == id) null else it }
    }

    private val _dropNotice = MutableStateFlow<DropNotice?>(null)
    val dropNotice: StateFlow<DropNotice?> = _dropNotice.asStateFlow()
    private var dropNoticeJob: Job? = null

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
        clipboardRouting.start()
        agent.startScanning()
        scope.launch {
            files.finished.collect { t ->
                if (t.direction != Direction.RECEIVE || t.status != TransferStatus.DONE) return@collect
                val notifications = settings.settings.value.notifications
                if (t.kind == FileKind.SCREENSHOT) {
                    val file = t.location?.let(::File)
                    if (notifications.screenshot && file != null) {
                        _screenshot.value = ScreenshotNotice(t.id, file, t.peerName, System.currentTimeMillis())
                    }
                } else if (notifications.fileReceived) {
                    notifier.notify(getString(Res.string.notify_received_title), getString(Res.string.notify_received_body, t.name, t.peerName))
                }
            }
        }
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
        clipboardRouting.stop()
        mirror.stop()
        agent.shutdown()
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

    /** Sends regular files (folders are skipped) to one connected phone. */
    fun sendFiles(fingerprint: Fingerprint, list: List<File>) {
        val session = sessions[fingerprint] ?: return
        list.filter { it.isFile }.forEach { file ->
            val mime = runCatching { Files.probeContentType(file.toPath()) }.getOrNull()
            files.send(session, FileSource(file.name, file.length(), mime) { file.inputStream() })
        }
    }

    /** Files dropped on the window go to the phone when exactly one is connected. */
    fun sendDroppedFiles(list: List<File>) {
        val targets = sessions.keys.toList()
        when (targets.size) {
            1 -> {
                _dropNotice.value = null
                sendFiles(targets.single(), list)
            }
            0 -> showDropNotice(DropNotice.NO_DEVICE)
            else -> showDropNotice(DropNotice.MULTIPLE_DEVICES)
        }
    }

    private fun showDropNotice(notice: DropNotice) {
        _dropNotice.value = notice
        dropNoticeJob?.cancel()
        dropNoticeJob = scope.launch {
            delay(6_000)
            _dropNotice.compareAndSet(notice, null)
        }
    }

    private fun onSessionOpened(session: LinkSession): MessageHandler {
        val fingerprint = session.peerFingerprint
        files.attach(session)
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
        return handler@{ message ->
            if (files.onMessage(session, message)) return@handler
            if (sync.onMessage(message)) return@handler
            when (message) {
                is BatteryStatus -> onBattery(fingerprint, session.peerName, message)
                is ClipboardText -> clipboardRouting.onPhoneText(message.text)
                else -> Unit
            }
        }
    }

    private suspend fun onBattery(fingerprint: Fingerprint, name: String, status: BatteryStatus) {
        _devices.update { list -> list.map { if (it.fingerprint == fingerprint) it.copy(battery = status) else it } }
        val policy = batteryAlerts.getOrPut(fingerprint) { BatteryAlertPolicy() }
        val prefs = settings.settings.value.battery
        val level = "${status.level}%"
        for (alert in policy.evaluate(status, prefs.effectiveLowThreshold)) {
            val (title, body) = when (alert) {
                BatteryAlertPolicy.Alert.FULL -> {
                    if (!prefs.fullEnabled) continue
                    Res.string.notify_full_title to Res.string.notify_full_body
                }
                BatteryAlertPolicy.Alert.LOW -> Res.string.notify_low_title to Res.string.notify_low_body
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
