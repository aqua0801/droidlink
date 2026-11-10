package io.github.aqua0801.droidlink.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.StringRes
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aqua0801.droidlink.AppGraph
import io.github.aqua0801.droidlink.R
import io.github.aqua0801.droidlink.clipboard.ClipEvent
import io.github.aqua0801.droidlink.data.PairedPc
import io.github.aqua0801.droidlink.link.LinkState
import io.github.aqua0801.droidlink.link.fingerprint
import io.github.aqua0801.droidlink.shared.crypto.Fingerprint
import io.github.aqua0801.droidlink.shared.transfer.Transfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun MainScreen(
    graph: AppGraph,
    onScan: () -> Unit,
    onSendClipboard: () -> Unit,
    onPickFiles: () -> Unit,
    onOpenFile: (Transfer) -> Unit,
) {
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
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            }
            item { StatusCard(state, onDisconnect = graph.link::disconnect) }
            item {
                Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_scan)) }
            }
            item { BatteryOptimizationCard() }
            item { ClipboardCard(graph, connected = state is LinkState.Connected, onSendNow = onSendClipboard) }
            item { FilesCard(graph, connected = state is LinkState.Connected, onPick = onPickFiles, onOpen = onOpenFile) }
            item {
                Text(
                    stringResource(R.string.section_paired),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (pcs.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.paired_empty),
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
                    ownFingerprint?.let { stringResource(R.string.own_fingerprint, it) }
                        ?: stringResource(R.string.own_fingerprint_pending),
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
                    LinkState.Idle -> Title(stringResource(R.string.status_idle))
                    is LinkState.Pairing -> Title(stringResource(R.string.status_pairing, state.serverName))
                    is LinkState.Connecting -> {
                        Title(stringResource(R.string.status_connecting, state.pcName))
                        Detail(
                            state.lastError?.let { stringResource(R.string.status_connecting_attempt_error, state.attempt, it) }
                                ?: stringResource(R.string.status_connecting_attempt, state.attempt),
                        )
                    }
                    is LinkState.Connected -> {
                        Title(stringResource(R.string.status_connected, state.pcName))
                        Detail(stringResource(R.string.status_connected_via, state.host))
                    }
                }
            }
            if (state !is LinkState.Idle) {
                TextButton(onClick = onDisconnect) { Text(stringResource(R.string.action_disconnect)) }
            }
        }
    }
}

@Composable
private fun Title(text: String) = Text(text, style = MaterialTheme.typography.titleMedium)

@Composable
private fun Detail(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun ClipboardCard(graph: AppGraph, connected: Boolean, onSendNow: () -> Unit) {
    val receive by graph.settings.clipboardReceive.collectAsStateWithLifecycle()
    val lastSent by graph.clipboard.lastSent.collectAsStateWithLifecycle()
    val lastReceived by graph.clipboard.lastReceived.collectAsStateWithLifecycle()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Title(stringResource(R.string.section_clipboard))
            SwitchRow(stringResource(R.string.clip_receive), receive, graph.settings::setClipboardReceive)
            lastSent?.let { ClipLine(R.string.clip_last_sent, it) }
            lastReceived?.let { ClipLine(R.string.clip_last_received, it) }
            OutlinedButton(onClick = onSendNow, enabled = connected) { Text(stringResource(R.string.clip_send_now)) }
            Detail(stringResource(R.string.clip_hint))
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ClipLine(@StringRes label: Int, event: ClipEvent) {
    val time = remember(event.at) { DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(event.at)) }
    val preview = event.text.replace(Regex("\\s+"), " ").trim()
    Text(
        stringResource(label, "$time  $preview"),
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
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
            Detail(stringResource(R.string.pc_addresses, (listOfNotNull(pc.manualHost) + pc.hosts).distinct().joinToString(", ")))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConnect, enabled = !active) {
                    Text(stringResource(if (active) R.string.action_in_use else R.string.action_connect))
                }
                OutlinedButton(onClick = onEditHost) { Text(stringResource(R.string.action_manual_host)) }
                TextButton(onClick = onForget) { Text(stringResource(R.string.action_forget)) }
            }
        }
    }
}

@Composable
private fun ManualHostDialog(pc: PairedPc, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(pc.manualHost.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.manual_host_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.manual_host_message), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.manual_host_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
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
            Text(stringResource(R.string.battery_opt_message), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri()),
                )
            }) { Text(stringResource(R.string.battery_opt_action)) }
        }
    }
}
