package io.github.aqua0801.droidlink.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import io.github.aqua0801.droidlink.desktop.mirror.MirrorController
import io.github.aqua0801.droidlink.desktop.mirror.MirrorFailure
import io.github.aqua0801.droidlink.desktop.mirror.MirrorState
import io.github.aqua0801.droidlink.desktop.mirror.ScrcpyControl
import io.github.aqua0801.droidlink.desktop.platform.ImageClipboard
import io.github.aqua0801.droidlink.desktop.resources.Res
import io.github.aqua0801.droidlink.desktop.resources.ic_apps
import io.github.aqua0801.droidlink.desktop.resources.ic_arrow_back
import io.github.aqua0801.droidlink.desktop.resources.ic_home
import io.github.aqua0801.droidlink.desktop.resources.ic_keep
import io.github.aqua0801.droidlink.desktop.resources.ic_keep_off
import io.github.aqua0801.droidlink.desktop.resources.ic_music_note
import io.github.aqua0801.droidlink.desktop.resources.ic_music_off
import io.github.aqua0801.droidlink.desktop.resources.ic_photo_camera
import io.github.aqua0801.droidlink.desktop.resources.ic_power_settings_new
import io.github.aqua0801.droidlink.desktop.resources.ic_screen_rotation
import io.github.aqua0801.droidlink.desktop.resources.ic_volume_down
import io.github.aqua0801.droidlink.desktop.resources.ic_volume_up
import io.github.aqua0801.droidlink.desktop.resources.mirror_back
import io.github.aqua0801.droidlink.desktop.resources.mirror_declined
import io.github.aqua0801.droidlink.desktop.resources.mirror_failed
import io.github.aqua0801.droidlink.desktop.resources.mirror_home
import io.github.aqua0801.droidlink.desktop.resources.mirror_mute
import io.github.aqua0801.droidlink.desktop.resources.mirror_no_answer
import io.github.aqua0801.droidlink.desktop.resources.mirror_pin
import io.github.aqua0801.droidlink.desktop.resources.mirror_power
import io.github.aqua0801.droidlink.desktop.resources.mirror_recents
import io.github.aqua0801.droidlink.desktop.resources.mirror_rotate
import io.github.aqua0801.droidlink.desktop.resources.mirror_screenshot
import io.github.aqua0801.droidlink.desktop.resources.mirror_shot_failed
import io.github.aqua0801.droidlink.desktop.resources.mirror_shot_saved
import io.github.aqua0801.droidlink.desktop.resources.mirror_starting
import io.github.aqua0801.droidlink.desktop.resources.mirror_title
import io.github.aqua0801.droidlink.desktop.resources.mirror_unmute
import io.github.aqua0801.droidlink.desktop.resources.mirror_unpin
import io.github.aqua0801.droidlink.desktop.resources.mirror_view_only
import io.github.aqua0801.droidlink.desktop.resources.mirror_volume_down
import io.github.aqua0801.droidlink.desktop.resources.mirror_volume_up
import io.github.aqua0801.droidlink.desktop.resources.mirror_waiting_phone
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private val ToolbarHeight = 48.dp

/**
 * Phone screen in its own window. Over ADB it has control: left click / drag = touch, right click =
 * back, middle click = home, wheel = scroll, typing goes to the focused field, Ctrl+V pastes
 * the PC clipboard. Wireless mirroring is view only (screenshot and pin remain). The picture
 * keeps its aspect ratio; on rotation the window is reshaped.
 */
