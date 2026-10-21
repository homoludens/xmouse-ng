package com.stripe1.xmouse.secrets

import android.content.Context
import android.content.ContentValues
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.preference.PreferenceManager
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stripe1.xmouse.DatabaseHandler
import com.stripe1.xmouse.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real Android SQLite, preferences, secret-store boundary, restore markers and packaged backup rules. */
@RunWith(AndroidJUnit4::class)
class CredentialMetadataBackupAndroidTest {
    private val target get() = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun packagedLegacyAndModernRulesAreAllowlistedForCloudAndDeviceTransfer() {
        val legacy = parseRules(R.xml.backup_rules)
        assertEquals(setOf(
            "file:backup/safe-config-v1.bin", "file:keyLayoutFile.csv", "file:keyFileOne.csv",
            "file:keyFileTwo.csv", "file:keyFileThree.csv",
        ), legacy.includes)
        assertTrue("No SQLite or preferences domain may be allowlisted", legacy.includes.none {
            it.startsWith("database:") || it.startsWith("sharedpref:")
        })
        assertTrue(legacy.excludes.isEmpty())

        val modern = target.resources.getXml(R.xml.data_extraction_rules)
        val sections = linkedMapOf<String, Rules>()
        var current: String? = null
        try {
            while (modern.eventType != XmlPullParser.END_DOCUMENT) {
                if (modern.eventType == XmlPullParser.START_TAG) {
                    when (modern.name) {
                        "cloud-backup", "device-transfer" -> {
                            current = modern.name
                            sections[current!!] = Rules(mutableSetOf(), mutableSetOf())
                        }
                        "include", "exclude" -> {
                            val scope = current ?: throw AssertionError("Rule outside a transfer section")
                            val domain = modern.getAttributeValue(null, "domain")
                            val path = modern.getAttributeValue(null, "path")
                            val values = if (modern.name == "include") sections.getValue(scope).includes
                                else sections.getValue(scope).excludes
                            values.add("$domain:$path")
                        }
                    }
                } else if (modern.eventType == XmlPullParser.END_TAG &&
                    (modern.name == "cloud-backup" || modern.name == "device-transfer")) {
                    current = null
                }
                modern.next()
            }
        } finally { modern.close() }
        assertEquals(setOf("cloud-backup", "device-transfer"), sections.keys)
        sections.values.forEach { rules ->
            assertEquals(legacy.includes, rules.includes)
            assertTrue("No SQLite or preferences domain may be allowlisted", rules.includes.none {
                it.startsWith("database:") || it.startsWith("sharedpref:")
            })
            assertTrue(rules.excludes.isEmpty())
        }
    }

