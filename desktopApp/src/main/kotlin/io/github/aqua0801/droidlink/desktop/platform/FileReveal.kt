package io.github.aqua0801.droidlink.desktop.platform

import java.awt.Desktop
import java.io.File

/**
 * Opens files and folders in the OS shell.
 * - Windows: the shell via java.awt.Desktop; "show in folder" via `explorer /select`.
 * - Linux: `xdg-open`, which follows the user's default apps on any desktop. "Show in folder"
 *   asks the file manager over D-Bus (org.freedesktop.FileManager1, supported by Nautilus,
 *   Dolphin, Nemo, Thunar, ...) and falls back to opening the folder.
 */
object FileReveal {
    fun open(file: File) = background { openPath(file) }

    fun openFolder(dir: File) = background {
        dir.mkdirs()
        openPath(dir)
    }

    /** Opens the containing folder with the file selected where the platform allows it. */
    fun reveal(file: File) = background {
        when {
            Os.isWindows && file.exists() -> ProcessBuilder("explorer.exe", "/select,", file.absolutePath).start()
            Os.isLinux && file.exists() && showItemOverDbus(file) -> Unit
            else -> openPath(file.parentFile)
        }
    }

    private fun openPath(file: File) {
        if (Os.isLinux && Os.which("xdg-open") != null) {
            Os.launch("xdg-open", file.absolutePath)
        } else {
            Desktop.getDesktop().open(file)
        }
    }

    private fun showItemOverDbus(file: File): Boolean {
        if (Os.which("gdbus") == null) return false
        val uri = file.toURI().toASCIIString().replace("'", "%27")
        val result = Os.run(
            "gdbus", "call", "--session",
            "--dest", "org.freedesktop.FileManager1",
            "--object-path", "/org/freedesktop/FileManager1",
            "--method", "org.freedesktop.FileManager1.ShowItems",
            "['$uri']", "",
        )
        return result?.first == 0
    }

    // Opening can block for a moment; keep it off the UI thread.
    private fun background(action: () -> Unit) {
        Thread { runCatching(action) }.apply { isDaemon = true; start() }
    }
}
