package net.droopia.xmouse_ng.secrets

import android.content.Context
import android.content.ContextWrapper
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/** Real AndroidKeyStore and Android private storage; only isolated test aliases/files are deleted. */
@RunWith(AndroidJUnit4::class)
class AndroidSecretStoreTest {
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var alias: String
    private val id = SecretId("host_42_password")
    private val password = "NOT_REAL_password_秘密_0123456789".toByteArray()
    private val sshBytes = "NOT_REAL_IMPORTED_SSH_KEY_BYTES\n0123456789\n".toByteArray()
    private val directory get() = File(root, AndroidSecretStore.DIRECTORY)
    private val record get() = File(directory, id.value + SecretFiles.SUFFIX)
    private fun store() = AndroidSecretStore.open(context, alias)
    private fun keystore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Before
    fun setUp() {
        val target = ApplicationProvider.getApplicationContext<Context>()
        val unique = UUID.randomUUID().toString()
        root = File(target.noBackupFilesDir, "secret-store-test-$unique")
        alias = AndroidSecretStore.KEY_ALIAS + ".test." + unique
        context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
    }

    @After
    fun tearDown() {
        keystore().deleteEntry(alias)
        if (root.exists()) root.walkBottomUp().forEach { assertTrue(it.delete()) }
    }

