package io.github.aqua0801.droidlink.desktop.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.aqua0801.droidlink.desktop.BatteryAlertSettings
import io.github.aqua0801.droidlink.desktop.DesktopController
import io.github.aqua0801.droidlink.desktop.PcToPhoneMode
import io.github.aqua0801.droidlink.desktop.PhoneToPcMode
import io.github.aqua0801.droidlink.desktop.hotkey.Hotkey
import io.github.aqua0801.droidlink.desktop.hotkey.HotkeyAction
import io.github.aqua0801.droidlink.desktop.hotkey.HotkeyStatus
import io.github.aqua0801.droidlink.desktop.platform.FileReveal
import io.github.aqua0801.droidlink.desktop.platform.chooseFolder
import io.github.aqua0801.droidlink.desktop.resources.*
import io.github.aqua0801.droidlink.shared.settings.AppLanguage
import io.github.aqua0801.droidlink.shared.settings.ThemeMode
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import java.awt.event.KeyEvent as AwtKeyEvent
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(controller: DesktopController, onBack: () -> Unit) {
    val settings by controller.settings.settings.collectAsState()
    val status by controller.clipboardRouting.hotkeyStatus.collectAsState()
    val update = controller.settings::update

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconAction(Res.drawable.ic_arrow_back, stringResource(Res.string.settings_back), onBack)
                Text(stringResource(Res.string.settings_title), style = MaterialTheme.typography.headlineMedium)
            }

            Section(stringResource(Res.string.settings_appearance)) {
                Text(stringResource(Res.string.settings_theme), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ThemeOption(settings.theme == ThemeMode.SYSTEM, Res.string.theme_system) { update { it.copy(theme = ThemeMode.SYSTEM) } }
                    ThemeOption(settings.theme == ThemeMode.LIGHT, Res.string.theme_light) { update { it.copy(theme = ThemeMode.LIGHT) } }
                    ThemeOption(settings.theme == ThemeMode.DARK, Res.string.theme_dark) { update { it.copy(theme = ThemeMode.DARK) } }
                }
                Text(stringResource(Res.string.settings_language), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    AppLanguage.entries.forEach { language ->
                        ThemeOption(settings.language == language, language.label) { update { it.copy(language = language) } }
                    }
                }
            }

            Section(stringResource(Res.string.section_clipboard)) {
                Text(stringResource(Res.string.settings_pc_to_phone), style = MaterialTheme.typography.titleSmall)
                ModeOption(settings.pcToPhone == PcToPhoneMode.AUTO, Res.string.pc_auto_title, Res.string.pc_auto_desc) {
                    update { it.copy(pcToPhone = PcToPhoneMode.AUTO) }
                }
                ModeOption(settings.pcToPhone == PcToPhoneMode.HOTKEY, Res.string.pc_hotkey_title, Res.string.pc_hotkey_desc) {
                    update { it.copy(pcToPhone = PcToPhoneMode.HOTKEY) }
                }
                ModeOption(settings.pcToPhone == PcToPhoneMode.OFF, Res.string.pc_off_title, Res.string.pc_off_desc) {
                    update { it.copy(pcToPhone = PcToPhoneMode.OFF) }
                }
                HotkeyField(
                    label = stringResource(Res.string.hotkey_push),
                    hotkey = settings.pushHotkey,
                    default = Hotkey.DEFAULT_PUSH,
                    inUse = settings.pcToPhone == PcToPhoneMode.HOTKEY,
                    status = status[HotkeyAction.PUSH_CLIPBOARD],
                    onChange = { hk -> update { it.copy(pushHotkey = hk) } },
                    onRecording = controller.clipboardRouting::pauseHotkeys,
                )

                HorizontalDivider()

                Text(stringResource(Res.string.settings_phone_to_pc), style = MaterialTheme.typography.titleSmall)
                ModeOption(settings.phoneToPc == PhoneToPcMode.MANUAL, Res.string.phone_manual_title, Res.string.phone_manual_desc) {
                    update { it.copy(phoneToPc = PhoneToPcMode.MANUAL) }
                }
                ModeOption(settings.phoneToPc == PhoneToPcMode.AUTO, Res.string.phone_auto_title, Res.string.phone_auto_desc) {
                    update { it.copy(phoneToPc = PhoneToPcMode.AUTO) }
                }
                ModeOption(settings.phoneToPc == PhoneToPcMode.OFF, Res.string.phone_off_title, Res.string.phone_off_desc) {
                    update { it.copy(phoneToPc = PhoneToPcMode.OFF) }
                }
                HotkeyField(
                    label = stringResource(Res.string.hotkey_pull),
                    hotkey = settings.pullHotkey,
                    default = Hotkey.DEFAULT_PULL,
                    inUse = settings.phoneToPc == PhoneToPcMode.MANUAL,
                    status = status[HotkeyAction.PULL_CLIPBOARD],
                    onChange = { hk -> update { it.copy(pullHotkey = hk) } },
                    onRecording = controller.clipboardRouting::pauseHotkeys,
                )
            }

            Section(stringResource(Res.string.settings_battery)) {
                BatterySettings(settings.battery) { next -> update { it.copy(battery = next) } }
            }

            Section(stringResource(Res.string.settings_notifications)) {
                val n = settings.notifications
                SwitchOption(n.clipboard, Res.string.notify_clipboard, Res.string.notify_clipboard_desc) { on ->
                    update { it.copy(notifications = it.notifications.copy(clipboard = on)) }
                }
                SwitchOption(n.fileReceived, Res.string.notify_file_received, Res.string.notify_file_received_desc) { on ->
                    update { it.copy(notifications = it.notifications.copy(fileReceived = on)) }
                }
                SwitchOption(n.screenshot, Res.string.notify_screenshot, Res.string.notify_screenshot_desc) { on ->
                    update { it.copy(notifications = it.notifications.copy(screenshot = on)) }
                }
                Text(
                    stringResource(Res.string.notify_errors_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section(stringResource(Res.string.settings_sync)) {
                val custom = settings.sync.folder != null
                val chooserTitle = stringResource(Res.string.sync_choose_title)
                Text(stringResource(Res.string.sync_folder_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(controller.syncFolder.absolutePath, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        chooseFolder(chooserTitle, controller.syncFolder)?.let { dir ->
                            update { it.copy(sync = it.sync.copy(folder = dir.absolutePath)) }
                        }
                    }) { Text(stringResource(Res.string.sync_change)) }
                    OutlinedButton(onClick = {
                        controller.syncFolder.mkdirs()
                        FileReveal.openFolder(controller.syncFolder)
                    }) { Text(stringResource(Res.string.transfers_open_folder)) }
                    if (custom) TextButton(onClick = controller::resetSyncFolder) { Text(stringResource(Res.string.sync_use_default)) }
                }
                SwitchOption(settings.sync.mirror, Res.string.sync_mirror, Res.string.sync_mirror_desc) { on ->
                    update { it.copy(sync = it.sync.copy(mirror = on)) }
                }
            }

            Section(stringResource(Res.string.settings_files)) {
                Text(stringResource(Res.string.settings_downloads), style = MaterialTheme.typography.bodyMedium)
                Text(controller.downloadsDir.absolutePath, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                OutlinedButton(onClick = { FileReveal.openFolder(controller.downloadsDir) }) {
                    Text(stringResource(Res.string.transfers_open_folder))
                }
            }
        }
    }
}

@Composable
private fun BatterySettings(value: BatteryAlertSettings, onChange: (BatteryAlertSettings) -> Unit) {
    // Local while dragging; persisted when the thumb is released.
    var dragging by remember(value.lowThreshold) { mutableStateOf(value.lowThreshold.toFloat()) }
    val shown = dragging.roundToInt()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SwitchOption(value.lowEnabled, Res.string.alert_battery_low, null) { onChange(value.copy(lowEnabled = it)) }
        Text(
            stringResource(Res.string.alert_battery_low_threshold, "$shown%"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = dragging,
            onValueChange = { dragging = it },
            onValueChangeFinished = { onChange(value.copy(lowThreshold = dragging.roundToInt())) },
            valueRange = BatteryAlertSettings.MIN_LOW_THRESHOLD.toFloat()..BatteryAlertSettings.MAX_LOW_THRESHOLD.toFloat(),
            // 5% steps: 5, 10, ... 50
            steps = (BatteryAlertSettings.MAX_LOW_THRESHOLD - BatteryAlertSettings.MIN_LOW_THRESHOLD) / 5 - 1,
            enabled = value.lowEnabled,
        )
        SwitchOption(value.fullEnabled, Res.string.alert_battery_full, Res.string.alert_battery_full_desc) { onChange(value.copy(fullEnabled = it)) }
    }
}

@Composable
private fun SwitchOption(checked: Boolean, title: StringResource, description: StringResource?, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, onValueChange = onChange, role = Role.Switch)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            description?.let {
                Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

private val AppLanguage.label: StringResource
    get() = when (this) {
        AppLanguage.SYSTEM -> Res.string.language_system
        AppLanguage.ENGLISH -> Res.string.language_english
        AppLanguage.TRADITIONAL_CHINESE -> Res.string.language_zh_tw
    }

/** A radio option with a label; used for theme and language. */
@Composable
private fun ThemeOption(selected: Boolean, label: StringResource, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 4.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(end = 8.dp))
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ModeOption(selected: Boolean, title: StringResource, description: StringResource, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(end = 12.dp, top = 2.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Shows a hotkey and records a new one: click Change, then press the combination.
 * The global hotkeys are released while recording so the keys reach this window.
 */
@Composable
private fun HotkeyField(
    label: String,
    hotkey: Hotkey,
    default: Hotkey,
    inUse: Boolean,
    status: HotkeyStatus?,
    onChange: (Hotkey) -> Unit,
    onRecording: (Boolean) -> Unit,
) {
    var recording by remember { mutableStateOf(false) }
    var invalid by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    fun stop() {
        if (!recording) return
        recording = false
        onRecording(false)
    }
    LaunchedEffect(recording) { if (recording) focus.requestFocus() }
    DisposableEffect(Unit) { onDispose { if (recording) onRecording(false) } }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.widthIn(min = 96.dp))
            Box(
                modifier = Modifier
                    .widthIn(min = 180.dp)
                    .border(
                        width = if (recording) 2.dp else 1.dp,
                        color = if (recording) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .focusRequester(focus)
                    .onFocusChanged { if (!it.isFocused) stop() }
                    .onPreviewKeyEvent { event ->
                        if (!recording) return@onPreviewKeyEvent false
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                        val code = (event.nativeKeyEvent as? AwtKeyEvent)?.keyCode ?: return@onPreviewKeyEvent true
                        when (code) {
                            AwtKeyEvent.VK_ESCAPE -> stop()
                            AwtKeyEvent.VK_CONTROL, AwtKeyEvent.VK_ALT, AwtKeyEvent.VK_SHIFT,
                            AwtKeyEvent.VK_WINDOWS, AwtKeyEvent.VK_META, AwtKeyEvent.VK_ALT_GRAPH,
                            -> Unit // wait for the main key
                            else -> {
                                val recorded = Hotkey.of(event.isCtrlPressed, event.isAltPressed, event.isShiftPressed, event.isMetaPressed, code)
                                invalid = recorded == null
                                if (recorded != null) {
                                    onChange(recorded)
                                    stop()
                                }
                            }
                        }
                        true
                    }
                    .focusable()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    if (recording) stringResource(Res.string.hotkey_recording) else hotkey.label,
                    fontFamily = if (recording) FontFamily.Default else FontFamily.Monospace,
                    color = if (recording) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
            if (recording) {
                TextButton(onClick = ::stop) { Text(stringResource(Res.string.hotkey_cancel)) }
            } else {
                OutlinedButton(onClick = {
                    invalid = false
                    recording = true
                    onRecording(true)
                }) { Text(stringResource(Res.string.hotkey_change)) }
                if (hotkey != default) {
                    TextButton(onClick = { onChange(default) }) { Text(stringResource(Res.string.hotkey_reset)) }
                }
            }
        }
        val (message, isError) = when {
            invalid -> stringResource(Res.string.hotkey_invalid) to true
            !inUse -> stringResource(Res.string.hotkey_inactive) to false
            status == HotkeyStatus.ACTIVE -> stringResource(Res.string.hotkey_active) to false
            status == HotkeyStatus.CONFLICT -> stringResource(Res.string.hotkey_conflict) to true
            status == HotkeyStatus.UNSUPPORTED -> stringResource(Res.string.hotkey_unsupported) to true
            else -> "" to false
        }
        if (message.isNotEmpty()) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
