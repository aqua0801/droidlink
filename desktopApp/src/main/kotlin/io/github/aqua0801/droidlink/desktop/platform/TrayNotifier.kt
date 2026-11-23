package io.github.aqua0801.droidlink.desktop.platform

import java.io.File
import java.util.concurrent.Executors

/** Desktop notification sink. */
interface TrayNotifier {
    fun notify(title: String, message: String)

    companion object {
        /**
         * Windows: the tray icon's toast. Linux: the freedesktop notification service, which
         * every mainstream desktop provides; the tray balloon only if that is missing.
         */
        fun create(tray: SwingTray?, iconFile: File?): TrayNotifier = when {
            Os.isLinux -> LinuxNotifier(iconFile, fallback = tray)
            tray != null -> tray
            else -> Silent
        }
    }

    object Silent : TrayNotifier {
        override fun notify(title: String, message: String) = Unit
    }
}

/**
 * Sends org.freedesktop.Notifications messages through `notify-send` (libnotify) or, when
 * that isn't installed, `gdbus` (part of GLib, present on GNOME, KDE, Xfce, ...).
 * Runs the tool on one background thread so callers never wait on D-Bus.
 */
class LinuxNotifier(private val iconFile: File?, private val fallback: TrayNotifier?) : TrayNotifier {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "droidlink-notify").apply { isDaemon = true } }

    @Volatile
    private var serviceMissing = false

    override fun notify(title: String, message: String) {
        worker.execute {
            if (!serviceMissing && (sendWithNotifySend(title, message) || sendWithGdbus(title, message))) return@execute
            if (!serviceMissing) {
                serviceMissing = true
                System.err.println("DroidLink: no desktop notification service found; notifications are off")
            }
            fallback?.notify(title, message)
        }
    }

    private fun sendWithNotifySend(title: String, message: String): Boolean {
        if (Os.which("notify-send") == null) return false
        val command = buildList {
            add("notify-send")
            add("--app-name=DroidLink")
            iconFile?.takeIf(File::isFile)?.let { add("--icon=${it.absolutePath}") }
            add("--")
            add(title)
            add(message)
        }
        return Os.run(*command.toTypedArray())?.first == 0
    }

    private fun sendWithGdbus(title: String, message: String): Boolean {
        if (Os.which("gdbus") == null) return false
        val icon = iconFile?.takeIf(File::isFile)?.absolutePath.orEmpty()
        val result = Os.run(
            "gdbus", "call", "--session",
            "--dest", "org.freedesktop.Notifications",
            "--object-path", "/org/freedesktop/Notifications",
            "--method", "org.freedesktop.Notifications.Notify",
            "DroidLink", "0", gvariantString(icon), gvariantString(title), gvariantString(message),
            "[]", "{}", "-1",
        )
        return result?.first == 0
    }

    /** Quotes text as a GVariant string literal for gdbus. */
    private fun gvariantString(text: String): String =
        "'" + text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'"
}
