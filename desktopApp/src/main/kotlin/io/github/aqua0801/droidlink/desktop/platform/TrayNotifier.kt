package io.github.aqua0801.droidlink.desktop.platform

import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.TrayState

/**
 * Desktop notification sink. Platform-specific so a Linux implementation
 * (libnotify / D-Bus) can replace the AWT tray balloon later.
 */
interface TrayNotifier {
    fun notify(title: String, message: String)
}

/** Uses the Compose tray icon; on Windows this shows a native toast. */
class ComposeTrayNotifier(private val tray: TrayState) : TrayNotifier {
    override fun notify(title: String, message: String) {
        tray.sendNotification(Notification(title, message, Notification.Type.Info))
    }
}
