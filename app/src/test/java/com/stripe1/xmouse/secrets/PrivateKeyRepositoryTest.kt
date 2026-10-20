package com.stripe1.xmouse.secrets

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** All identities are generated synthetic fixtures in memory; never opens a user's key or a socket. */
class PrivateKeyRepositoryTest {
    @Test fun rsaImportsWithAndWithoutPassphraseAndRemainsUsableWithoutSource() = roundTrips(KeyPair.RSA, 1024)
    @Test fun ecdsaImportsWithAndWithoutPassphraseAndRemainsUsableWithoutSource() = roundTrips(KeyPair.ECDSA, 256)

    private fun roundTrips(type: Int, bits: Int) {
        listOf(byteArrayOf(), "NOT_REAL_fixture_phrase".toByteArray()).forEach { phrase ->
            val f = Fixture()
            val key = syntheticKey(type, bits, phrase)
            f.import(key, phrase)
            assertNull(f.refs.state.legacyPath)
            assertNotEquals(f.oldId.value, f.refs.state.reference)
            assertEquals(2, f.store.values.size)
            assertArrayEquals(key, f.store.values[SecretId(f.refs.state.reference!!)])
            key.fill(0) // Source no longer exists; the store owns its copy.
            val imported = f.repository.readForAuthentication(phrase)
            try {
                val jsch = JSch()
                jsch.addIdentity("synthetic-fixture", imported, null, phrase)
                val identity = jsch.identityRepository.identities.single() as com.jcraft.jsch.Identity
                assertFalse(identity.isEncrypted)
                assertNotNull(identity.getSignature(byteArrayOf(1, 2, 3)))
                jsch.removeAllIdentity()
            } finally { imported.fill(0) }
            assertArrayEquals(f.oldBytes, f.store.values[f.oldId])
        }
    }

