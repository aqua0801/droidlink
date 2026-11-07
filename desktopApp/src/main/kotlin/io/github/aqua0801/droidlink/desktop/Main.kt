package io.github.aqua0801.droidlink.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.TrayState
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberWindowState
import io.github.aqua0801.droidlink.desktop.platform.ComposeTrayNotifier
import io.github.aqua0801.droidlink.desktop.resources.Res
import io.github.aqua0801.droidlink.desktop.resources.app_name
import io.github.aqua0801.droidlink.desktop.resources.tray_quit
import io.github.aqua0801.droidlink.desktop.resources.tray_show
import io.github.aqua0801.droidlink.desktop.ui.AppIcon
import io.github.aqua0801.droidlink.desktop.ui.DroidLinkDesktopTheme
import io.github.aqua0801.droidlink.desktop.ui.MainScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.jetbrains.compose.resources.stringResource

fun main() {
    val trayState = TrayState()
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val controller = DesktopController(appScope, ComposeTrayNotifier(trayState))
    controller.start()

    application {
        var windowVisible by remember { mutableStateOf(true) }
        val quit: () -> Unit = {
            controller.stop()
            appScope.cancel()
            exitApplication()
        }
        val trayAvailable = isTraySupported
        val appName = stringResource(Res.string.app_name)

        if (trayAvailable) {
            Tray(
                icon = AppIcon,
                state = trayState,
                tooltip = appName,
                onAction = { windowVisible = true },
                menu = {
                    Item(stringResource(Res.string.tray_show), onClick = { windowVisible = true })
                    Separator()
                    Item(stringResource(Res.string.tray_quit), onClick = quit)
                },
            )
        }

        Window(
            // With a tray icon, closing the window keeps the server running in the background.
            onCloseRequest = { if (trayAvailable) windowVisible = false else quit() },
            visible = windowVisible,
            title = appName,
            icon = AppIcon,
            state = rememberWindowState(width = 560.dp, height = 880.dp),
        ) {
            DroidLinkDesktopTheme {
                MainScreen(controller)
            }
        }
    }
}
