package net.droopia.xmouse_ng.secrets

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.droopia.xmouse_ng.DatabaseHandler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.util.UUID

/** Real SQLite + SharedPreferences + AndroidKeyStore. Fault exceptions model interruption, not power cuts. */
@RunWith(AndroidJUnit4::class)
class CredentialMigrationAndroidTest {
    private val target get() = ApplicationProvider.getApplicationContext<Context>()
    private val firstId = LegacyCredentialReferences.hostPassword(7)
    private val defaultSecret = "NOT_REAL_default_fallback_秘密"
    private val rawPath = "/fixture-only/NOT_A_REAL_PRIVATE_KEY"

    @Test
    fun realV2MigrationRetainsAllNonsecretDataGlobalAuthAndKeyContextAndRestartsWithoutDuplicates() = fixture { f ->
        assertTrue(f.run().legacyKeyImportRequired)
        f.assertUsable()
        f.assertNonsecretUnchanged()
        assertEquals(4, f.secretRecords().size)
        val complete = f.rows("contacts")
        repeat(3) {
            f.reopen()
            assertTrue(f.run().legacyKeyImportRequired)
            assertEquals(complete, f.rows("contacts"))
            f.assertUsable()
            f.assertNonsecretUnchanged()
            assertEquals(4, f.secretRecords().size)
        }
        assertEquals(rawPath, f.preferences.getString("pref_addkeybutton", null))
        assertTrue(f.preferences.getBoolean("pref_usekeyauth", false))
        assertFalse(File(rawPath).exists()) // No fixture file exists, and no path was treated as key bytes.
        assertFalse(f.preferences.contains(LegacyCredentialReferences.GLOBAL_PRIVATE_KEY))
        f.secretRecords().forEach { record ->
            val bytes = record.readBytes().toString(Charsets.ISO_8859_1)
            f.originalSecrets.values.forEach { assertFalse(bytes.contains(it)) }
            assertFalse(bytes.contains(rawPath))
        }
        assertEquals(setOf(".lock") + f.secretRecords().map { it.name }, File(f.context.noBackupFilesDir, "secrets").list()!!.toSet())
    }

    @Test
    fun eachCriticalCheckpointAcrossEverySourceCanInterruptReopenAndResumeWithoutDuplicates() {
        val slots = listOf("host7", "host42", "default", "phrase")
        var scenarios = 0
        slots.forEach { wanted ->
            CredentialMigrationCheckpoint.entries.filter { point ->
                when (point) {
                    CredentialMigrationCheckpoint.BEFORE_DATABASE_UPDATE,
                    CredentialMigrationCheckpoint.AFTER_DATABASE_UPDATE,
                    CredentialMigrationCheckpoint.AFTER_DATABASE_COMMIT -> wanted.startsWith("host")
                    CredentialMigrationCheckpoint.BEFORE_PREFERENCES_COMMIT,
                    CredentialMigrationCheckpoint.AFTER_PREFERENCES_COMMIT -> !wanted.startsWith("host")
                    else -> true
                }
            }.forEach { checkpoint -> fixture { f ->
                var reached = false
                expect<Interruption> {
                    f.run(probe = CredentialMigrationProbe { point, slot ->
                        if (point == checkpoint && name(slot) == wanted) {
                            reached = true
                            throw Interruption()
                        }
                    })
                }
                assertTrue(reached)
                f.reopen()
                f.assertUsable()
                f.assertNonsecretUnchanged()
                f.assertPreferencesMatchDisk()
                f.run()
                f.reopen()
                f.run()
                f.assertUsable()
                f.assertNonsecretUnchanged()
                assertEquals(4, f.secretRecords().size)
                scenarios++
            } }
        }
        assertEquals(26, scenarios)
    }

