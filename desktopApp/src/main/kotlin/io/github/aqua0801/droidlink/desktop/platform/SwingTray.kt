package io.github.aqua0801.droidlink.desktop.platform

import java.awt.AWTException
import java.awt.Color
import java.awt.EventQueue
import java.awt.Image
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.Window
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JDialog
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.UIManager
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

/**
 * System tray icon with a Swing popup menu; also the notification sink.
 *
 * Compose's Tray uses a native AWT PopupMenu. On Windows that menu encodes its labels with the
 * JVM default charset, which is UTF-8 since JDK 18 (JEP 400), so anything outside ASCII
 * (Chinese, "…") is drawn as boxes. A Swing JPopupMenu is drawn by Java2D and has no such issue.
 * Call everything except [notify] on the AWT event thread (Compose already runs there).
 */
class SwingTray(image: Image, tooltip: String) : TrayNotifier {
    /** A menu entry; null in [setMenu] adds a separator. */
    class Item(val label: String, val onClick: () -> Unit)

    private val icon = TrayIcon(image, tooltip).apply { isImageAutoSize = true }
    private var items: List<Item?> = emptyList()
    private var host: JDialog? = null

    /** Double-click / Enter on the icon. */
    var onAction: () -> Unit = {}

    init {
        // Native look for the menu (the folder chooser sets the same look and feel).
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
        icon.addActionListener { onAction() }
        icon.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybeShowMenu(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowMenu(e)
        })
    }

    /** Adds the icon to the system tray. Returns false when the platform has no tray. */
    fun install(): Boolean {
        if (!SystemTray.isSupported()) return false
        return try {
            SystemTray.getSystemTray().add(icon)
            true
        } catch (_: AWTException) {
            false
        }
    }

    fun remove() {
        host?.dispose()
        if (SystemTray.isSupported()) SystemTray.getSystemTray().remove(icon)
    }

    fun setTooltip(text: String) {
        icon.toolTip = text
    }

    fun setMenu(entries: List<Item?>) {
        items = entries
    }

    override fun notify(title: String, message: String) {
        EventQueue.invokeLater { icon.displayMessage(title, message, TrayIcon.MessageType.INFO) }
    }

    private fun maybeShowMenu(e: MouseEvent) {
        // Tray icon events carry screen coordinates.
        if (e.isPopupTrigger && items.isNotEmpty()) showMenu(e.x, e.y)
    }

    /**
     * A popup needs an owner window that can take focus, so it closes again when the user
     * clicks elsewhere; an invisible, transparent dialog at the cursor serves as that owner.
     */
    private fun showMenu(x: Int, y: Int) {
        host?.dispose()
        val dialog = JDialog().apply {
            isUndecorated = true
            type = Window.Type.UTILITY
            isAlwaysOnTop = true
            background = Color(0, 0, 0, 0)
            setBounds(x, y, 1, 1)
        }
        val menu = JPopupMenu()
        for (item in items) {
            if (item == null) {
                menu.addSeparator()
            } else {
                menu.add(JMenuItem(item.label).apply { addActionListener { item.onClick() } })
            }
        }
        menu.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) = Unit
            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) {
                dialog.dispose()
                if (host === dialog) host = null
            }
            override fun popupMenuCanceled(e: PopupMenuEvent) = Unit
        })
        host = dialog
        dialog.isVisible = true
        dialog.toFront()

        // Open above/left of the cursor when there is no room below/right (taskbar at the bottom).
        val config = dialog.graphicsConfiguration
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
        val screen = config.bounds
        val size = menu.preferredSize
        val dy = if (y + size.height > screen.y + screen.height - insets.bottom) -size.height else 0
        val dx = if (x + size.width > screen.x + screen.width - insets.right) -size.width else 0
        menu.show(dialog, dx, dy)
    }
}