    @Test
    fun realKeystoreRoundTripsPasswordsImportedBytesAndPassphrasesAcrossReopen() {
        store().write(id, password)
        store().write(SecretId("global_ssh_key"), sshBytes)
        store().write(SecretId("global_passphrase"), password)
        store().write(SecretId("empty"), byteArrayOf())
        assertArrayEquals(password, store().read(id))
        assertArrayEquals(sshBytes, store().read(SecretId("global_ssh_key")))
        assertArrayEquals(password, store().read(SecretId("global_passphrase")))
        assertArrayEquals(byteArrayOf(), store().read(SecretId("empty")))

        val key = keystore().getKey(alias, null) as SecretKey
        assertNull(key.encoded)
        val info = SecretKeyFactory.getInstance("AES", "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
        assertEquals(256, info.keySize)
        assertEquals(KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT, info.purposes)
        assertArrayEquals(arrayOf(KeyProperties.BLOCK_MODE_GCM), info.blockModes)
        assertArrayEquals(arrayOf(KeyProperties.ENCRYPTION_PADDING_NONE), info.encryptionPaddings)
        // Non-exportability is not a claim that this emulator has hardware-backed Keystore.
    }

    @Test
    fun realKeystoreGeneratesFreshNoncesOnRepeatedWrites() {
        val nonces = (1..128).map {
            store().write(id, password)
            assertArrayEquals(password, store().read(id))
            record.readBytes().copyOfRange(5, 17).toList()
        }
        assertEquals(128, nonces.toSet().size)
    }

    @Test
    fun tamperedCiphertextNonceAndTagFailWithoutReplacingOrDeletingRecord() {
        store().write(id, password)
        val original = record.readBytes()
        for (index in listOf(5, 17, original.lastIndex)) {
            val tampered = original.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            record.writeBytes(tampered)
            expect(SecretStoreException.Reason.INVALID_RECORD) { store().read(id) }
            expect(SecretStoreException.Reason.INVALID_RECORD) { store().write(id, sshBytes) }
            assertArrayEquals(tampered, record.readBytes())
        }
    }

    @Test
    fun rejectsIdentitySwapsMalformedRecordsAndUnknownVersionsWithoutOverwriting() {
        store().write(id, password)
        val original = record.readBytes()
        File(directory, "other.secret").writeBytes(original)
        expect(SecretStoreException.Reason.INVALID_RECORD) { store().read(SecretId("other")) }
        for (invalid in listOf(original.copyOf(12), original + 0, original.copyOf().also { it[0] = 0 })) {
            record.writeBytes(invalid)
            expect(SecretStoreException.Reason.INVALID_RECORD) { store().read(id) }
            expect(SecretStoreException.Reason.INVALID_RECORD) { store().write(id, sshBytes) }
            assertArrayEquals(invalid, record.readBytes())
        }
        val future = original.copyOf().also { it[4] = 2 }
        record.writeBytes(future)
        expect(SecretStoreException.Reason.UNSUPPORTED_VERSION) { store().write(id, sshBytes) }
        assertArrayEquals(future, record.readBytes())
    }

    @Test
    fun lostTestKeystoreKeyIsNotRegeneratedAndExistingCiphertextIsPreserved() {
        store().write(id, password)
        val original = record.readBytes()
        keystore().deleteEntry(alias) // Only this test's UUID alias, never the production alias.
        expect(SecretStoreException.Reason.KEY_UNAVAILABLE) { store().read(id) }
        expect(SecretStoreException.Reason.KEY_UNAVAILABLE) { store().write(id, sshBytes) }
        expect(SecretStoreException.Reason.KEY_UNAVAILABLE) { store().write(SecretId("new"), sshBytes) }
        assertFalse(keystore().containsAlias(alias))
        assertArrayEquals(original, record.readBytes())
        assertFalse(File(directory, "new.secret").exists())
        // No UI re-entry or non-secret DB integration is asserted here (task 3.2).
    }

    @Test
    fun symlinkCannotRedirectARecordOutsideStorageDirectory() {
        store().write(id, password)
        val external = File(root, "outside")
        external.writeBytes(password)
        assertTrue(record.delete())
        Os.symlink(external.path, record.path)
        expect(SecretStoreException.Reason.STORAGE_FAILURE) { store().read(id) }
        expect(SecretStoreException.Reason.STORAGE_FAILURE) { store().write(id, sshBytes) }
        assertArrayEquals(password, external.readBytes())
        assertTrue(record.delete()) // Remove only this test's symlink before fixture cleanup.
    }

    @Test
    fun privateNonBackupStorageAndEncryptedStagingNeverContainPlaintext() {
        store().write(id, password)
        val target = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(target.noBackupFilesDir.canonicalFile, root.canonicalFile.parentFile)
        assertFalse(directory.canonicalPath.startsWith(target.filesDir.canonicalPath + File.separator))
        assertEquals(0x1c0, Os.stat(directory.path).st_mode and 0x1ff) // 0700
        assertEquals(0x180, Os.stat(record.path).st_mode and 0x1ff) // 0600
        val original = record.readBytes()
        var sawStaging = false
        val failRename = object : SecretFileIo by AndroidSecretFileIo {
            override fun replace(source: File, destination: File) {
                sawStaging = true
                assertEquals(directory.canonicalFile, source.canonicalFile.parentFile)
                assertEquals(0x180, Os.stat(source.path).st_mode and 0x1ff)
                assertArrayEquals(sshBytes, SecretRecordCipher(AndroidSecretKeySource(alias)).decrypt(id, source.readBytes()))
                root.walkTopDown().filter { it.isFile }.forEach {
                    assertFalse(contains(it.readBytes(), password))
                    assertFalse(contains(it.readBytes(), sshBytes))
                }
                throw IOException() // Deliberate failure before commit.
            }
        }
        val faultStore = EncryptedSecretStore(SecretFiles(directory, failRename), SecretRecordCipher(AndroidSecretKeySource(alias)))
        expect(SecretStoreException.Reason.STORAGE_FAILURE) { faultStore.write(id, sshBytes) }
        assertTrue(sawStaging)
        assertArrayEquals(original, record.readBytes())
        assertArrayEquals(password, store().read(id))
        assertEquals(listOf(".lock", "host_42_password.secret"), directory.list()!!.sorted())
    }

    private fun expect(reason: SecretStoreException.Reason, action: () -> Unit) {
        // The legacy app graph aligns Android tests to JUnit 4.12 (no assertThrows API).
        try {
            action()
            fail("Expected secret-store failure: $reason")
        } catch (failure: SecretStoreException) {
            assertEquals(reason, failure.reason)
        }
    }

    private fun contains(bytes: ByteArray, needle: ByteArray): Boolean =
        bytes.toList().windowed(needle.size).any { it == needle.toList() }
}
