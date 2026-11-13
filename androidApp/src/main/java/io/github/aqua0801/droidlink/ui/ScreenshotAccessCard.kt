package io.github.aqua0801.droidlink.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aqua0801.droidlink.R
import io.github.aqua0801.droidlink.data.PhoneSettings
import io.github.aqua0801.droidlink.screenshot.ScreenshotAccess

/** Permission state for screenshot forwarding, re-checked whenever the screen resumes. */
private class AccessState(context: Context) {
    var granted by mutableStateOf(ScreenshotAccess.granted(context))

    /** The system dialog was answered "no"; asking again may not show it any more. */
    var denied by mutableStateOf(false)
}

@Composable
private fun rememberAccessState(): Pair<AccessState, () -> Unit> {
    val context = LocalContext.current
    val state = remember { AccessState(context) }
    LifecycleResumeEffect(Unit) {
        state.granted = ScreenshotAccess.granted(context)
        onPauseOrDispose { }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        state.granted = ok
        state.denied = !ok
    }
    val request: () -> Unit = {
        if (state.denied) {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()),
            )
        } else {
            launcher.launch(ScreenshotAccess.permission)
        }
    }
    return state to request
}

/** Main-screen prompt, shown only while the feature is on but the permission is missing. */
@Composable
fun ScreenshotAccessCard(toggle: PhoneSettings.Toggle) {
    val enabled by toggle.value.collectAsStateWithLifecycle()
    val (access, request) = rememberAccessState()
    if (!enabled || access.granted) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.screenshot_access_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(if (access.denied) R.string.screenshot_access_denied else R.string.screenshot_access_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = request) {
                    Text(stringResource(if (access.denied) R.string.action_open_system_settings else R.string.action_allow))
                }
                TextButton(onClick = { toggle.set(false) }) { Text(stringResource(R.string.action_turn_off)) }
            }
        }
    }
}

/** Compact status line for the settings page. */
@Composable
fun ScreenshotAccessStatus(toggle: PhoneSettings.Toggle) {
    val enabled by toggle.value.collectAsStateWithLifecycle()
    val (access, request) = rememberAccessState()
    if (!enabled || access.granted) return

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.screenshot_access_missing),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = request) {
            Text(stringResource(if (access.denied) R.string.action_open_system_settings else R.string.action_allow))
        }
    }
}
