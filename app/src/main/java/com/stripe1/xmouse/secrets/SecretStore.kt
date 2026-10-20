package com.stripe1.xmouse.secrets

/** Opaque, non-secret reference, never a user label, endpoint, or filesystem path. */
data class SecretId(val value: String) {
    init {
        require(value.matches(Regex("[a-z0-9][a-z0-9_-]{0,63}"))) { "Invalid secret reference" }
    }
}

/** Blocking I/O: call off the main thread. Returned plaintext belongs to the caller; never log it. */
interface SecretStore {
    /** Null means absent, not unreadable. No key or ciphertext is replaced on a read failure. */
    @Throws(SecretStoreException::class)
    fun read(id: SecretId): ByteArray?

    /** An existing record must decrypt successfully before replacement is allowed. */
    @Throws(SecretStoreException::class)
    fun write(id: SecretId, plaintext: ByteArray)
}

/** Deliberately omits underlying provider/IO messages and causes from diagnostics. */
class SecretStoreException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { KEY_UNAVAILABLE, INVALID_RECORD, UNSUPPORTED_VERSION, ENCRYPTION_FAILED, STORAGE_FAILURE }
}

internal const val MAX_SECRET_BYTES = 1024 * 1024

internal interface EncryptedRecords {
    fun <T> exclusively(action: () -> T): T
    fun read(id: SecretId): ByteArray?
    fun writeAtomically(id: SecretId, ciphertext: ByteArray)
    fun hasArtifacts(): Boolean
}

internal class EncryptedSecretStore(
    private val records: EncryptedRecords,
    private val cipher: SecretRecordCipher,
) : SecretStore {
    override fun read(id: SecretId): ByteArray? = records.exclusively {
        records.read(id)?.let { cipher.decrypt(id, it) }
    }

    override fun write(id: SecretId, plaintext: ByteArray) {
        require(plaintext.size <= MAX_SECRET_BYTES) { "Secret exceeds storage limit" }
        records.exclusively {
            // Neither damaged credentials nor a missing/invalidated key are an implicit reset request.
            records.read(id)?.let { cipher.decrypt(id, it).fill(0) }
            val encrypted = cipher.encrypt(id, plaintext, allowKeyCreation = !records.hasArtifacts())
            records.writeAtomically(id, encrypted)
        }
    }
}
