package io.github.aqua0801.droidlink.shared.transfer

/** Turns a peer-supplied file name into one that is safe to create on any platform. */
object FileNames {
    private const val MAX_LENGTH = 200
    private const val ILLEGAL = "<>:\"/\\|?*"
    private val reserved = setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }

    fun sanitize(raw: String): String {
        // Never honor directories from the peer: keep only the last path segment.
        var name = raw.substringAfterLast('/').substringAfterLast('\\')
        name = name.filter { it >= ' ' && it != '\u007f' && it !in ILLEGAL }.trim().trimEnd('.', ' ')
        if (name.isEmpty() || name == "." || name == "..") name = "file"
        if (name.substringBefore('.').uppercase() in reserved) name = "_$name"
        if (name.length > MAX_LENGTH) {
            val ext = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 20 }
            name = if (ext != null) name.take(MAX_LENGTH - ext.length - 1) + "." + ext else name.take(MAX_LENGTH)
        }
        return name
    }

    /** `report.pdf` -> `report (n).pdf`, keeping a leading-dot name like `.env` whole. */
    fun numbered(name: String, n: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) "$name ($n)" else "${name.substring(0, dot)} ($n)${name.substring(dot)}"
    }
}