    @Test fun partialMigrationBackupOmitsOldAndNewSecretsAndRestoresMetadataWithReentryOnly() = Fixture().use { source ->
        source.insertLegacyV2()
        source.upgradeToCurrent()
        source.preferences.edit(commit = true) {
            clear()
            putString("hostPreferenceList", "42")
            putString("setting_host", "fallback.fixture.invalid")
            putString("setting_user", "fallback-user")
            putString("setting_port", "2200")
            putBoolean("pref_usekeyauth", false)
            putString("setting_pass", OLD_DEFAULT_PASSWORD)
            putString("pref_key_passphrase_credential_ref", NEW_PASSPHRASE_REFERENCE)
            putString("pref_addkeybutton", OLD_PRIVATE_KEY_PATH)
            putString("pref_private_key_credential_ref", NEW_PRIVATE_KEY_REFERENCE)
            putString("not_allowlisted", OLD_DEFAULT_PASSWORD)
        }
        source.store.write(SecretId(NEW_HOST_REFERENCE), OLD_HOST_PASSWORD_7.toByteArray())
        source.helper.writableDatabase.execSQL(
            "UPDATE contacts SET Password=NULL, PasswordCredentialRef=? WHERE id=7",
            arrayOf(NEW_HOST_REFERENCE),
        )

        // Use the same serialized gate as the BackupAgent, including a failed credential preparation.
        val repository = CredentialRepository(source.helper, source.preferences, source.store)
        val snapshot = source.onRepositoryWorker(repository) {
            assertFalse(repository.prepare()) // The referenced ciphertext is intentionally absent.
            repository.snapshotForBackup()
        }
        val binary = snapshot.toString(Charsets.ISO_8859_1)
        listOf(OLD_HOST_PASSWORD_7, OLD_HOST_PASSWORD, OLD_DEFAULT_PASSWORD, OLD_PASSPHRASE, OLD_PRIVATE_KEY_PATH,
            NEW_HOST_REFERENCE, NEW_PASSPHRASE_REFERENCE, NEW_PRIVATE_KEY_REFERENCE).forEach {
            assertFalse("backup snapshot leaked $it", binary.contains(it))
        }
        val encryptedNewSecret = File(source.context.noBackupFilesDir, "secrets/$NEW_HOST_REFERENCE.secret")
        assertTrue(encryptedNewSecret.isFile)
        assertFalse(snapshot.containsBytes(encryptedNewSecret.readBytes()))
        assertTrue(binary.contains("Fixture desk A"))
        assertTrue(binary.contains("fixture-user-b"))
        assertTrue(binary.contains("Fixture message"))
        assertFalse(binary.contains("not_allowlisted"))

        Fixture().use { destination ->
            destination.assertDatabasePath()
            CredentialMetadataBackup.restore(destination.context, snapshot)
            destination.assertDatabasePath()
            val restoredHosts = destination.helper.writableDatabase.rawQuery(
                "SELECT id, Alias, Host, Username, Port, Password, PasswordCredentialRef FROM contacts ORDER BY id", null,
            ).use { cursor -> buildList {
                while (cursor.moveToNext()) add((0 until cursor.columnCount).map { cursor.getString(it) })
            } }
            assertEquals(listOf("7", "Fixture desk A", "desk-a.fixture.invalid", "fixture-user-a", "22", null, null), restoredHosts[0])
            assertEquals(listOf("42", "Fixture lab B", "lab-b.fixture.invalid", "fixture-user-b", "2222", null, null), restoredHosts[1])
            assertEquals(listOf(13L, 29L), destination.ids("scripts"))
            assertEquals("printf '%s\\n' 'fixture-only: hello'", destination.script(29))

            val restored = destination.preferences
            assertEquals("42", restored.getString("hostPreferenceList", null))
            assertEquals("fallback.fixture.invalid", restored.getString("setting_host", null))
            assertFalse(restored.getBoolean("pref_usekeyauth", true))
            listOf("setting_pass", "pref_key_passphrase", "pref_addkeybutton",
                LegacyCredentialReferences.DEFAULT_PASSWORD, LegacyCredentialReferences.GLOBAL_PASSPHRASE,
                LegacyCredentialReferences.GLOBAL_PRIVATE_KEY, "pref_key_passphrase_credential_ref",
                "pref_private_key_credential_ref", "not_allowlisted").forEach { assertFalse(restored.contains(it)) }
            assertTrue(restored.getBoolean(CredentialMetadataBackup.DEFAULT_PASSWORD_REENTRY, false))
            assertTrue(restored.getBoolean(CredentialMetadataBackup.PASSPHRASE_REENTRY, false))
            assertTrue(restored.getBoolean(CredentialMetadataBackup.PRIVATE_KEY_REENTRY, false))
            assertEquals(setOf("7", "42"), restored.getStringSet(CredentialMetadataBackup.HOST_REENTRY_IDS, emptySet()))

            // A fresh device has no old SecretStore record/key. Metadata still opens, and authentication
            // asks for attempt-only entry instead of treating a missing backup credential as empty.
            val newDeviceAlias = AndroidSecretStore.KEY_ALIAS + ".restore-test." + destination.unique
            val newDeviceStore = AndroidSecretStore.open(destination.context, newDeviceAlias)
            val newDeviceRepository = CredentialRepository(destination.helper, restored, newDeviceStore)
            assertTrue(destination.onRepositoryWorker(newDeviceRepository) { newDeviceRepository.prepare() })
            assertEquals(listOf(7L, 42L), newDeviceRepository.hosts().map { it.id })
            val prompt = AtomicReference<CredentialReentryRequest?>()
            val kind = AtomicReference<CredentialKind?>()
            val material = AtomicReference<AuthenticationMaterial?>()
            val done = CountDownLatch(1)
            destination.onRepositoryWorker(newDeviceRepository) {
                newDeviceRepository.authenticate({ requestedKind, request ->
                    kind.set(requestedKind); prompt.set(request)
                }) { value, _ -> material.set(value); done.countDown() }
            }
            assertEquals(CredentialKind.PASSWORD, kind.get())
            assertNotNull(prompt.get())
            assertTrue(prompt.get()!!.useForThisAttempt("REENTERED_NOT_REAL".toByteArray()))
            assertTrue(done.await(10, TimeUnit.SECONDS))
            material.get()!!.use { assertArrayEquals("REENTERED_NOT_REAL".toByteArray(), it.password) }
            assertFalse(destination.keystoreContains(newDeviceAlias))
            assertTrue(destination.encryptedSecretFiles(newDeviceAlias).isEmpty())
            snapshot.fill(0)
        }
    }

