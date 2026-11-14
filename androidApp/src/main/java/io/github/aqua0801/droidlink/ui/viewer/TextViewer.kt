package io.github.aqua0801.droidlink.ui.viewer

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.aqua0801.droidlink.R
import io.github.aqua0801.droidlink.sync.DocEntry
import io.github.aqua0801.droidlink.sync.SafTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_BYTES = 512 * 1024

private data class TextContent(val text: String, val truncated: Boolean)

/** Plain-text and source viewer; lines are not wrapped so code keeps its shape. */
@Composable
fun TextViewer(tree: SafTree, entry: DocEntry, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val content by produceState<Result<TextContent>?>(null, entry.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val stream = context.contentResolver.openInputStream(tree.uri(entry.id)) ?: error("cannot open")
                val bytes = stream.use { readUpTo(it, MAX_BYTES + 1) }
                TextContent(String(bytes, 0, minOf(bytes.size, MAX_BYTES), Charsets.UTF_8), bytes.size > MAX_BYTES)
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column {
            ViewerTopBar(tree, entry, subtitle = null, visible = true, onBack = onBack, overMedia = false)
            val loaded = content
            when {
                loaded == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                loaded.isFailure -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.viewer_load_failed))
                }
                else -> {
                    val text = loaded.getOrThrow()
                    if (text.truncated) {
                        Text(
                            stringResource(R.string.viewer_text_truncated, Formatter.formatShortFileSize(context, MAX_BYTES.toLong())),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    SelectionContainer {
                        Text(
                            text.text,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            softWrap = false,
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
                                .padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

/** InputStream.readNBytes needs API 33; minSdk is 29. */
private fun readUpTo(input: java.io.InputStream, limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (out.size() < limit) {
        val n = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
