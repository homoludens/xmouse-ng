package net.droopia.xmouse_ng.secrets

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/** Platform operations are injectable for deterministic write/fsync/rename fault tests on the JVM. */
internal interface SecretFileIo {
    fun privateDirectory(directory: File)
    fun privateFile(file: File)
    fun sync(output: FileOutputStream)
    fun replace(source: File, destination: File)
    fun syncDirectory(directory: File)
}

/** Same-directory encrypted staging, fsync, atomic rename, then directory fsync. No plaintext files. */
internal class SecretFiles(private val directory: File, private val io: SecretFileIo) : EncryptedRecords {
    override fun <T> exclusively(action: () -> T): T = synchronized(processLock) {
        storageOperation {
            val created = !directory.isDirectory
            if (created && !directory.mkdirs()) throw IOException()
            io.privateDirectory(directory)
            if (created) io.syncDirectory(directory.parentFile ?: throw IOException())
            val lockFile = child(".lock")
            RandomAccessFile(lockFile, "rw").use { lockHandle ->
                io.privateFile(lockFile)
                lockHandle.channel.lock().use { action() }
            }
        }
    }

    override fun read(id: SecretId): ByteArray? = storageOperation {
        val file = child(id.value + SUFFIX)
        if (!file.exists()) return@storageOperation null
        // Bound reads even if the file changes size; truncated/extra bytes fail record validation.
        FileInputStream(file).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var remaining = SecretRecordCipher.MAX_RECORD_BYTES + 1
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (count == -1) break
                output.write(buffer, 0, count)
                remaining -= count
            }
            val result = output.toByteArray()
            if (result.size > SecretRecordCipher.MAX_RECORD_BYTES) fail(SecretStoreException.Reason.INVALID_RECORD)
            result
        }
    }

    override fun writeAtomically(id: SecretId, ciphertext: ByteArray) = storageOperation {
        val destination = child(id.value + SUFFIX)
        val pending = File.createTempFile("record-", ".pending", directory)
        try {
            io.privateFile(pending)
            FileOutputStream(pending).use { output ->
                output.write(ciphertext)
                io.sync(output)
            }
            io.replace(pending, destination)
            // A failure here is reported, not success: rename may already be committed.
            io.syncDirectory(directory)
        } finally {
            // Only an encrypted temporary file created by this operation; never the old destination.
            if (pending.exists()) pending.delete()
        }
    }

    override fun hasArtifacts(): Boolean = storageOperation {
        val files = directory.list() ?: throw IOException()
        // Includes interrupted writes and unrecognized files: never regenerate a key over recovery data.
        files.any { it != ".lock" }
    }

    private fun child(name: String): File {
        val file = File(directory, name)
        if (file.canonicalFile.parentFile != directory.canonicalFile) throw IOException()
        return file
    }

    private fun <T> storageOperation(action: () -> T): T = try {
        action()
    } catch (_: IOException) {
        fail(SecretStoreException.Reason.STORAGE_FAILURE)
    } catch (_: SecurityException) {
        fail(SecretStoreException.Reason.STORAGE_FAILURE)
    }

    companion object {
        internal const val SUFFIX = ".secret"
        // Avoid OverlappingFileLockException across instances; the file lock also serializes processes.
        private val processLock = Any()
    }
}
