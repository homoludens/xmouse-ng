package net.droopia.xmouse_ng.backup

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.os.ParcelFileDescriptor
import android.util.AtomicFile
import android.util.Log
import net.droopia.xmouse_ng.secrets.AndroidCredentials
import net.droopia.xmouse_ng.secrets.CredentialMetadataBackup
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Full-backup-only Android agent. Raw database and preference files are excluded by both rule sets;
 * the generated snapshot is an explicit allowlist assembled behind the credential worker gate.
 */
class MetadataBackupAgent : BackupAgent() {
    override fun onBackup(
        oldState: ParcelFileDescriptor?,
        data: BackupDataOutput?,
        newState: ParcelFileDescriptor?,
    ) {
        // android:fullBackupOnly routes supported devices through onFullBackup instead.
    }

    override fun onRestore(data: BackupDataInput?, appVersionCode: Int, newState: ParcelFileDescriptor?) {
        // Key/value restore is intentionally unsupported; the app uses versioned file-based restore.
    }

    override fun onFullBackup(output: FullBackupDataOutput) {
        val payload = createSnapshot()
        val target = File(filesDir, SNAPSHOT_PATH)
        val parent = target.parentFile ?: throw IOException("Safe configuration backup could not be prepared")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Safe configuration backup could not be prepared")
        val atomic = AtomicFile(target)
        var stream: java.io.FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(payload)
            atomic.finishWrite(stream)
            stream = null
            // Super applies the manifest's strict file allowlist and excludes database/shared prefs.
            super.onFullBackup(output)
        } catch (failure: Exception) {
            stream?.let(atomic::failWrite)
            if (failure is IOException) throw failure
            throw IOException("Safe configuration backup could not be prepared")
        } finally {
            payload.fill(0)
            atomic.delete()
        }
    }

    override fun onRestoreFinished() {
        val restored = File(filesDir, SNAPSHOT_PATH)
        if (!restored.isFile) return
        val atomic = AtomicFile(restored)
        try {
            if (restored.length() > MAX_SNAPSHOT_BYTES) throw IOException("Restored configuration exceeds the safe limit")
            val payload = FileInputStream(restored).use { it.readBytes() }
            try {
                CredentialMetadataBackup.restore(this, payload)
            } finally {
                payload.fill(0)
            }
            atomic.delete()
        } catch (_: Exception) {
            // Keep the bounded snapshot for diagnosis/retry; never log restored metadata or credentials.
            Log.e(TAG, "Safe configuration restore failed")
        }
    }

    private fun createSnapshot(): ByteArray {
        val repository = AndroidCredentials.get(this)
        val finished = CountDownLatch(1)
        val result = AtomicReference<Any?>()
        repository.execute {
            try {
                // Migration may fail on a device-key/storage fault. Snapshot projection still preserves
                // metadata while omitting every old/new credential value and every reference.
                repository.prepare()
                result.set(repository.snapshotForBackup())
            } catch (failure: Exception) {
                result.set(failure)
            } finally {
                finished.countDown()
            }
        }
        if (!finished.await(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw IOException("Safe configuration backup timed out")
        }
        return result.get() as? ByteArray ?: throw IOException("Safe configuration backup could not be prepared")
    }

    companion object {
        const val SNAPSHOT_PATH = "backup/safe-config-v1.bin"
        private const val TAG = "XMouseBackup"
        private const val MAX_SNAPSHOT_BYTES = 8L * 1024 * 1024
        private const val WORKER_TIMEOUT_SECONDS = 60L
    }
}
