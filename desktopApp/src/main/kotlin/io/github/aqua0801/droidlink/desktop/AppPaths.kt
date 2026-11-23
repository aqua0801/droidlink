package io.github.aqua0801.droidlink.desktop

import io.github.aqua0801.droidlink.desktop.platform.Os
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Per-user locations. Data: `%APPDATA%\DroidLink` on Windows, `$XDG_CONFIG_HOME/droidlink`
 * (usually `~/.config/droidlink`) elsewhere. Received files: the user's Downloads folder.
 */
class AppPaths(val dir: File) {
    val identityFile = File(dir, "identity.json")
    val trustedFile = File(dir, "trusted-devices.json")
    val settingsFile = File(dir, "settings.json")

    /** Received files go to `<Downloads>/DroidLink`. */
    val downloadsDir = File(userDownloadsDir(), "DroidLink")

    /** Default sync folder, `<home>/DroidLink Sync`, used until the user picks another. */
    val defaultSyncDir = File(System.getProperty("user.home"), "DroidLink Sync")
    val syncCacheFile = File(dir, "sync-cache.json")

    companion object {
        fun default(): AppPaths {
            val home = System.getProperty("user.home")
            val dir = when {
                Os.isWindows -> System.getenv("APPDATA")?.let { File(it, "DroidLink") }
                else -> System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let { File(it, "droidlink") }
            } ?: File(home, ".config/droidlink")
            dir.mkdirs()
            return AppPaths(dir)
        }

        /**
         * Linux desktops may localize the folder (e.g. ~/下載); `xdg-user-dir` knows the real one.
         * It answers the home folder itself when none is configured, so that falls back too.
         */
        private fun userDownloadsDir(): File {
            val home = File(System.getProperty("user.home"))
            if (Os.isLinux) {
                Os.run("xdg-user-dir", "DOWNLOAD", timeoutMs = 2_000)
                    ?.takeIf { it.first == 0 }
                    ?.second?.let(::File)
                    ?.takeIf { it.isDirectory && it != home }
                    ?.let { return it }
            }
            return File(home, "Downloads")
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
