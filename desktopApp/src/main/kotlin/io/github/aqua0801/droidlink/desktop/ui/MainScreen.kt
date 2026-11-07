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
import androidx.compose.ui.unit.dp
import io.github.aqua0801.droidlink.desktop.ConnectedDevice
import io.github.aqua0801.droidlink.desktop.DesktopController
import io.github.aqua0801.droidlink.desktop.PairingOffer
import io.github.aqua0801.droidlink.desktop.ServerStatus
import io.github.aqua0801.droidlink.desktop.TrustedPeer
import io.github.aqua0801.droidlink.shared.crypto.Fingerprint
import io.github.aqua0801.droidlink.shared.protocol.BatteryStatus
import io.github.aqua0801.droidlink.shared.protocol.PowerSource
import kotlinx.coroutines.delay

@Composable
fun MainScreen(controller: DesktopController) {
    val server by controller.serverStatus.collectAsState()
    val devices by controller.devices.collectAsState()
    val pairing by controller.pairing.collectAsState()
    val trusted by controller.trustedPeers.peers.collectAsState()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Header(controller.serverName, server)

            Section("已連線裝置") {
                if (devices.isEmpty()) Hint("目前沒有手機連線。")
                devices.forEach { DeviceRow(it) }
            }

            Section("配對新裝置") {
                PairingPanel(
                    offer = pairing,
                    canPair = server is ServerStatus.Running,
                    onStart = controller::startPairing,
                    onCancel = controller::cancelPairing,
                )
            }

            Section("已信任的裝置") {
                if (trusted.isEmpty()) Hint("尚未配對任何手機。")
                trusted.forEach { peer ->
                    TrustedRow(
                        peer = peer,
                        connected = devices.any { it.fingerprint.hex == peer.fingerprint },
                        onForget = { controller.forget(peer.fingerprint) },
                    )
                }
            }

            Text(
                "本機指紋　${controller.identity.fingerprint.display()}",
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
        Text("DroidLink", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        val (text, color) = when (status) {
            ServerStatus.Starting -> "啟動中…" to MaterialTheme.colorScheme.onSurfaceVariant
            is ServerStatus.Running -> "$name · 監聽連接埠 ${status.port}" to MaterialTheme.colorScheme.primary
            is ServerStatus.Failed -> "伺服器無法啟動：${status.message}" to MaterialTheme.colorScheme.error
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
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
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Hint("等待電量資訊…")
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

private fun batteryLine(b: BatteryStatus): String = buildString {
    append("${b.level}%")
    append(" · ")
    append(
        when {
            b.full -> "已充飽"
            b.charging -> "充電中"
            b.plugged != PowerSource.NONE -> "已接電源"
            else -> "使用電池"
        },
    )
    when (b.plugged) {
        PowerSource.AC -> "變壓器"
        PowerSource.USB -> "USB"
        PowerSource.WIRELESS -> "無線充電"
        PowerSource.DOCK -> "底座"
        else -> null
    }?.let { append("（$it）") }
    b.temperatureC?.let { append(" · %.1f°C".format(it)) }
}

@Composable
private fun PairingPanel(offer: PairingOffer?, canPair: Boolean, onStart: () -> Unit, onCancel: () -> Unit) {
    if (offer == null) {
        Hint("在手機的 DroidLink 點「掃描 QR 碼配對電腦」，再掃描這裡產生的 QR 碼。")
        Button(onClick = onStart, enabled = canPair) { Text("產生配對 QR 碼") }
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
            contentDescription = "配對 QR 碼",
            modifier = Modifier.size(240.dp).background(Color.White),
            filterQuality = FilterQuality.None,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("剩餘 %d:%02d".format(remaining / 60, remaining % 60), style = MaterialTheme.typography.titleMedium)
            Hint("手機會依序嘗試：")
            offer.info.hosts.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            OutlinedButton(onClick = onCancel) { Text("取消") }
        }
    }
}

@Composable
private fun TrustedRow(peer: TrustedPeer, connected: Boolean, onForget: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (connected) "${peer.name}　· 已連線" else peer.name,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                Fingerprint.parseOrNull(peer.fingerprint)?.display() ?: peer.fingerprint,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onForget) { Text("移除") }
    }
}
