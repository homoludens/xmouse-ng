package com.stripe1.xmouse.secrets

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Keystore wraps arbitrary secret bytes; the validated private-key importer is a production caller. */
object AndroidSecretStore {
    internal const val DIRECTORY = "secrets"
    internal const val KEY_ALIAS = "com.stripe1.xmouse.secrets.aes-gcm.v1"

    @JvmStatic
    fun open(context: Context): SecretStore = open(context, KEY_ALIAS)

    internal fun open(context: Context, alias: String): SecretStore = EncryptedSecretStore(
        SecretFiles(File(context.applicationContext.noBackupFilesDir, DIRECTORY), AndroidSecretFileIo),
        SecretRecordCipher(AndroidSecretKeySource(alias)),
    )
}

internal class AndroidSecretKeySource(private val alias: String) : SecretKeySource {
    override fun key(allowCreation: Boolean): SecretKey = try {
        // Reload rather than caching a stale handle after key loss/invalidation.
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keystore.containsAlias(alias)) {
            keystore.getKey(alias, null) as? SecretKey ?: fail(SecretStoreException.Reason.KEY_UNAVAILABLE)
        } else {
            if (!allowCreation) fail(SecretStoreException.Reason.KEY_UNAVAILABLE)
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generator.generateKey()
        }
    } catch (_: GeneralSecurityException) {
        fail(SecretStoreException.Reason.KEY_UNAVAILABLE)
    } catch (_: IOException) {
        fail(SecretStoreException.Reason.KEY_UNAVAILABLE)
    } catch (_: ProviderException) {
        fail(SecretStoreException.Reason.KEY_UNAVAILABLE)
    } catch (_: SecurityException) {
        fail(SecretStoreException.Reason.KEY_UNAVAILABLE)
    }
}

internal object AndroidSecretFileIo : SecretFileIo {
    override fun privateDirectory(directory: File) = posix { Os.chmod(directory.path, 0x1c0) } // 0700
    override fun privateFile(file: File) = posix { Os.chmod(file.path, 0x180) } // 0600
    override fun sync(output: FileOutputStream) = output.fd.sync()
    override fun replace(source: File, destination: File) = posix { Os.rename(source.path, destination.path) }
    override fun syncDirectory(directory: File) = posix {
        val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(fd)
        } finally {
            Os.close(fd)
        }
    }

    private fun <T> posix(action: () -> T): T = try {
        action()
    } catch (_: ErrnoException) {
        throw IOException()
    }
}
