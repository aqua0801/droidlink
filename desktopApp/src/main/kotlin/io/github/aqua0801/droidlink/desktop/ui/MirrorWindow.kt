package io.github.aqua0801.droidlink.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import io.github.aqua0801.droidlink.desktop.mirror.MirrorController
import io.github.aqua0801.droidlink.desktop.mirror.MirrorState
import io.github.aqua0801.droidlink.desktop.resources.Res
import io.github.aqua0801.droidlink.desktop.resources.mirror_failed
import io.github.aqua0801.droidlink.desktop.resources.mirror_starting
import io.github.aqua0801.droidlink.desktop.resources.mirror_title
import org.jetbrains.compose.resources.stringResource

/**
 * Phone screen in its own window. The picture keeps its aspect ratio; when the phone
 * rotates (a new capture session) the window is reshaped to match.
 */
@Composable
fun MirrorWindow(mirror: MirrorController, dark: Boolean) {
    val state by mirror.state.collectAsState()
    if (state is MirrorState.Idle) return
    val frame by mirror.frame.collectAsState()
    val windowState = rememberWindowState(size = DpSize(420.dp, 860.dp))

    val running = state as? MirrorState.Running
    LaunchedEffect(running?.width, running?.height) {
        if (running == null || running.width <= 0 || running.height <= 0) return@LaunchedEffect
        // Keep the current height (or width, for landscape) and fit the other side.
        val current = windowState.size
        windowState.size = if (running.height >= running.width) {
            DpSize(current.height * running.width / running.height, current.height)
        } else {
            DpSize(current.width, current.width * running.height / running.width)
        }
    }

    val title = when (val s = state) {
        is MirrorState.Running -> stringResource(Res.string.mirror_title, s.deviceName)
        else -> stringResource(Res.string.mirror_starting)
    }
    Window(onCloseRequest = mirror::stop, state = windowState, title = title, icon = AppIcon) {
        DroidLinkDesktopTheme(dark) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
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
                        stringResource(Res.string.mirror_failed, (state as MirrorState.Failed).reason),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(24.dp),
                    )
                    else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text(stringResource(Res.string.mirror_starting), color = Color.White)
                    }
                }
            }
        }
    }
}
