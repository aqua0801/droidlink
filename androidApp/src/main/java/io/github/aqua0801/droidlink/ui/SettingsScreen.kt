package io.github.aqua0801.droidlink.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aqua0801.droidlink.R
import io.github.aqua0801.droidlink.data.PhoneSettings
import io.github.aqua0801.droidlink.shared.settings.ThemeMode

@Composable
fun SettingsScreen(settings: PhoneSettings, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val theme by settings.theme.collectAsStateWithLifecycle()

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.settings_back))
                }
                Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
            }

            Section(R.string.settings_appearance) {
                ThemeMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = mode == theme, onClick = { settings.setTheme(mode) }, role = Role.RadioButton)
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mode == theme, onClick = null, modifier = Modifier.padding(end = 12.dp))
                        Text(stringResource(mode.label), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            Section(R.string.section_clipboard) {
                ToggleRow(settings.clipboardReceive, R.string.clip_receive, R.string.clip_receive_desc)
            }

            Section(R.string.section_screenshot) {
                ToggleRow(settings.sendScreenshots, R.string.screenshot_send, R.string.screenshot_send_desc)
                ScreenshotAccessStatus(settings.sendScreenshots)
            }

            Section(R.string.settings_notifications) {
                ToggleRow(settings.notifyClipboardSent, R.string.notify_clip_sent, R.string.notify_clip_sent_desc)
                ToggleRow(settings.notifyFilesSent, R.string.notify_files_sent, R.string.notify_files_sent_desc)
                ToggleRow(settings.notifyFileReceived, R.string.notify_file_received, R.string.notify_file_received_desc)
                Text(
                    stringResource(R.string.notify_errors_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Section(@StringRes title: Int, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
            content()
        }
    }
}

@Composable
private fun ToggleRow(toggle: PhoneSettings.Toggle, @StringRes title: Int, @StringRes description: Int) {
    val checked by toggle.value.collectAsStateWithLifecycle()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, onValueChange = toggle::set, role = Role.Switch)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