    @Test fun emptyRandomAndPublicOnlyDocumentsCannotReplaceCurrentKey() {
        val public = ByteArrayOutputStream()
        val key = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 1024)
        try { key.writePublicKey(public, "NOT_REAL_PUBLIC_ONLY") } finally { key.dispose() }
        listOf(byteArrayOf(), "NOT_A_PRIVATE_KEY".toByteArray(), public.toByteArray()).forEach { bytes ->
            val f = Fixture()
            assertEquals(PrivateKeyImportException.Reason.INVALID_KEY, expect<PrivateKeyImportException> { f.import(bytes) }.reason)
            f.assertUnchanged()
        }
    }

    @Test fun missingAndWrongPassphrasesKeepCurrentKeyAndNeverStoreInvalidCandidate() {
        val encrypted = syntheticKey(KeyPair.RSA, 1024, "NOT_REAL_right".toByteArray())
        listOf(byteArrayOf(), "NOT_REAL_wrong".toByteArray()).forEach { phrase ->
            val f = Fixture()
            assertEquals(PrivateKeyImportException.Reason.PASSPHRASE_REQUIRED_OR_INVALID,
                expect<PrivateKeyImportException> { f.import(encrypted, phrase) }.reason)
            f.assertUnchanged()
        }
    }

    @Test fun oversizedStreamReadsAtMostLimitPlusOneAndCloses() {
        val f = Fixture()
        var read = 0
        var closed = false
        val stream = object : InputStream() {
            override fun read(): Int { read++; return 1 }
            override fun read(b: ByteArray, off: Int, len: Int): Int { read += len; return len }
            override fun close() { closed = true }
        }
        assertEquals(PrivateKeyImportException.Reason.TOO_LARGE,
            expect<PrivateKeyImportException> { f.repository.importKey(f.repository.beginImport(), stream, byteArrayOf()) }.reason)
        assertEquals(PrivateKeyRepository.MAX_KEY_BYTES + 1, read)
        assertTrue(closed)
        f.assertUnchanged()
    }

    @Test fun maximumSizeSupportedKeyIsAccepted() {
        val f = Fixture()
        val key = syntheticKey(KeyPair.RSA, 1024)
        val padded = ByteArray(PrivateKeyRepository.MAX_KEY_BYTES) { '\n'.code.toByte() }
        key.copyInto(padded)
        f.import(padded)
        assertArrayEquals(padded, f.repository.readForAuthentication(byteArrayOf()))
    }

    @Test fun providerReadAndCloseFailuresHaveSafeDiagnosticsAndPreserveCurrentKey() {
        repeat(3) { mode ->
            val f = Fixture()
            val stream = object : ByteArrayInputStream(syntheticKey(KeyPair.RSA, 1024)) {
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    if (mode != 1) throw IOException("SECRET_PROVIDER_MESSAGE") else super.read(b, off, len)
                override fun close() { if (mode != 0) throw IOException("SECRET_PROVIDER_MESSAGE") }
            }
            val failure = expect<PrivateKeyImportException> { f.repository.importKey(f.repository.beginImport(), stream, byteArrayOf()) }
            assertEquals(PrivateKeyImportException.Reason.READ_FAILURE, failure.reason)
            assertFalse(failure.toString().contains("SECRET_PROVIDER_MESSAGE"))
            assertNull(failure.cause)
            assertTrue(failure.suppressed.isEmpty())
            f.assertUnchanged()
        }
    }

    @Test fun cancelledSelectionAndLatePreparedResultsNeverPublish() {
        repeat(2) { mode ->
            val f = Fixture()
            val attempt = f.repository.beginImport()
            if (mode == 0) attempt.cancel() else f.store.afterWrite = { attempt.cancel() }
            assertEquals(PrivateKeyImportException.Reason.CANCELLED, expect<PrivateKeyImportException> {
                f.repository.importKey(attempt, ByteArrayInputStream(syntheticKey(KeyPair.RSA, 1024)), byteArrayOf())
            }.reason)
            assertEquals(f.original, f.refs.state)
            assertArrayEquals(f.oldBytes, f.store.values[f.oldId])
        }
    }

    @Test fun sourceChangesWhileReadingCannotOverwriteNewerConfiguration() {
        val f = Fixture()
        f.store.afterWrite = { f.refs.state = PrivateKeyConfiguration(f.oldId.value, "new-context") }
        assertEquals(PrivateKeyImportException.Reason.SOURCE_CHANGED,
            expect<PrivateKeyImportException> { f.import(syntheticKey(KeyPair.RSA, 1024)) }.reason)
        assertEquals("new-context", f.refs.state.legacyPath)
        assertEquals(f.oldId.value, f.refs.state.reference)
    }

    @Test fun cancellingAnAlreadyOpenBlockedSourceClosesItWithoutPublishing() {
        val f = Fixture()
        val reading = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val outcome = AtomicReference<Throwable>()
        val attempt = f.repository.beginImport()
        val input = object : InputStream() {
            override fun read(): Int {
                reading.countDown()
                check(closed.await(5, TimeUnit.SECONDS))
                return -1
            }
            override fun close() { closed.countDown() }
        }
        val worker = Thread {
            try { f.repository.importKey(attempt, input, byteArrayOf()) }
            catch (failure: Throwable) { outcome.set(failure) }
        }
        worker.start()
        assertTrue(reading.await(5, TimeUnit.SECONDS))
        attempt.cancel()
        worker.join(5000)
        assertFalse(worker.isAlive)
        assertEquals(0L, closed.count)
        assertEquals(PrivateKeyImportException.Reason.CANCELLED, (outcome.get() as PrivateKeyImportException).reason)
        f.assertUnchanged()
    }

    @Test fun failedAndUncertainSecretWritesNeverPublishReference() {
        repeat(2) { mode ->
            val f = Fixture()
            f.store.writeFailure = true
            f.store.writeBeforeFailure = mode == 1
            assertEquals(SecretStoreException.Reason.STORAGE_FAILURE,
                expect<SecretStoreException> { f.import(syntheticKey(KeyPair.RSA, 1024)) }.reason)
            assertEquals(f.original, f.refs.state)
            assertArrayEquals(f.oldBytes, f.store.values[f.oldId])
        }
    }

    @Test fun missingWrongAndFailedReadbackNeverPublishReference() {
        repeat(3) { mode ->
            val f = Fixture()
            f.store.afterWrite = { f.store.readbackFault = mode }
            expect<Exception> { f.import(syntheticKey(KeyPair.RSA, 1024)) }
            assertEquals(f.original, f.refs.state)
            assertArrayEquals(f.oldBytes, f.store.values[f.oldId])
        }
    }

    @Test fun failedReferencePublicationPreservesPreviousRepresentation() {
        val f = Fixture()
        f.refs.failCommit = true
        assertEquals(PrivateKeyImportException.Reason.STORAGE_FAILURE,
            expect<PrivateKeyImportException> { f.import(syntheticKey(KeyPair.RSA, 1024)) }.reason)
        assertEquals(f.original, f.refs.state)
        assertArrayEquals(f.oldBytes, f.store.values[f.oldId])
    }

    @Test fun unreadableCurrentCiphertextCannotBeBypassedWithFreshOpaqueId() {
        SecretStoreException.Reason.entries.forEach { reason ->
            val f = Fixture()
            f.store.readFailure = reason
            assertEquals(reason, expect<SecretStoreException> { f.import(syntheticKey(KeyPair.RSA, 1024)) }.reason)
            assertEquals(f.original, f.refs.state)
            assertEquals(1, f.store.values.size)
        }
    }

    @Test fun missingAndInvalidExistingReferencesDoNotFallBackToLegacyPath() {
        listOf("missing_record", "/INVALID/reference").forEach { reference ->
            val f = Fixture()
            f.refs.state = PrivateKeyConfiguration(reference, "NOT_A_REAL_KEY_PATH")
            expect<PrivateKeyImportException> { f.repository.readForAuthentication(byteArrayOf()) }
            expect<PrivateKeyImportException> { f.import(syntheticKey(KeyPair.RSA, 1024)) }
            assertEquals(reference, f.refs.state.reference)
            assertEquals(1, f.store.values.size)
        }
    }

    @Test fun unreadableLegacyPathKeepsContextAndRequestsReimport() {
        val f = Fixture()
        f.refs.state = PrivateKeyConfiguration(null, "/NOT_REAL_SYNTHETIC_DOES_NOT_EXIST/key")
        val before = f.refs.state
        assertEquals(PrivateKeyImportException.Reason.READ_FAILURE,
            expect<PrivateKeyImportException> { f.repository.readForAuthentication(byteArrayOf()) }.reason)
        assertEquals(before, f.refs.state)
    }

    private class Fixture {
        val oldId = SecretId("old_key")
        val oldBytes = "NOT_REAL_previous_protected_representation".toByteArray()
        val original = PrivateKeyConfiguration(oldId.value, "NOT_REAL_old_context")
        val refs = MemoryReferences(original)
        val store = MemoryStore().apply { values[oldId] = oldBytes.copyOf() }
        val repository = PrivateKeyRepository(refs, store)
        fun import(bytes: ByteArray, phrase: ByteArray = byteArrayOf()) =
            repository.importKey(repository.beginImport(), ByteArrayInputStream(bytes), phrase)
        fun assertUnchanged() {
            assertEquals(original, refs.state)
            assertEquals(1, store.values.size)
            assertArrayEquals(oldBytes, store.values[oldId])
        }
    }

    private class MemoryReferences(var state: PrivateKeyConfiguration) : PrivateKeyReferences {
        var failCommit = false
        override fun snapshot() = state
        override fun replace(expected: PrivateKeyConfiguration, replacement: SecretId) {
            if (failCommit) keyImportFail(PrivateKeyImportException.Reason.STORAGE_FAILURE)
            state = PrivateKeyConfiguration(replacement.value, null)
        }
    }

    private class MemoryStore : SecretStore {
        val values = mutableMapOf<SecretId, ByteArray>()
        var afterWrite: () -> Unit = {}
        var writeFailure = false
        var writeBeforeFailure = false
        var readFailure: SecretStoreException.Reason? = null
        var readbackFault = -1
        override fun read(id: SecretId): ByteArray? {
            readFailure?.let { throw SecretStoreException(it) }
            return when (readbackFault) {
                0 -> null
                1 -> "NOT_REAL_WRONG".toByteArray()
                2 -> throw SecretStoreException(SecretStoreException.Reason.STORAGE_FAILURE)
                else -> values[id]?.copyOf()
            }
        }
        override fun write(id: SecretId, plaintext: ByteArray) {
            if (!writeFailure || writeBeforeFailure) values[id] = plaintext.copyOf()
            if (writeFailure) throw SecretStoreException(SecretStoreException.Reason.STORAGE_FAILURE)
            afterWrite()
        }
    }

    companion object {
        fun syntheticKey(type: Int, bits: Int, phrase: ByteArray = byteArrayOf()): ByteArray {
            val key = KeyPair.genKeyPair(JSch(), type, bits)
            return try {
                ByteArrayOutputStream().also { key.writePrivateKey(it, phrase.takeIf { it.isNotEmpty() }) }.toByteArray()
            } finally { key.dispose() }
        }
    }

    private inline fun <reified T : Throwable> expect(action: () -> Unit): T {
        try { action() } catch (failure: Throwable) { if (failure is T) return failure else throw failure }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }
}
