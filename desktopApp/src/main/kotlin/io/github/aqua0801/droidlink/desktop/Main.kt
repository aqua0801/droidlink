package io.github.aqua0801.droidlink.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import io.github.aqua0801.droidlink.desktop.platform.WindowChrome
import io.github.aqua0801.droidlink.desktop.resources.Res
import io.github.aqua0801.droidlink.desktop.resources.app_name
import io.github.aqua0801.droidlink.desktop.resources.tray_quit
import io.github.aqua0801.droidlink.desktop.resources.tray_settings
import io.github.aqua0801.droidlink.desktop.resources.tray_show
import io.github.aqua0801.droidlink.desktop.ui.AppIcon
import io.github.aqua0801.droidlink.desktop.ui.DroidLinkDesktopTheme
import io.github.aqua0801.droidlink.desktop.ui.MainScreen
import io.github.aqua0801.droidlink.desktop.ui.ScreenshotPopup
import io.github.aqua0801.droidlink.desktop.ui.SettingsScreen
import io.github.aqua0801.droidlink.desktop.ui.isDarkTheme
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
        var showSettings by remember { mutableStateOf(false) }
        val quit: () -> Unit = {
            controller.stop()
            appScope.cancel()
            exitApplication()
        }
        val trayAvailable = isTraySupported
        val appName = stringResource(Res.string.app_name)
        val settings by controller.settings.settings.collectAsState()
        val dark = isDarkTheme(settings.theme)

        if (trayAvailable) {
            Tray(
                icon = AppIcon,
                state = trayState,
                tooltip = appName,
                onAction = { windowVisible = true },
                menu = {
                    Item(stringResource(Res.string.tray_show), onClick = { windowVisible = true })
                    Item(stringResource(Res.string.tray_settings), onClick = {
                        showSettings = true
                        windowVisible = true
                    })
                    Separator()
                    Item(stringResource(Res.string.tray_quit), onClick = quit)
                },
            )
        }

        val shot by controller.screenshot.collectAsState()
        shot?.let { notice ->
            key(notice.id) {
                ScreenshotPopup(notice, dark, onDismiss = { controller.dismissScreenshot(notice.id) })
            }
        }

        Window(
            // With a tray icon, closing the window keeps the server running in the background.
            onCloseRequest = { if (trayAvailable) windowVisible = false else quit() },
            visible = windowVisible,
            title = appName,
            icon = AppIcon,
            state = rememberWindowState(width = 560.dp, height = 880.dp),
        ) {
            // Re-applied when the window is shown again, since a hidden window may be re-created natively.
            LaunchedEffect(dark, windowVisible) { if (windowVisible) WindowChrome.setDarkTitleBar(window, dark) }
            DroidLinkDesktopTheme(dark) {
                if (showSettings) {
                    SettingsScreen(controller, onBack = { showSettings = false })
                } else {
                    MainScreen(controller, onOpenSettings = { showSettings = true })
                }
            }
        }
    }
}
