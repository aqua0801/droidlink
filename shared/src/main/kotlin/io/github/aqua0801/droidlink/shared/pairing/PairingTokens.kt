package io.github.aqua0801.droidlink.shared.pairing

import io.github.aqua0801.droidlink.shared.DroidLinkDefaults
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** At most one active, single-use, short-lived pairing token at a time. */
class PairingTokens(
    private val ttlMs: Long = DroidLinkDefaults.PAIRING_TOKEN_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Token(val value: String, val expiresAt: Long)

    private val random = SecureRandom()
    private var active: Token? = null

    @Synchronized
    fun issue(): Token {
        val bytes = ByteArray(16).also(random::nextBytes)
        val token = Token(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), clock() + ttlMs)
        active = token
        return token
    }

    /** True exactly once for the active, unexpired token. */
    @Synchronized
    fun consume(candidate: String): Boolean {
        val token = active ?: return false
        if (clock() > token.expiresAt) {
            active = null
            return false
        }
        val ok = MessageDigest.isEqual(token.value.toByteArray(), candidate.toByteArray())
        if (ok) active = null
        return ok
    }

    @Synchronized
    fun cancel() {
        active = null
    }
}
