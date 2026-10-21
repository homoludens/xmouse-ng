package com.stripe1.xmouse.trust

import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/** Immutable full public key. Size/SSH-string preflight precedes maintained BC parsing. */
class ApprovedHostKey private constructor(private val encoded: ByteArray, val algorithm: String) {
    val fingerprint: String = "SHA256:" + Base64.getEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(encoded))
    fun bytes(): ByteArray = encoded.copyOf()
    fun matches(other: ApprovedHostKey): Boolean = MessageDigest.isEqual(encoded, other.encoded)

    companion object {
        const val MAX_BYTES = 16 * 1024
        @JvmStatic fun parse(source: ByteArray): ApprovedHostKey {
            try {
                require(source.size in 1..MAX_BYTES)
                val copy = source.copyOf()
                val input = ByteBuffer.wrap(copy)
                var fields = 0
                var algorithm = ""
                while (input.hasRemaining()) {
                    require(input.remaining() >= 4 && ++fields <= 3)
                    val length = input.int
                    require(length in 1..minOf(2049, input.remaining()))
                    if (fields == 1) {
                        require(length <= 64)
                        val name = ByteArray(length).also { input.get(it) }
                        require(name.all { it.toInt() in 33..126 })
                        algorithm = String(name, StandardCharsets.US_ASCII)
                        require(algorithm in setOf("ssh-rsa", "ssh-ed25519", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521"))
                    } else input.position(input.position() + length)
                }
                val parsed = OpenSSHPublicKeyUtil.parsePublicKey(copy)
                // Reject inconsistent EC type/curve, non-canonical integers, trailing bytes, etc.
                require(copy.contentEquals(OpenSSHPublicKeyUtil.encodePublicKey(parsed)))
                return ApprovedHostKey(copy, algorithm)
            } catch (_: Exception) { trustFailure(HostTrustException.Reason.INVALID_KEY) }
        }
    }
}
