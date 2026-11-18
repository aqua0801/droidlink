package io.github.aqua0801.droidlink.desktop.platform

/**
 * Desktop notification sink. Platform-specific so a Linux implementation
 * (libnotify / D-Bus) can replace the AWT tray balloon later. On Windows, [SwingTray]
 * implements it with the tray icon's toast.
 */
interface TrayNotifier {
    fun notify(title: String, message: String)
}
