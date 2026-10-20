package com.stripe1.xmouse.secrets

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Actual host filesystem + real JCA, injected POSIX adapter/key; NOT Android platform verification. */
class SecretFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val id = SecretId("host_42_password")
    private val oldSecret = "NOT_REAL_old_password_0123456789".toByteArray()
    private val newSecret = "NOT_REAL_new_password_9876543210".toByteArray()
    private val keys = TestSecretKeySource()
    private val io = TestFileIo()
    private val directory get() = File(temporary.root, "no_backup/secrets")
    private val record get() = File(directory, id.value + SecretFiles.SUFFIX)
    private fun store() = EncryptedSecretStore(SecretFiles(directory, io), SecretRecordCipher(keys))

    @Test
    fun persistsCiphertextAcrossInstancesWithNoPlaintextInAnyFile() {
        store().write(id, oldSecret)
        assertArrayEquals(oldSecret, store().read(id))
        assertFalse(contains(record.readBytes(), oldSecret))
        assertEquals(listOf(".lock", "host_42_password.secret"), directory.list()!!.sorted())
        assertEquals(listOf(true, false), keys.creationRequests)
    }

    @Test
    fun missingRecordReturnsNullWithoutAccessingOrCreatingKey() {
        keys.available = false
        assertNull(store().read(id))
        assertTrue(keys.creationRequests.isEmpty())
    }

    @Test
    fun replacesReadableRecordAndLeavesOtherIdentityUnchanged() {
        val other = SecretId("global_ssh_key")
        store().write(id, oldSecret)
        store().write(other, oldSecret)
        val otherBytes = File(directory, other.value + SecretFiles.SUFFIX).readBytes()
        store().write(id, newSecret)
        assertArrayEquals(newSecret, store().read(id))
        assertArrayEquals(otherBytes, File(directory, other.value + SecretFiles.SUFFIX).readBytes())
        assertFalse(keys.creationRequests.drop(1).any { it })
    }

    @Test
    fun tamperedReadAndReplacementPreserveOriginalFile() {
        store().write(id, oldSecret)
        val corrupted = record.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        record.writeBytes(corrupted)
        assertReason(SecretStoreException.Reason.INVALID_RECORD) { store().read(id) }
        assertReason(SecretStoreException.Reason.INVALID_RECORD) { store().write(id, newSecret) }
        assertArrayEquals(corrupted, record.readBytes())
    }

    @Test
    fun unknownVersionCannotBeOverwritten() {
        store().write(id, oldSecret)
        val future = record.readBytes().also { it[4] = 2 }
        record.writeBytes(future)
        assertReason(SecretStoreException.Reason.UNSUPPORTED_VERSION) { store().write(id, newSecret) }
        assertArrayEquals(future, record.readBytes())
    }

    @Test
    fun unavailableKeyPreservesExistingFilesAndCannotCreateAnotherCredential() {
        store().write(id, oldSecret)
        val original = record.readBytes()
        keys.available = false
        assertReason(SecretStoreException.Reason.KEY_UNAVAILABLE) { store().read(id) }
        assertReason(SecretStoreException.Reason.KEY_UNAVAILABLE) { store().write(id, newSecret) }
        assertReason(SecretStoreException.Reason.KEY_UNAVAILABLE) { store().write(SecretId("new_id"), newSecret) }
        assertFalse(keys.creationRequests.drop(1).any { it })
        assertArrayEquals(original, record.readBytes())
        assertFalse(File(directory, "new_id.secret").exists())
    }

    @Test
    fun interruptedOrUnrecognizedArtifactsForbidKeyRegeneration() {
        directory.mkdirs()
        File(directory, "record-crash.pending").writeBytes(byteArrayOf(1, 2, 3))
        keys.available = false
        assertReason(SecretStoreException.Reason.KEY_UNAVAILABLE) { store().write(id, newSecret) }
        assertEquals(listOf(false), keys.creationRequests)
        assertTrue(File(directory, "record-crash.pending").exists())
    }

    @Test
    fun fsyncFailurePreservesPreviousCiphertextAndReportsFailure() {
        store().write(id, oldSecret)
        val original = record.readBytes()
        io.failure = "sync"
        assertReason(SecretStoreException.Reason.STORAGE_FAILURE) { store().write(id, newSecret) }
        assertArrayEquals(original, record.readBytes())
        assertArrayEquals(oldSecret, store().read(id))
    }

    @Test
    fun renameFailurePreservesPreviousCiphertextAndReportsFailure() {
        store().write(id, oldSecret)
        val original = record.readBytes()
        io.failure = "replace"
        assertReason(SecretStoreException.Reason.STORAGE_FAILURE) { store().write(id, newSecret) }
        assertArrayEquals(original, record.readBytes())
        assertArrayEquals(oldSecret, store().read(id))
    }

    @Test
    fun directorySyncFailureReportsUncertainCommitButLeavesCompleteDecryptableRecord() {
        store().write(id, oldSecret)
        io.failure = "directory"
        assertReason(SecretStoreException.Reason.STORAGE_FAILURE) { store().write(id, newSecret) }
        assertArrayEquals(newSecret, store().read(id))
    }

    @Test
    fun stagingFileContainsOnlyCiphertextEvenWhenWriteFails() {
        store().write(id, oldSecret)
        io.onSync = {
            val pending = directory.listFiles()!!.single { it.name.endsWith(".pending") }.readBytes()
            assertFalse(contains(pending, oldSecret))
            assertFalse(contains(pending, newSecret))
            assertArrayEquals(newSecret, SecretRecordCipher(keys).decrypt(id, pending))
            throw IOException()
        }
        assertReason(SecretStoreException.Reason.STORAGE_FAILURE) { store().write(id, newSecret) }
        assertArrayEquals(oldSecret, store().read(id))
    }

    @Test
    fun interruptedEncryptedStagingDoesNotHidePreviousRecord() {
        store().write(id, oldSecret)
        File(directory, "record-crash.pending").writeBytes(SecretRecordCipher(keys).encrypt(id, newSecret, false))
        assertArrayEquals(oldSecret, store().read(id))
        assertTrue(File(directory, "record-crash.pending").exists())
    }

    @Test
    fun oversizedInputIsRejectedBeforeAnyStorageOrKeyAccess() {
        assertThrows(IllegalArgumentException::class.java) { store().write(id, ByteArray(MAX_SECRET_BYTES + 1)) }
        assertFalse(directory.exists())
        assertTrue(keys.creationRequests.isEmpty())
    }

    @Test
    fun oversizedStoredRecordIsBoundedAndPreserved() {
        directory.mkdirs()
        val oversized = ByteArray(SecretRecordCipher.MAX_RECORD_BYTES + 1)
        record.writeBytes(oversized)
        assertReason(SecretStoreException.Reason.INVALID_RECORD) { store().read(id) }
        assertReason(SecretStoreException.Reason.INVALID_RECORD) { store().write(id, newSecret) }
        assertEquals(oversized.size.toLong(), record.length())
    }

    @Test
    fun failedPartiallyWrittenEncryptedStagingPreservesOldRecord() {
        store().write(id, oldSecret)
        val original = record.readBytes()
        io.onSync = {
            val pending = directory.listFiles()!!.single { it.name.endsWith(".pending") }
            pending.writeBytes(pending.readBytes().copyOf(9))
            throw IOException()
        }
        assertReason(SecretStoreException.Reason.STORAGE_FAILURE) { store().write(id, newSecret) }
        assertArrayEquals(original, record.readBytes())
        assertArrayEquals(oldSecret, store().read(id))
    }

    @Test
    fun concurrentInstancesSerializeCompleteReadModifyWrites() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val results = (1..40).map { index ->
                executor.submit {
                    store().write(id, "NOT_REAL_concurrent_$index".toByteArray())
                    assertTrue(String(store().read(id)!!).startsWith("NOT_REAL_concurrent_"))
                }
            }
            results.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, keys.creationRequests.count { it })
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun contains(bytes: ByteArray, needle: ByteArray): Boolean =
        bytes.toList().windowed(needle.size).any { it == needle.toList() }
}

private class TestFileIo : SecretFileIo {
    var failure: String? = null
    var onSync: (() -> Unit)? = null
    override fun privateDirectory(directory: File) = Unit
    override fun privateFile(file: File) = Unit
    override fun sync(output: FileOutputStream) {
        onSync?.invoke()
        if (failure == "sync") throw IOException()
        output.fd.sync()
    }
    override fun replace(source: File, destination: File) {
        if (failure == "replace") throw IOException()
        if (!source.renameTo(destination)) throw IOException()
    }
    override fun syncDirectory(directory: File) {
        if (failure == "directory") throw IOException()
        // Injectable durability boundary only. Real directory fsync is verified on Android.
    }
}
