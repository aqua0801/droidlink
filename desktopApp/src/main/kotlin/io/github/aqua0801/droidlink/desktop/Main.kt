package io.github.aqua0801.droidlink.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.aqua0801.droidlink.desktop.platform.SwingTray
import io.github.aqua0801.droidlink.desktop.platform.WindowChrome
import io.github.aqua0801.droidlink.desktop.resources.Res
import io.github.aqua0801.droidlink.desktop.resources.app_name
import io.github.aqua0801.droidlink.desktop.resources.tray_quit
import io.github.aqua0801.droidlink.desktop.resources.tray_settings
import io.github.aqua0801.droidlink.desktop.resources.tray_show
import io.github.aqua0801.droidlink.desktop.ui.AppIcon
import io.github.aqua0801.droidlink.desktop.ui.DroidLinkDesktopTheme
import io.github.aqua0801.droidlink.desktop.ui.MainScreen
import io.github.aqua0801.droidlink.desktop.ui.MirrorWindow
import io.github.aqua0801.droidlink.desktop.ui.ScreenshotPopup
import io.github.aqua0801.droidlink.desktop.ui.SettingsScreen
import io.github.aqua0801.droidlink.desktop.ui.isDarkTheme
import io.github.aqua0801.droidlink.shared.settings.AppLanguage
import java.awt.EventQueue
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.jetbrains.compose.resources.stringResource

fun main() {
    // AWT/Swing objects belong on the event thread.
    val (tray, trayAvailable) = runOnEventThread {
        val icon = SwingTray(AppIcon.toAwtImage(Density(1f), LayoutDirection.Ltr, Size(64f, 64f)), "DroidLink")
        icon to icon.install()
    }
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val controller = DesktopController(appScope, tray)
    UiLocale.apply(controller.settings.settings.value.language)
    controller.start()

    application {
        var windowVisible by remember { mutableStateOf(true) }
        var showSettings by remember { mutableStateOf(false) }
        val quit: () -> Unit = {
            controller.stop()
            appScope.cancel()
            tray.remove()
            exitApplication()
        }
        val settings by controller.settings.settings.collectAsState()
        val dark = isDarkTheme(settings.theme)
        // Kept outside key() so a language switch doesn't move or resize the main window.
        val mainWindowState = rememberWindowState(width = 560.dp, height = 880.dp)

        // Resources resolve against the JVM default locale; key() recomposes every window and
        // the tray with the new strings when the language changes.
        val language = remember(settings.language) { UiLocale.apply(settings.language); settings.language }
        key(language) {
            val appName = stringResource(Res.string.app_name)

            val showLabel = stringResource(Res.string.tray_show)
            val settingsLabel = stringResource(Res.string.tray_settings)
            val quitLabel = stringResource(Res.string.tray_quit)
            SideEffect {
                tray.setTooltip(appName)
                tray.onAction = { windowVisible = true }
                tray.setMenu(
                    listOf(
                        SwingTray.Item(showLabel) { windowVisible = true },
                        SwingTray.Item(settingsLabel) {
                            showSettings = true
                            windowVisible = true
                        },
                        null,
                        SwingTray.Item(quitLabel, quit),
                    ),
                )
            }

            MirrorWindow(controller.mirror, dark)

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
                state = mainWindowState,
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
}

/** Applies [AppLanguage] to the JVM default locale, which Compose resources and getString() read. */
private object UiLocale {
    private val system: Locale = Locale.getDefault()

    fun apply(language: AppLanguage) {
        Locale.setDefault(language.tag?.let(Locale::forLanguageTag) ?: system)
    }
}

private fun <T> runOnEventThread(block: () -> T): T {
    var result: Result<T>? = null
    EventQueue.invokeAndWait { result = runCatching(block) }
    return result!!.getOrThrow()
}
