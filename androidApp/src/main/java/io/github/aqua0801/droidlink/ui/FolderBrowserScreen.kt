package io.github.aqua0801.droidlink.ui

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.SubcomposeAsyncImage
import io.github.aqua0801.droidlink.R
import io.github.aqua0801.droidlink.sync.DocEntry
import io.github.aqua0801.droidlink.sync.PhoneSyncFolder
import io.github.aqua0801.droidlink.sync.SafTree
import io.github.aqua0801.droidlink.ui.viewer.MediaViewer
import io.github.aqua0801.droidlink.ui.viewer.PdfViewer
import io.github.aqua0801.droidlink.ui.viewer.TextViewer
import io.github.aqua0801.droidlink.ui.viewer.ViewKind
import io.github.aqua0801.droidlink.ui.viewer.isGallery
import io.github.aqua0801.droidlink.ui.viewer.openExternally
import io.github.aqua0801.droidlink.ui.viewer.shareFile
import io.github.aqua0801.droidlink.ui.viewer.viewKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** A folder on the breadcrumb trail. The trail is saved as "id\tname" lines so it survives rotation. */
private data class Crumb(val id: String, val name: String) {
    fun encode() = "$id\t$name"

    companion object {
        fun decode(s: String) = Crumb(s.substringBefore('\t'), s.substringAfter('\t'))
    }
}

/**
 * Read-only browser for the sync folder: breadcrumb trail, list or grid with thumbnails,
 * and in-app viewers for media, PDF and text. Other files open in another app.
 * Content changes only when the PC pushes.
 */
@Composable
fun FolderBrowserScreen(folder: PhoneSyncFolder, onBack: () -> Unit) {
    val tree by folder.tree.collectAsStateWithLifecycle()
    val t = tree
    if (t == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val context = LocalContext.current
    var trail by rememberSaveable { mutableStateOf("") }
    val crumbs = remember(trail) { trail.lines().filter { it.isNotEmpty() }.map(Crumb::decode) }
    fun setCrumbs(list: List<Crumb>) {
        trail = list.joinToString("\n") { it.encode() }
    }
    var grid by rememberSaveable { mutableStateOf(false) }
    /** Id of the file open in a viewer, or empty. */
    var viewing by rememberSaveable { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    val currentId = crumbs.lastOrNull()?.id ?: t.rootId

    val rootName by produceState("…", t) { value = withContext(Dispatchers.IO) { folder.folderName(t) } }
    val entries by produceState<List<DocEntry>?>(null, t, currentId, refresh) {
        value = null
        value = withContext(Dispatchers.IO) {
            runCatching { t.children(currentId) }.getOrDefault(emptyList())
                .filterNot { it.name.startsWith('.') }
                .sortedWith(compareBy<DocEntry>({ !it.isDirectory }, { it.name.lowercase() }))
        }
    }

    val open: (DocEntry) -> Unit = { entry ->
        when {
            entry.isDirectory -> setCrumbs(crumbs + Crumb(entry.id, entry.name))
            entry.viewKind == ViewKind.OTHER -> openExternally(context, t.uri(entry.id), entry.mime)
            else -> viewing = entry.id
        }
    }

    BackHandler {
        if (crumbs.isNotEmpty()) setCrumbs(crumbs.dropLast(1)) else onBack()
    }

    // The list stays composed under the viewer so its scroll position survives.
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                Row(modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.settings_back))
                    }
                    Text(stringResource(R.string.sync_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { grid = !grid }) {
                        Icon(
                            painterResource(if (grid) R.drawable.ic_view_list else R.drawable.ic_grid_view),
                            contentDescription = stringResource(if (grid) R.string.browser_list else R.string.browser_grid),
                        )
                    }
                    IconButton(onClick = { refresh++ }) {
                        Icon(painterResource(R.drawable.ic_refresh), contentDescription = stringResource(R.string.browser_refresh))
                    }
                }
                Breadcrumbs(rootName = rootName, crumbs = crumbs, onSelect = { depth -> setCrumbs(crumbs.take(depth)) })
                HorizontalDivider()
                val list = entries
                when {
                    list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    list.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.browser_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    grid -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(108.dp),
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(list, key = { it.id }) { entry -> GridCell(t, entry, onClick = { open(entry) }) }
                    }
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(list, key = { it.id }) { entry -> EntryRow(t, entry, onClick = { open(entry) }) }
                    }
                }
            }
        }

        val shown = entries?.firstOrNull { it.id == viewing }
        if (shown != null) {
            val close = { viewing = "" }
            when (shown.viewKind) {
                ViewKind.IMAGE, ViewKind.VIDEO -> {
                    val gallery = entries.orEmpty().filter { it.viewKind.isGallery }
                    MediaViewer(t, gallery, gallery.indexOf(shown), onBack = close)
                }
                ViewKind.AUDIO -> MediaViewer(t, listOf(shown), 0, onBack = close)
                ViewKind.PDF -> PdfViewer(t, shown, onBack = close)
                ViewKind.TEXT -> TextViewer(t, shown, onBack = close)
                ViewKind.OTHER -> Unit
            }
        }
    }
}

