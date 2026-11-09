package io.github.aqua0801.droidlink.transfer

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import io.github.aqua0801.droidlink.shared.transfer.FileSink
import io.github.aqua0801.droidlink.shared.transfer.IncomingFiles
import java.io.BufferedOutputStream
import java.io.IOException

/**
 * Saves received files to `Download/DroidLink` through MediaStore, so no storage permission
 * is needed. Entries stay IS_PENDING (hidden from other apps) until the digest checks out;
 * MediaStore picks a unique name if one already exists.
 */
class MediaStoreIncomingFiles(private val context: Context) : IncomingFiles {
    override fun open(name: String, size: Long, mime: String?): FileSink {
        // Allocatable bytes include cache the system can clear; 0 if unknown (then don't block).
        val storage = context.getSystemService(StorageManager::class.java)
        val free = context.getExternalFilesDir(null)
            ?.let { dir -> runCatching { storage.getAllocatableBytes(storage.getUuidForPath(dir)) }.getOrNull() }
            ?: 0L
        if (free > 0 && free < size + SPACE_MARGIN) throw IOException("not enough space")

        val resolver = context.contentResolver
        // Prefer the type implied by the extension; a mismatch makes MediaStore append one.
        val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: mime?.takeIf { '/' in it }
            ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, type)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert failed")
        val raw = try {
            resolver.openOutputStream(uri, "w") ?: throw IOException("no output stream")
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        val output = BufferedOutputStream(raw, 256 * 1024)

        return object : FileSink {
            override val output = output

            override fun commit(): String {
                output.close()
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                return uri.toString()
            }

            override fun abort() {
                runCatching { output.close() }
                runCatching { resolver.delete(uri, null, null) }
            }
        }
    }

    companion object {
        const val FOLDER = "DroidLink"
        private const val SPACE_MARGIN = 64L * 1024 * 1024
    }
}
