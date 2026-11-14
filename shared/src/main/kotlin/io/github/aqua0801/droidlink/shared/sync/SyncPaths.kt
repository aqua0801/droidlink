package io.github.aqua0801.droidlink.shared.sync

import io.github.aqua0801.droidlink.shared.transfer.FileNames

/**
 * Rules for paths inside a sync folder, applied the same way on both sides.
 * Paths are relative, `/`-separated, and compared case-insensitively because both
 * Windows and Android shared storage are case-insensitive.
 */
object SyncPaths {
    private val ignoredNames = setOf("desktop.ini", "thumbs.db")

    /**
     * Canonical form of a peer-supplied path, or null if it is empty, could leave the root
     * (`..`, absolute) or contains a segment that is not a safe file name.
     */
    fun normalize(raw: String): String? {
        if (raw.startsWith('/') || raw.startsWith('\\') || raw.contains(':')) return null
        val parts = raw.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty()) return null
        if (parts.any { it == ".." || FileNames.sanitize(it) != it }) return null
        return parts.joinToString("/")
    }

    fun key(path: String): String = path.lowercase()

    /**
     * Hidden entries (any segment starting with '.') and OS clutter are never synced. This
     * also keeps `.nomedia` and in-progress temp files out of listings.
     */
    fun isIgnored(path: String): Boolean =
        path.split('/').any { it.startsWith('.') || it.lowercase() in ignoredNames }

    fun segments(path: String): List<String> = path.split('/')
}
