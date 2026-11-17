package io.github.aqua0801.droidlink.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.aqua0801.droidlink.desktop.ClipEvent
import io.github.aqua0801.droidlink.desktop.ConnectedDevice
import io.github.aqua0801.droidlink.desktop.DesktopController
import io.github.aqua0801.droidlink.desktop.DesktopSettings
import io.github.aqua0801.droidlink.desktop.PairingOffer
import io.github.aqua0801.droidlink.desktop.PcToPhoneMode
import io.github.aqua0801.droidlink.desktop.PhoneToPcMode
import io.github.aqua0801.droidlink.desktop.ServerStatus
import io.github.aqua0801.droidlink.desktop.TrustedPeer
import io.github.aqua0801.droidlink.desktop.mirror.MirrorState
import io.github.aqua0801.droidlink.desktop.resources.*
import io.github.aqua0801.droidlink.desktop.sync.SyncFailure
import io.github.aqua0801.droidlink.desktop.sync.SyncStatus
import io.github.aqua0801.droidlink.shared.crypto.Fingerprint
import io.github.aqua0801.droidlink.shared.protocol.BatteryStatus
import io.github.aqua0801.droidlink.shared.protocol.PowerSource
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun MainScreen(controller: DesktopController, onOpenSettings: () -> Unit) {
    val server by controller.serverStatus.collectAsState()
    val devices by controller.devices.collectAsState()
    val pairing by controller.pairing.collectAsState()
    val trusted by controller.trustedPeers.peers.collectAsState()
    val settings by controller.settings.settings.collectAsState()
    val lastSent by controller.clipboard.lastSent.collectAsState()
    val lastReceived by controller.clipboard.lastReceived.collectAsState()
    val syncStatus by controller.sync.status.collectAsState()
    val mirrorState by controller.mirror.state.collectAsState()

    var dropHover by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().fileDropTarget(onHover = { dropHover = it }, onFiles = controller::sendDroppedFiles)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Header(controller.serverName, server, onOpenSettings)

                Section(stringResource(Res.string.section_devices)) {
                    if (devices.isEmpty()) Hint(stringResource(Res.string.devices_empty))
                    devices.forEach { device ->
                        DeviceRow(
                            device = device,
                            sync = syncStatus[device.fingerprint],
                            onSendFiles = { files -> controller.sendFiles(device.fingerprint, files) },
                            onSync = { controller.startSync(device.fingerprint) },
                            mirroring = mirrorState !is MirrorState.Idle,
                            onMirror = {
                                if (mirrorState is MirrorState.Idle) controller.startWirelessMirror(device.fingerprint)
                                else controller.mirror.stop()
                            },
                        )
                    }
                }

                TransfersSection(controller)

                Section(stringResource(Res.string.section_clipboard)) {
                    Text(stringResource(Res.string.clipboard_summary_pc, pcModeLabel(settings)), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(Res.string.clipboard_summary_phone, phoneModeLabel(settings)), style = MaterialTheme.typography.bodyMedium)
                    lastSent?.let { ClipLine(Res.string.clipboard_last_sent, it) }
                    lastReceived?.let { ClipLine(Res.string.clipboard_last_received, it) }
                    Hint(stringResource(Res.string.clipboard_phone_hint))
                    OutlinedButton(onClick = onOpenSettings) { Text(stringResource(Res.string.clipboard_open_settings)) }
                }

                EnhancedSection(controller)

                Section(stringResource(Res.string.section_pairing)) {
                    PairingPanel(
                        offer = pairing,
                        canPair = server is ServerStatus.Running,
                        onStart = controller::startPairing,
                        onCancel = controller::cancelPairing,
                    )
                }

                Section(stringResource(Res.string.section_trusted)) {
                    if (trusted.isEmpty()) Hint(stringResource(Res.string.trusted_empty))
                    trusted.forEach { peer ->
                        TrustedRow(
                            peer = peer,
                            connected = devices.any { it.fingerprint.hex == peer.fingerprint },
                            onForget = { controller.forget(peer.fingerprint) },
                        )
                    }
                }

                Text(
                    stringResource(Res.string.own_fingerprint, controller.identity.fingerprint.display()),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (dropHover) DropOverlay()
    }
}

@Composable
private fun Header(name: String, status: ServerStatus, onOpenSettings: () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            val (text, color) = when (status) {
                ServerStatus.Starting ->
                    stringResource(Res.string.server_starting) to MaterialTheme.colorScheme.onSurfaceVariant
                is ServerStatus.Running ->
                    stringResource(Res.string.server_running, name, status.port.toString()) to MaterialTheme.colorScheme.primary
                is ServerStatus.Failed ->
                    stringResource(Res.string.server_failed, status.message) to MaterialTheme.colorScheme.error
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
        }
        IconAction(Res.drawable.ic_settings, stringResource(Res.string.action_settings), onOpenSettings)
    }
}

@Composable
private fun pcModeLabel(s: DesktopSettings): String = when (s.pcToPhone) {
    PcToPhoneMode.AUTO -> stringResource(Res.string.mode_auto)
    PcToPhoneMode.HOTKEY -> stringResource(Res.string.mode_hotkey_with, s.pushHotkey.label)
    PcToPhoneMode.OFF -> stringResource(Res.string.mode_off)
}

