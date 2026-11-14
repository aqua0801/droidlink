package io.github.aqua0801.droidlink.desktop.platform

import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager

/** Native-looking "pick a folder" dialog. Blocks until closed; returns null if cancelled. */
fun chooseFolder(title: String, start: File?): File? {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    val chooser = JFileChooser(start?.takeIf { it.isDirectory } ?: File(System.getProperty("user.home"))).apply {
        dialogTitle = title
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isAcceptAllFileFilterUsed = false
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}