    @Test fun sqliteCompactionFailureIsReportedAndRetryPreservesMigratedData() = Fixture().use { fixture ->
        fixture.insertLegacyV2()
        val compacting = object : DatabaseHandler(fixture.context) {
            var failCompaction = true
            override fun checkpointAndCompactAfterCredentialMigration() {
                if (failCompaction) throw SQLiteException("synthetic compaction fault")
                super.checkpointAndCompactAfterCredentialMigration()
            }
        }
        fixture.context.assertDatabasePath(compacting.writableDatabase, "contactsManager")
        val repository = CredentialRepository(compacting, fixture.preferences, fixture.store)
        val passwordBytes = OLD_HOST_PASSWORD.toByteArray()
        assertTrue(fixture.databaseDirectory().listFiles()!!.any { it.readBytes().containsBytes(passwordBytes) })

        assertFalse(fixture.onRepositoryWorker(repository) { repository.prepare() })
        assertFalse(fixture.preferences.getBoolean("credential_database_compacted_v1", false))
        assertEquals(listOf(7L, 42L), repository.hosts().map { it.id })
        assertTrue(fixture.rows("contacts").all { it[6] == null && it[7] != null })
        assertTrue(fixture.preferences.contains(LegacyCredentialReferences.DEFAULT_PASSWORD))
        assertFalse(fixture.preferences.contains("setting_pass"))

        compacting.failCompaction = false
        assertTrue(fixture.onRepositoryWorker(repository) { repository.prepare() })
        assertTrue(fixture.preferences.getBoolean("credential_database_compacted_v1", false))
        assertEquals(listOf(7L, 42L), repository.hosts().map { it.id })
        assertEquals(listOf(13L, 29L), fixture.ids("scripts"))
        fixture.helper.readableDatabase.rawQuery("PRAGMA integrity_check", null).use { cursor ->
            assertTrue(cursor.moveToFirst()); assertEquals("ok", cursor.getString(0))
        }
        val secretBytes = listOf(OLD_HOST_PASSWORD_7, OLD_HOST_PASSWORD, OLD_DEFAULT_PASSWORD, OLD_PASSPHRASE)
            .map { it.toByteArray() }
        fixture.databaseDirectory().listFiles()!!.forEach { file ->
            val content = file.readBytes()
            secretBytes.forEach { assertFalse("SQLite residual in ${file.name}", content.containsBytes(it)) }
        }
    }

    @Test fun failedPreferencesCommitCanRetryMarkersWithoutMarkingConflictingExistingHosts() = Fixture().use { source ->
        source.insertLegacyV2()
        source.upgradeToCurrent()
        source.preferences.edit(commit = true) {
            putString("hostPreferenceList", "42")
            putBoolean("pref_usekeyauth", false)
            putString("pref_key_passphrase", "NOT_REAL_retry_passphrase")
        }
        source.helper.writableDatabase.insertOrThrow("contacts", null, hostValues(
            43L, "Snapshot host 43", "snapshot-43.fixture.invalid", null, "snapshot_host_43_reference",
        ))
        val repository = CredentialRepository(source.helper, source.preferences, source.store)
        val snapshot = source.onRepositoryWorker(repository) { repository.snapshotForBackup() }
        try {
            Fixture().use { destination ->
                val db = destination.helper.writableDatabase
                destination.context.assertDatabasePath(db, "contactsManager")
                db.delete("contacts", null, null)
                db.delete("scripts", null, null)
                db.insertOrThrow("contacts", null, hostValues(
                    7L, "Legitimate credentialed host", "existing-7.fixture.invalid", null, "legitimate_existing_reference",
                ))
                db.insertOrThrow("contacts", null, hostValues(
                    42L, "Different existing host", "existing-42.fixture.invalid", "EXISTING_NOT_REAL_HOST_PASSWORD", null,
                ))
                destination.preferences.edit(commit = true) {
                    clear()
                    putString("setting_host", "keep-existing.fixture.invalid")
                }

                val failedPreferences = FailFirstCommitPreferences(destination.preferences)
                var failed = false
                try {
                    CredentialMetadataBackup.restore(destination.context, snapshot, failedPreferences)
                } catch (_: IOException) { failed = true }
                assertTrue("The injected preferences commit should fail", failed)
                assertTrue(destination.preferences.getStringSet(CredentialMetadataBackup.HOST_REENTRY_IDS, emptySet()).isNullOrEmpty())
                assertEquals(1, scalarInt(db, "SELECT COUNT(*) FROM credential_backup_restore_pending"))

                // A second delivery sees CONFLICT_IGNORE for existing host IDs. The SQLite journal
                // records that only 43 was actually imported and retries its marker after metadata.
                CredentialMetadataBackup.restore(destination.context, snapshot, destination.preferences)
                assertEquals(setOf("43"), destination.preferences.getStringSet(CredentialMetadataBackup.HOST_REENTRY_IDS, emptySet()))
                assertEquals("keep-existing.fixture.invalid", destination.preferences.getString("setting_host", null))
                assertEquals(0, scalarInt(db, "SELECT COUNT(*) FROM credential_backup_restore_pending"))
                assertEquals(listOf("Legitimate credentialed host", null, "legitimate_existing_reference"),
                    hostState(db, 7L))
                assertEquals(listOf("Different existing host", "EXISTING_NOT_REAL_HOST_PASSWORD", null),
                    hostState(db, 42L))
                assertEquals(listOf("Snapshot host 43", null, null), hostState(db, 43L))
                assertFalse("A conflicting existing credentialed host must not become a restore marker",
                    "7" in destination.preferences.getStringSet(CredentialMetadataBackup.HOST_REENTRY_IDS, emptySet()).orEmpty())
                assertFalse("A different-metadata host conflict must not become a restore marker",
                    "42" in destination.preferences.getStringSet(CredentialMetadataBackup.HOST_REENTRY_IDS, emptySet()).orEmpty())
            }
        } finally { snapshot.fill(0) }
    }