@Composable
private fun phoneModeLabel(s: DesktopSettings): String = when (s.phoneToPc) {
    PhoneToPcMode.AUTO -> stringResource(Res.string.mode_auto_enhanced)
    PhoneToPcMode.MANUAL -> stringResource(Res.string.mode_manual_with, s.pullHotkey.label)
    PhoneToPcMode.OFF -> stringResource(Res.string.mode_off)
}

@Composable
internal fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

@Composable
internal fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private val clipTimeFormat = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

@Composable
private fun ClipLine(label: StringResource, event: ClipEvent) {
    val preview = event.text.replace(Regex("\\s+"), " ").trim()
    Text(
        stringResource(label, "${clipTimeFormat.format(Instant.ofEpochMilli(event.at))}  $preview"),
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun DeviceRow(
    device: ConnectedDevice,
    sync: SyncStatus?,
    onSendFiles: (List<File>) -> Unit,
    onSync: () -> Unit,
    mirroring: Boolean,
    onMirror: () -> Unit,
) {
    val dialogTitle = stringResource(Res.string.send_dialog_title)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleSmall)
                Text(device.address, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onMirror) {
                    Text(stringResource(if (mirroring) Res.string.mirror_stop else Res.string.device_mirror))
                }
                OutlinedButton(onClick = onSync, enabled = sync !is SyncStatus.Running) {
                    Text(stringResource(Res.string.device_sync))
                }
                OutlinedButton(onClick = { chooseFiles(dialogTitle).takeIf { it.isNotEmpty() }?.let(onSendFiles) }) {
                    Text(stringResource(Res.string.device_send_files))
                }
            }
        }
        sync?.let { SyncLine(it) }
        val battery = device.battery
        if (battery == null) {
            Hint(stringResource(Res.string.battery_waiting))
        } else {
            val low = battery.level <= 20 && battery.plugged == PowerSource.NONE
            LinearProgressIndicator(
                progress = { battery.level.coerceIn(0, 100) / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = if (low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Text(batteryLine(battery), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SyncLine(status: SyncStatus) {
    val text = when (status) {
        is SyncStatus.Running ->
            if (status.total == 0) stringResource(Res.string.sync_comparing)
            else stringResource(Res.string.sync_running, status.done, status.total)
        is SyncStatus.Finished -> {
            val r = status.result
            val time = clipTimeFormat.format(Instant.ofEpochMilli(status.at))
            val base = stringResource(Res.string.sync_finished, time, r.updated, r.unchanged)
            val deleted = if (r.deleted > 0) stringResource(Res.string.sync_finished_deleted, r.deleted) else ""
            val failed = if (r.failed > 0) stringResource(Res.string.sync_finished_failed, r.failed) else ""
            base + deleted + failed
        }
        is SyncStatus.Failed -> stringResource(
            when (status.reason) {
                SyncFailure.FOLDER_MISSING -> Res.string.sync_failed_folder
                SyncFailure.PHONE_NOT_READY -> Res.string.sync_failed_phone_not_ready
                SyncFailure.NO_REPLY -> Res.string.sync_failed_no_reply
                SyncFailure.DISCONNECTED -> Res.string.sync_failed_disconnected
            },
        )
    }
    val error = status is SyncStatus.Failed || (status is SyncStatus.Finished && status.result.failed > 0)
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (status is SyncStatus.Running && status.total > 0) {
        LinearProgressIndicator(progress = { status.done.toFloat() / status.total }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun batteryLine(b: BatteryStatus): String {
    val state = stringResource(
        when {
            b.full -> Res.string.battery_full
            b.charging -> Res.string.battery_charging
            b.plugged != PowerSource.NONE -> Res.string.battery_plugged
            else -> Res.string.battery_discharging
        },
    )
    val source = when (b.plugged) {
        PowerSource.AC -> Res.string.power_ac
        PowerSource.USB -> Res.string.power_usb
        PowerSource.WIRELESS -> Res.string.power_wireless
        PowerSource.DOCK -> Res.string.power_dock
        else -> null
    }?.let { stringResource(it) }
    return buildString {
        append("${b.level}% · $state")
        source?.let { append("（$it）") }
        b.temperatureC?.let { append(" · %.1f°C".format(it)) }
    }
}

@Composable
private fun PairingPanel(offer: PairingOffer?, canPair: Boolean, onStart: () -> Unit, onCancel: () -> Unit) {
    if (offer == null) {
        Hint(stringResource(Res.string.pairing_hint))
        Button(onClick = onStart, enabled = canPair) { Text(stringResource(Res.string.pairing_generate)) }
        return
    }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(offer) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val remaining = ((offer.expiresAt - now) / 1000).coerceAtLeast(0)

    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(
            bitmap = offer.qr,
            contentDescription = stringResource(Res.string.pairing_qr_description),
            modifier = Modifier.size(240.dp).background(Color.White),
            filterQuality = FilterQuality.None,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(Res.string.pairing_remaining, "%d:%02d".format(remaining / 60, remaining % 60)),
                style = MaterialTheme.typography.titleMedium,
            )
            Hint(stringResource(Res.string.pairing_hosts))
            offer.info.hosts.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            OutlinedButton(onClick = onCancel) { Text(stringResource(Res.string.action_cancel)) }
        }
    }
}

@Composable
private fun TrustedRow(peer: TrustedPeer, connected: Boolean, onForget: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (connected) stringResource(Res.string.trusted_connected, peer.name) else peer.name,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                Fingerprint.parseOrNull(peer.fingerprint)?.display() ?: peer.fingerprint,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onForget) { Text(stringResource(Res.string.action_forget)) }
    }
}
