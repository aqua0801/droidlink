package io.github.aqua0801.droidlink.desktop.hotkey

import io.github.aqua0801.droidlink.desktop.platform.Os
import kotlinx.serialization.Serializable

/**
 * A global key combination. [modifiers] uses the Win32 MOD_* bit values and [keyCode] a Win32
 * virtual-key code. Only letters, digits and F1-F12 are allowed: for those the AWT key codes
 * the settings recorder sees are identical to the Win32 codes.
 */
@Serializable
data class Hotkey(val modifiers: Int, val keyCode: Int) {
    val label: String
        get() = buildList {
            if (modifiers and MOD_CONTROL != 0) add("Ctrl")
            if (modifiers and MOD_ALT != 0) add("Alt")
            if (modifiers and MOD_SHIFT != 0) add("Shift")
            if (modifiers and MOD_WIN != 0) add(if (Os.isWindows) "Win" else "Super")
            add(keyName(keyCode))
        }.joinToString("+")

    val isValid: Boolean get() = isSupportedKey(keyCode) && hasPrimaryModifier(modifiers)

    companion object {
        const val MOD_ALT = 0x1
        const val MOD_CONTROL = 0x2
        const val MOD_SHIFT = 0x4
        const val MOD_WIN = 0x8

        val DEFAULT_PUSH = Hotkey(MOD_CONTROL or MOD_ALT, 'C'.code)
        val DEFAULT_PULL = Hotkey(MOD_CONTROL or MOD_ALT, 'V'.code)

        fun isSupportedKey(code: Int): Boolean = code in 0x41..0x5A || code in 0x30..0x39 || code in 0x70..0x7B

        /** Shift alone is not enough: Shift+letter is ordinary typing. */
        private fun hasPrimaryModifier(modifiers: Int) = modifiers and (MOD_CONTROL or MOD_ALT or MOD_WIN) != 0

        /** Builds a hotkey from a key press, or null if the combination isn't allowed. */
        fun of(ctrl: Boolean, alt: Boolean, shift: Boolean, win: Boolean, keyCode: Int): Hotkey? {
            var modifiers = 0
            if (ctrl) modifiers = modifiers or MOD_CONTROL
            if (alt) modifiers = modifiers or MOD_ALT
            if (shift) modifiers = modifiers or MOD_SHIFT
            if (win) modifiers = modifiers or MOD_WIN
            return Hotkey(modifiers, keyCode).takeIf { it.isValid }
        }

        private fun keyName(code: Int): String = when (code) {
            in 0x70..0x7B -> "F${code - 0x6F}"
            else -> code.toChar().toString()
        }
    }
}

/** Which action a registered hotkey triggers. */
enum class HotkeyAction(val id: Int) {
    PUSH_CLIPBOARD(1),
    PULL_CLIPBOARD(2),
    ;

    companion object {
        fun byId(id: Int): HotkeyAction? = entries.firstOrNull { it.id == id }
    }
}

enum class HotkeyStatus { INACTIVE, ACTIVE, CONFLICT, UNSUPPORTED }