@Composable
private fun Breadcrumbs(rootName: String, crumbs: List<Crumb>, onSelect: (depth: Int) -> Unit) {
    val scroll = rememberScrollState()
    LaunchedEffect(crumbs.size) { scroll.animateScrollTo(scroll.maxValue) }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val names = listOf(rootName) + crumbs.map { it.name }
        names.forEachIndexed { depth, name ->
            if (depth > 0) Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
            val last = depth == names.lastIndex
            TextButton(onClick = { onSelect(depth) }, enabled = !last) {
                Text(
                    name,
                    fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (last) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun EntryRow(tree: SafTree, entry: DocEntry, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Thumbnail(tree, entry, size = 44.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!entry.isDirectory) {
                val date = remember(entry.modified) {
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.modified))
                }
                Text(
                    "${Formatter.formatShortFileSize(context, entry.size)} · $date",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!entry.isDirectory) {
            IconButton(onClick = { shareFile(context, tree.uri(entry.id), entry.mime) }) {
                Icon(painterResource(R.drawable.ic_share), contentDescription = stringResource(R.string.browser_share))
            }
        }
    }
}

@Composable
private fun GridCell(tree: SafTree, entry: DocEntry, onClick: () -> Unit) {
    val media = entry.viewKind.isGallery
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
    ) {
        if (media) {
            Thumbnail(tree, entry, size = null)
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    painterResource(iconFor(entry)),
                    contentDescription = null,
                    tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(36.dp),
                )
                Text(
                    entry.name,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** Image/video preview via Coil; other files (and failures) fall back to a type icon. [size] null fills the parent. */
@Composable
private fun Thumbnail(tree: SafTree, entry: DocEntry, size: Dp?) {
    val box = if (size != null) Modifier.size(size).clip(RoundedCornerShape(6.dp)) else Modifier.fillMaxSize()
    val fallback = @Composable {
        Box(box, contentAlignment = Alignment.Center) {
            Icon(
                painterResource(iconFor(entry)),
                contentDescription = null,
                tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }
    }
    if (!entry.viewKind.isGallery) {
        fallback()
        return
    }
    Box(box) {
        SubcomposeAsyncImage(
            model = tree.uri(entry.id),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            loading = { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh)) },
            error = { fallback() },
        )
        if (entry.viewKind == ViewKind.VIDEO) {
            Icon(
                painterResource(R.drawable.ic_play_circle),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(if (size == null) 36.dp else 20.dp),
            )
        }
    }
}

@DrawableRes
private fun iconFor(entry: DocEntry): Int = when {
    entry.isDirectory -> R.drawable.ic_folder
    else -> when (entry.viewKind) {
        ViewKind.IMAGE -> R.drawable.ic_image
        ViewKind.VIDEO -> R.drawable.ic_movie
        ViewKind.AUDIO -> R.drawable.ic_music_note
        ViewKind.PDF -> R.drawable.ic_picture_as_pdf
        ViewKind.TEXT -> R.drawable.ic_description
        ViewKind.OTHER -> R.drawable.ic_draft
    }
}
