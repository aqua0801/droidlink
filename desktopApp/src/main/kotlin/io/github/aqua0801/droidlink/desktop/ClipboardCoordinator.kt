package io.github.aqua0801.droidlink.desktop

import io.github.aqua0801.droidlink.desktop.adb.AgentController
import io.github.aqua0801.droidlink.desktop.hotkey.HotkeyAction
import io.github.aqua0801.droidlink.desktop.hotkey.HotkeyStatus
import io.github.aqua0801.droidlink.desktop.platform.GlobalHotkeys
import io.github.aqua0801.droidlink.desktop.platform.SystemClipboard
import io.github.aqua0801.droidlink.desktop.platform.TrayNotifier
import io.github.aqua0801.droidlink.desktop.resources.Res
import io.github.aqua0801.droidlink.desktop.resources.clip_needs_enhanced_body
import io.github.aqua0801.droidlink.desktop.resources.clip_needs_enhanced_title
import io.github.aqua0801.droidlink.desktop.resources.clip_no_phone
import io.github.aqua0801.droidlink.desktop.resources.clip_pc_empty
import io.github.aqua0801.droidlink.desktop.resources.clip_phone_empty
import io.github.aqua0801.droidlink.desktop.resources.clip_pulled
import io.github.aqua0801.droidlink.desktop.resources.clip_pushed
import io.github.aqua0801.droidlink.shared.link.LinkSession
import io.github.aqua0801.droidlink.shared.protocol.ClipboardText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import java.io.IOException

/**
 * Decides when clipboard text crosses between the PC and phones, per [DesktopSettings]:
 *
 * - PC -> phone: AUTO forwards every copy; HOTKEY forwards only on the push hotkey.
 * - Phone -> PC: AUTO has the enhanced-mode helper report every phone copy; MANUAL accepts
 *   explicit sends from the phone and enables the pull hotkey (enhanced mode reads the
 *   phone clipboard once, on request).
 *
 * Hotkey actions confirm themselves with a tray notification, since they happen blind.
 */
class ClipboardCoordinator(
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val agent: AgentController,
    private val notifier: TrayNotifier,
    private val p2pSessions: () -> Collection<LinkSession>,
) {
    val sync = ClipboardSync(SystemClipboard.create(), scope, ::onLocalCopy)

    private val hotkeys = GlobalHotkeys.create { id ->
        HotkeyAction.byId(id)?.let { action -> scope.launch { onHotkey(action) } }
    }
    private val hotkeyLock = Mutex()

    @Volatile
    private var hotkeysPaused = false

    private val _hotkeyStatus = MutableStateFlow<Map<HotkeyAction, HotkeyStatus>>(emptyMap())
    val hotkeyStatus: StateFlow<Map<HotkeyAction, HotkeyStatus>> = _hotkeyStatus.asStateFlow()

    private val mode get() = settings.settings.value

    fun start() {
        sync.start()
        scope.launch {
            settings.settings.collect { s ->
                agent.setAutoPush(s.phoneToPc == PhoneToPcMode.AUTO)
                applyHotkeys()
            }
        }
        scope.launch {
            agent.clipboardIn.collect { text -> if (mode.phoneToPc == PhoneToPcMode.AUTO) sync.applyRemote(text) }
        }
        scope.launch { agent.clipboardPulled.collect(::onPulled) }
    }

    fun stop() {
        sync.stop()
        hotkeys.close()
    }

    /** Text a phone sent over the P2P link: always an explicit action on the phone. */
    fun onPhoneText(text: String) {
        if (mode.phoneToPc != PhoneToPcMode.OFF) sync.applyRemote(text)
    }

    /** Releases the hotkeys while the settings page records a new combination. */
    fun pauseHotkeys(paused: Boolean) {
        hotkeysPaused = paused
        scope.launch { applyHotkeys() }
    }

    private suspend fun applyHotkeys() = hotkeyLock.withLock {
        val s = mode
        val wanted = buildMap {
            if (s.pcToPhone == PcToPhoneMode.HOTKEY) put(HotkeyAction.PUSH_CLIPBOARD, s.pushHotkey)
            if (s.phoneToPc == PhoneToPcMode.MANUAL) put(HotkeyAction.PULL_CLIPBOARD, s.pullHotkey)
        }
        val failed = withContext(Dispatchers.IO) {
            hotkeys.setBindings(if (hotkeysPaused) emptyMap() else wanted.mapKeys { it.key.id })
        }
        if (hotkeysPaused) return@withLock // keep showing the last real status while recording
        _hotkeyStatus.value = HotkeyAction.entries.associateWith { action ->
            when {
                !hotkeys.isSupported -> HotkeyStatus.UNSUPPORTED
                action !in wanted -> HotkeyStatus.INACTIVE
                action.id in failed -> HotkeyStatus.CONFLICT
                else -> HotkeyStatus.ACTIVE
            }
        }
    }

    // --- PC -> phone --------------------------------------------------------------------

    private suspend fun onLocalCopy(text: String): Boolean =
        mode.pcToPhone == PcToPhoneMode.AUTO && deliver(text, force = false)

    /** Sends to every phone (P2P link and enhanced mode). Returns true if any got it. */
    private suspend fun deliver(text: String, force: Boolean): Boolean {
        var delivered = false
        for (session in p2pSessions()) {
            try {
                session.send(ClipboardText(text))
                delivered = true
            } catch (_: IOException) {
                // that session is going away; the others still get it
            }
        }
        if (agent.sendClipboard(text, force)) delivered = true
        return delivered
    }

    // --- hotkeys ------------------------------------------------------------------------

    private suspend fun onHotkey(action: HotkeyAction) {
        when (action) {
            HotkeyAction.PUSH_CLIPBOARD -> {
                val text = sync.currentText() ?: return notify(Res.string.clip_pc_empty)
                if (deliver(text, force = true)) {
                    sync.recordSent(text)
                    confirm(Res.string.clip_pushed, preview(text))
                } else {
                    notify(Res.string.clip_no_phone)
                }
            }
            HotkeyAction.PULL_CLIPBOARD -> {
                if (!agent.isConnected || !agent.requestClipboard()) {
                    notifier.notify(getString(Res.string.clip_needs_enhanced_title), getString(Res.string.clip_needs_enhanced_body))
                }
            }
        }
    }

    private suspend fun onPulled(text: String) {
        if (text.isEmpty()) return notify(Res.string.clip_phone_empty)
        sync.applyRemote(text)
        confirm(Res.string.clip_pulled, preview(text))
    }

    /** A success message the user can mute; failures go through [notify] and always show. */
    private suspend fun confirm(title: StringResource, body: String) {
        if (settings.settings.value.notifications.clipboard) notify(title, body)
    }

    private suspend fun notify(title: StringResource, body: String = "") {
        notifier.notify(getString(title), body)
    }

    private fun preview(text: String): String {
        val oneLine = text.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length > PREVIEW_CHARS) oneLine.take(PREVIEW_CHARS) + "…" else oneLine
    }

    private companion object {
        const val PREVIEW_CHARS = 60
    }
}
