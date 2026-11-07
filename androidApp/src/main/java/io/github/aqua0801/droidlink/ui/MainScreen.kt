package io.github.aqua0801.droidlink.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aqua0801.droidlink.AppGraph
import io.github.aqua0801.droidlink.data.PairedPc
import io.github.aqua0801.droidlink.link.LinkState
import io.github.aqua0801.droidlink.link.fingerprint
import io.github.aqua0801.droidlink.shared.crypto.Fingerprint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MainScreen(graph: AppGraph, onScan: () -> Unit) {
    val state by graph.link.state.collectAsStateWithLifecycle()
    val pcs by graph.pairedPcs.pcs.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<PairedPc?>(null) }

    LaunchedEffect(Unit) {
        graph.link.events.collect { snackbar.showSnackbar(it) }
    }
    val ownFingerprint by produceState<String?>(null) {
        value = withContext(Dispatchers.IO) { graph.identity.fingerprint.display() }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("DroidLink", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            }
            item { StatusCard(state, onDisconnect = graph.link::disconnect) }
            item {
                Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text("掃描 QR 碼配對電腦") }
            }
            item { BatteryOptimizationCard() }
            item {
                Text("已配對的電腦", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            }
            if (pcs.isEmpty()) {
                item {
                    Text(
                        "還沒有配對任何電腦。在電腦端按「產生配對 QR 碼」，再用上面的按鈕掃描。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(pcs, key = { it.fingerprint }) { pc ->
                PcCard(
                    pc = pc,
                    active = state.fingerprint == pc.fingerprint,
                    onConnect = { graph.link.connect(pc.fingerprint) },
                    onEditHost = { editing = pc },
                    onForget = { graph.link.forget(pc.fingerprint) },
                )
            }
            item {
                Text(
                    "本機指紋　${ownFingerprint ?: "產生中…"}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }

    editing?.let { pc ->
        ManualHostDialog(
            pc = pc,
            onDismiss = { editing = null },
            onSave = { host ->
                graph.pairedPcs.update(pc.fingerprint) { it.copy(manualHost = host.trim().ifEmpty { null }) }
                editing = null
            },
        )
    }
}

@Composable
private fun StatusCard(state: LinkState, onDisconnect: () -> Unit) {
    val connected = state is LinkState.Connected
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (connected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (state) {
                    LinkState.Idle -> Text("未連線", style = MaterialTheme.typography.titleMedium)
                    is LinkState.Pairing -> Text("正在與 ${state.serverName} 配對…", style = MaterialTheme.typography.titleMedium)
                    is LinkState.Connecting -> {
                        Text("正在連線到 ${state.pcName}", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "第 ${state.attempt} 次嘗試" + (state.lastError?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is LinkState.Connected -> {
                        Text("已連線到 ${state.pcName}", style = MaterialTheme.typography.titleMedium)
                        Text("經由 ${state.host}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (state !is LinkState.Idle) {
                TextButton(onClick = onDisconnect) { Text("中斷") }
            }
        }
    }
}

@Composable
private fun PcCard(pc: PairedPc, active: Boolean, onConnect: () -> Unit, onEditHost: () -> Unit, onForget: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(pc.name, style = MaterialTheme.typography.titleSmall)
            Text(
                runCatching { Fingerprint(pc.fingerprint).display() }.getOrDefault(pc.fingerprint),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "位址：" + (listOfNotNull(pc.manualHost) + pc.hosts).distinct().joinToString("、"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConnect, enabled = !active) { Text(if (active) "使用中" else "連線") }
                OutlinedButton(onClick = onEditHost) { Text("手動位址") }
                TextButton(onClick = onForget) { Text("移除") }
            }
        }
    }
}

@Composable
private fun ManualHostDialog(pc: PairedPc, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(pc.manualHost.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手動位址") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "不在同一個區網時（例如走 Tailscale），輸入電腦的 MagicDNS 名稱或 IP。留空即可清除。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text("my-pc.tail1234.ts.net") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("儲存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** vivo's OriginOS kills background apps aggressively; being exempt from battery optimization helps. */
@SuppressLint("BatteryLife")
@Composable
private fun BatteryOptimizationCard() {
    val context = LocalContext.current
    val power = remember { context.getSystemService(PowerManager::class.java) }
    var exempt by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    LifecycleResumeEffect(Unit) {
        exempt = power.isIgnoringBatteryOptimizations(context.packageName)
        onPauseOrDispose { }
    }
    if (exempt) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "系統可能會在背景關閉 DroidLink，讓連線中斷。建議允許它不受電池最佳化限制。",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                )
            }) { Text("允許背景執行") }
        }
    }
}
