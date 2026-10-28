package net.droopia.xmouse_ng.secrets

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.droopia.xmouse_ng.DatabaseHandler
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Opt-in fixture for exercising Android's real full-backup agent and a local backup transport.
 * Run each method with `backupTransportScenario=stage` or `backupTransportScenario=verify`.
 * Default instrumentation runs skip these destructive-to-fixture methods.
 */
@RunWith(AndroidJUnit4::class)
class CredentialMetadataBackupTransportAndroidTest {
    private val target get() = ApplicationProvider.getApplicationContext<Context>()
    private val arguments get() = InstrumentationRegistry.getArguments()

    @Test fun stageSyntheticPackageDataForSystemBackup() {
        assumeTrue(arguments.getString(ARG_SCENARIO) == SCENARIO_STAGE)
        val preferences = PreferenceManager.getDefaultSharedPreferences(target)
        listOf(
            "setting_pass", "pref_key_passphrase", "setting_pass_credential_ref",
            "pref_key_passphrase_credential_ref", "pref_private_key_credential_ref", AndroidPrivateKeys.LEGACY_PATH,
        ).forEach { assertFalse("Refusing to overwrite existing app preference: $it", preferences.contains(it)) }
        listOf("keyLayoutFile.csv", "keyFileOne.csv", "keyFileTwo.csv", "keyFileThree.csv", NOT_ALLOWLISTED_FILE)
            .forEach { assertFalse("Refusing to overwrite existing app file: $it", File(target.filesDir, it).exists()) }

        val helper = DatabaseHandler(target)
        try {
            val db = helper.writableDatabase
            assertEquals(0, count(db, "contacts"))
            assertEquals(0, count(db, "scripts"))

            val store = AndroidSecretStore.open(target)
            writeSynthetic(store, CURRENT_HOST_SECRET_ID, CURRENT_HOST_SECRET)
            writeSynthetic(store, SecretId(LegacyCredentialReferences.GLOBAL_PASSPHRASE), CURRENT_PASSPHRASE)
            writeSynthetic(store, CURRENT_PRIVATE_KEY_ID, CURRENT_PRIVATE_KEY)

            insertHost(db, LEGACY_HOST_ID, "Synthetic legacy host", LEGACY_HOST_PASSWORD, null)
            insertHost(db, CURRENT_HOST_ID, "Synthetic protected host", null, CURRENT_HOST_SECRET_ID.value)
            db.insertOrThrow("scripts", null, ContentValues().apply {
                put("id", SCRIPT_ID)
                put("time", "2026-10-20 00:00:00")
                put("Alias", "Synthetic backup script")
                put("Script", "printf '%s\\n' 'backup fixture only'")
            })

            check(preferences.edit()
                .putString("hostPreferenceList", LEGACY_HOST_ID.toString())
                .putString("setting_host", "backup.fixture.invalid")
                .putString("setting_user", "backup-fixture-user")
                .putString("setting_port", "2222")
                .putString("setting_pass", LEGACY_DEFAULT_PASSWORD)
                .putString(LegacyCredentialReferences.GLOBAL_PASSPHRASE, LegacyCredentialReferences.GLOBAL_PASSPHRASE)
                .putString(LegacyCredentialReferences.GLOBAL_PRIVATE_KEY, CURRENT_PRIVATE_KEY_ID.value)
                .putString(AndroidPrivateKeys.LEGACY_PATH, LEGACY_PRIVATE_KEY_PATH)
                .putString("not_allowlisted", NOT_ALLOWLISTED_VALUE)
                .putBoolean("pref_usekeyauth", true)
                .commit())

            FILES.forEach { (name, contents) -> File(target.filesDir, name).writeText(contents) }
            File(target.filesDir, NOT_ALLOWLISTED_FILE).writeText(NOT_ALLOWLISTED_VALUE)
        } finally {
            helper.close()
        }
    }

