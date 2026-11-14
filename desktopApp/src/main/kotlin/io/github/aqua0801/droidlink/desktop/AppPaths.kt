package io.github.aqua0801.droidlink.desktop

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Per-user data directory: `%APPDATA%\DroidLink` on Windows, `~/.config/droidlink` elsewhere. */
class AppPaths(val dir: File) {
    val identityFile = File(dir, "identity.json")
    val trustedFile = File(dir, "trusted-devices.json")
    val settingsFile = File(dir, "settings.json")

    /** Received files go to `<home>/Downloads/DroidLink`. */
    val downloadsDir = File(System.getProperty("user.home"), "Downloads${File.separator}DroidLink")

    /** Default sync folder, `<home>/DroidLink Sync`, used until the user picks another. */
    val defaultSyncDir = File(System.getProperty("user.home"), "DroidLink Sync")
    val syncCacheFile = File(dir, "sync-cache.json")

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

/** Writes via a temp file + move so a crash never leaves a half-written file. */
fun File.writeTextAtomically(text: String) {
    parentFile?.mkdirs()
    val tmp = File(parentFile, "$name.tmp")
    tmp.writeText(text)
    Files.move(tmp.toPath(), toPath(), StandardCopyOption.REPLACE_EXISTING)
}
