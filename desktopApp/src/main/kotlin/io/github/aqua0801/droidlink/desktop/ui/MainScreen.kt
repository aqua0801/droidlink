package io.github.aqua0801.droidlink.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import io.github.aqua0801.droidlink.desktop.PairingOffer
import io.github.aqua0801.droidlink.desktop.ServerStatus
import io.github.aqua0801.droidlink.desktop.TrustedPeer
import io.github.aqua0801.droidlink.desktop.resources.*
import io.github.aqua0801.droidlink.shared.crypto.Fingerprint
import io.github.aqua0801.droidlink.shared.protocol.BatteryStatus
import io.github.aqua0801.droidlink.shared.protocol.PowerSource
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun MainScreen(controller: DesktopController) {
    val server by controller.serverStatus.collectAsState()
    val devices by controller.devices.collectAsState()
    val pairing by controller.pairing.collectAsState()
    val trusted by controller.trustedPeers.peers.collectAsState()
    val settings by controller.settings.settings.collectAsState()
    val lastSent by controller.clipboard.lastSent.collectAsState()
    val lastReceived by controller.clipboard.lastReceived.collectAsState()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Header(controller.serverName, server)

            Section(stringResource(Res.string.section_devices)) {
                if (devices.isEmpty()) Hint(stringResource(Res.string.devices_empty))
                devices.forEach { DeviceRow(it) }
            }

            Section(stringResource(Res.string.section_clipboard)) {
                SwitchRow(stringResource(Res.string.clipboard_send_to_phone), settings.clipboardSendToPhone) { on ->
                    controller.settings.update { it.copy(clipboardSendToPhone = on) }
                }
                SwitchRow(stringResource(Res.string.clipboard_receive_from_phone), settings.clipboardReceiveFromPhone) { on ->
                    controller.settings.update { it.copy(clipboardReceiveFromPhone = on) }
                }
                lastSent?.let { ClipLine(Res.string.clipboard_last_sent, it) }
                lastReceived?.let { ClipLine(Res.string.clipboard_last_received, it) }
                Hint(stringResource(Res.string.clipboard_phone_hint))
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
}

@Composable
private fun Header(name: String, status: ServerStatus) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
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
private fun DeviceRow(device: ConnectedDevice) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(device.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(device.address, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
