package io.github.aqua0801.droidlink.sync

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

/** One entry in a Storage Access Framework tree. */
data class DocEntry(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val modified: Long,
) {
    val isDirectory: Boolean get() = mime == Document.MIME_TYPE_DIR
}

/**
 * Thin wrapper over DocumentsContract for a folder the user granted with
 * ACTION_OPEN_DOCUMENT_TREE. Queries the provider directly, which is much faster than
 * DocumentFile for listing.
 */
class SafTree(private val resolver: ContentResolver, val treeUri: Uri) {
    val rootId: String = DocumentsContract.getTreeDocumentId(treeUri)

    fun uri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)

    fun children(parentId: String): List<DocEntry> {
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        return resolver.query(uri, projection, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        DocEntry(
                            id = c.getString(0),
                            name = c.getString(1) ?: continue,
                            mime = c.getString(2) ?: "application/octet-stream",
                            size = if (c.isNull(3)) 0 else c.getLong(3),
                            modified = if (c.isNull(4)) 0 else c.getLong(4),
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    /** Case-insensitive, like the storage underneath. */
    fun find(parentId: String, name: String): DocEntry? = children(parentId).firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** Walks [segments] from the root, creating missing folders; returns the last folder's id. */
    fun ensureDirs(segments: List<String>): String {
        var parent = rootId
        for (segment in segments) {
            val existing = find(parent, segment)
            parent = when {
                existing == null -> createDocument(parent, Document.MIME_TYPE_DIR, segment)
                existing.isDirectory -> existing.id
                else -> throw java.io.IOException("$segment is a file, not a folder")
            }
        }
        return parent
    }

    /** Resolves a path of folder names without creating anything. */
    fun resolveDirs(segments: List<String>): String? {
        var parent = rootId
        for (segment in segments) {
            val next = find(parent, segment)?.takeIf { it.isDirectory } ?: return null
            parent = next.id
        }
        return parent
    }

    fun createDocument(parentId: String, mime: String, name: String): String {
        val created = DocumentsContract.createDocument(resolver, uri(parentId), mime, name)
            ?: throw java.io.IOException("could not create $name")
        return DocumentsContract.getDocumentId(created)
    }

    fun rename(id: String, name: String): String {
        val renamed = DocumentsContract.renameDocument(resolver, uri(id), name) ?: return id
        return DocumentsContract.getDocumentId(renamed)
    }

    fun delete(id: String): Boolean = runCatching { DocumentsContract.deleteDocument(resolver, uri(id)) }.getOrDefault(false)
}
