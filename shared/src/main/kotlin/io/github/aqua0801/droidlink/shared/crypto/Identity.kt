package io.github.aqua0801.droidlink.shared.crypto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date

/**
 * A device's long-term identity: an EC P-256 key and a self-signed certificate.
 * Peers pin each other by [fingerprint]; no CA is involved.
 */
class Identity(val privateKey: PrivateKey, val certificate: X509Certificate) {
    val fingerprint: Fingerprint = Fingerprint.of(certificate)

    fun encode(): String = json.encodeToString(
        StoredIdentity.serializer(),
        StoredIdentity(
            key = Base64.getEncoder().encodeToString(privateKey.encoded),
            cert = Base64.getEncoder().encodeToString(certificate.encoded),
        ),
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private const val VALIDITY_YEARS = 20L
        private const val DAY_MS = 24L * 60 * 60 * 1000

        fun generate(commonName: String): Identity {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
            val keyPair = generator.generateKeyPair()

            val name = X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, commonName).build()
            val now = System.currentTimeMillis()
            val serial = BigInteger(64, SecureRandom()).add(BigInteger.ONE)
            val builder = JcaX509v3CertificateBuilder(
                name,
                serial,
                Date(now - DAY_MS),
                Date(now + VALIDITY_YEARS * 365 * DAY_MS),
                name,
                keyPair.public,
            )
            val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
            val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))
            return Identity(keyPair.private, certificate)
        }

        fun decode(text: String): Identity {
            val stored = json.decodeFromString(StoredIdentity.serializer(), text)
            val key = KeyFactory.getInstance("EC")
                .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(stored.key)))
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(stored.cert))) as X509Certificate
            return Identity(key, certificate)
        }
    }
}

@Serializable
private data class StoredIdentity(val version: Int = 1, val key: String, val cert: String)

/** Loads the identity from [file], creating and persisting a new one on first run. */
object IdentityFile {
    fun loadOrCreate(file: File, commonName: String): Identity {
        if (file.isFile) {
            try {
                return Identity.decode(file.readText())
            } catch (e: Exception) {
                // Keep the unreadable file for inspection instead of silently overwriting it.
                file.renameTo(File(file.parentFile, "${file.name}.bad-${System.currentTimeMillis()}"))
            }
        }
        val identity = Identity.generate(commonName)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(identity.encode())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return identity
    }
}
