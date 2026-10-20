package com.stripe1.xmouse.secrets

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceManager
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.stripe1.xmouse.SettingsActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PrivateKeyImportAndroidTest {
    private val target get() = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun realProviderRsaAndEcdsaEncryptedAndPlainImportsSurviveRemovalAndRevocation() {
        listOf(KeyPair.RSA, KeyPair.ECDSA).forEach { type ->
            listOf(byteArrayOf(), "NOT_REAL_fixture_passphrase".toByteArray()).forEach { phrase -> fixture { f ->
                val bytes = key(type, phrase)
                f.preferences.edit(commit = true) { putString("pref_key_passphrase", phrase.toString(Charsets.UTF_8)) }
                val before = f.preferences.all.filterKeys { it != AndroidPrivateKeys.LEGACY_PATH }
                val uri = document(bytes)
                try {
                    f.importProvider(uri, phrase)
                    val ref = f.reference()
                    assertNotNull(ref)
                    assertNull(f.preferences.getString(AndroidPrivateKeys.LEGACY_PATH, null))
                    before.forEach { (name, value) -> assertEquals(value, f.preferences.all[name]) }
                    assertArrayEquals(bytes, f.store().read(SecretId(ref!!)))
                    removeDocument(uri)
                    expect<Exception> { target.contentResolver.openInputStream(uri)?.close() }
                    f.assertUsable(phrase)
                    f.records().forEach { record ->
                        val ciphertext = record.readBytes().toString(Charsets.ISO_8859_1)
                        assertFalse(ciphertext.contains("BEGIN RSA PRIVATE KEY"))
                        assertFalse(ciphertext.contains("BEGIN EC PRIVATE KEY"))
                        assertFalse(ciphertext.contains("NOT_REAL_fixture_passphrase"))
                    }
                    assertTrue(File(f.context.noBackupFilesDir, "secrets").canonicalPath.startsWith(f.root.canonicalPath))
                } finally { removeDocument(uri); bytes.fill(0) }
            } }
        }
    }

    @Test fun providerInvalidOversizedAndUnavailableSourcesPreserveCurrentUsableKey() = fixture { f ->
        f.importBytes(key())
        val before = f.preferences.all
        val original = f.reference()
        val invalid = document("NOT_REAL_invalid_private_key".toByteArray())
        val oversize = document(byteArrayOf(), oversize = true)
        val removed = document(key()).also(::removeDocument)
        try {
            assertEquals(PrivateKeyImportException.Reason.INVALID_KEY,
                expect<PrivateKeyImportException> { f.importProvider(invalid) }.reason)
            assertEquals(PrivateKeyImportException.Reason.TOO_LARGE,
                expect<PrivateKeyImportException> { f.importProvider(oversize) }.reason)
            expect<Exception> { f.importProvider(removed) }
            assertEquals(before, f.preferences.all)
            assertEquals(original, f.reference())
            f.assertUsable()
        } finally { removeDocument(invalid); removeDocument(oversize) }
    }

    @Test fun readableLegacyKeysValidateAndEncryptBeforeRemovingOnlyThePreferencePath() {
        listOf(byteArrayOf(), "NOT_REAL_legacy_phrase".toByteArray()).forEach { phrase -> fixture { f ->
            val bytes = key(KeyPair.ECDSA, phrase)
            val legacy = File(f.root, "NOT_REAL_synthetic_legacy.pem").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
            f.preferences.edit(commit = true) { putString(AndroidPrivateKeys.LEGACY_PATH, legacy.path) }
            val repository = f.repository()
            repository.importLegacy(repository.beginImport(), phrase)
            assertNull(f.preferences.getString(AndroidPrivateKeys.LEGACY_PATH, null))
            assertArrayEquals(bytes, legacy.readBytes())
            assertArrayEquals(bytes, f.store().read(SecretId(f.reference()!!)))
            f.assertChoices()
            f.assertUsable(phrase)
        } }
    }

    @Test fun inaccessibleLegacyPathRetainsContextUntilExplicitValidatedProviderReimport() = fixture { f ->
        val before = f.preferences.all
        val repository = f.repository()
        assertEquals(PrivateKeyImportException.Reason.READ_FAILURE,
            expect<PrivateKeyImportException> { repository.importLegacy(repository.beginImport(), byteArrayOf()) }.reason)
        assertEquals(before, f.preferences.all)
        assertNull(f.reference())
        val uri = document(key())
        try {
            f.importProvider(uri)
            assertNotNull(f.reference())
            f.assertChoices()
            f.assertUsable()
        } finally { removeDocument(uri) }
    }

    @Test fun invalidPassphraseLeavesExistingKeyAndGlobalPassphraseChoiceUntouched() = fixture { f ->
        f.importBytes(key())
        val before = f.preferences.all
        val encrypted = key(KeyPair.RSA, "NOT_REAL_correct".toByteArray())
        listOf(byteArrayOf(), "NOT_REAL_incorrect".toByteArray()).forEach { phrase ->
            assertEquals(PrivateKeyImportException.Reason.PASSPHRASE_REQUIRED_OR_INVALID,
                expect<PrivateKeyImportException> { f.importBytes(encrypted, phrase) }.reason)
            assertEquals(before, f.preferences.all)
            f.assertUsable()
        }
    }

    @Test fun realStoreWriteAndReadbackFaultsNeverReplaceThePreviousReference() = fixture { f ->
        f.importBytes(key())
        val before = f.preferences.all
        val failing = EncryptedSecretStore(SecretFiles(File(f.context.noBackupFilesDir, "secrets"),
            object : SecretFileIo by AndroidSecretFileIo {
                override fun replace(source: File, destination: File) = throw IOException()
            }), SecretRecordCipher(AndroidSecretKeySource(f.alias)))
        expect<SecretStoreException> { f.importBytes(key(), store = failing) }
        repeat(4) { mode ->
            var written = false
            val backing = f.store()
            val fault = object : SecretStore by backing {
                override fun write(id: SecretId, plaintext: ByteArray) {
                    backing.write(id, plaintext)
                    written = true
                    if (mode == 3) throw SecretStoreException(SecretStoreException.Reason.STORAGE_FAILURE)
                }
                override fun read(id: SecretId): ByteArray? = if (!written) backing.read(id) else when (mode) {
                    0 -> null
                    1 -> "NOT_REAL_wrong_readback".toByteArray()
                    else -> throw SecretStoreException(SecretStoreException.Reason.STORAGE_FAILURE)
                }
            }
            expect<Exception> { f.importBytes(key(), store = fault) }
            assertEquals(before, f.preferences.all)
            f.assertUsable()
        }
    }

    @Test fun preferencesCommitFalseBeforeAfterAndCacheOnlyUpdatesRestoresPriorUsableKey() {
        repeat(3) { mode -> fixture { f ->
            f.importBytes(key())
            val before = f.preferences.all
            val fault = FailingPreferences(f.preferences, mode)
            assertEquals(PrivateKeyImportException.Reason.STORAGE_FAILURE,
                expect<PrivateKeyImportException> { f.importBytes(key(), preferences = fault) }.reason)
            assertEquals(before, f.preferences.all)
            assertEquals(before, fault.all)
            f.assertUsable()
        } }
    }

    @Test fun unavailableKeystoreCannotBeResetOrBypassedByImportingAFreshId() = fixture { f ->
        f.importBytes(key())
        val before = f.preferences.all
        val records = f.records().associate { it.name to it.readBytes().toList() }
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(f.alias) }
        assertEquals(SecretStoreException.Reason.KEY_UNAVAILABLE,
            expect<SecretStoreException> { f.importBytes(key()) }.reason)
        assertEquals(before, f.preferences.all)
        assertEquals(records, f.records().associate { it.name to it.readBytes().toList() })
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(f.alias))
    }

    @Test fun cancellationAfterDurablePreparationAndSupersededConfigurationCannotPublish() = fixture { f ->
        f.importBytes(key())
        val before = f.preferences.all
        val backing = f.store()
        lateinit var attempt: PrivateKeyImportAttempt
        val fault = object : SecretStore by backing {
            override fun write(id: SecretId, plaintext: ByteArray) { backing.write(id, plaintext); attempt.cancel() }
        }
        val repository = f.repository(fault)
        attempt = repository.beginImport()
        assertEquals(PrivateKeyImportException.Reason.CANCELLED,
            expect<PrivateKeyImportException> { repository.importKey(attempt, ByteArrayInputStream(key()), byteArrayOf()) }.reason)
        assertEquals(before, f.preferences.all)
        f.assertUsable()
        val oldAttempt = f.repository().beginImport()
        f.importBytes(key())
        val newer = f.reference()
        assertEquals(PrivateKeyImportException.Reason.SOURCE_CHANGED,
            expect<PrivateKeyImportException> { f.repository().importKey(oldAttempt, ByteArrayInputStream(key()), byteArrayOf()) }.reason)
        assertEquals(newer, f.reference())
        f.assertUsable()
    }

    @Test fun protectedPassphraseTakesPrecedenceAndUnreadableProtectedValueNeverFallsBack() = fixture { f ->
        val id = SecretId("synthetic_passphrase")
        val phrase = "NOT_REAL_protected_phrase".toByteArray()
        f.store().write(id, phrase)
        f.preferences.edit(commit = true) { putString(LegacyCredentialReferences.GLOBAL_PASSPHRASE, id.value) }
        assertArrayEquals(phrase, AndroidPrivateKeys.readConfiguredPassphrase(f.preferences, f.store()))
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(f.alias) }
        expect<SecretStoreException> { AndroidPrivateKeys.readConfiguredPassphrase(f.preferences, f.store()) }
        assertEquals("NOT_REAL_retained_choice", f.preferences.getString("pref_key_passphrase", null))
    }

    @Test fun actualSettingsDispatchesSystemPickerImportsProviderAndRetainsKeyAfterRemoval() = uiFixture { preferences ->
        val uri = document(key())
        val monitor = pickerMonitor(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri)))
        try {
            ActivityScenario.launch<SettingsActivity>(Intent(target, SettingsActivity::class.java)).use { scenario ->
                scenario.onActivity { activity ->
                    val pref = activity.findPreference(AndroidPrivateKeys.LEGACY_PATH)
                    assertTrue(pref.onPreferenceClickListener.onPreferenceClick(pref))
                }
                waitFor { preferences.contains(LegacyCredentialReferences.GLOBAL_PRIVATE_KEY) }
                awaitKeyWorker(scenario)
                assertEquals(1, monitor.hits)
                scenario.onActivity { activity ->
                    assertEquals("Imported SSH private key", activity.findPreference(AndroidPrivateKeys.LEGACY_PATH).summary)
                }
                removeDocument(uri)
                val bytes = AndroidPrivateKeys.open(target).readForAuthentication(byteArrayOf())
                try { assertIdentity(bytes, byteArrayOf()) } finally { bytes.fill(0) }
                assertTrue(preferences.getBoolean("pref_usekeyauth", false))
                assertEquals("", preferences.getString("pref_key_passphrase", null))
                assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
                    target.checkSelfPermission("android.permission.READ_EXTERNAL_STORAGE"))
                assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
                    target.checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE"))
            }
        } finally { instrumentation.removeMonitor(monitor); removeDocument(uri) }
    }

    @Test fun actualSettingsCancelledAndRecreatedPickerResultsKeepPreviousKeyAndLegacyReimportGuidance() = uiFixture { preferences ->
        val repository = AndroidPrivateKeys.open(target)
        repository.importKey(repository.beginImport(), ByteArrayInputStream(key()), byteArrayOf())
        val previous = preferences.getString(LegacyCredentialReferences.GLOBAL_PRIVATE_KEY, null)
        val cancelled = pickerMonitor(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
        try {
            ActivityScenario.launch<SettingsActivity>(Intent(target, SettingsActivity::class.java)).use { scenario ->
                scenario.onActivity { activity ->
                    val pref = activity.findPreference(AndroidPrivateKeys.LEGACY_PATH)
                    pref.onPreferenceClickListener.onPreferenceClick(pref)
                }
                instrumentation.waitForIdleSync()
                assertEquals(1, cancelled.hits)
                assertEquals(previous, preferences.getString(LegacyCredentialReferences.GLOBAL_PRIVATE_KEY, null))
                instrumentation.removeMonitor(cancelled)
                val pending = pickerMonitor(null)
                val uri = document(key())
                try {
                    var request = -1
                    scenario.onActivity { activity ->
                        val pref = activity.findPreference(AndroidPrivateKeys.LEGACY_PATH)
                        pref.onPreferenceClickListener.onPreferenceClick(pref)
                        val controller = SettingsActivity::class.java.getDeclaredField("keyImport").apply { isAccessible = true }.get(activity)
                        request = PrivateKeySettingsController::class.java.getDeclaredField("requestCode").apply { isAccessible = true }.getInt(controller)
                    }
                    scenario.recreate()
                    scenario.onActivity { activity ->
                        SettingsActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType, Intent::class.java).apply { isAccessible = true }
                            .invoke(activity, request, Activity.RESULT_OK, Intent().setData(uri))
                    }
                    assertEquals(previous, preferences.getString(LegacyCredentialReferences.GLOBAL_PRIVATE_KEY, null))
                } finally { instrumentation.removeMonitor(pending); removeDocument(uri) }
            }
            preferences.edit(commit = true) {
                remove(LegacyCredentialReferences.GLOBAL_PRIVATE_KEY)
                putString(AndroidPrivateKeys.LEGACY_PATH, "/NOT_REAL_SYNTHETIC_MISSING/key")
            }
            ActivityScenario.launch<SettingsActivity>(Intent(target, SettingsActivity::class.java)).use { scenario ->
                scenario.onActivity { activity ->
                    assertTrue(activity.findPreference(AndroidPrivateKeys.LEGACY_PATH).summary.toString().contains("Tap"))
                    assertFalse(activity.findPreference(AndroidPrivateKeys.LEGACY_PATH).summary.toString().contains("/NOT_REAL"))
                }
                assertEquals("/NOT_REAL_SYNTHETIC_MISSING/key", preferences.getString(AndroidPrivateKeys.LEGACY_PATH, null))
            }
        } finally { instrumentation.removeMonitor(cancelled) }
    }

    @Test fun actualSettingsInvalidDocumentResultKeepsPreviousUsableKey() = uiFixture { preferences ->
        val repository = AndroidPrivateKeys.open(target)
        repository.importKey(repository.beginImport(), ByteArrayInputStream(key()), byteArrayOf())
        val previous = preferences.all
        val uri = document("NOT_REAL_invalid_document".toByteArray())
        val monitor = pickerMonitor(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri)))
        try {
            ActivityScenario.launch<SettingsActivity>(Intent(target, SettingsActivity::class.java)).use { scenario ->
                scenario.onActivity { activity ->
                    val pref = activity.findPreference(AndroidPrivateKeys.LEGACY_PATH)
                    pref.onPreferenceClickListener.onPreferenceClick(pref)
                }
                instrumentation.waitForIdleSync() // Deliver the platform activity result before the barrier.
                awaitKeyWorker(scenario)
                previous.forEach { (name, value) -> assertEquals(value, preferences.all[name]) }
                val bytes = repository.readForAuthentication(byteArrayOf())
                try { assertIdentity(bytes, byteArrayOf()) } finally { bytes.fill(0) }
                assertEquals(1, monitor.hits)
            }
        } finally { instrumentation.removeMonitor(monitor); removeDocument(uri) }
    }

    private fun pickerMonitor(result: Instrumentation.ActivityResult?): Instrumentation.ActivityMonitor =
        instrumentation.addMonitor(IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); addDataType("*/*")
        }, result, true)

    private fun awaitKeyWorker(scenario: ActivityScenario<SettingsActivity>) {
        lateinit var worker: java.util.concurrent.ExecutorService
        scenario.onActivity { activity ->
            val controller = SettingsActivity::class.java.getDeclaredField("keyImport").apply { isAccessible = true }.get(activity)
            worker = PrivateKeySettingsController::class.java.getDeclaredField("worker").apply { isAccessible = true }
                .get(controller) as java.util.concurrent.ExecutorService
        }
        worker.submit {}.get(10, java.util.concurrent.TimeUnit.SECONDS)
        instrumentation.waitForIdleSync()
    }

    private fun document(bytes: ByteArray, oversize: Boolean = false): Uri {
        val id = "synthetic-${UUID.randomUUID()}"
        providerCall("fixture.put", id, Bundle().apply { putByteArray("bytes", bytes); putBoolean("oversize", oversize) })
        return SyntheticKeyDocumentsProvider.uri(id)
    }
    private fun removeDocument(uri: Uri) = providerCall("fixture.remove", android.provider.DocumentsContract.getDocumentId(uri), null)
    private fun providerCall(method: String, id: String, extras: Bundle?) {
        target.contentResolver.call("content://${SyntheticKeyDocumentsProvider.AUTHORITY}.control".toUri(), method, id, extras)
    }

    private inner class Fixture : Closeable {
        private val unique = UUID.randomUUID().toString()
        val root = File(target.noBackupFilesDir, "key-import-test-$unique")
        val alias = AndroidSecretStore.KEY_ALIAS + ".key-import-test." + unique
        private val name = "key-import-test-$unique"
        val preferences = target.getSharedPreferences(name, Context.MODE_PRIVATE)
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = File(root, "no_backup")
        }
        init {
            preferences.edit(commit = true) {
                putBoolean("pref_usekeyauth", true)
                putString("pref_key_passphrase", "NOT_REAL_retained_choice")
                putString("hostPreferenceList", "42")
                putString("setting_host", "NOT_REAL_desktop.local")
                putString(AndroidPrivateKeys.LEGACY_PATH, "/NOT_REAL_SYNTHETIC_MISSING/key")
            }
        }
        fun store() = AndroidSecretStore.open(context, alias)
        fun repository(store: SecretStore = store()) = AndroidPrivateKeys.open(preferences, store)
        fun importBytes(bytes: ByteArray, phrase: ByteArray = byteArrayOf(), store: SecretStore = store(), preferences: SharedPreferences = this.preferences) {
            val repository = AndroidPrivateKeys.open(preferences, store)
            repository.importKey(repository.beginImport(), ByteArrayInputStream(bytes), phrase)
        }
        fun importProvider(uri: Uri, phrase: ByteArray = byteArrayOf()) {
            val repository = repository()
            val attempt = repository.beginImport()
            target.contentResolver.openInputStream(uri)!!.let { repository.importKey(attempt, it, phrase) }
        }
        fun reference() = preferences.getString(LegacyCredentialReferences.GLOBAL_PRIVATE_KEY, null)
        fun records() = File(context.noBackupFilesDir, "secrets").listFiles()!!.filter { it.name.endsWith(SecretFiles.SUFFIX) }
        fun assertUsable(phrase: ByteArray = byteArrayOf()) {
            val bytes = repository().readForAuthentication(phrase)
            try { assertIdentity(bytes, phrase) } finally { bytes.fill(0) }
        }
        fun assertChoices() {
            assertTrue(preferences.getBoolean("pref_usekeyauth", false))
            assertEquals("NOT_REAL_retained_choice", preferences.getString("pref_key_passphrase", null))
            assertEquals("42", preferences.getString("hostPreferenceList", null))
            assertEquals("NOT_REAL_desktop.local", preferences.getString("setting_host", null))
        }
        override fun close() {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            target.deleteSharedPreferences(name)
            if (root.exists()) root.walkBottomUp().forEach { assertTrue(it.delete()) }
        }
    }

    private inline fun fixture(action: (Fixture) -> Unit) = Fixture().use(action)

    /** Only on the disposable test AVD. Preserve pre-existing defaults/key artifacts if present. */
    private fun uiFixture(action: (SharedPreferences) -> Unit) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(target)
        val original = preferences.all.toMap()
        val directory = File(target.noBackupFilesDir, "secrets")
        val originalFiles = directory.list()?.toSet() ?: emptySet()
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val hadAlias = keystore.containsAlias(AndroidSecretStore.KEY_ALIAS)
        try {
            preferences.edit(commit = true) {
                remove(LegacyCredentialReferences.GLOBAL_PRIVATE_KEY)
                remove(LegacyCredentialReferences.GLOBAL_PASSPHRASE)
                remove(AndroidPrivateKeys.LEGACY_PATH)
                putBoolean("pref_usekeyauth", true)
                putString("pref_key_passphrase", "")
            }
            action(preferences)
        } finally {
            preferences.edit(commit = true) {
                clear()
                original.forEach { (name, value) -> when (value) {
                    is String -> putString(name, value)
                    is Boolean -> putBoolean(name, value)
                    is Int -> putInt(name, value)
                    is Long -> putLong(name, value)
                    is Float -> putFloat(name, value)
                    is Set<*> -> putStringSet(name, value.filterIsInstance<String>().toSet())
                } }
            }
            directory.listFiles()?.filter { it.name !in originalFiles }?.forEach { assertTrue(it.delete()) }
            if (originalFiles.isEmpty() && directory.exists()) assertTrue(directory.delete())
            if (!hadAlias) keystore.deleteEntry(AndroidSecretStore.KEY_ALIAS)
        }
    }

    private class FailingPreferences(private val backing: SharedPreferences, private val mode: Int) : SharedPreferences by backing {
        private var failed = false
        private val cache = mutableMapOf<String, String?>()
        override fun getAll(): MutableMap<String, *> = backing.all.toMutableMap().apply {
            cache.forEach { (key, value) -> if (value == null) remove(key) else put(key, value) }
        }
        override fun getString(key: String?, defValue: String?): String? =
            if (cache.containsKey(key)) cache[key] ?: defValue else backing.getString(key, defValue)
        override fun edit(): SharedPreferences.Editor {
            val editor = backing.edit()
            val changes = mutableMapOf<String, String?>()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                    changes[key!!] = value; editor.putString(key, value); return this
                }
                override fun remove(key: String?): SharedPreferences.Editor {
                    changes[key!!] = null; editor.remove(key); return this
                }
                override fun commit(): Boolean {
                    if (!failed) {
                        failed = true
                        if (mode == 1) check(editor.commit())
                        if (mode == 2) cache.putAll(changes)
                        return false
                    }
                    cache.filterKeys { it !in changes }.forEach { (key, value) ->
                        if (value == null) editor.remove(key) else editor.putString(key, value)
                    }
                    val result = editor.commit()
                    if (result) cache.clear()
                    return result
                }
            }
        }
    }

    private fun key(type: Int = KeyPair.RSA, phrase: ByteArray = byteArrayOf()): ByteArray {
        val key = KeyPair.genKeyPair(JSch(), type, if (type == KeyPair.RSA) 1024 else 256)
        return try { ByteArrayOutputStream().also { key.writePrivateKey(it, phrase.takeIf { it.isNotEmpty() }) }.toByteArray() }
        finally { key.dispose() }
    }
    private fun assertIdentity(bytes: ByteArray, phrase: ByteArray) {
        val jsch = JSch()
        jsch.addIdentity("NOT_REAL_synthetic", bytes, null, phrase)
        try {
            val identity = jsch.identityRepository.identities.single() as com.jcraft.jsch.Identity
            assertFalse(identity.isEncrypted)
            assertNotNull(identity.getSignature(byteArrayOf(1, 2, 3)))
        } finally { jsch.removeAllIdentity() }
    }
    private fun waitFor(condition: () -> Boolean) {
        val end = System.nanoTime() + 10_000_000_000L
        while (!condition() && System.nanoTime() < end) Thread.sleep(25)
        assertTrue("Timed out waiting for key import", condition())
        instrumentation.waitForIdleSync()
    }
    private inline fun <reified T : Throwable> expect(action: () -> Unit): T {
        try { action() } catch (failure: Throwable) { if (failure is T) return failure else throw failure }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }
}