    private data class Rules(val includes: MutableSet<String>, val excludes: MutableSet<String>)

    private fun parseRules(resource: Int): Rules {
        val parser = target.resources.getXml(resource)
        val rules = Rules(mutableSetOf(), mutableSetOf())
        try {
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && (parser.name == "include" || parser.name == "exclude")) {
                    val domain = parser.getAttributeValue(null, "domain")
                    val path = parser.getAttributeValue(null, "path")
                    (if (parser.name == "include") rules.includes else rules.excludes).add("$domain:$path")
                }
                parser.next()
            }
        } finally { parser.close() }
        return rules
    }

    private fun ByteArray.containsBytes(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        return indices.any { start -> start + needle.size <= size && needle.indices.all { this[start + it] == needle[it] } }
    }

    private fun hostValues(id: Long, alias: String, host: String, password: String?, reference: String?): ContentValues =
        ContentValues().apply {
            put("id", id); put("time", "2026-10-20 00:00:00"); put("Alias", alias)
            put("Host", host); put("Username", "fixture-user"); put("Port", "22")
            if (password == null) putNull("Password") else put("Password", password)
            if (reference == null) putNull(DatabaseHandler.PASSWORD_CREDENTIAL_REF)
            else put(DatabaseHandler.PASSWORD_CREDENTIAL_REF, reference)
            put(DatabaseHandler.LOCATOR_TYPE, DatabaseHandler.LOCATOR_MANUAL)
        }

    private fun scalarInt(db: SQLiteDatabase, query: String): Int = db.rawQuery(query, null).use {
        assertTrue(it.moveToFirst()); it.getInt(0)
    }

    private fun hostState(db: SQLiteDatabase, id: Long): List<String?> = db.rawQuery(
        "SELECT Alias, Password, PasswordCredentialRef FROM contacts WHERE id=?", arrayOf(id.toString()),
    ).use { cursor ->
        assertTrue(cursor.moveToFirst())
        (0 until cursor.columnCount).map(cursor::getString)
    }

    private class FailFirstCommitPreferences(private val backing: SharedPreferences) : SharedPreferences by backing {
        private var failed = false
        override fun edit(): SharedPreferences.Editor {
            val delegate = backing.edit()
            return object : SharedPreferences.Editor by delegate {
                override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                    delegate.putStringSet(key, values)
                    return this
                }
                override fun commit(): Boolean {
                    if (!failed) { failed = true; return false }
                    return delegate.commit()
                }
            }
        }
    }

    private inner class Fixture : Closeable {
        val unique = UUID.randomUUID().toString()
        private val preferencePrefix = "metadata-backup-test-$unique"
        private val root = File(target.noBackupFilesDir, preferencePrefix)
        val context = object : IsolatedDatabaseContext(target, File(root, "databases")) {
            override fun getNoBackupFilesDir(): File = File(root, "no_backup").also { it.mkdirs() }
            override fun getFilesDir(): File = File(root, "files").also { it.mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) =
                target.getSharedPreferences("$preferencePrefix-$name", mode)
        }
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        private val alias = AndroidSecretStore.KEY_ALIAS + ".metadata-source." + unique
        val store = AndroidSecretStore.open(context, alias)
        var helper = DatabaseHandler(context)

        fun insertLegacyV2() {
            val sql = instrumentation.context.assets.open("legacy/v2/contactsManager-v2.sql").bufferedReader().use { it.readText() }
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("contactsManager"), null).use { db ->
                context.assertDatabasePath(db, "contactsManager")
                sql.lineSequence().filterNot { it.trimStart().startsWith("--") }
                    .joinToString("\n").split(';').map(String::trim).filter(String::isNotEmpty).forEach(db::execSQL)
            }
            preferences.edit(commit = true) {
                clear()
                putString("hostPreferenceList", "42")
                putBoolean("pref_usekeyauth", false)
                putString("setting_host", "fallback.fixture.invalid")
                putString("setting_user", "fallback-user")
                putString("setting_port", "2200")
                putString("setting_pass", OLD_DEFAULT_PASSWORD)
                putString("pref_key_passphrase", OLD_PASSPHRASE)
                putString("pref_addkeybutton", OLD_PRIVATE_KEY_PATH)
            }
        }

        fun assertDatabasePath() = context.assertDatabasePath(helper.writableDatabase, "contactsManager")
        fun upgradeToCurrent() { assertDatabasePath() }
        fun rows(table: String): List<List<String?>> = helper.readableDatabase.rawQuery("SELECT * FROM $table ORDER BY id", null).use { c ->
            buildList { while (c.moveToNext()) add((0 until c.columnCount).map(c::getString)) }
        }
        fun ids(table: String): List<Long> = helper.readableDatabase.rawQuery("SELECT id FROM $table ORDER BY id", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0)) }
        }
        fun script(id: Long): String? = helper.readableDatabase.rawQuery("SELECT Script FROM scripts WHERE id=?", arrayOf(id.toString())).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        fun databaseDirectory() = context.getDatabasePath("contactsManager").parentFile!!
        fun keystoreContains(keyAlias: String): Boolean = java.security.KeyStore.getInstance("AndroidKeyStore").run {
            load(null); containsAlias(keyAlias)
        }
        fun encryptedSecretFiles(keyAlias: String): List<File> = File(context.noBackupFilesDir, "secrets").listFiles()?.toList().orEmpty()
        override fun close() {
            helper.close()
            java.security.KeyStore.getInstance("AndroidKeyStore").run { load(null); deleteEntry(alias); deleteEntry(AndroidSecretStore.KEY_ALIAS + ".restore-test." + unique) }
            target.deleteSharedPreferences("$preferencePrefix-${target.packageName}_preferences")
            root.walkBottomUp().forEach { it.delete() }
        }
    }

    private fun <T> Fixture.onRepositoryWorker(repository: CredentialRepository, action: () -> T): T {
        val result = AtomicReference<T?>()
        val error = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        repository.execute {
            try { result.set(action()) } catch (failure: Throwable) { error.set(failure) } finally { done.countDown() }
        }
        assertTrue(done.await(20, TimeUnit.SECONDS))
        error.get()?.let { throw AssertionError(it) }
        @Suppress("UNCHECKED_CAST")
        return result.get() as T
    }

    companion object {
        private const val OLD_HOST_PASSWORD_7 = "SYNTHETIC_FIXTURE_PASSWORD_FOR_HOST_7_NOT_REAL"
        private const val OLD_HOST_PASSWORD = "SYNTHETIC_FIXTURE_PASSWORD_FOR_HOST_42_NOT_REAL"
        private const val OLD_DEFAULT_PASSWORD = "NOT_REAL_default_backup_fixture"
        private const val OLD_PASSPHRASE = "NOT_REAL_passphrase_backup_fixture"
        private const val OLD_PRIVATE_KEY_PATH = "/NOT_REAL/device/private-key.pem"
        private const val NEW_HOST_REFERENCE = "migration_host_7_password"
        private const val NEW_PASSPHRASE_REFERENCE = "migration_global_passphrase"
        private const val NEW_PRIVATE_KEY_REFERENCE = "credential_private_key_reference"
    }
}
