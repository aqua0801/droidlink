package io.github.aqua0801.droidlink.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import io.github.aqua0801.droidlink.data.PhoneSettings
import io.github.aqua0801.droidlink.shared.protocol.SyncEntry
import io.github.aqua0801.droidlink.shared.sync.Sha256
import io.github.aqua0801.droidlink.shared.sync.SyncPaths
import io.github.aqua0801.droidlink.shared.transfer.FileSink
import androidx.core.net.toUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

/**
 * The phone's copy of the PC sync folder, kept in a folder the user picked (Storage Access
 * Framework). It is a read-only mirror from the app's point of view: files only change
 * when the PC pushes them. A `.nomedia` marker keeps its pictures out of the gallery.
 *
 * Hashes are remembered in [indexFile] by (size, last-modified), so listing the folder for
 * the PC only reads files that were added or changed by other apps.
 */
class PhoneSyncFolder(private val context: Context, private val settings: PhoneSettings) {
    private val resolver = context.contentResolver
    private val indexFile = File(context.filesDir, "sync-index.json")
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Indexed(val size: Long, val modified: Long, val sha256: String)

    private val _tree = MutableStateFlow(settings.syncTreeUri.value?.let(::treeOrNull))
    /** Null until the user picks a folder (or after access to it was lost). */
    val tree: StateFlow<SafTree?> = _tree.asStateFlow()

    private fun treeOrNull(uri: String): SafTree? {
        val parsed = uri.toUri()
        val granted = resolver.persistedUriPermissions.any { it.uri == parsed && it.isReadPermission && it.isWritePermission }
        return if (granted) SafTree(resolver, parsed) else null
    }

    /** Stores the folder picked with ACTION_OPEN_DOCUMENT_TREE and prepares it. */
    fun choose(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        resolver.takePersistableUriPermission(uri, flags)
        // Release the previous folder so grants do not pile up.
        settings.syncTreeUri.value?.toUri()?.takeIf { it != uri }?.let { old ->
            runCatching { resolver.releasePersistableUriPermission(old, flags) }
        }
        val tree = SafTree(resolver, uri)
        runCatching { if (tree.find(tree.rootId, NOMEDIA) == null) tree.createDocument(tree.rootId, "application/octet-stream", NOMEDIA) }
        settings.setSyncTreeUri(uri.toString())
        indexFile.delete()
        _tree.value = tree
    }

    /** Display name of the chosen folder. */
    fun folderName(tree: SafTree): String =
        runCatching {
            resolver.query(tree.uri(tree.rootId), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: tree.rootId.substringAfterLast(':').substringAfterLast('/')

    /** Blocking. Everything the PC should compare against; null if no folder is set. */
    @Synchronized
    fun list(): List<SyncEntry>? {
        val tree = _tree.value ?: return null
        val previous = loadIndex()
        val fresh = HashMap<String, Indexed>()
        val out = ArrayList<SyncEntry>()
        fun walk(parentId: String, prefix: String) {
            for (child in tree.children(parentId)) {
                val path = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                if (SyncPaths.isIgnored(path)) continue
                if (child.isDirectory) {
                    walk(child.id, path)
                    continue
                }
                val key = SyncPaths.key(path)
                val known = previous[key]?.takeIf { it.size == child.size && it.modified == child.modified }
                    ?: runCatching {
                        val sha = resolver.openInputStream(tree.uri(child.id))?.use(Sha256::hex) ?: return@runCatching null
                        Indexed(child.size, child.modified, sha)
                    }.getOrNull()
                    ?: continue
                fresh[key] = known
                out += SyncEntry(path, child.size, known.sha256)
            }
        }
        walk(tree.rootId, "")
        saveIndex(fresh)
        return out
    }

    /**
     * Destination for a pushed file. Data goes to a hidden temp file first; on commit the
     * old file (if any) is deleted and the temp file renamed into place, so a failed
     * transfer never leaves a half-written file under the real name.
     */
    fun openSink(rawPath: String): FileSink {
        val tree = _tree.value ?: throw IOException("no sync folder")
        val path = SyncPaths.normalize(rawPath)?.takeUnless(SyncPaths::isIgnored) ?: throw IOException("bad path")
        val segments = SyncPaths.segments(path)
        val name = segments.last()
        val dirId = tree.ensureDirs(segments.dropLast(1))
        val tempId = tree.createDocument(dirId, "application/octet-stream", ".droidlink-${System.nanoTime()}.part")
        val digest = MessageDigest.getInstance("SHA-256")
        val stream = resolver.openOutputStream(tree.uri(tempId), "wt") ?: throw IOException("cannot write")
        val output = DigestOutputStream(stream, digest)

        return object : FileSink {
            override val output: OutputStream = output

            override fun commit(): String {
                output.close()
                tree.find(dirId, name)?.let { old -> if (!old.isDirectory) tree.delete(old.id) }
                val finalId = tree.rename(tempId, name)
                val written = tree.children(dirId).firstOrNull { it.id == finalId }
                if (written != null) remember(path, Indexed(written.size, written.modified, Sha256.toHex(digest.digest())))
                return tree.uri(finalId).toString()
            }

            override fun abort() {
                runCatching { output.close() }
                tree.delete(tempId)
            }
        }
    }

    /** Deletes the given files (mirror mode). Returns (deleted, failed). */
    @Synchronized
    fun delete(paths: List<String>): Pair<Int, Int> {
        val tree = _tree.value ?: return 0 to paths.size
        var deleted = 0
        var failed = 0
        val index = loadIndex().toMutableMap()
        for (raw in paths) {
            val path = SyncPaths.normalize(raw)
            val segments = path?.let(SyncPaths::segments)
            val dir = segments?.let { tree.resolveDirs(it.dropLast(1)) }
            val file = dir?.let { tree.find(it, segments.last()) }?.takeUnless { it.isDirectory }
            if (file != null && tree.delete(file.id)) {
                deleted++
                index.remove(SyncPaths.key(path))
            } else {
                failed++
            }
        }
        saveIndex(index)
        return deleted to failed
    }

    @Synchronized
    private fun remember(path: String, entry: Indexed) {
        saveIndex(loadIndex() + (SyncPaths.key(path) to entry))
    }

    private fun loadIndex(): Map<String, Indexed> =
        runCatching { json.decodeFromString<Map<String, Indexed>>(indexFile.readText()) }.getOrNull().orEmpty()

    private fun saveIndex(index: Map<String, Indexed>) {
        runCatching {
            val tmp = File(indexFile.parentFile, indexFile.name + ".tmp")
            tmp.writeText(json.encodeToString(index))
            tmp.renameTo(indexFile)
        }
    }

    private companion object {
        const val NOMEDIA = ".nomedia"
    }
}
