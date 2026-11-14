package io.github.aqua0801.droidlink.transfer

import io.github.aqua0801.droidlink.shared.protocol.FileKind
import io.github.aqua0801.droidlink.shared.transfer.FileSink
import io.github.aqua0801.droidlink.shared.transfer.IncomingFiles
import io.github.aqua0801.droidlink.sync.PhoneSyncFolder
import java.io.IOException

/** Sync pushes go into the sync folder; everything else to Downloads via MediaStore. */
class PhoneIncomingFiles(
    private val downloads: MediaStoreIncomingFiles,
    private val syncFolder: PhoneSyncFolder,
) : IncomingFiles {
    override fun open(name: String, size: Long, mime: String?, kind: FileKind, path: String?): FileSink =
        if (kind == FileKind.SYNC) {
            syncFolder.openSink(path ?: throw IOException("sync file without a path"))
        } else {
            downloads.open(name, size, mime, kind, path)
        }
}
