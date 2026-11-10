package io.github.aqua0801.droidlink.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aqua0801.droidlink.R
import io.github.aqua0801.droidlink.data.PhoneSettings
import io.github.aqua0801.droidlink.shared.settings.ThemeMode

@get:DrawableRes
private val ThemeMode.icon: Int
    get() = when (this) {
        ThemeMode.SYSTEM -> R.drawable.ic_brightness_auto
        ThemeMode.LIGHT -> R.drawable.ic_light_mode
        ThemeMode.DARK -> R.drawable.ic_dark_mode
    }

@get:StringRes
private val ThemeMode.label: Int
    get() = when (this) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    }

/** Top-bar button showing the current theme; tapping opens a menu with the three choices. */
@Composable
fun ThemeMenuButton(settings: PhoneSettings) {
    val current by settings.theme.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val description = stringResource(R.string.theme_button, stringResource(current.label))

    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(current.icon), contentDescription = description)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ThemeMode.entries.forEach { mode ->
                val selected = mode == current
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(mode.label),
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            painterResource(mode.icon),
                            contentDescription = null,
                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onClick = {
                        settings.setTheme(mode)
                        open = false
                    },
                )
            }
        }
    }
}
