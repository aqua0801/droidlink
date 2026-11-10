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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import io.github.aqua0801.droidlink.desktop.DesktopController
import io.github.aqua0801.droidlink.desktop.PcToPhoneMode
import io.github.aqua0801.droidlink.desktop.PhoneToPcMode
import io.github.aqua0801.droidlink.desktop.ThemeMode
import io.github.aqua0801.droidlink.desktop.hotkey.Hotkey
import io.github.aqua0801.droidlink.desktop.hotkey.HotkeyAction
import io.github.aqua0801.droidlink.desktop.hotkey.HotkeyStatus
import io.github.aqua0801.droidlink.desktop.platform.FileReveal
import io.github.aqua0801.droidlink.desktop.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import java.awt.event.KeyEvent as AwtKeyEvent

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
                TextButton(onClick = onBack) { Text("← " + stringResource(Res.string.settings_back)) }
                Text(stringResource(Res.string.settings_title), style = MaterialTheme.typography.headlineMedium)
            }

            Section(stringResource(Res.string.settings_appearance)) {
                Text(stringResource(Res.string.settings_theme), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ThemeOption(settings.theme == ThemeMode.SYSTEM, Res.string.theme_system) { update { it.copy(theme = ThemeMode.SYSTEM) } }
                    ThemeOption(settings.theme == ThemeMode.LIGHT, Res.string.theme_light) { update { it.copy(theme = ThemeMode.LIGHT) } }
                    ThemeOption(settings.theme == ThemeMode.DARK, Res.string.theme_dark) { update { it.copy(theme = ThemeMode.DARK) } }
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
 * Shows a hotkey and records a new one: click 變更, then press the combination.
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
