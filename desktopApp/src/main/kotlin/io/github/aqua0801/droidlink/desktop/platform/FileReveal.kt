package io.github.aqua0801.droidlink.desktop.platform

import java.awt.Desktop
import java.io.File

/**
 * Opens files and folders in the OS shell. Platform-specific so Linux file managers can get
 * proper "show in folder" support later; for now non-Windows just opens the folder.
 */
object FileReveal {
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    fun open(file: File) = background { Desktop.getDesktop().open(file) }

    fun openFolder(dir: File) = background {
        dir.mkdirs()
        Desktop.getDesktop().open(dir)
    }

    /** Opens the containing folder with the file selected (Windows), or just the folder. */
    fun reveal(file: File) = background {
        if (isWindows && file.exists()) {
            ProcessBuilder("explorer.exe", "/select,", file.absolutePath).start()
        } else {
            Desktop.getDesktop().open(file.parentFile)
        }
    }

    // Desktop.open can block for a moment; keep it off the UI thread.
    private fun background(action: () -> Unit) {
        Thread { runCatching(action) }.apply { isDaemon = true; start() }
    }
}
