package io.github.aqua0801.droidlink.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import io.github.aqua0801.droidlink.desktop.ScreenshotNotice
import io.github.aqua0801.droidlink.desktop.platform.FileReveal
import io.github.aqua0801.droidlink.desktop.platform.ImageClipboard
import io.github.aqua0801.droidlink.desktop.resources.Res
import io.github.aqua0801.droidlink.desktop.resources.ic_close
import io.github.aqua0801.droidlink.desktop.resources.ic_content_copy
import io.github.aqua0801.droidlink.desktop.resources.ic_folder_open
import io.github.aqua0801.droidlink.desktop.resources.ic_open_in_new
import io.github.aqua0801.droidlink.desktop.resources.shot_close
import io.github.aqua0801.droidlink.desktop.resources.shot_copied
import io.github.aqua0801.droidlink.desktop.resources.shot_copy
import io.github.aqua0801.droidlink.desktop.resources.shot_copy_failed
import io.github.aqua0801.droidlink.desktop.resources.shot_open
import io.github.aqua0801.droidlink.desktop.resources.shot_reveal
import io.github.aqua0801.droidlink.desktop.resources.shot_title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.skia.Image as SkiaImage
import java.awt.GraphicsEnvironment
import java.text.DateFormat
import java.util.Date

private val PopupSize = DpSize(380.dp, 300.dp)
private const val MARGIN = 12
private const val AUTO_DISMISS_MS = 8_000L

private enum class CopyState { IDLE, DONE, FAILED }

/**
 * Small always-on-top card in the bottom-right corner of the screen, like a toast with a
 * thumbnail. It does not take focus; it closes itself after a few seconds unless the
 * pointer is over it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ScreenshotPopup(notice: ScreenshotNotice, dark: Boolean, onDismiss: () -> Unit) {
    // Work area in AWT user-space units, which match dp under Java's HiDPI scaling.
    val area = remember { GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }
    val state = rememberWindowState(
        size = PopupSize,
        position = WindowPosition(
            (area.x + area.width - PopupSize.width.value - MARGIN).dp,
            (area.y + area.height - PopupSize.height.value - MARGIN).dp,
        ),
    )
    var hovered by remember { mutableStateOf(false) }
    var copy by remember { mutableStateOf(CopyState.IDLE) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(hovered) {
        if (!hovered) {
            delay(AUTO_DISMISS_MS)
            onDismiss()
        }
    }
    val thumbnail by produceState<ImageBitmap?>(null, notice.file) {
        value = withContext(Dispatchers.IO) {
            runCatching { SkiaImage.makeFromEncoded(notice.file.readBytes()).toComposeImageBitmap() }.getOrNull()
        }
    }
    val time = remember(notice.receivedAt) { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(notice.receivedAt)) }

    Window(
        onCloseRequest = onDismiss,
        state = state,
        title = stringResource(Res.string.shot_title, notice.peerName),
        icon = AppIcon,
        undecorated = true,
        transparent = true,
        resizable = false,
        alwaysOnTop = true,
        focusable = false,
    ) {
        DroidLinkDesktopTheme(dark) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp)
                    .onPointerEvent(PointerEventType.Enter) { hovered = true }
                    .onPointerEvent(PointerEventType.Exit) { hovered = false },
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                tonalElevation = 3.dp,
                shadowElevation = 4.dp,
            ) {
                Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        modifier = Modifier
                            .width(120.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { FileReveal.open(notice.file) },
                        contentAlignment = Alignment.Center,
                    ) {
                        val image = thumbnail
                        if (image != null) {
                            Image(image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                        } else {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                    Column(modifier = Modifier.fillMaxHeight().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(Res.string.shot_title, notice.peerName),
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "$time · ${notice.file.name}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconAction(Res.drawable.ic_close, stringResource(Res.string.shot_close), onDismiss)
                        }
                        Spacer(Modifier.weight(1f))
                        FilledTonalButton(
                            onClick = {
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) { ImageClipboard.copy(notice.file) }
                                    copy = if (ok) CopyState.DONE else CopyState.FAILED
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            ButtonLabel(
                                Res.drawable.ic_content_copy,
                                when (copy) {
                                    CopyState.IDLE -> Res.string.shot_copy
                                    CopyState.DONE -> Res.string.shot_copied
                                    CopyState.FAILED -> Res.string.shot_copy_failed
                                },
                            )
                        }
                        OutlinedButton(onClick = { FileReveal.open(notice.file) }, modifier = Modifier.fillMaxWidth()) {
                            ButtonLabel(Res.drawable.ic_open_in_new, Res.string.shot_open)
                        }
                        OutlinedButton(onClick = { FileReveal.reveal(notice.file) }, modifier = Modifier.fillMaxWidth()) {
                            ButtonLabel(Res.drawable.ic_folder_open, Res.string.shot_reveal)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ButtonLabel(icon: DrawableResource, label: StringResource) {
    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(8.dp))
    Text(stringResource(label))
}
