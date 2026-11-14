package io.github.aqua0801.droidlink.ui.viewer

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import io.github.aqua0801.droidlink.R
import io.github.aqua0801.droidlink.sync.DocEntry

/** How the in-app viewer handles a file. */
enum class ViewKind { IMAGE, VIDEO, AUDIO, PDF, TEXT, OTHER }

private val textExtensions = setOf(
    "txt", "md", "markdown", "log", "csv", "tsv", "json", "xml", "yml", "yaml", "toml", "ini", "properties", "cfg", "conf",
    "kt", "kts", "java", "gradle", "cs", "py", "js", "ts", "html", "css", "c", "h", "cpp", "hpp", "sh", "bat", "ps1", "sql",
)

/** Providers often report source files as octet-stream, so the extension is checked too. */
val DocEntry.viewKind: ViewKind
    get() {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            isDirectory -> ViewKind.OTHER
            mime.startsWith("image/") -> ViewKind.IMAGE
            mime.startsWith("video/") -> ViewKind.VIDEO
            mime.startsWith("audio/") -> ViewKind.AUDIO
            mime == "application/pdf" || ext == "pdf" -> ViewKind.PDF
            mime.startsWith("text/") || ext in textExtensions -> ViewKind.TEXT
            else -> ViewKind.OTHER
        }
    }

/** Images and videos are browsed together, like a gallery. */
val ViewKind.isGallery: Boolean get() = this == ViewKind.IMAGE || this == ViewKind.VIDEO

fun openExternally(context: Context, uri: Uri, mime: String) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.transfer_no_viewer, Toast.LENGTH_SHORT).show()
    }
}

/** Shares several files at once; falls back to a single share for one file. */
fun shareFiles(context: Context, files: List<Pair<Uri, String>>) {
    if (files.size == 1) return shareFile(context, files[0].first, files[0].second)
    val uris = ArrayList(files.map { it.first })
    val types = files.map { it.second }.distinct()
    val send = Intent(Intent.ACTION_SEND_MULTIPLE)
        .setType(if (types.size == 1) types[0] else "*/*")
        .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    context.startActivity(Intent.createChooser(send, null))
}

fun shareFile(context: Context, uri: Uri, mime: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType(mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(null, uri)
    context.startActivity(Intent.createChooser(send, null))
}