    @Test
    fun realEncryptedFileWriteFailureAndUncertainCompletedWriteKeepPlaintextUntilVerifiedRetry() {
        listOf(false, true).forEach { uncertain -> fixture { f ->
            val failed = if (uncertain) object : SecretStore by f.store() {
                override fun write(id: SecretId, plaintext: ByteArray) {
                    f.store().write(id, plaintext)
                    throw SecretStoreException(SecretStoreException.Reason.STORAGE_FAILURE)
                }
            } else EncryptedSecretStore(
                SecretFiles(File(f.context.noBackupFilesDir, "secrets"), object : SecretFileIo by AndroidSecretFileIo {
                    override fun replace(source: File, destination: File) = throw IOException()
                }), SecretRecordCipher(AndroidSecretKeySource(f.alias)),
            )
            assertEquals(SecretStoreException.Reason.STORAGE_FAILURE,
                expect<SecretStoreException> { f.run(failed) }.reason)
            assertEquals(f.originalHosts, f.rows("contacts"))
            f.assertUsable()
            f.reopen()
            f.run()
            f.assertUsable()
            f.assertNonsecretUnchanged()
        } }
    }

    @Test
    fun failedMissingAndWrongReadbackDoNotClearSolePlaintextCopy() {
        repeat(3) { fault -> fixture { f ->
            var written = false
            val underlying = f.store()
            val failed = object : SecretStore by underlying {
                override fun write(id: SecretId, plaintext: ByteArray) {
                    underlying.write(id, plaintext)
                    written = true
                }
                override fun read(id: SecretId): ByteArray? {
                    if (!written) return underlying.read(id)
                    return when (fault) {
                        0 -> throw SecretStoreException(SecretStoreException.Reason.STORAGE_FAILURE)
                        1 -> null
                        else -> "NOT_REAL_wrong_readback".toByteArray()
                    }
                }
            }
            expect<Exception> { f.run(failed) }
            assertEquals(f.originalHosts, f.rows("contacts"))
            assertEquals(f.originalPreferences, f.preferences.all)
            f.assertUsable()
            f.reopen()
            f.run()
            f.assertUsable()
            f.assertNonsecretUnchanged()
        } }
    }

    @Test
    fun failedPreferencesCommitBeforeOrAfterDiskWriteAndMemoryOnlyCacheAreRestartable() {
        listOf("setting_pass", "pref_key_passphrase").forEach { plaintextKey ->
            repeat(3) { failureMode -> fixture { f ->
                val failed = FailingPreferences(f.preferences, plaintextKey, failureMode)
                assertEquals(CredentialMigrationException.Reason.STORAGE_FAILURE,
                    expect<CredentialMigrationException> { f.run(preferences = failed) }.reason)
                f.assertUsable() // Actual durable backing preferences, not just the failure wrapper cache.
                f.assertNonsecretUnchanged()
                if (failureMode == 2) {
                    assertFalse(failed.contains(plaintextKey)) // Android-like cache changed, disk did not.
                    assertTrue(f.preferences.contains(plaintextKey))
                    // Retry in the same process/cache MUST synchronously flush reference + removal.
                    f.run(preferences = failed)
                    assertFalse(f.preferences.contains(plaintextKey))
                }
                f.reopen()
                f.run()
                f.assertUsable()
                f.assertNonsecretUnchanged()
                f.assertPreferencesMatchDisk()
                assertEquals(4, f.secretRecords().size)
            } }
        }
    }

    @Test
    fun existingReferenceIsPreservedWhetherReplacementIsAlreadyPresentOrStillMissing() {
        listOf(false, true).forEach { present -> fixture { f ->
            val id = SecretId("existing_host_credential")
            f.database.execSQL("UPDATE contacts SET PasswordCredentialRef=? WHERE id=7", arrayOf(id.value))
            if (present) f.store().write(id, f.originalSecrets.getValue("host7").toByteArray())
            f.run()
            assertEquals(id.value, f.hostReference(7))
            assertNull(f.store().read(firstId))
            f.assertUsable()
            f.assertNonsecretUnchanged()
        } }
    }

