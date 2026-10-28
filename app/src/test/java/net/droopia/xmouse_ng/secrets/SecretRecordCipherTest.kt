package net.droopia.xmouse_ng.secrets

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** Real JVM JCA AES-GCM, but a synthetic in-memory key: NOT AndroidKeyStore evidence. */
class SecretRecordCipherTest {
    private val id = SecretId("host_42_password")
    private val keys = TestSecretKeySource()
    private val cipher = SecretRecordCipher(keys)
    private val plaintext = "NOT_REAL_password_秘密\n\u0000".toByteArray()

    @Test
    fun roundTripsEmptyUnicodeBinaryAndMaximumSizedSecret() {
        for (bytes in listOf(byteArrayOf(), plaintext, ByteArray(256) { it.toByte() }, ByteArray(MAX_SECRET_BYTES) { 42 })) {
            val record = cipher.encrypt(id, bytes, true)
            assertArrayEquals(bytes, cipher.decrypt(id, record))
            assertEquals(bytes.size + SecretRecordCipher.MIN_RECORD_BYTES, record.size)
        }
    }

    @Test
    fun authenticatesEveryHeaderNonceCiphertextAndTagByte() {
        val record = cipher.encrypt(id, plaintext, true)
        for (index in record.indices) {
            val changed = record.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertThrows("Byte $index", SecretStoreException::class.java) { cipher.decrypt(id, changed) }
        }
    }

    @Test
    fun swappingRecordsBetweenIdentitiesFailsAuthentication() {
        val record = cipher.encrypt(id, plaintext, true)
        assertReason(SecretStoreException.Reason.INVALID_RECORD) { cipher.decrypt(SecretId("host_43_password"), record) }
    }

    @Test
    fun rejectsEveryTruncationAndAppendedData() {
        val record = cipher.encrypt(id, plaintext, true)
        for (length in 0 until record.size) {
            assertThrows(SecretStoreException::class.java) { cipher.decrypt(id, record.copyOf(length)) }
        }
        assertReason(SecretStoreException.Reason.INVALID_RECORD) { cipher.decrypt(id, record + 0) }
        assertReason(SecretStoreException.Reason.INVALID_RECORD) {
            cipher.decrypt(id, ByteArray(SecretRecordCipher.MAX_RECORD_BYTES + 1))
        }
    }

    @Test
    fun versionIsExplicitAndUnknownVersionIsNotInterpretedAsV1() {
        val record = cipher.encrypt(id, plaintext, true)
        assertArrayEquals(byteArrayOf(0x58, 0x4d, 0x53, 0x53, 1), record.copyOf(5))
        record[4] = 2
        assertReason(SecretStoreException.Reason.UNSUPPORTED_VERSION) { cipher.decrypt(id, record) }
    }

    @Test
    fun newProviderNonceForEveryEncryptionIncludingSameIdentityAndPlaintext() {
        val nonces = (1..256).map {
            cipher.encrypt(id, plaintext, true).copyOfRange(5, 17).toList()
        }
        assertEquals(256, nonces.toSet().size)
    }

    @Test
    fun wrongKeyRejectsCiphertextAndDecryptNeverRequestsKeyCreation() {
        val record = cipher.encrypt(id, plaintext, true)
        keys.current = SecretKeySpec(ByteArray(32) { 7 }, "AES")
        assertReason(SecretStoreException.Reason.INVALID_RECORD) { cipher.decrypt(id, record) }
        assertFalse(keys.creationRequests.last())
    }

    @Test
    fun unavailableKeyHasTypedNonSensitiveFailure() {
        val record = cipher.encrypt(id, plaintext, true)
        keys.available = false
        val failure = assertThrows(SecretStoreException::class.java) { cipher.decrypt(id, record) }
        assertEquals(SecretStoreException.Reason.KEY_UNAVAILABLE, failure.reason)
        assertEquals("KEY_UNAVAILABLE", failure.message)
        assertEquals(null, failure.cause)
    }

    @Test
    fun invalidIdsCannotBecomePathsOrAmbiguousFileNames() {
        for (value in listOf("", ".", "..", "../secret", "/secret", "a/b", "a\\b", "a.secret", "A", "é", "a\u0000", "a\n", "a".repeat(65))) {
            assertThrows(IllegalArgumentException::class.java) { SecretId(value) }
        }
        assertEquals("a".repeat(64), SecretId("a".repeat(64)).value)
        assertEquals("host_42-password", SecretId("host_42-password").value)
    }
}

internal class TestSecretKeySource : SecretKeySource {
    var current: SecretKey = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    var available = true
    val creationRequests = mutableListOf<Boolean>()
    override fun key(allowCreation: Boolean): SecretKey {
        creationRequests += allowCreation
        if (!available) fail(SecretStoreException.Reason.KEY_UNAVAILABLE)
        return current
    }
}

internal fun assertReason(reason: SecretStoreException.Reason, action: () -> Unit) {
    assertEquals(reason, assertThrows(SecretStoreException::class.java, action).reason)
}
