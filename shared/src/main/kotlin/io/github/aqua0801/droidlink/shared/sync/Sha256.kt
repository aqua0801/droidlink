package io.github.aqua0801.droidlink.shared.sync

import java.io.InputStream
import java.security.MessageDigest

object Sha256 {
    /** Lower-case hex digest of everything left in [input]; does not close it. */
    fun hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        return toHex(digest.digest())
    }

    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