    @Test fun verifyMetadataAndReentryAfterSystemTransportRestore() {
        assumeTrue(arguments.getString(ARG_SCENARIO) == SCENARIO_VERIFY)
        val helper = DatabaseHandler(target)
        try {
            val hosts = helper.readableDatabase.rawQuery(
                "SELECT id, Alias, Host, Username, Port, Password, PasswordCredentialRef FROM contacts ORDER BY id", null,
            ).use { cursor -> buildList {
                while (cursor.moveToNext()) add((0 until cursor.columnCount).map(cursor::getString))
            } }
            assertEquals(2, hosts.size)
            assertEquals(listOf(LEGACY_HOST_ID.toString(), "Synthetic legacy host", "backup.fixture.invalid", "backup-fixture-user", "2222", null, null), hosts[0])
            assertEquals(listOf(CURRENT_HOST_ID.toString(), "Synthetic protected host", "backup.fixture.invalid", "backup-fixture-user", "2222", null, null), hosts[1])
            assertEquals(listOf(SCRIPT_ID), helper.readableDatabase.rawQuery("SELECT id FROM scripts ORDER BY id", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
            })

            val preferences = PreferenceManager.getDefaultSharedPreferences(target)
            assertEquals(LEGACY_HOST_ID.toString(), preferences.getString("hostPreferenceList", null))
            assertEquals("backup.fixture.invalid", preferences.getString("setting_host", null))
            assertEquals("backup-fixture-user", preferences.getString("setting_user", null))
            assertEquals("2222", preferences.getString("setting_port", null))
            assertTrue(preferences.getBoolean("pref_usekeyauth", false))
            listOf(
                "setting_pass", "pref_key_passphrase", LegacyCredentialReferences.DEFAULT_PASSWORD,
                LegacyCredentialReferences.GLOBAL_PASSPHRASE, LegacyCredentialReferences.GLOBAL_PRIVATE_KEY,
                AndroidPrivateKeys.LEGACY_PATH, "not_allowlisted",
            ).forEach { assertFalse("Credential or non-allowlisted preference was restored: $it", preferences.contains(it)) }
            assertEquals(setOf(LEGACY_HOST_ID.toString(), CURRENT_HOST_ID.toString()),
                preferences.getStringSet(CredentialMetadataBackup.HOST_REENTRY_IDS, emptySet()))
            assertTrue(preferences.getBoolean(CredentialMetadataBackup.DEFAULT_PASSWORD_REENTRY, false))
            assertTrue(preferences.getBoolean(CredentialMetadataBackup.PASSPHRASE_REENTRY, false))
            assertTrue(preferences.getBoolean(CredentialMetadataBackup.PRIVATE_KEY_REENTRY, false))

            FILES.forEach { (name, contents) -> assertEquals(contents, File(target.filesDir, name).readText()) }
            assertFalse(File(target.filesDir, NOT_ALLOWLISTED_FILE).exists())
            assertFalse(File(target.filesDir, "backup/safe-config-v1.bin").exists())

            // The protected no-backup records are absent on the restored installation. The marker
            // path must request new input rather than accepting an empty or stale credential.
            val store = AndroidSecretStore.open(target)
            listOf(CURRENT_HOST_SECRET_ID, SecretId(LegacyCredentialReferences.GLOBAL_PASSPHRASE), CURRENT_PRIVATE_KEY_ID)
                .forEach { id -> assertNull("Encrypted credential was restored: ${id.value}", store.read(id)) }
            assertDatabaseDoesNotContainSecrets(target.getDatabasePath("contactsManager").parentFile)
        } finally {
            helper.close()
        }
    }

    private fun insertHost(db: SQLiteDatabase, id: Long, alias: String, password: String?, reference: String?) {
        db.insertOrThrow("contacts", null, ContentValues().apply {
            put("id", id)
            put("time", "2026-10-20 00:00:00")
            put("Alias", alias)
            put("Host", "backup.fixture.invalid")
            put("Username", "backup-fixture-user")
            put("Port", "2222")
            if (password == null) putNull("Password") else put("Password", password)
            if (reference == null) putNull(DatabaseHandler.PASSWORD_CREDENTIAL_REF)
            else put(DatabaseHandler.PASSWORD_CREDENTIAL_REF, reference)
            put(DatabaseHandler.LOCATOR_TYPE, DatabaseHandler.LOCATOR_MANUAL)
        })
    }

    private fun writeSynthetic(store: SecretStore, id: SecretId, value: String) {
        val bytes = value.toByteArray()
        try { store.write(id, bytes) } finally { bytes.fill(0) }
    }

    private fun count(db: SQLiteDatabase, table: String): Int = db.rawQuery("SELECT COUNT(*) FROM $table", null).use {
        assertTrue(it.moveToFirst())
        it.getInt(0)
    }

    private fun assertDatabaseDoesNotContainSecrets(directory: File?) {
        val needles = listOf(LEGACY_HOST_PASSWORD, LEGACY_DEFAULT_PASSWORD, CURRENT_HOST_SECRET, CURRENT_PASSPHRASE, CURRENT_PRIVATE_KEY)
            .map(String::toByteArray)
        directory?.listFiles().orEmpty().filter(File::isFile).forEach { file ->
            val contents = file.readBytes()
            needles.forEach { needle -> assertFalse("SQLite backup restore retained secret bytes in ${file.name}", contents.containsBytes(needle)) }
        }
    }

    private fun ByteArray.containsBytes(needle: ByteArray): Boolean =
        needle.isNotEmpty() && indices.any { start -> start + needle.size <= size && needle.indices.all { this[start + it] == needle[it] } }

    companion object {
        private const val ARG_SCENARIO = "backupTransportScenario"
        private const val SCENARIO_STAGE = "stage"
        private const val SCENARIO_VERIFY = "verify"
        private const val LEGACY_HOST_ID = 9101L
        private const val CURRENT_HOST_ID = 9102L
        private const val SCRIPT_ID = 9201L
        private const val CURRENT_HOST_SECRET = "SYNTHETIC_NOT_REAL_current_host_password"
        private const val LEGACY_HOST_PASSWORD = "SYNTHETIC_NOT_REAL_legacy_host_password"
        private const val LEGACY_DEFAULT_PASSWORD = "SYNTHETIC_NOT_REAL_legacy_default_password"
        private const val CURRENT_PASSPHRASE = "SYNTHETIC_NOT_REAL_current_passphrase"
        private const val CURRENT_PRIVATE_KEY = "SYNTHETIC_NOT_REAL_current_private_key_bytes"
        private const val LEGACY_PRIVATE_KEY_PATH = "/synthetic/not-a-real/private-key"
        private const val NOT_ALLOWLISTED_VALUE = "SYNTHETIC_NOT_ALLOWLISTED"
        private const val NOT_ALLOWLISTED_FILE = "not-allowlisted.txt"
        private val CURRENT_HOST_SECRET_ID = SecretId("transport_host_current")
        private val CURRENT_PRIVATE_KEY_ID = SecretId("transport_private_key_current")
        private val FILES = linkedMapOf(
            "keyLayoutFile.csv" to "layout-safe-active-fixture",
            "keyFileOne.csv" to "layout-safe-preset-one",
            "keyFileTwo.csv" to "layout-safe-preset-two",
            "keyFileThree.csv" to "layout-safe-preset-three",
        )
    }
}