    @Test
    fun differentExistingCredentialOrMalformedReferenceNeverOverwritesEitherCopy() {
        listOf(false, true).forEach { malformed -> fixture { f ->
            val reference = if (malformed) "not/a/reference" else "existing_host_credential"
            f.database.execSQL("UPDATE contacts SET PasswordCredentialRef=? WHERE id=7", arrayOf(reference))
            if (!malformed) f.store().write(SecretId(reference), "NOT_REAL_different_credential".toByteArray())
            val before = f.rows("contacts")
            val expected = if (malformed) CredentialMigrationException.Reason.INVALID_REFERENCE
                else CredentialMigrationException.Reason.REPLACEMENT_CONFLICT
            assertEquals(expected, expect<CredentialMigrationException> { f.run() }.reason)
            assertEquals(before, f.rows("contacts"))
            if (!malformed) assertArrayEquals("NOT_REAL_different_credential".toByteArray(), f.store().read(SecretId(reference)))
            f.assertUsable()
            f.assertNonsecretUnchanged()
        } }
    }

    @Test
    fun deletedKeystoreKeyAfterPreparationRetainsPlaintextAndNeverRegeneratesKey() = fixture { f ->
        f.interruptAfterFirstWrite()
        val ciphertext = f.secretRecords().single().readBytes()
        keystore().deleteEntry(f.alias)
        f.reopen()
        assertEquals(SecretStoreException.Reason.KEY_UNAVAILABLE, expect<SecretStoreException> { f.run() }.reason)
        assertEquals(f.originalHosts, f.rows("contacts"))
        assertEquals(f.originalPreferences, f.preferences.all)
        assertFalse(keystore().containsAlias(f.alias))
        assertArrayEquals(ciphertext, f.secretRecords().single().readBytes())
        f.assertUsable() // Every source is still plaintext; ciphertext is preserved, not reset.
        f.assertNonsecretUnchanged()
    }

    @Test
    fun tamperedPreparationIsNotOverwrittenAndDoesNotClearPlaintext() = fixture { f ->
        f.interruptAfterFirstWrite()
        val record = f.secretRecords().single()
        val tampered = record.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        record.writeBytes(tampered)
        f.reopen()
        assertEquals(SecretStoreException.Reason.INVALID_RECORD, expect<SecretStoreException> { f.run() }.reason)
        assertArrayEquals(tampered, record.readBytes())
        assertEquals(f.originalHosts, f.rows("contacts"))
        f.assertUsable()
        f.assertNonsecretUnchanged()
    }

    @Test
    fun clearedReferenceWithMissingRecordOrLostKeyFailsWithoutRecreatingOrDiscardingConfiguration() {
        listOf(false, true).forEach { keyLost -> fixture { f ->
            f.run()
            val before = f.rows("contacts")
            if (keyLost) keystore().deleteEntry(f.alias)
            else assertTrue(File(f.context.noBackupFilesDir, "secrets/${firstId.value}.secret").delete())
            f.reopen()
            if (keyLost) assertEquals(SecretStoreException.Reason.KEY_UNAVAILABLE, expect<SecretStoreException> { f.run() }.reason)
            else assertEquals(CredentialMigrationException.Reason.MISSING_REPLACEMENT,
                expect<CredentialMigrationException> { f.run() }.reason)
            assertEquals(before, f.rows("contacts"))
            f.assertNonsecretUnchanged()
            if (keyLost) assertFalse(keystore().containsAlias(f.alias))
            // Loss AFTER verified cleanup requires CredentialAccess/re-entry (3.2), not a plaintext fallback.
        } }
    }

    @Test
    fun changedDatabaseAndPreferenceSourcesAreNotClearedUsingAStaleSnapshot() {
        listOf(false, true).forEach { preference -> fixture { f ->
            val changed = "NOT_REAL_concurrent_source_change"
            assertEquals(CredentialMigrationException.Reason.SOURCE_CHANGED,
                expect<CredentialMigrationException> {
                    f.run(probe = CredentialMigrationProbe { point, slot ->
                        if (!preference && point == CredentialMigrationCheckpoint.BEFORE_DATABASE_UPDATE && name(slot) == "host7") {
                            f.database.execSQL("UPDATE contacts SET Password=? WHERE id=7", arrayOf(changed))
                        }
                        if (preference && point == CredentialMigrationCheckpoint.BEFORE_PREFERENCES_COMMIT && name(slot) == "default") {
                            assertTrue(f.preferences.edit().putString("setting_pass", changed).commit())
                        }
                    })
                }.reason)
            // DB changes made inside the failing transaction roll back; external preference change remains.
            if (preference) assertEquals(changed, f.preferences.getString("setting_pass", null))
            else assertEquals(f.originalHosts, f.rows("contacts"))
            f.assertNonsecretUnchanged()
        } }
    }

