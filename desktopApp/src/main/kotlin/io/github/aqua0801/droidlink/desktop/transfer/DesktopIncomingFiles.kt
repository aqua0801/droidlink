package io.github.aqua0801.droidlink.desktop.transfer

import io.github.aqua0801.droidlink.shared.transfer.FileNames
import io.github.aqua0801.droidlink.shared.transfer.FileSink
import io.github.aqua0801.droidlink.shared.transfer.IncomingFiles
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files

/**
 * Saves received files into [dir]. Data is written to a temporary `.part` file and renamed
 * to its final name only after the digest checks out, so a half-received file never appears
 * under its real name. Existing files are never overwritten: `name (1).ext` and so on.
 */
class DesktopIncomingFiles(private val dir: File) : IncomingFiles {
    override fun open(name: String, size: Long, mime: String?): FileSink {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
        val free = dir.usableSpace
        if (free > 0 && free < size + SPACE_MARGIN) throw IOException("not enough space")

        val part = File.createTempFile(".droidlink-", ".part", dir)
        val output = BufferedOutputStream(FileOutputStream(part), 256 * 1024)
        return object : FileSink {
            override val output = output

            override fun commit(): String {
                output.close()
                return moveToUniqueName(part, name).absolutePath
            }

            override fun abort() {
                runCatching { output.close() }
                part.delete()
            }
        }
    }

    private fun moveToUniqueName(part: File, name: String): File = synchronized(commitLock) {
        var n = 0
        while (true) {
            val target = File(dir, if (n == 0) name else FileNames.numbered(name, n))
            if (!target.exists()) {
                try {
                    // No REPLACE_EXISTING: if something appeared meanwhile, try the next name.
                    Files.move(part.toPath(), target.toPath())
                    return target
                } catch (_: FileAlreadyExistsException) {
                    // fall through
                }
            }
            n++
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private companion object {
        const val SPACE_MARGIN = 64L * 1024 * 1024
        val commitLock = Any()
    }
}