@Composable
fun MirrorWindow(mirror: MirrorController, dark: Boolean) {
    val state by mirror.state.collectAsState()
    if (state is MirrorState.Idle) return
    val frame by mirror.frame.collectAsState()
    val audio by mirror.audio.collectAsState()
    val windowState = rememberWindowState(size = DpSize(420.dp, 900.dp))
    var pinned by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<StringResource?>(null) }
    val scope = rememberCoroutineScope()

    val running = state as? MirrorState.Running
    // Wireless sessions are view only; until the first frame, assume nothing is controllable.
    val controllable = running?.controllable == true
    LaunchedEffect(running?.width, running?.height) {
        if (running == null || running.width <= 0 || running.height <= 0) return@LaunchedEffect
        // Keep the current height (or width, for landscape) and fit the other side to the video.
        val chrome = ToolbarHeight + 32.dp // toolbar + title bar, roughly
        val current = windowState.size
        windowState.size = if (running.height >= running.width) {
            DpSize((current.height - chrome) * running.width / running.height, current.height)
        } else {
            DpSize(current.width, current.width * running.height / running.width + chrome)
        }
    }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(2_000)
            notice = null
        }
    }

    val title = when (val s = state) {
        is MirrorState.Running -> stringResource(Res.string.mirror_title, s.deviceName)
        is MirrorState.Starting -> stringResource(if (s.waitingForPhone) Res.string.mirror_waiting_phone else Res.string.mirror_starting)
        else -> stringResource(Res.string.mirror_starting)
    }
    Window(
        onCloseRequest = mirror::stop,
        state = windowState,
        title = title,
        icon = AppIcon,
        alwaysOnTop = pinned,
        onKeyEvent = { event -> controllable && handleKey(mirror, event) },
    ) {
        DroidLinkDesktopTheme(dark) {
            Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth().then(
                        if (running != null && controllable) Modifier.pointerInput(running.width, running.height) { handlePointer(mirror, running) } else Modifier,
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    val image = frame
                    when {
                        image != null -> Image(
                            bitmap = image,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            filterQuality = FilterQuality.Medium,
                            modifier = Modifier.fillMaxSize(),
                        )
                        state is MirrorState.Failed -> Text(
                            failureText(state as MirrorState.Failed),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(24.dp),
                        )
                        else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator()
                            Text(title, color = Color.White)
                        }
                    }
                    notice?.let {
                        Surface(
                            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.inverseSurface,
                        ) {
                            Text(
                                stringResource(it),
                                color = MaterialTheme.colorScheme.inverseOnSurface,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
                Surface(modifier = Modifier.fillMaxWidth().height(ToolbarHeight), tonalElevation = 2.dp) {
                    Row(horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        if (controllable) {
                        IconAction(Res.drawable.ic_arrow_back, stringResource(Res.string.mirror_back), { mirror.pressKey(ScrcpyControl.KEYCODE_BACK) })
                        IconAction(Res.drawable.ic_home, stringResource(Res.string.mirror_home), { mirror.pressKey(ScrcpyControl.KEYCODE_HOME) })
                        IconAction(Res.drawable.ic_apps, stringResource(Res.string.mirror_recents), { mirror.pressKey(ScrcpyControl.KEYCODE_APP_SWITCH) })
                        IconAction(Res.drawable.ic_volume_down, stringResource(Res.string.mirror_volume_down), { mirror.pressKey(ScrcpyControl.KEYCODE_VOLUME_DOWN) })
                        IconAction(Res.drawable.ic_volume_up, stringResource(Res.string.mirror_volume_up), { mirror.pressKey(ScrcpyControl.KEYCODE_VOLUME_UP) })
                        IconAction(Res.drawable.ic_power_settings_new, stringResource(Res.string.mirror_power), { mirror.pressKey(ScrcpyControl.KEYCODE_POWER) })
                        IconAction(Res.drawable.ic_screen_rotation, stringResource(Res.string.mirror_rotate), { mirror.send(ScrcpyControl.rotateDevice()) })
                        } else if (running != null) {
                            Text(
                                stringResource(Res.string.mirror_view_only),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                            )
                        }
                        if (audio != MirrorController.Audio.OFF) {
                            val muted = audio == MirrorController.Audio.MUTED
                            IconAction(
                                if (muted) Res.drawable.ic_music_off else Res.drawable.ic_music_note,
                                stringResource(if (muted) Res.string.mirror_unmute else Res.string.mirror_mute),
                                { mirror.toggleMute() },
                            )
                        }
                        IconAction(Res.drawable.ic_photo_camera, stringResource(Res.string.mirror_screenshot), {
                            scope.launch {
                                val saved = withContext(Dispatchers.IO) { mirror.saveScreenshot()?.also { ImageClipboard.copy(it) } }
                                notice = if (saved != null) Res.string.mirror_shot_saved else Res.string.mirror_shot_failed
                            }
                        })
                        IconAction(
                            if (pinned) Res.drawable.ic_keep_off else Res.drawable.ic_keep,
                            stringResource(if (pinned) Res.string.mirror_unpin else Res.string.mirror_pin),
                            { pinned = !pinned },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun failureText(failed: MirrorState.Failed): String = when (failed.reason) {
    MirrorFailure.DECLINED -> stringResource(Res.string.mirror_declined)
    MirrorFailure.NO_ANSWER -> stringResource(Res.string.mirror_no_answer)
    MirrorFailure.ERROR -> stringResource(Res.string.mirror_failed, failed.detail.orEmpty())
}

/** Maps pointer positions in the view to video-frame pixels (the picture is letterboxed). */
private fun toFrame(position: Offset, view: IntSize, frameWidth: Int, frameHeight: Int): Pair<Int, Int>? {
    val scale = minOf(view.width.toFloat() / frameWidth, view.height.toFloat() / frameHeight)
    if (scale <= 0f) return null
    val left = (view.width - frameWidth * scale) / 2
    val top = (view.height - frameHeight * scale) / 2
    val x = (position.x - left) / scale
    val y = (position.y - top) / scale
    if (x < 0 || y < 0 || x >= frameWidth || y >= frameHeight) return null
    return x.toInt() to y.toInt()
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.handlePointer(mirror: MirrorController, running: MirrorState.Running) {
    awaitPointerEventScope {
        var touching = false
        var last: Pair<Int, Int>? = null
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: continue
            val mapped = toFrame(change.position, size, running.width, running.height)
            when (event.type) {
                PointerEventType.Press -> when {
                    event.buttons.isPrimaryPressed && mapped != null -> {
                        touching = true
                        last = mapped
                        mirror.point(mapped.first, mapped.second)?.let { mirror.send(ScrcpyControl.touch(ScrcpyControl.ACTION_DOWN, it)) }
                    }
                    event.buttons.isSecondaryPressed -> {
                        mirror.send(ScrcpyControl.backOrScreenOn(ScrcpyControl.ACTION_DOWN))
                        mirror.send(ScrcpyControl.backOrScreenOn(ScrcpyControl.ACTION_UP))
                    }
                    event.buttons.isTertiaryPressed -> mirror.pressKey(ScrcpyControl.KEYCODE_HOME)
                }
                PointerEventType.Move -> if (touching) {
                    // Outside the picture, keep the finger at the last point inside it.
                    val at = mapped ?: last ?: continue
                    last = at
                    mirror.point(at.first, at.second)?.let { mirror.send(ScrcpyControl.touch(ScrcpyControl.ACTION_MOVE, it)) }
                }
                PointerEventType.Release -> if (touching && !event.buttons.isPrimaryPressed) {
                    touching = false
                    val at = mapped ?: last ?: continue
                    mirror.point(at.first, at.second)?.let { mirror.send(ScrcpyControl.touch(ScrcpyControl.ACTION_UP, it)) }
                }
                PointerEventType.Scroll -> if (mapped != null) {
                    val delta = change.scrollDelta
                    mirror.point(mapped.first, mapped.second)?.let {
                        // Wheel down (positive y) scrolls the content down = negative AXIS_VSCROLL.
                        mirror.send(ScrcpyControl.scroll(it, h = -delta.x, v = -delta.y))
                    }
                }
            }
            change.consume()
        }
    }
}

private val specialKeys = mapOf(
    Key.Enter to ScrcpyControl.KEYCODE_ENTER,
    Key.NumPadEnter to ScrcpyControl.KEYCODE_ENTER,
    Key.Backspace to ScrcpyControl.KEYCODE_DEL,
    Key.Delete to ScrcpyControl.KEYCODE_FORWARD_DEL,
    Key.Tab to ScrcpyControl.KEYCODE_TAB,
    Key.DirectionUp to ScrcpyControl.KEYCODE_DPAD_UP,
    Key.DirectionDown to ScrcpyControl.KEYCODE_DPAD_DOWN,
    Key.DirectionLeft to ScrcpyControl.KEYCODE_DPAD_LEFT,
    Key.DirectionRight to ScrcpyControl.KEYCODE_DPAD_RIGHT,
    Key.MoveHome to ScrcpyControl.KEYCODE_MOVE_HOME,
    Key.MoveEnd to ScrcpyControl.KEYCODE_MOVE_END,
    Key.PageUp to ScrcpyControl.KEYCODE_PAGE_UP,
    Key.PageDown to ScrcpyControl.KEYCODE_PAGE_DOWN,
    Key.Escape to ScrcpyControl.KEYCODE_BACK,
)

/**
 * Keyboard: navigation/editing keys become Android key events, printable characters are
 * injected as text, Ctrl+V pastes the PC clipboard. IME composition (e.g. Zhuyin) is not
 * forwarded; paste such text with Ctrl+V.
 */
private fun handleKey(mirror: MirrorController, event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (event.isCtrlPressed && event.key == Key.V) {
        val text = runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
        }.getOrNull()
        if (!text.isNullOrEmpty()) mirror.send(ScrcpyControl.setClipboard(text, paste = true))
        return true
    }
    specialKeys[event.key]?.let {
        mirror.pressKey(it)
        return true
    }
    if (event.isCtrlPressed || event.isAltPressed) return false
    val codePoint = event.utf16CodePoint
    if (codePoint <= 0 || Character.isISOControl(codePoint)) return false
    mirror.send(ScrcpyControl.text(String(Character.toChars(codePoint))))
    return true
}