    @Test
    fun actualSqlUpdateFailureRollsBackPlaintextAndReferenceThenRetryWorks() = fixture { f ->
        f.database.execSQL("CREATE TRIGGER fixture_fail_migration BEFORE UPDATE OF Password ON contacts BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
        assertEquals(CredentialMigrationException.Reason.STORAGE_FAILURE,
            expect<CredentialMigrationException> { f.run() }.reason)
        assertEquals(f.originalHosts, f.rows("contacts"))
        f.assertUsable()
        f.database.execSQL("DROP TRIGGER fixture_fail_migration")
        f.reopen()
        f.run()
        f.assertUsable()
        f.assertNonsecretUnchanged()
    }

    @Test
    fun absentAndEmptyValuesStayDistinctAndDisabledGlobalAuthIsNotTurnedOn() = fixture { f ->
        f.database.execSQL("UPDATE contacts SET Password=NULL WHERE id=7")
        f.database.execSQL("UPDATE contacts SET Password='' WHERE id=42")
        assertTrue(f.preferences.edit().remove("setting_pass").putString("pref_key_passphrase", "")
            .putBoolean("pref_usekeyauth", false).putString("pref_addkeybutton", "").commit())
        assertFalse(f.run().legacyKeyImportRequired)
        assertNull(f.hostReference(7))
        assertNull(f.store().read(firstId))
        assertArrayEquals(byteArrayOf(), f.store().read(SecretId(f.hostReference(42)!!)))
        assertArrayEquals(byteArrayOf(), f.store().read(LegacyCredentialReferences.globalPassphrase()))
        assertFalse(f.preferences.contains(LegacyCredentialReferences.DEFAULT_PASSWORD))
        assertFalse(f.preferences.getBoolean("pref_usekeyauth", true))
        assertEquals("", f.preferences.getString("pref_addkeybutton", null))
        assertEquals(f.originalScripts, f.rows("scripts"))
    }

    @Test
    fun nondefaultLocatorMetadataExtraColumnsAndLiteralEndpointStringsAreNotNormalized() = fixture { f ->
        f.database.execSQL("ALTER TABLE contacts ADD COLUMN FixtureExtra TEXT DEFAULT 'synthetic untouched'")
        f.database.execSQL("UPDATE contacts SET Host=' Desk.Fixture.INVALID. ', Port='02222', LocatorType='discovered_service', " +
            "ServiceInstanceName='Desk. literal SSH', ServiceType='_ssh._tcp.', ServiceDomain='local.', " +
            "StableTargetHostname=' Desk.Fixture.INVALID. ' WHERE id=42")
        val before = f.rows("contacts").map { it.take(6) + it.drop(8) }
        f.run()
        f.reopen()
        f.run()
        assertEquals(before, f.rows("contacts").map { it.take(6) + it.drop(8) })
        assertEquals(f.originalScripts, f.rows("scripts"))
        f.assertUsable()
    }

    /** mode 0: false/no write; 1: disk write then false (uncertain); 2: cache-only then false. */
    private class FailingPreferences(
        private val backing: SharedPreferences, private val plaintextKey: String, private val mode: Int,
    ) : SharedPreferences by backing {
        private var failed = false
        private val cached = mutableMapOf<String, String?>()
        override fun getString(key: String?, defValue: String?): String? =
            if (cached.containsKey(key)) cached[key] ?: defValue else backing.getString(key, defValue)
        override fun contains(key: String?): Boolean = if (cached.containsKey(key)) cached[key] != null else backing.contains(key)
        override fun edit(): SharedPreferences.Editor {
            val editor = backing.edit()
            val changes = mutableMapOf<String, String?>()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                    changes[key!!] = value
                    editor.putString(key, value)
                    return this
                }
                override fun remove(key: String?): SharedPreferences.Editor {
                    changes[key!!] = null
                    editor.remove(key)
                    return this
                }
                override fun commit(): Boolean {
                    if (!failed && changes.containsKey(plaintextKey)) {
                        failed = true
                        when (mode) {
                            1 -> check(editor.commit())
                            2 -> cached.putAll(changes)
                        }
                        return false
                    }
                    // Android commits its entire current memory snapshot, including earlier uncertain
                    // cache-only changes, not just this editor's changed keys.
                    cached.filterKeys { it !in changes }.forEach { (key, value) ->
                        if (value == null) editor.remove(key) else editor.putString(key, value)
                    }
                    val result = editor.commit()
                    if (result) cached.clear()
                    return result
                }
            }
        }
    }

    private inner class Fixture : Closeable {
        private val unique = UUID.randomUUID().toString()
        val root = File(target.noBackupFilesDir, "credential-migration-test-$unique")
        val alias = AndroidSecretStore.KEY_ALIAS + ".migration-test." + unique
        private val preferenceName = "credential-migration-test-$unique"
        val preferences: SharedPreferences = target.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        val context = object : IsolatedDatabaseContext(target, File(root, "databases")) {
            override fun getNoBackupFilesDir(): File = File(root, "no_backup")
        }
        var helper = DatabaseHandler(context)
        val database get() = helper.writableDatabase.also { context.assertDatabasePath(it, "contactsManager") }
        val originalHosts: List<List<String?>>
        val originalScripts: List<List<String?>>
        val originalPreferences: Map<String, *>
        val originalSecrets: Map<String, String>

        init {
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("contactsManager"), null).use { db ->
                val sql = asset("contactsManager-v2.sql").bufferedReader().use { it.readText() }
                sql.lineSequence().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
                    .split(';').map(String::trim).filter(String::isNotEmpty).forEach { db.execSQL(it) }
            }
            val values = asset("com.stripe1.xmouse_preferences.xml").use { parsePreferences(it.reader().readText()) }
            val editor = preferences.edit()
            values.forEach { (name, value) ->
                when (value) {
                    is String -> editor.putString(name, value)
                    is Boolean -> editor.putBoolean(name, value)
                }
            }
            assertTrue(editor.putString("setting_pass", defaultSecret).putString("setting_host", " Default.Fixture.INVALID. ")
                .putString("setting_user", "fixture user").putString("setting_port", "00022")
                .putString("sensitivity_list", "1.5f").putBoolean("autologin_checkbox", true).commit())
            originalHosts = rows("contacts")
            originalScripts = rows("scripts")
            originalPreferences = preferences.all.toMap()
            originalSecrets = mapOf(
                "host7" to originalHosts[0][6]!!, "host42" to originalHosts[1][6]!!,
                "default" to defaultSecret, "phrase" to preferences.getString("pref_key_passphrase", null)!!,
            )
        }

        fun store() = AndroidSecretStore.open(context, alias)
        fun run(
            secrets: SecretStore = store(), preferences: SharedPreferences = this.preferences,
            probe: CredentialMigrationProbe = CredentialMigrationProbe { _, _ -> },
        ) = AndroidCredentialMigration.run(helper.also {
            context.assertDatabasePath(it.writableDatabase, "contactsManager")
        }, preferences, secrets, probe)

        fun reopen() {
            helper.close()
            helper = DatabaseHandler(context)
            context.assertDatabasePath(helper.writableDatabase, "contactsManager")
        }

        fun secretRecords() = File(context.noBackupFilesDir, "secrets").listFiles()!!.filter { it.name.endsWith(".secret") }
        fun rows(table: String): List<List<String?>> {
            // Explicit projection also avoids stale SELECT-* column metadata in Android's statement
            // cache when this preservation test adds an extra column on the same open connection.
            val columns = database.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
            }
            return database.rawQuery("SELECT ${columns.joinToString()} FROM $table ORDER BY id", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map(cursor::getString)) }
            }
        }
        fun hostReference(id: Long): String? = database.rawQuery("SELECT PasswordCredentialRef FROM contacts WHERE id=?", arrayOf(id.toString())).use {
            check(it.moveToFirst()); it.getString(0)
        }

        fun assertUsable() {
            rows("contacts").forEach { row ->
                val name = "host${row[0]}"
                if (row[6] != null) assertEquals(originalSecrets.getValue(name), row[6])
                else assertArrayEquals(originalSecrets.getValue(name).toByteArray(), store().read(SecretId(row[7]!!)))
            }
            listOf(
                Triple("default", "setting_pass", LegacyCredentialReferences.DEFAULT_PASSWORD),
                Triple("phrase", "pref_key_passphrase", LegacyCredentialReferences.GLOBAL_PASSPHRASE),
            ).forEach { (name, plaintext, reference) ->
                if (preferences.contains(plaintext)) assertEquals(originalSecrets.getValue(name), preferences.getString(plaintext, null))
                else assertArrayEquals(originalSecrets.getValue(name).toByteArray(), store().read(SecretId(preferences.getString(reference, null)!!)))
            }
        }

        fun assertNonsecretUnchanged() {
            assertEquals(originalHosts.map { it.take(6) + it.drop(8) }, rows("contacts").map { it.take(6) + it.drop(8) })
            assertEquals(originalScripts, rows("scripts"))
            val secretKeys = setOf("setting_pass", "pref_key_passphrase", LegacyCredentialReferences.DEFAULT_PASSWORD, LegacyCredentialReferences.GLOBAL_PASSPHRASE)
            val actualPreferences = preferences.all.toMutableMap()
            val compactionMarker = actualPreferences.remove("credential_database_compacted_v1")
            if (compactionMarker != null) assertEquals("Only the successful compaction marker is an added internal preference", true, compactionMarker)
            assertEquals(originalPreferences.filterKeys { it !in secretKeys }, actualPreferences.filterKeys { it !in secretKeys })
            assertEquals(listOf("7", "42"), rows("contacts").map { it[0] })
            assertEquals(listOf("13", "29"), rows("scripts").map { it[0] })
            assertEquals(DatabaseHandler.DATABASE_VERSION, database.version)
        }

        fun assertPreferencesMatchDisk() {
            val file = File(target.applicationInfo.dataDir, "shared_prefs/$preferenceName.xml")
            assertEquals(preferences.all, parsePreferences(file.readText()))
        }

        fun interruptAfterFirstWrite() {
            expect<Interruption> { run(probe = CredentialMigrationProbe { point, slot ->
                if (point == CredentialMigrationCheckpoint.AFTER_SECRET_WRITE && name(slot) == "host7") throw Interruption()
            }) }
        }

        override fun close() {
            helper.close()
            keystore().deleteEntry(alias)
            assertTrue(target.deleteSharedPreferences(preferenceName))
            if (root.exists()) root.walkBottomUp().forEach { assertTrue(it.delete()) }
        }
    }

    private fun parsePreferences(xml: String): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply { setInput(xml.reader()) }
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                val name = parser.getAttributeValue(null, "name")
                when (parser.name) {
                    "string" -> result[name] = parser.nextText()
                    "boolean" -> result[name] = parser.getAttributeValue(null, "value").toBoolean()
                }
            }
            parser.next()
        }
        return result
    }

    private fun name(slot: LegacyCredentialSlot) = when (slot) {
        is LegacyCredentialSlot.HostPassword -> "host${slot.id}"
        LegacyCredentialSlot.DefaultPassword -> "default"
        LegacyCredentialSlot.GlobalPassphrase -> "phrase"
    }
    private fun keystore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun asset(name: String) = InstrumentationRegistry.getInstrumentation().context.assets.open("legacy/v2/$name")
    private fun fixture(action: (Fixture) -> Unit) = Fixture().use(action)
    private class Interruption : RuntimeException()
    private inline fun <reified T : Throwable> expect(block: () -> Unit): T {
        try { block() } catch (error: Throwable) {
            if (error is T) return error
            throw error
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }
}
