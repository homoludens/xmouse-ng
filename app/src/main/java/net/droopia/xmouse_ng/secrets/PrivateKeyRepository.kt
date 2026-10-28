package net.droopia.xmouse_ng.secrets

import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/** Safe, deliberately value-free diagnostics, including for hostile provider/key content. */
class PrivateKeyImportException(val reason: Reason) : Exception(reason.name) {
    enum class Reason {
        INVALID_KEY, PASSPHRASE_REQUIRED_OR_INVALID, TOO_LARGE, READ_FAILURE, CANCELLED,
        SOURCE_CHANGED, MISSING_KEY, INVALID_REFERENCE, VERIFICATION_FAILED, STORAGE_FAILURE,
        UNSUPPORTED_FORMAT, RESOURCE_LIMIT,
    }
}

/** Cancellation and publication share a lock: late work cannot publish after cancellation. */
class PrivateKeyImportAttempt internal constructor(internal val expected: PrivateKeyConfiguration) {
    private var cancelled = false
    private var source: InputStream? = null

    @Synchronized fun cancel() {
        cancelled = true
        // CancellationSignal cancels provider open; close also interrupts an already-open Android
        // descriptor read. Late provider work must not hold its Activity/stream indefinitely.
        try { source?.close() } catch (_: IOException) { } catch (_: SecurityException) { }
        source = null
    }
    @Synchronized internal fun attachSource(input: InputStream) { checkActive(); source = input }
    @Synchronized internal fun detachSource(input: InputStream) { if (source === input) source = null }
    @Synchronized internal fun checkActive() {
        if (cancelled) keyImportFail(PrivateKeyImportException.Reason.CANCELLED)
    }
    @Synchronized internal fun <T> publish(action: () -> T): T {
        checkActive()
        return action()
    }
}

// Configuration contains references/context only, never key bytes or passphrases.
internal data class PrivateKeyConfiguration(val reference: String?, val legacyPath: String?)
internal interface PrivateKeyReferences {
    fun snapshot(): PrivateKeyConfiguration
    fun replace(expected: PrivateKeyConfiguration, replacement: SecretId)
}

