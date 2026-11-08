package io.github.aqua0801.droidlink.desktop.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.aqua0801.droidlink.desktop.DesktopController
import io.github.aqua0801.droidlink.desktop.adb.AgentState
import io.github.aqua0801.droidlink.desktop.resources.Res
import io.github.aqua0801.droidlink.desktop.resources.enhanced_adb_missing
import io.github.aqua0801.droidlink.desktop.resources.enhanced_device_offline
import io.github.aqua0801.droidlink.desktop.resources.enhanced_device_unauthorized
import io.github.aqua0801.droidlink.desktop.resources.enhanced_intro
import io.github.aqua0801.droidlink.desktop.resources.enhanced_no_devices
import io.github.aqua0801.droidlink.desktop.resources.enhanced_start
import io.github.aqua0801.droidlink.desktop.resources.enhanced_state_connected
import io.github.aqua0801.droidlink.desktop.resources.enhanced_state_failed
import io.github.aqua0801.droidlink.desktop.resources.enhanced_state_off
import io.github.aqua0801.droidlink.desktop.resources.enhanced_state_starting
import io.github.aqua0801.droidlink.desktop.resources.enhanced_stop
import io.github.aqua0801.droidlink.desktop.resources.install_choose
import io.github.aqua0801.droidlink.desktop.resources.install_dialog_title
import io.github.aqua0801.droidlink.desktop.resources.install_title
import io.github.aqua0801.droidlink.desktop.resources.logcat_clear
import io.github.aqua0801.droidlink.desktop.resources.logcat_empty
import io.github.aqua0801.droidlink.desktop.resources.logcat_filter_hint
import io.github.aqua0801.droidlink.desktop.resources.logcat_start
import io.github.aqua0801.droidlink.desktop.resources.logcat_stop
import io.github.aqua0801.droidlink.desktop.resources.logcat_title
import io.github.aqua0801.droidlink.desktop.resources.section_enhanced
import org.jetbrains.compose.resources.stringResource
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
fun EnhancedSection(controller: DesktopController) {
    val agent = controller.agent
    val devices by agent.devices.collectAsState()
    val state by agent.state.collectAsState()

    val activeSerial = when (val s = state) {
        is AgentState.Connected -> s.serial
        is AgentState.Starting -> s.serial
        else -> null
    }

    Section(stringResource(Res.string.section_enhanced)) {
        Hint(stringResource(Res.string.enhanced_intro))

        if (!agent.adbAvailable) {
            Hint(stringResource(Res.string.enhanced_adb_missing))
            return@Section
        }

        if (devices.isEmpty()) {
            Hint(stringResource(Res.string.enhanced_no_devices))
        }

        devices.forEach { device ->
            val label = when (device.state) {
                "device" -> device.model
                "unauthorized" -> stringResource(Res.string.enhanced_device_unauthorized, device.model)
                else -> stringResource(Res.string.enhanced_device_offline, device.model)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                if (device.serial == activeSerial) {
                    OutlinedButton(onClick = { agent.disconnect() }) { Text(stringResource(Res.string.enhanced_stop)) }
                } else {
                    Button(onClick = { agent.connect(device.serial) }, enabled = device.usable) {
                        Text(stringResource(Res.string.enhanced_start))
                    }
                }
            }
        }

        StateLine(state)

        if (state is AgentState.Connected) {
            LogcatPanel(controller)
            InstallPanel(controller)
        }
    }
}

@Composable
private fun StateLine(state: AgentState) {
    when (state) {
        AgentState.Off -> Hint(stringResource(Res.string.enhanced_state_off))
        is AgentState.Starting -> Hint(stringResource(Res.string.enhanced_state_starting))
        is AgentState.Connected ->
            Text(
                stringResource(Res.string.enhanced_state_connected, state.model, state.release, state.sdk),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        is AgentState.Failed ->
            Text(
                stringResource(Res.string.enhanced_state_failed, state.message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
    }
}

@Composable
private fun LogcatPanel(controller: DesktopController) {
    val agent = controller.agent
    val running by agent.logcatRunning.collectAsState()
    val lines by agent.logcatLines.collectAsState()
    var filter by remember { mutableStateOf("") }

    Text(stringResource(Res.string.logcat_title), style = MaterialTheme.typography.titleSmall)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            singleLine = true,
            placeholder = { Text(stringResource(Res.string.logcat_filter_hint)) },
            modifier = Modifier.weight(1f),
        )
        if (running) {
            OutlinedButton(onClick = agent::stopLogcat) { Text(stringResource(Res.string.logcat_stop)) }
        } else {
            Button(onClick = { agent.startLogcat(filter) }) { Text(stringResource(Res.string.logcat_start)) }
        }
        TextButton(onClick = agent::clearLogcat) { Text(stringResource(Res.string.logcat_clear)) }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }
    if (lines.isEmpty()) {
        Hint(stringResource(Res.string.logcat_empty))
    } else {
        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    .padding(8.dp),
            ) {
                items(lines) { line ->
                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun InstallPanel(controller: DesktopController) {
    val title = stringResource(Res.string.install_dialog_title)
    Text(stringResource(Res.string.install_title), style = MaterialTheme.typography.titleSmall)
    Button(onClick = { chooseApk(title)?.let(controller.agent::installApk) }) {
        Text(stringResource(Res.string.install_choose))
    }
}

/** Native file picker limited to .apk. Modal; returns null if cancelled. */
private fun chooseApk(title: String): File? {
    val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD).apply {
        setFilenameFilter { _, name -> name.endsWith(".apk", ignoreCase = true) }
        isVisible = true
    }
    val dir = dialog.directory ?: return null
    val file = dialog.file ?: return null
    return File(dir, file)
}
