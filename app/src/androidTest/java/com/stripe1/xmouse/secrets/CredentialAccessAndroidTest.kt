package com.stripe1.xmouse.secrets

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stripe1.xmouse.DatabaseHandler
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** Real Android key loss + production re-entry contract, not hardware/biometric invalidation evidence. */
@RunWith(AndroidJUnit4::class)
class CredentialAccessAndroidTest {
    private lateinit var target: Context
    private lateinit var context: IsolatedDatabaseContext
    private lateinit var root: File
    private lateinit var alias: String
    private lateinit var preferencesName: String
    private lateinit var preferences: SharedPreferences
    private lateinit var metadata: Metadata
    private val id = SecretId("host_42_password")
    private val secret = "NOT_REAL_encrypted_credential_秘密".toByteArray()
    private val replacement = "NOT_REAL_reentered_credential".toByteArray()
    private val record get() = File(context.noBackupFilesDir, "secrets/${id.value}.secret")
    private fun store() = AndroidSecretStore.open(context, alias)
    private fun keystore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun asset(name: String) = InstrumentationRegistry.getInstrumentation().context.assets.open(name)

    @Before
    fun setUp() {
        target = ApplicationProvider.getApplicationContext()
        val unique = UUID.randomUUID().toString()
        root = File(target.noBackupFilesDir, "credential-access-test-$unique")
        alias = AndroidSecretStore.KEY_ALIAS + ".reentry-test." + unique
        preferencesName = "credential-access-test-$unique"
        context = object : IsolatedDatabaseContext(target, File(root, "databases")) {
            override fun getNoBackupFilesDir(): File = File(root, "no_backup")
        }
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("contactsManager"), null).use { db ->
            val sql = asset("legacy/v2/contactsManager-v2.sql").bufferedReader().use { it.readText() }
            sql.lineSequence().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
                .split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { db.execSQL(it) }
        }
        preferences = target.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        preferences.edit(commit = true) {
            asset("legacy/v2/com.stripe1.xmouse_preferences.xml").use { input ->
                val parser = XmlPullParserFactory.newInstance().newPullParser().apply { setInput(input, "UTF-8") }
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG) {
                        val name = parser.getAttributeValue(null, "name")
                        when (parser.name) {
                            "string" -> putString(name, parser.nextText())
                            "boolean" -> putBoolean(name, parser.getAttributeValue(null, "value").toBoolean())
                        }
                    }
                    parser.next()
                }
            }
        }
        // Synthetic active/preset records in the actual legacy format; never app/user layout files.
        val layouts = File(root, "files").apply { check(mkdirs()) }
        listOf("keyLayoutFile.csv", "keyFileOne.csv", "keyFileTwo.csv", "keyFileThree.csv").forEachIndexed { index, name ->
            File(layouts, name).writeText("Fixture $index<xmousesep>printf fixture-$index<xmousesep>${index + 1}<xmousesep>#DDEEFF\n")
        }
        metadata = snapshotMetadata()
        assertEquals(listOf("7", "42"), metadata.hosts.map { it.first() })
        assertEquals(listOf("13", "29"), metadata.scripts.map { it.first() })
        assertEquals("42", preferences.getString("hostPreferenceList", null))
        assertTrue(preferences.getBoolean("pref_usekeyauth", false))
        assertEquals("/fixture-only/NOT_A_REAL_PRIVATE_KEY", preferences.getString("pref_addkeybutton", null))
        assertEquals("SYNTHETIC_FIXTURE_PASSPHRASE_NOT_REAL", preferences.getString("pref_key_passphrase", null))
        store().write(id, secret)
    }

    @After
    fun tearDown() {
        keystore().deleteEntry(alias) // Only this test's alias; production key is never touched.
        assertTrue(target.deleteSharedPreferences(preferencesName))
        if (root.exists()) root.walkBottomUp().forEach { assertTrue(it.delete()) }
    }

    @Test
    fun deletedDeviceKeyRequestsCredentialReentryAndCancelPreservesStoredHostsScriptsAndLayouts() {
        val ciphertext = record.readBytes()
        keystore().deleteEntry(alias)
        val presentation = RecordingPresentation()
        val results = mutableListOf<CredentialReadResult>()
        val request = CredentialAccess(store()).read(id, presentation) { results.add(it) }!!
        assertSame(request, presentation.requests.single()) // Actual consumer called the presentation contract.
        assertEquals(id, request.id)
        assertEquals(SecretStoreException.Reason.KEY_UNAVAILABLE, request.reason)
        assertTrue(results.isEmpty()) // Never continues authentication with an empty/default password.
        assertTrue(request.cancel()) // Dismissal/Back is an explicit terminal cancellation.
        assertSame(CredentialReadResult.Cancelled, results.single())
        assertFalse(request.useForThisAttempt(replacement))
        assertFalse(keystore().containsAlias(alias))
        assertArrayEquals(ciphertext, record.readBytes())
        assertEquals(metadata, snapshotMetadata())
    }

    @Test
    fun explicitReentryIsMemoryOnlyAndAReopenedStoreStillRequestsReentry() {
        val ciphertext = record.readBytes()
        keystore().deleteEntry(alias)
        val presentation = RecordingPresentation()
        val results = mutableListOf<CredentialReadResult>()
        val request = CredentialAccess(store()).read(id, presentation) { results.add(it) }!!
        assertTrue(request.useForThisAttempt(replacement))
        val result = results.single() as CredentialReadResult.Available
        assertEquals(CredentialReadResult.Origin.REENTERED_FOR_ATTEMPT, result.origin)
        assertArrayEquals(replacement, result.plaintext)
        result.plaintext.fill(0)
        assertFalse(request.cancel())
        assertFalse(keystore().containsAlias(alias))
        assertArrayEquals(ciphertext, record.readBytes())
        assertEquals(metadata, snapshotMetadata())
        val reopenedRequest = CredentialAccess(store()).read(id, presentation) { results.add(it) }!!
        assertEquals(2, presentation.requests.size)
        assertTrue(reopenedRequest.cancel())
        assertSame(CredentialReadResult.Cancelled, results.last())
    }

    @Test
    fun injectedAndroidInvalidationExceptionUsesTheSameReentryContractWithoutReset() {
        val ciphertext = record.readBytes()
        // Deterministic Android provider-exception path, NOT a claim of real biometric invalidation.
        val invalidated = EncryptedSecretStore(
            SecretFiles(record.parentFile!!, AndroidSecretFileIo),
            SecretRecordCipher { throw KeyPermanentlyInvalidatedException() },
        )
        val presentation = RecordingPresentation()
        val results = mutableListOf<CredentialReadResult>()
        CredentialAccess(invalidated).read(id, presentation) { results.add(it) }
        assertEquals(SecretStoreException.Reason.KEY_UNAVAILABLE, presentation.requests.single().reason)
        assertTrue(presentation.requests.single().cancel())
        assertSame(CredentialReadResult.Cancelled, results.single())
        assertArrayEquals(ciphertext, record.readBytes())
        assertEquals(metadata, snapshotMetadata())
        assertArrayEquals(secret, store().read(id)) // Injected failure did not delete even the test key.
    }

    @Test
    fun tamperingUnsupportedVersionAndAbsenceDoNotRequestKeyLossReentry() {
        val original = record.readBytes()
        val presentation = RecordingPresentation()
        val results = mutableListOf<CredentialReadResult>()
        val tampered = original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        record.writeBytes(tampered)
        assertNull(CredentialAccess(store()).read(id, presentation) { results.add(it) })
        assertEquals(SecretStoreException.Reason.INVALID_RECORD, (results.last() as CredentialReadResult.Failed).reason)
        assertArrayEquals(tampered, record.readBytes())
        val future = original.copyOf().also { it[4] = 2 }
        record.writeBytes(future)
        assertNull(CredentialAccess(store()).read(id, presentation) { results.add(it) })
        assertEquals(SecretStoreException.Reason.UNSUPPORTED_VERSION, (results.last() as CredentialReadResult.Failed).reason)
        assertArrayEquals(future, record.readBytes())
        assertNull(CredentialAccess(store()).read(SecretId("absent"), presentation) { results.add(it) })
        assertSame(CredentialReadResult.Missing, results.last())
        assertTrue(presentation.requests.isEmpty())
        assertEquals(metadata, snapshotMetadata())
    }

    private fun snapshotMetadata(): Metadata {
        // Open/migrate the v2 fixture through the current real helper in this isolated Context.
        val helper = DatabaseHandler(context, arrayListOf(), arrayListOf())
        val hosts: List<List<String?>>
        val scripts: List<List<String?>>
        helper.use {
            val db = helper.readableDatabase
            context.assertDatabasePath(db, "contactsManager")
            assertEquals(DatabaseHandler.DATABASE_VERSION, db.version)
            hosts = rows(db, "contacts")
            scripts = rows(db, "scripts")
        }
        val layouts = File(root, "files").listFiles()!!.associate { it.name to it.readBytes().toList() }
        return Metadata(hosts, scripts, preferences.all.toMap(), layouts)
    }

    private fun rows(db: SQLiteDatabase, table: String): List<List<String?>> =
        db.rawQuery("SELECT * FROM $table ORDER BY id", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { cursor.getString(it) }) }
        }

    private data class Metadata(
        val hosts: List<List<String?>>,
        val scripts: List<List<String?>>,
        val preferences: Map<String, *>,
        val layouts: Map<String, List<Byte>>,
    )

    private class RecordingPresentation : CredentialReentryHandler {
        val requests = mutableListOf<CredentialReentryRequest>()
        override fun requestReentry(request: CredentialReentryRequest) {
            requests.add(request)
        }
    }
}
