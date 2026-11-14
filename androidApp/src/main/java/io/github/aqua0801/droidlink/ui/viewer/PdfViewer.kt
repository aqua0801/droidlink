package io.github.aqua0801.droidlink.ui.viewer

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import io.github.aqua0801.droidlink.R
import io.github.aqua0801.droidlink.sync.DocEntry
import io.github.aqua0801.droidlink.sync.SafTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable

/** PdfRenderer allows one open page at a time, so rendering is serialized. */
private class PdfDocument(val fd: ParcelFileDescriptor) {
    val renderer = PdfRenderer(fd)
    val lock = Mutex()

    /** Width / height of each page, read once so placeholders have the right shape. */
    val ratios: List<Float> = (0 until renderer.pageCount).map { i ->
        renderer.openPage(i).use { it.width.toFloat() / it.height.coerceAtLeast(1) }
    }

    suspend fun render(index: Int, widthPx: Int): Bitmap = lock.withLock {
        renderer.openPage(index).use { page ->
            val height = (widthPx / ratios[index]).toInt().coerceAtLeast(1)
            val bitmap = createBitmap(widthPx, height)
            bitmap.eraseColor(AndroidColor.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        }
    }

    fun close() {
        runCatching { renderer.close() }
        runCatching { fd.close() }
    }
}

@Composable
fun PdfViewer(tree: SafTree, entry: DocEntry, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val doc by produceState<Result<PdfDocument>?>(null, entry.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val fd = context.contentResolver.openFileDescriptor(tree.uri(entry.id), "r") ?: error("no descriptor")
                PdfDocument(fd)
            }
        }
    }
    // Close exactly the document this effect was started for; reading `doc` inside onDispose
    // would see the newer value and close the document that just finished loading.
    val opened = doc?.getOrNull()
    DisposableEffect(opened) { onDispose { opened?.close() } }
    val list = rememberLazyListState()
    // Only recompose the title when the page number actually changes, not on every scroll frame.
    val firstPage by remember { derivedStateOf { list.firstVisibleItemIndex } }
    val pageCount = doc?.getOrNull()?.ratios?.size ?: 0

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
        ViewerTopBar(
            tree = tree,
            entry = entry,
            subtitle = if (pageCount > 0) stringResource(R.string.viewer_pdf_page, firstPage + 1, pageCount) else null,
            visible = true,
            onBack = onBack,
            overMedia = false,
        )
        val loaded = doc
        when {
            loaded == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            loaded.isFailure -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.viewer_load_failed))
            }
            else -> {
                val pdf = loaded.getOrThrow()
                LazyColumn(
                    state = list,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(pdf.ratios.size) { index -> PdfPage(pdf, index) }
                }
            }
        }
    }
}

@Composable
private fun PdfPage(pdf: PdfDocument, index: Int) {
    // Render at twice the window width so zoomed-in text stays sharp.
    val widthPx = LocalWindowInfo.current.containerSize.width.coerceAtLeast(1) * 2
    val bitmap by produceState<Result<ImageBitmap>?>(null, pdf, index, widthPx) {
        value = withContext(Dispatchers.IO) { runCatching { pdf.render(index, widthPx).asImageBitmap() } }
    }
    val zoom = rememberZoomableState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(pdf.ratios[index])
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        val result = bitmap
        val image = result?.getOrNull()
        when {
            result == null -> CircularProgressIndicator()
            image == null -> Text(stringResource(R.string.viewer_load_failed), color = Color.DarkGray)
            else -> Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().zoomable(zoom),
            )
        }
    }
}