/** Blocking validated import/recovery. All callers own/clear passphrase and returned key buffers. */
class PrivateKeyRepository internal constructor(
    private val references: PrivateKeyReferences,
    private val secrets: SecretStore,
) {
    fun beginImport(): PrivateKeyImportAttempt = synchronized(lock) {
        PrivateKeyImportAttempt(references.snapshot())
    }

    /** The stream is owned/closed here. No plaintext file or source URI is persisted. */
    @Throws(PrivateKeyImportException::class, SecretStoreException::class)
    fun importKey(attempt: PrivateKeyImportAttempt, source: InputStream, passphrase: ByteArray) {
        val bytes = readSource(source, attempt)
        try {
            attempt.checkActive()
            BoundedSshKey.validate(bytes, passphrase)
            // A new import is not permission to reset an unreadable device key/ciphertext.
            attempt.expected.reference?.let { readReference(it).fill(0) }
            attempt.checkActive()
            val id = SecretId("private_key_${UUID.randomUUID().toString().replace("-", "")}")
            secrets.write(id, bytes)
            val verified = secrets.read(id) ?: keyImportFail(PrivateKeyImportException.Reason.VERIFICATION_FAILED)
            try {
                if (!bytes.contentEquals(verified)) keyImportFail(PrivateKeyImportException.Reason.VERIFICATION_FAILED)
            } finally {
                verified.fill(0)
            }
            attempt.publish {
                synchronized(lock) {
                    if (references.snapshot() != attempt.expected) keyImportFail(PrivateKeyImportException.Reason.SOURCE_CHANGED)
                    references.replace(attempt.expected, id)
                }
            }
        } finally {
            bytes.fill(0)
        }
    }

    /** Readable legacy paths migrate without deleting the source; failures retain their full context. */
    @Throws(PrivateKeyImportException::class, SecretStoreException::class)
    fun importLegacy(attempt: PrivateKeyImportAttempt, passphrase: ByteArray) {
        if (attempt.expected.reference != null) {
            readReference(attempt.expected.reference).fill(0)
            return
        }
        val path = attempt.expected.legacyPath?.takeIf { it.isNotEmpty() }
            ?: keyImportFail(PrivateKeyImportException.Reason.MISSING_KEY)
        val input = try {
            FileInputStream(path)
        } catch (_: IOException) {
            keyImportFail(PrivateKeyImportException.Reason.READ_FAILURE)
        } catch (_: SecurityException) {
            keyImportFail(PrivateKeyImportException.Reason.READ_FAILURE)
        }
        importKey(attempt, input, passphrase)
    }

    /** Minimal old-transport handoff: never fall back to a raw path when a reference is unreadable. */
    @Throws(PrivateKeyImportException::class, SecretStoreException::class)
    fun readForAuthentication(passphrase: ByteArray): ByteArray {
        val attempt = beginImport()
        if (attempt.expected.reference == null) importLegacy(attempt, passphrase)
        val bytes = synchronized(lock) {
            readReference(references.snapshot().reference ?: keyImportFail(PrivateKeyImportException.Reason.MISSING_KEY))
        }
        try {
            BoundedSshKey.validate(bytes, passphrase)
            return bytes
        } catch (failure: Exception) {
            bytes.fill(0)
            throw failure
        }
    }

    private fun readReference(reference: String): ByteArray {
        val id = try { SecretId(reference) } catch (_: IllegalArgumentException) {
            keyImportFail(PrivateKeyImportException.Reason.INVALID_REFERENCE)
        }
        return secrets.read(id) ?: keyImportFail(PrivateKeyImportException.Reason.MISSING_KEY)
    }

    companion object {
        const val MAX_KEY_BYTES = 1024 * 1024
        private val lock = Any()

        /** Explicit memory-only key re-entry: same bounds/stream cancellation, no publication. */
        internal fun readForReentry(source: InputStream, attempt: PrivateKeyImportAttempt): ByteArray = readSource(source, attempt)

        private fun readSource(source: InputStream, attempt: PrivateKeyImportAttempt): ByteArray {
            var bytes: ByteArray? = null
            try {
                source.use {
                    attempt.attachSource(it)
                    bytes = readBounded(it, attempt)
                }
                return bytes!!
            } catch (failure: PrivateKeyImportException) {
                bytes?.fill(0)
                // use() may attach a provider's close exception as suppressed: do not retain it.
                keyImportFail(failure.reason)
            } catch (_: IOException) {
                bytes?.fill(0)
                attempt.checkActive()
                keyImportFail(PrivateKeyImportException.Reason.READ_FAILURE)
            } catch (_: SecurityException) {
                bytes?.fill(0)
                attempt.checkActive()
                keyImportFail(PrivateKeyImportException.Reason.READ_FAILURE)
            } finally {
                attempt.detachSource(source)
            }
        }

        private fun readBounded(input: InputStream, attempt: PrivateKeyImportAttempt): ByteArray {
            val buffer = ByteArray(MAX_KEY_BYTES + 1)
            try {
                var used = 0
                while (used < buffer.size) {
                    attempt.checkActive()
                    val count = input.read(buffer, used, minOf(8192, buffer.size - used))
                    if (count < 0) return buffer.copyOf(used)
                    // InputStream implementations may return zero; still make bounded progress.
                    if (count == 0) {
                        val next = input.read()
                        if (next < 0) return buffer.copyOf(used)
                        buffer[used++] = next.toByte()
                    } else used += count
                }
                keyImportFail(PrivateKeyImportException.Reason.TOO_LARGE)
            } catch (_: IOException) {
                attempt.checkActive()
                keyImportFail(PrivateKeyImportException.Reason.READ_FAILURE)
            } catch (_: SecurityException) {
                attempt.checkActive()
                keyImportFail(PrivateKeyImportException.Reason.READ_FAILURE)
            } finally {
                buffer.fill(0)
            }
        }

    }
}

internal fun keyImportFail(reason: PrivateKeyImportException.Reason): Nothing = throw PrivateKeyImportException(reason)
