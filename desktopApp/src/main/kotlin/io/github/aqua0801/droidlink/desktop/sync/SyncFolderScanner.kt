package io.github.aqua0801.droidlink.desktop.sync

import io.github.aqua0801.droidlink.desktop.writeTextAtomically
import io.github.aqua0801.droidlink.shared.protocol.SyncEntry
import io.github.aqua0801.droidlink.shared.sync.Sha256
import io.github.aqua0801.droidlink.shared.sync.SyncPaths
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** A file in the PC sync folder with its sync metadata. */
data class LocalSyncFile(val entry: SyncEntry, val file: File)

/**
 * Lists the PC sync folder. Hashes are cached by (size, last-modified) in [cacheFile], so
 * only new or edited files are read again on the next scan.
 */
class SyncFolderScanner(private val cacheFile: File) {
    @Serializable
    private data class Cached(val size: Long, val modified: Long, val sha256: String)

    @Serializable
    private data class Cache(val root: String, val files: Map<String, Cached>)

    private val json = Json { ignoreUnknownKeys = true }

    /** Blocking. Hidden folders, OS clutter and names that are unsafe on the phone are skipped. */
    @Synchronized
    fun scan(root: File): List<LocalSyncFile> {
        if (!root.isDirectory) return emptyList()
        val rootPath = root.toPath().toAbsolutePath()
        val previous = load()?.takeIf { it.root == rootPath.toString() }?.files.orEmpty()
        val fresh = HashMap<String, Cached>()
        val found = ArrayList<LocalSyncFile>()

        Files.walkFileTree(rootPath, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (dir != rootPath && dir.fileName.toString().startsWith('.')) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                val relative = rootPath.relativize(file).joinToString("/")
                if (SyncPaths.isIgnored(relative)) return FileVisitResult.CONTINUE
                val path = SyncPaths.normalize(relative) ?: return FileVisitResult.CONTINUE
                val size = attrs.size()
                val modified = attrs.lastModifiedTime().toMillis()
                val cached = previous[path]?.takeIf { it.size == size && it.modified == modified }
                    ?: runCatching { Cached(size, modified, file.toFile().inputStream().use(Sha256::hex)) }.getOrNull()
                    ?: return FileVisitResult.CONTINUE // unreadable (locked, removed meanwhile)
                fresh[path] = cached
                found += LocalSyncFile(SyncEntry(path, size, cached.sha256), file.toFile())
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })

        runCatching { cacheFile.writeTextAtomically(json.encodeToString(Cache.serializer(), Cache(rootPath.toString(), fresh))) }
        return found.sortedBy { it.entry.path.lowercase() }
    }

    private fun load(): Cache? = runCatching { json.decodeFromString(Cache.serializer(), cacheFile.readText()) }.getOrNull()
}
