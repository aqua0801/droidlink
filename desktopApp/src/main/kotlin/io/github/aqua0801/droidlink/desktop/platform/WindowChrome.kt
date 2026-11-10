package io.github.aqua0801.droidlink.desktop.platform

import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.Window

/**
 * Native window frame tweaks. Windows draws the title bar itself, so a dark app theme
 * otherwise keeps a light title bar. Other platforms and older Windows builds are a no-op.
 */
object WindowChrome {
    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    // DWMWA_USE_IMMERSIVE_DARK_MODE: 20 on Windows 10 20H1+ and 11, 19 on earlier Windows 10 builds.
    private const val ATTR_DARK_MODE = 20
    private const val ATTR_DARK_MODE_LEGACY = 19

    // SWP_NOSIZE | SWP_NOMOVE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED
    private const val FRAME_CHANGED_ONLY = 0x0001 or 0x0002 or 0x0004 or 0x0010 or 0x0020

    private interface Dwmapi : StdCallLibrary {
        fun DwmSetWindowAttribute(hwnd: WinDef.HWND, attribute: Int, value: IntByReference, size: Int): Int
    }

    private val dwm: Dwmapi? by lazy {
        if (!isWindows) null else runCatching { Native.load("dwmapi", Dwmapi::class.java) }.getOrNull()
    }

    fun setDarkTitleBar(window: Window, dark: Boolean) {
        val api = dwm ?: return
        runCatching {
            val hwnd = WinDef.HWND(Native.getComponentPointer(window) ?: return)
            val value = IntByReference(if (dark) 1 else 0)
            if (api.DwmSetWindowAttribute(hwnd, ATTR_DARK_MODE, value, 4) != 0) {
                api.DwmSetWindowAttribute(hwnd, ATTR_DARK_MODE_LEGACY, value, 4)
            }
            // Ask Windows to redraw the frame now instead of on the next activation.
            User32.INSTANCE.SetWindowPos(hwnd, null, 0, 0, 0, 0, FRAME_CHANGED_ONLY)
        }
    }
}
