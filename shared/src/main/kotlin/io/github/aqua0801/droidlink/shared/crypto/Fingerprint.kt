package io.github.aqua0801.droidlink.shared.crypto

import java.security.MessageDigest
import java.security.cert.Certificate

/** SHA-256 of a certificate's DER encoding, as 64 lowercase hex characters. */
@JvmInline
value class Fingerprint(val hex: String) {
    init {
        require(isValid(hex)) { "invalid fingerprint: $hex" }
    }

    /** Short, human-comparable form, e.g. `3FA2 91C0 7B1E 0D44 A9F3 22E8`. */
    fun display(): String = hex.uppercase().take(24).chunked(4).joinToString(" ")

    override fun toString(): String = hex

    companion object {
        fun of(certificate: Certificate): Fingerprint {
            val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            return Fingerprint(digest.joinToString("") { "%02x".format(it) })
        }

        fun parseOrNull(text: String): Fingerprint? =
            text.trim().lowercase().takeIf(::isValid)?.let(::Fingerprint)

        private fun isValid(text: String): Boolean =
            text.length == 64 && text.all { it in '0'..'9' || it in 'a'..'f' }
    }
}
