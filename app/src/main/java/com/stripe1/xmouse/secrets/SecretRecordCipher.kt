package com.stripe1.xmouse.secrets

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.ProviderException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal fun interface SecretKeySource {
    fun key(allowCreation: Boolean): SecretKey
}

/** V1: magic[4], version[1], nonce[12], ciphertext + GCM tag[16]. No plaintext serialization. */
internal class SecretRecordCipher(private val keys: SecretKeySource) {
    fun encrypt(id: SecretId, plaintext: ByteArray, allowKeyCreation: Boolean): ByteArray = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // AndroidKeyStore supplies its own random IV; caller-selected IVs remain forbidden.
        cipher.init(Cipher.ENCRYPT_MODE, keys.key(allowKeyCreation))
        val nonce = cipher.iv
        if (nonce == null || nonce.size != NONCE_BYTES) fail(SecretStoreException.Reason.ENCRYPTION_FAILED)
        cipher.updateAAD(aad(id))
        HEADER + nonce + cipher.doFinal(plaintext)
    } catch (_: GeneralSecurityException) {
        fail(SecretStoreException.Reason.KEY_UNAVAILABLE)
    } catch (_: ProviderException) {
        fail(SecretStoreException.Reason.KEY_UNAVAILABLE)
    }

    fun decrypt(id: SecretId, record: ByteArray): ByteArray {
        if (record.size < MIN_RECORD_BYTES || record.size > MAX_RECORD_BYTES ||
            !record.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)
        ) fail(SecretStoreException.Reason.INVALID_RECORD)
        if (record[MAGIC.size] != VERSION) fail(SecretStoreException.Reason.UNSUPPORTED_VERSION)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE, keys.key(false),
                GCMParameterSpec(TAG_BYTES * 8, record.copyOfRange(HEADER.size, HEADER.size + NONCE_BYTES)),
            )
            cipher.updateAAD(aad(id))
            // doFinal only: never expose unauthenticated plaintext from update().
            return cipher.doFinal(record, HEADER.size + NONCE_BYTES, record.size - HEADER.size - NONCE_BYTES)
        } catch (_: BadPaddingException) {
            // Includes AEADBadTagException on Android and JVM providers.
            fail(SecretStoreException.Reason.INVALID_RECORD)
        } catch (_: GeneralSecurityException) {
            fail(SecretStoreException.Reason.KEY_UNAVAILABLE)
        } catch (_: ProviderException) {
            fail(SecretStoreException.Reason.KEY_UNAVAILABLE)
        }
    }

    private fun aad(id: SecretId): ByteArray {
        val identity = id.value.toByteArray(StandardCharsets.US_ASCII)
        return DOMAIN + HEADER + ByteBuffer.allocate(2).putShort(identity.size.toShort()).array() + identity
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private val MAGIC = byteArrayOf(0x58, 0x4d, 0x53, 0x53) // XMSS
        private const val VERSION: Byte = 1
        private val HEADER = MAGIC + VERSION
        private val DOMAIN = "com.stripe1.xmouse/secret-store\u0000".toByteArray(StandardCharsets.US_ASCII)
        internal const val NONCE_BYTES = 12
        internal const val TAG_BYTES = 16
        internal const val HEADER_BYTES = 5
        internal const val MIN_RECORD_BYTES = HEADER_BYTES + NONCE_BYTES + TAG_BYTES
        internal const val MAX_RECORD_BYTES = MIN_RECORD_BYTES + MAX_SECRET_BYTES
    }
}

internal fun fail(reason: SecretStoreException.Reason): Nothing = throw SecretStoreException(reason)
