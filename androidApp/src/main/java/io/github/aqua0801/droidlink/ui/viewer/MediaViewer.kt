package io.github.aqua0801.droidlink.ui.viewer

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import io.github.aqua0801.droidlink.R
import io.github.aqua0801.droidlink.sync.DocEntry
import io.github.aqua0801.droidlink.sync.SafTree
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage

/**
 * Full-screen gallery over [items] (images and videos of one folder), starting at [start].
 * Images get pinch, double-tap and pan zoom; swiping at the edge of a zoomed image moves to
 * the next page. Only the settled page's video plays.
 */
@Composable
fun MediaViewer(tree: SafTree, items: List<DocEntry>, start: Int, onBack: () -> Unit, onDelete: (DocEntry) -> Unit) {
    BackHandler(onBack = onBack)
    val pager = rememberPagerState(initialPage = start.coerceIn(0, (items.size - 1).coerceAtLeast(0))) { items.size }
    var chrome by rememberSaveable { mutableStateOf(true) }
    val toggleChrome = { chrome = !chrome }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, beyondViewportPageCount = 1, key = { items[it].id }) { page ->
            val entry = items[page]
            val uri = remember(entry.id) { tree.uri(entry.id) }
            when (entry.viewKind) {
                ViewKind.VIDEO, ViewKind.AUDIO -> VideoPage(uri, active = pager.settledPage == page)
                else -> ZoomableAsyncImage(
                    model = uri,
                    contentDescription = entry.name,
                    modifier = Modifier.fillMaxSize(),
                    onClick = { toggleChrome() },
                )
            }
        }
        val current = items.getOrNull(pager.currentPage)
        if (current != null) {
            ViewerTopBar(
                tree = tree,
                entry = current,
                subtitle = if (items.size > 1) stringResource(R.string.viewer_position, pager.currentPage + 1, items.size) else null,
                visible = chrome,
                onBack = onBack,
                overMedia = true,
                onDelete = { onDelete(current) },
            )
        }
    }
}

/** One ExoPlayer per video page, released when the page leaves composition. */
@OptIn(UnstableApi::class)
@Composable
private fun VideoPage(uri: android.net.Uri, active: Boolean) {
    val context = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(active) {
        if (active) player.play() else player.pause()
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PlayerView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setShowNextButton(false)
                setShowPreviousButton(false)
                this.player = player
            }
        },
        update = { it.player = player },
    )
}
