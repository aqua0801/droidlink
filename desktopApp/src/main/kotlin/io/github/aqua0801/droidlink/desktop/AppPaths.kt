package io.github.aqua0801.droidlink.desktop

import java.io.File

/** Per-user data directory: `%APPDATA%\DroidLink` on Windows, `~/.config/droidlink` elsewhere. */
class AppPaths(val dir: File) {
    val identityFile = File(dir, "identity.json")
    val trustedFile = File(dir, "trusted-devices.json")

    companion object {
        fun default(): AppPaths {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val dir = System.getenv("APPDATA")
                ?.takeIf { isWindows }
                ?.let { File(it, "DroidLink") }
                ?: File(System.getProperty("user.home"), ".config/droidlink")
            dir.mkdirs()
            return AppPaths(dir)
        }
    }
}
