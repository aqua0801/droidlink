package io.github.aqua0801.droidlink.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aqua0801.droidlink.R
import io.github.aqua0801.droidlink.sync.DocEntry
import io.github.aqua0801.droidlink.sync.PhoneSyncFolder
import io.github.aqua0801.droidlink.sync.SafTree
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
 * Read-only browser for the sync folder: breadcrumb trail, folders first, tap a file to open
 * it with another app or share it. Content changes only when the PC pushes.
 */
@Composable
fun FolderBrowserScreen(folder: PhoneSyncFolder, onBack: () -> Unit) {
    val tree by folder.tree.collectAsStateWithLifecycle()
    val t = tree
    if (t == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    var trail by rememberSaveable { mutableStateOf("") }
    val crumbs = remember(trail) { trail.lines().filter { it.isNotEmpty() }.map(Crumb::decode) }
    fun setCrumbs(list: List<Crumb>) {
        trail = list.joinToString("\n") { it.encode() }
    }
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

    BackHandler {
        if (crumbs.isNotEmpty()) setCrumbs(crumbs.dropLast(1)) else onBack()
    }

    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.settings_back))
                }
                Text(
                    stringResource(R.string.sync_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { refresh++ }) {
                    Icon(painterResource(R.drawable.ic_refresh), contentDescription = stringResource(R.string.browser_refresh))
                }
            }
            Breadcrumbs(
                rootName = rootName,
                crumbs = crumbs,
                onSelect = { depth -> setCrumbs(crumbs.take(depth)) },
            )
            HorizontalDivider()
            val list = entries
            when {
                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                list.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.browser_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(list, key = { it.id }) { entry ->
                        EntryRow(
                            tree = t,
                            entry = entry,
                            onOpenFolder = { setCrumbs(crumbs + Crumb(entry.id, entry.name)) },
                        )
                    }
                }
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
private fun EntryRow(tree: SafTree, entry: DocEntry, onOpenFolder: () -> Unit) {
    val context = LocalContext.current
    val uri = remember(entry.id) { tree.uri(entry.id) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (entry.isDirectory) onOpenFolder() else open(context, uri, entry.mime) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            painterResource(iconFor(entry)),
            contentDescription = null,
            tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
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
            IconButton(onClick = { share(context, uri, entry.mime) }) {
                Icon(painterResource(R.drawable.ic_share), contentDescription = stringResource(R.string.browser_share))
            }
        }
    }
}

@DrawableRes
private fun iconFor(entry: DocEntry): Int = when {
    entry.isDirectory -> R.drawable.ic_folder
    entry.mime.startsWith("image/") -> R.drawable.ic_image
    entry.mime.startsWith("video/") -> R.drawable.ic_movie
    entry.mime.startsWith("audio/") -> R.drawable.ic_music_note
    entry.mime == "application/pdf" -> R.drawable.ic_picture_as_pdf
    entry.mime.startsWith("text/") -> R.drawable.ic_description
    else -> R.drawable.ic_draft
}

private fun open(context: Context, uri: Uri, mime: String) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.transfer_no_viewer, Toast.LENGTH_SHORT).show()
    }
}

private fun share(context: Context, uri: Uri, mime: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType(mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(null, uri)
    context.startActivity(Intent.createChooser(send, null))
}
