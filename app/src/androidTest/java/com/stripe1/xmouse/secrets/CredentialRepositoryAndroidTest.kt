package com.stripe1.xmouse.secrets

import android.app.AlertDialog
import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import android.os.Bundle
import android.preference.EditTextPreference
import android.preference.PreferenceManager
import android.widget.EditText
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.stripe1.xmouse.DatabaseHandler
import com.stripe1.xmouse.ListEditActivity
import com.stripe1.xmouse.MainActivity
import com.stripe1.xmouse.R
import com.stripe1.xmouse.SettingsActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real SQLite, SharedPreferences, Keystore, production repository, and existing Activities. */
@RunWith(AndroidJUnit4::class)
class CredentialRepositoryAndroidTest {
    private val target get() = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun v2DatabaseAndBothPreferenceSourcesMigrateBeforeWritesWithoutChangingNonsecretChoices() = fixture { f ->
        val scripts = f.rows("scripts")
        val before = f.rows("contacts").map { it.take(6) }
        val choices = f.preferences.all.filterKeys { it !in setOf("setting_pass", "pref_key_passphrase") }
        assertFalse(f.repository.isWritable)
        assertTrue(f.repository.prepare())
        assertEquals(before, f.rows("contacts").map { it.take(6) })
        assertEquals(scripts, f.rows("scripts"))
        choices.forEach { (key, value) -> assertEquals(value, f.preferences.all[key]) }
        assertEquals(42L, f.repository.selectedHost().id)
        assertArrayEquals(f.passwords.getValue(42).toByteArray(), f.repository.readPassword(f.repository.selectedHost()))
        assertArrayEquals("NOT_REAL_default".toByteArray(), f.store.read(SecretId(f.preferences.getString(LegacyCredentialReferences.DEFAULT_PASSWORD, null)!!)))
        f.assertNoPlaintext()
        f.reopen()
        assertTrue(f.repository.prepare())
        assertEquals(2, f.repository.hosts().size)
        assertEquals(scripts, f.rows("scripts"))
    }

    @Test fun hostAddEditReopenAndIdReuseNeverOverwriteAnotherCredentialOrStaleHost() = fixture { f ->
        assertTrue(f.repository.prepare())
        val original = f.repository.hosts().first()
        val originalBytes = f.repository.readPassword(original)
        val id = f.repository.saveHost(null, "added", "new.local", "user", "2222", "NOT_REAL_added".toByteArray())
        val added = f.repository.hosts().single { it.id == id }
        f.repository.saveHost(added, "edited", "new.local", "user", "2200", "NOT_REAL_edited".toByteArray())
        val edited = f.repository.hosts().single { it.id == id }
        assertNotEquals(added.passwordReference, edited.passwordReference)
        assertArrayEquals("NOT_REAL_added".toByteArray(), f.store.read(SecretId(added.passwordReference!!)))
        f.repository.deleteHost(edited)
        val reused = f.repository.saveHost(null, "reused", "reuse.local", "user", "22", "NOT_REAL_reused".toByteArray())
        assertEquals(id, reused)
        expect<CredentialRepositoryException> { f.repository.saveHost(added, "stale", "wrong.local", "wrong", "22", byteArrayOf()) }
        assertArrayEquals(originalBytes, f.repository.readPassword(original))
        f.reopen(); assertTrue(f.repository.prepare())
        assertArrayEquals("NOT_REAL_reused".toByteArray(), f.repository.readPassword(f.repository.hosts().single { it.id == reused }))
        f.assertNoPlaintext()
    }

    @Test fun defaultAndGlobalPassphraseSavesAreProtectedAndIndependentOfSelectedHostAndAuthChoice() = fixture { f ->
        assertTrue(f.repository.prepare())
        val host = f.repository.selectedHost()
        f.repository.saveDefaultPassword("NOT_REAL_new_default".toByteArray())
        f.repository.savePassphrase("NOT_REAL_new_phrase".toByteArray())
        f.repository.selectHost(7)
        assertEquals("7", f.preferences.getString("hostPreferenceList", null))
        assertTrue(f.preferences.getBoolean("pref_usekeyauth", false))
        assertArrayEquals(f.passwords.getValue(42).toByteArray(), f.repository.readPassword(host))
        f.preferences.edit(commit = true) { putString("hostPreferenceList", "99999"); putBoolean("pref_usekeyauth", false) }
        f.reopen(); assertTrue(f.repository.prepare())
        assertArrayEquals("NOT_REAL_new_default".toByteArray(), f.repository.readPassword(f.repository.selectedHost()))
        assertArrayEquals("NOT_REAL_new_phrase".toByteArray(), f.repository.readPassphrase())
        assertFalse(f.preferences.getBoolean("pref_usekeyauth", true))
        f.assertNoPlaintext()
    }

    @Test fun unsuccessfulMigrationBlocksEveryCredentialSaveButLeavesConfigurationAvailable() = fixture { f ->
        val before = f.rows("contacts")
        val prefs = f.preferences.all
        val broken = object : SecretStore by f.store {
            override fun write(id: SecretId, plaintext: ByteArray) { throw SecretStoreException(SecretStoreException.Reason.STORAGE_FAILURE) }
        }
        val repository = CredentialRepository(f.helper, f.preferences, broken)
        assertFalse(repository.prepare())
        assertEquals(2, repository.hosts().size)
        assertEquals(42L, repository.selectedHost().id)
        expect<CredentialRepositoryException> { repository.saveHost(null, "new", "new", "u", "22", byteArrayOf()) }
        expect<CredentialRepositoryException> { repository.saveDefaultPassword(byteArrayOf()) }
        expect<CredentialRepositoryException> { repository.savePassphrase(byteArrayOf()) }
        expect<CredentialRepositoryException> { repository.readPassword(repository.selectedHost()) }
        assertEquals(before, f.rows("contacts")); assertEquals(prefs, f.preferences.all)
    }

    @Test fun failedReplacementReadbackCannotPublishNewHostOrPreferenceReferences() = fixture { f ->
        assertTrue(f.repository.prepare())
        val before = f.rows("contacts")
        val preferences = f.preferences.all
        var written: SecretId? = null
        val broken = object : SecretStore by f.store {
            override fun write(id: SecretId, plaintext: ByteArray) { f.store.write(id, plaintext); written = id }
            override fun read(id: SecretId): ByteArray? = if (id == written) null else f.store.read(id)
        }
        val repository = CredentialRepository(f.helper, f.preferences, broken)
        assertTrue(repository.prepare())
        expect<CredentialRepositoryException> { repository.saveHost(null, "new", "new", "u", "22", "NOT_REAL_new".toByteArray()) }
        expect<CredentialRepositoryException> { repository.savePassphrase("NOT_REAL_phrase".toByteArray()) }
        assertEquals(before, f.rows("contacts")); assertEquals(preferences, f.preferences.all)
        f.assertNoPlaintext()
    }

    @Test fun deletedDeviceKeyRequestsExplicitOneAttemptEntryAndCancellationWithoutChangingAnyData() = fixture { f ->
        assertTrue(f.repository.prepare())
        f.preferences.edit(commit = true) { putBoolean("pref_usekeyauth", false) }
        val before = f.rows("contacts"); val prefs = f.preferences.all; val files = f.encryptedFiles()
        f.deleteKey()
        assertFalse(f.repository.prepare())
        val first = authentication(f.repository)
        val request = first.request.get()!!
        assertEquals(CredentialKind.PASSWORD, first.kind.get())
        val entered = "NOT_REAL_once".toByteArray()
        assertTrue(request.useForThisAttempt(entered)); entered.fill(0)
        assertTrue(first.done.await(10, TimeUnit.SECONDS))
        assertArrayEquals("NOT_REAL_once".toByteArray(), first.result.get()!!.password)
        first.result.get()!!.close()
        assertFalse(request.cancel())
        val second = authentication(f.repository)
        assertTrue(second.request.get()!!.cancel())
        assertTrue(second.done.await(10, TimeUnit.SECONDS)); assertNull(second.result.get())
        expect<CredentialRepositoryException> { f.repository.saveHost(null, "new", "new", "u", "22", byteArrayOf()) }
        assertEquals(before, f.rows("contacts")); assertEquals(prefs, f.preferences.all); assertEquals(files, f.encryptedFiles())
        assertFalse(f.keystore().containsAlias(f.alias))
    }

    @Test fun globalKeyAndProtectedPassphraseReachAuthenticationWithoutRawPathFallback() = fixture { f ->
        assertTrue(f.repository.prepare())
        val phrase = f.repository.readPassphrase()
        val key = KeyPair.genKeyPair(JSch(), KeyPair.ECDSA, 256)
        val bytes = try { ByteArrayOutputStream().also { key.writePrivateKey(it, phrase) }.toByteArray() } finally { key.dispose() }
        val keys = AndroidPrivateKeys.open(f.preferences, f.store)
        keys.importKey(keys.beginImport(), ByteArrayInputStream(bytes), phrase)
        val auth = authentication(f.repository, expectPrompt = false)
        assertTrue(auth.done.await(10, TimeUnit.SECONDS))
        auth.result.get()!!.use { material ->
            assertTrue(material.useKey); assertArrayEquals(bytes, material.privateKey); assertArrayEquals(phrase, material.passphrase)
            val jsch = JSch(); jsch.addIdentity("synthetic", material.privateKey, null, material.passphrase)
            try { assertNotNull((jsch.identityRepository.identities.single() as com.jcraft.jsch.Identity).getSignature(byteArrayOf(1))) }
            finally { jsch.removeAllIdentity() }
        }
        val protected = f.preferences.getString(LegacyCredentialReferences.GLOBAL_PRIVATE_KEY, null)!!
        File(f.context.noBackupFilesDir, "secrets/$protected.secret").delete()
        val raw = File(f.root, "synthetic-source.pem").apply { writeBytes(bytes) }
        f.preferences.edit(commit = true) { putString(AndroidPrivateKeys.LEGACY_PATH, raw.path) }
        val failed = authentication(f.repository, expectPrompt = false)
        assertTrue(failed.done.await(10, TimeUnit.SECONDS)); assertNull(failed.result.get()); assertNotNull(failed.error.get())
        assertEquals(protected, f.preferences.getString(LegacyCredentialReferences.GLOBAL_PRIVATE_KEY, null))
        assertTrue(f.preferences.getBoolean("pref_usekeyauth", false))
        phrase.fill(0); bytes.fill(0)
    }

    @Test fun globalKeyLossNeedsSeparatePassphraseAndPrivateKeyOneAttemptConsentsWithoutPersistence() = fixture { f ->
        assertTrue(f.repository.prepare())
        val phrase = f.repository.readPassphrase()
        val key = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 1024)
        val bytes = try { ByteArrayOutputStream().also { key.writePrivateKey(it, phrase) }.toByteArray() } finally { key.dispose() }
        val keys = AndroidPrivateKeys.open(f.preferences, f.store)
        keys.importKey(keys.beginImport(), ByteArrayInputStream(bytes), phrase)
        val files = f.encryptedFiles(); val preferences = f.preferences.all; val rows = f.rows("contacts")
        f.deleteKey()
        val auth = authentication(f.repository)
        assertEquals(CredentialKind.PASSPHRASE, auth.kind.get())
        assertTrue(auth.request.get()!!.useForThisAttempt(phrase))
        waitFor { auth.kind.get() == CredentialKind.PRIVATE_KEY }
        assertTrue(auth.request.get()!!.useForThisAttempt(bytes))
        assertTrue(auth.done.await(10, TimeUnit.SECONDS))
        auth.result.get()!!.use { assertArrayEquals(bytes, it.privateKey); assertArrayEquals(phrase, it.passphrase) }
        assertEquals(files, f.encryptedFiles()); assertEquals(preferences, f.preferences.all); assertEquals(rows, f.rows("contacts"))
        assertFalse(f.keystore().containsAlias(f.alias)); bytes.fill(0); phrase.fill(0)
    }

    @Test fun absentProtectedRecordNeverFallsBackToRetainedLegacyPreferenceText() = fixture { f ->
        assertTrue(f.repository.prepare())
        f.preferences.edit(commit = true) { putString("hostPreferenceList", "9999"); putBoolean("pref_usekeyauth", false) }
        val reference = f.preferences.getString(LegacyCredentialReferences.DEFAULT_PASSWORD, null)!!
        assertTrue(File(f.context.noBackupFilesDir, "secrets/$reference.secret").delete())
        f.preferences.edit(commit = true) { putString("setting_pass", "NOT_REAL_must_never_fall_back") }
        assertFalse(f.repository.prepare())
        val auth = authentication(f.repository, expectPrompt = false)
        assertTrue(auth.done.await(10, TimeUnit.SECONDS)); assertNull(auth.result.get()); assertNotNull(auth.error.get())
        assertEquals("NOT_REAL_must_never_fall_back", f.preferences.getString("setting_pass", null))
        expect<CredentialRepositoryException> { f.repository.saveDefaultPassword(byteArrayOf()) }
    }

    @Test fun failedPreferencePublicationIsReportedAndRetainsVerifiedOldOrNewCredentialsWithoutPlaintext() = fixture { f ->
        assertTrue(f.repository.prepare())
        val before = f.preferences.getString(LegacyCredentialReferences.GLOBAL_PASSPHRASE, null)!!
        val backing = f.preferences
        val failing = object : SharedPreferences by backing {
            override fun edit(): SharedPreferences.Editor {
                val editor = backing.edit()
                var candidate: String? = null
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        if (key == LegacyCredentialReferences.GLOBAL_PASSPHRASE && value?.startsWith("credential_") == true) candidate = value
                        editor.putString(key, value); return this
                    }
                    override fun remove(key: String?): SharedPreferences.Editor { editor.remove(key); return this }
                    override fun commit(): Boolean { val success = editor.commit(); return if (candidate != null) false else success }
                }
            }
        }
        val repository = CredentialRepository(f.helper, failing, f.store)
        assertTrue(repository.prepare())
        expect<CredentialRepositoryException> { repository.savePassphrase("NOT_REAL_uncertain".toByteArray()) }
        assertFalse(repository.isWritable)
        assertArrayEquals("NOT_REAL_phrase".toByteArray(), f.store.read(SecretId(before)))
        assertArrayEquals("NOT_REAL_uncertain".toByteArray(), f.store.read(SecretId(backing.getString(LegacyCredentialReferences.GLOBAL_PASSPHRASE, null)!!)))
        f.assertNoPlaintext()
    }

    @Test fun actualHostScreenLoadsEditsAddsCancelsAndReopensOnlyProtectedPasswords() = uiFixture { f ->
        ActivityScenario.launch<ListEditActivity>(Intent(target, ListEditActivity::class.java)).use { scenario ->
            barrier()
            scenario.onActivity { it.xMouseManage(0) }
            barrier()
            scenario.onActivity { activity ->
                val dialog = editor(activity)
                assertEquals("Delete", dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString())
                assertEquals("Cancel", dialog.getButton(AlertDialog.BUTTON_NEUTRAL).text.toString())
                val input = dialog.findViewById<EditText>(R.id.new_host_password)
                assertEquals(f.passwords.getValue(7), input.text.toString())
                assertFalse(input.isSaveEnabled)
                enter(input, "NOT_REAL_ui_edit")
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            barrier(); barrier()
            scenario.onActivity { it.xMouseManage(null) }
            barrier()
            scenario.onActivity { activity ->
                val dialog = editor(activity)
                assertEquals("Cancel", dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString())
                assertFalse(dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.visibility == android.view.View.VISIBLE)
                enter(dialog.findViewById(R.id.new_host_alias), "UI added")
                enter(dialog.findViewById(R.id.new_host_ip), "ui.local")
                enter(dialog.findViewById(R.id.new_host_username), "u")
                enter(dialog.findViewById(R.id.new_host_port), "22")
                enter(dialog.findViewById(R.id.new_host_password), "NOT_REAL_ui_add")
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            barrier(); barrier()
            scenario.recreate(); barrier()
            scenario.onActivity { it.xMouseManage(0) }; barrier()
            scenario.onActivity { activity ->
                val dialog = editor(activity)
                assertEquals("NOT_REAL_ui_edit", dialog.findViewById<EditText>(R.id.new_host_password).text.toString())
                enter(dialog.findViewById(R.id.new_host_password), "NOT_REAL_cancelled")
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            }
            barrier()
            assertArrayEquals("NOT_REAL_ui_edit".toByteArray(), f.repository.readPassword(f.repository.hosts().first()))
            assertArrayEquals("NOT_REAL_ui_add".toByteArray(), f.repository.readPassword(f.repository.hosts().last()))
            assertEquals(3, f.repository.hosts().size)
            f.assertNoPlaintext()
        }
    }

    @Test fun actualSettingsRetiresFrameworkSecretPersistenceAndSecureEditorSaveCancelRecreation() = uiFixture { f ->
        ActivityScenario.launch<SettingsActivity>(Intent(target, SettingsActivity::class.java)).use { scenario ->
            barrier()
            scenario.onActivity { activity ->
                val pref = activity.findPreference("pref_key_passphrase")
                assertFalse(pref is EditTextPreference); assertFalse(pref.isPersistent)
                assertEquals("For an encrypted key, set its passphrase before importing. Import never changes this choice.", pref.summary)
                // Even the retained, currently unused connection XML cannot initialize plaintext.
                activity.addPreferencesFromResource(R.xml.ssh_new_conn)
                val fallback = activity.findPreference("setting_pass")
                assertFalse(fallback is EditTextPreference); assertFalse(fallback.isPersistent)
                pref.onPreferenceClickListener.onPreferenceClick(pref)
            }
            barrier()
            scenario.onActivity { activity ->
                val dialog = secretDialog(activity)
                val input = dialog.findViewById<EditText>(android.R.id.edit) ?: findInput(dialog)
                assertEquals("NOT_REAL_phrase", input.text.toString()); assertFalse(input.isSaveEnabled)
                enter(input, "NOT_REAL_ui_phrase"); dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            barrier()
            scenario.onActivity { activity -> activity.findPreference("pref_key_passphrase").let { it.onPreferenceClickListener.onPreferenceClick(it) } }
            barrier()
            scenario.onActivity { activity -> enter(findInput(secretDialog(activity)), "NOT_REAL_cancelled_phrase") }
            scenario.recreate(); barrier()
            assertArrayEquals("NOT_REAL_ui_phrase".toByteArray(), f.repository.readPassphrase())
            assertEquals("42", f.preferences.getString("hostPreferenceList", null)); assertTrue(f.preferences.getBoolean("pref_usekeyauth", false))
            f.assertNoPlaintext()
            val xml = File(target.applicationInfo.dataDir, "shared_prefs/${target.packageName}_preferences.xml").readText()
            assertFalse(xml.contains("NOT_REAL_ui_phrase")); assertFalse(xml.contains("NOT_REAL_phrase")); assertFalse(xml.contains("NOT_REAL_default"))
        }
    }

    @Test fun actualMainPreferencesAndHostNavigationKeepFallbackIndependentAndPersistSelection() = uiFixture { f ->
        // Blank endpoint intentionally prevents any network/SSH work in this UI storage test.
        assertTrue(f.repository.prepare())
        val blank = f.repository.saveHost(null, "storage-only", "", "", "22", "NOT_REAL_navigation".toByteArray())
        val fallback = f.preferences.getString(LegacyCredentialReferences.DEFAULT_PASSWORD, null)
        ActivityScenario.launch<MainActivity>(Intent(target, MainActivity::class.java)).use { scenario ->
            barrier()
            scenario.onActivity { activity ->
                MainActivity.db = f.helper
                activity.getPreferences()
                assertEquals("42", f.preferences.getString("hostPreferenceList", null))
                val menu = android.widget.PopupMenu(activity, activity.findViewById(R.id.fab)).menu
                val item = menu.add(1, blank.toInt(), 0, "storage-only")
                activity.onNavigationItemSelected(item)
            }
            barrier()
            scenario.onActivity { activity -> activity.getPreferences(); assertEquals("", MainActivity.setting_host) }
            assertEquals(blank.toString(), f.preferences.getString("hostPreferenceList", null))
            assertEquals(fallback, f.preferences.getString(LegacyCredentialReferences.DEFAULT_PASSWORD, null))
            assertArrayEquals("NOT_REAL_default".toByteArray(), f.store.read(SecretId(fallback!!)))
            assertTrue(f.preferences.getBoolean("pref_usekeyauth", false)); f.assertNoPlaintext()
        }
    }

    @Test fun actualPasswordReentryDialogIsExplicitMemoryOnlyAndLeavingCancelsPendingAttempt() = uiFixture { f ->
        assertTrue(f.repository.prepare()); f.preferences.edit(commit = true) { putBoolean("pref_usekeyauth", false) }
        f.deleteKey(); val files = f.encryptedFiles(); val rows = f.rows("contacts")
        ActivityScenario.launch<SettingsActivity>(Intent(target, SettingsActivity::class.java)).use { scenario ->
            barrier()
            val material = AtomicReference<AuthenticationMaterial?>(); val done = CountDownLatch(1)
            lateinit var ui: CredentialUi
            scenario.onActivity { activity -> ui = field(activity, "credentialsUi") as CredentialUi }
            f.repository.execute { f.repository.authenticate(ui) { result, _ -> material.set(result); done.countDown() } }
            barrier(); barrier()
            scenario.onActivity { activity ->
                val dialog = secretDialog(activity)
                assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString().contains("once"))
                enter(findInput(dialog), "NOT_REAL_ui_once"); dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            assertTrue(done.await(10, TimeUnit.SECONDS)); assertArrayEquals("NOT_REAL_ui_once".toByteArray(), material.get()!!.password); material.get()!!.close()
            val cancelled = CountDownLatch(1)
            f.repository.execute { f.repository.authenticate(ui) { result, _ -> assertNull(result); cancelled.countDown() } }
            barrier(); barrier()
            scenario.recreate()
            assertTrue(cancelled.await(10, TimeUnit.SECONDS))
            assertEquals(files, f.encryptedFiles()); assertEquals(rows, f.rows("contacts")); assertFalse(f.keystore().containsAlias(f.alias))
        }
    }

    @Test fun actualMigrationFailureKeepsHostMetadataAndNonsecretSettingsAvailableButSaveDisabled() = uiFixture { f ->
        val rows = f.rows("contacts")
        val broken = object : SecretStore by f.store {
            override fun write(id: SecretId, plaintext: ByteArray) { throw SecretStoreException(SecretStoreException.Reason.STORAGE_FAILURE) }
        }
        f.repository = CredentialRepository(f.helper, f.preferences, broken)
        AndroidCredentials::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(AndroidCredentials, f.repository)
        ActivityScenario.launch<ListEditActivity>(Intent(target, ListEditActivity::class.java)).use { scenario ->
            barrier(); scenario.onActivity { it.xMouseManage(0) }; barrier()
            scenario.onActivity { activity ->
                val dialog = editor(activity)
                assertEquals(rows.first()[2], dialog.findViewById<EditText>(R.id.new_host_alias).text.toString())
                assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            }
        }
        ActivityScenario.launch<SettingsActivity>(Intent(target, SettingsActivity::class.java)).use { scenario ->
            barrier()
            scenario.onActivity { activity ->
                assertTrue(activity.findPreference("pref_usekeyauth").isEnabled)
                assertTrue(activity.findPreference("sensitivity_list").isEnabled)
                assertFalse(activity.findPreference("pref_key_passphrase").isPersistent)
            }
        }
        assertEquals(rows, f.rows("contacts"))
        assertEquals("NOT_REAL_default", f.preferences.getString("setting_pass", null))
        assertEquals("NOT_REAL_phrase", f.preferences.getString("pref_key_passphrase", null))
        assertEquals("42", f.preferences.getString("hostPreferenceList", null))
        assertTrue(f.preferences.getBoolean("pref_usekeyauth", false))
    }

    @Test fun actualMainKeyLossDocumentReentryIsBoundedMemoryOnlyAndDoesNotReplaceUnreadableCiphertext() = uiFixture { f ->
        assertTrue(f.repository.prepare())
        val phrase = f.repository.readPassphrase()
        val key = KeyPair.genKeyPair(JSch(), KeyPair.ECDSA, 256)
        val bytes = try { ByteArrayOutputStream().also { key.writePrivateKey(it, phrase) }.toByteArray() } finally { key.dispose() }
        val keys = AndroidPrivateKeys.open(f.preferences, f.store)
        keys.importKey(keys.beginImport(), ByteArrayInputStream(bytes), phrase)
        val reference = f.preferences.getString(LegacyCredentialReferences.GLOBAL_PRIVATE_KEY, null)
        val files = f.encryptedFiles(); f.deleteKey()
        val documentId = "synthetic-${UUID.randomUUID()}"
        val control = "content://${SyntheticKeyDocumentsProvider.AUTHORITY}.control".toUri()
        target.contentResolver.call(control, "fixture.put", documentId, Bundle().apply { putByteArray("bytes", bytes) })
        val monitor = instrumentation.addMonitor(IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); addDataType("*/*")
        }, Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(SyntheticKeyDocumentsProvider.uri(documentId))), true)
        try {
            ActivityScenario.launch<MainActivity>(Intent(target, MainActivity::class.java)).use { scenario ->
                barrier()
                lateinit var ui: CredentialUi
                scenario.onActivity { activity -> MainActivity.db = f.helper; activity.getPreferences(); ui = field(MainActivity.conn, "credentialUi") as CredentialUi }
                val material = AtomicReference<AuthenticationMaterial?>(); val done = CountDownLatch(1)
                f.repository.execute { f.repository.authenticate(ui) { value, _ -> material.set(value); done.countDown() } }
                barrier(); barrier()
                scenario.onActivity {
                    val dialog = dialog(ui)
                    enter(findInput(dialog), phrase.toString(Charsets.UTF_8)); dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                }
                barrier(); barrier()
                lateinit var completedDeadline: Runnable
                scenario.onActivity {
                    completedDeadline = (field(ui, "deadlines") as Set<*>).single() as Runnable
                    dialog(ui).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                }
                assertTrue(done.await(10, TimeUnit.SECONDS))
                material.get()!!.use { assertArrayEquals(bytes, it.privateKey); assertArrayEquals(phrase, it.passphrase) }
                scenario.onActivity { assertReentryIdle(ui) }
                val later = CredentialReentryRequest(SecretId("synthetic_later_password")) { }
                scenario.onActivity { ui.request(CredentialKind.PASSWORD, later) }; barrier()
                scenario.onActivity {
                    completedDeadline.run()
                    assertTrue(later.isPending); assertTrue(dialog(ui).isShowing)
                    dialog(ui).cancel()
                }
                barrier()
                scenario.onActivity { assertFalse(later.isPending); assertReentryIdle(ui) }
                assertEquals(1, monitor.hits)
                assertEquals(reference, f.preferences.getString(LegacyCredentialReferences.GLOBAL_PRIVATE_KEY, null))
                assertEquals(files, f.encryptedFiles()); assertFalse(f.keystore().containsAlias(f.alias))
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            target.contentResolver.call(control, "fixture.remove", documentId, null)
            phrase.fill(0); bytes.fill(0)
        }
    }

    @Test fun privateKeyReentryCancelledAndExpiredPickersCleanUpWithoutCancellingLaterRequests() = uiFixture { _ ->
        val monitor = instrumentation.addMonitor(IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); addDataType("*/*")
        }, null, true)
        try {
            ActivityScenario.launch<MainActivity>(Intent(target, MainActivity::class.java)).use { scenario ->
                barrier()
                lateinit var ui: CredentialUi
                scenario.onActivity { ui = field(MainActivity.conn, "credentialUi") as CredentialUi }
                val firstResult = AtomicReference<CredentialReadResult?>()
                val first = CredentialReentryRequest(SecretId("synthetic_first_key")) { firstResult.set(it) }
                scenario.onActivity { ui.request(CredentialKind.PRIVATE_KEY, first) }; barrier()
                lateinit var oldDeadline: Runnable
                scenario.onActivity {
                    oldDeadline = (field(ui, "deadlines") as Set<*>).single() as Runnable
                    dialog(ui).getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                }
                barrier()
                scenario.onActivity { assertSame(CredentialReadResult.Cancelled, firstResult.get()); assertReentryIdle(ui) }
                val second = CredentialReentryRequest(SecretId("synthetic_second_key")) { }
                scenario.onActivity { ui.request(CredentialKind.PRIVATE_KEY, second) }; barrier()
                var cancelledCode = -1
                scenario.onActivity {
                    oldDeadline.run()
                    assertTrue(second.isPending); assertTrue(dialog(ui).isShowing)
                    oldDeadline = (field(ui, "deadlines") as Set<*>).single() as Runnable
                    dialog(ui).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                }
                barrier()
                scenario.onActivity {
                    cancelledCode = field(ui, "pickerCode") as Int
                    assertTrue(cancelledCode in 0x3000..0x3fff)
                    assertTrue(ui.hasPicker())
                    assertTrue(ui.onActivityResult(cancelledCode, Activity.RESULT_CANCELED, null))
                    assertFalse(second.isPending); assertReentryIdle(ui)
                    assertFalse(ui.onActivityResult(cancelledCode, Activity.RESULT_CANCELED, null))
                }
                val expired = CredentialReentryRequest(SecretId("synthetic_expired_key")) { }
                scenario.onActivity { ui.request(CredentialKind.PRIVATE_KEY, expired) }; barrier()
                var expiredCode = -1
                scenario.onActivity {
                    oldDeadline.run()
                    assertTrue(expired.isPending)
                    dialog(ui).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                }
                barrier()
                scenario.onActivity {
                    val deadline = (field(ui, "deadlines") as Set<*>).single() as Runnable
                    expiredCode = field(ui, "pickerCode") as Int
                    assertNotEquals(cancelledCode, expiredCode)
                    deadline.run()
                    assertFalse(expired.isPending); assertReentryIdle(ui)
                    oldDeadline = deadline
                }
                val later = CredentialReentryRequest(SecretId("synthetic_after_expiry")) { }
                scenario.onActivity { ui.request(CredentialKind.PRIVATE_KEY, later) }; barrier()
                scenario.onActivity {
                    oldDeadline.run()
                    assertTrue(later.isPending); assertTrue(dialog(ui).isShowing)
                    dialog(ui).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                }
                barrier()
                scenario.onActivity {
                    val laterCode = field(ui, "pickerCode") as Int
                    assertNotEquals(expiredCode, laterCode)
                    assertFalse(ui.onActivityResult(expiredCode, Activity.RESULT_OK, Intent().setData(SyntheticKeyDocumentsProvider.uri("late"))))
                    assertTrue(later.isPending); assertTrue(ui.hasPicker())
                    assertTrue(ui.onActivityResult(laterCode, Activity.RESULT_CANCELED, null))
                    assertReentryIdle(ui)
                }
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun privateKeyReentryReadFailureRemovesRequestResourcesAndDeadlineImmediately() = uiFixture { _ ->
        val monitor = instrumentation.addMonitor(IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); addDataType("*/*")
        }, Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(SyntheticKeyDocumentsProvider.uri("missing-${UUID.randomUUID()}"))), true)
        try {
            ActivityScenario.launch<MainActivity>(Intent(target, MainActivity::class.java)).use { scenario ->
                barrier()
                lateinit var ui: CredentialUi
                scenario.onActivity { ui = field(MainActivity.conn, "credentialUi") as CredentialUi }
                val done = CountDownLatch(1)
                val result = AtomicReference<CredentialReadResult?>()
                val request = CredentialReentryRequest(SecretId("synthetic_unreadable_key")) { result.set(it); done.countDown() }
                scenario.onActivity { ui.request(CredentialKind.PRIVATE_KEY, request) }; barrier()
                scenario.onActivity { dialog(ui).getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
                assertTrue(done.await(10, TimeUnit.SECONDS))
                scenario.onActivity { assertSame(CredentialReadResult.Cancelled, result.get()); assertReentryIdle(ui) }
                assertEquals(1, monitor.hits)
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    private class Authentication {
        val request = AtomicReference<CredentialReentryRequest?>(); val kind = AtomicReference<CredentialKind?>()
        val prompt = CountDownLatch(1); val done = CountDownLatch(1)
        val result = AtomicReference<AuthenticationMaterial?>(); val error = AtomicReference<String?>()
    }
    private fun authentication(repository: CredentialRepository, expectPrompt: Boolean = true): Authentication = Authentication().also { auth ->
        repository.execute { repository.authenticate({ kind, request -> auth.kind.set(kind); auth.request.set(request); auth.prompt.countDown() }) { value, error -> auth.result.set(value); auth.error.set(error); auth.done.countDown() } }
        if (expectPrompt) assertTrue(auth.prompt.await(10, TimeUnit.SECONDS))
    }

    private inner class Fixture(val ui: Boolean = false) : Closeable {
        val unique = UUID.randomUUID().toString()
        val root = File(target.noBackupFilesDir, "credential-repository-test-$unique")
        val alias = AndroidSecretStore.KEY_ALIAS + ".repository-test." + unique
        private val preferenceName = "credential-repository-test-$unique"
        val preferences: SharedPreferences = if (ui) PreferenceManager.getDefaultSharedPreferences(target) else target.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        val originalPreferences = preferences.all.toMap()
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = File(root, "no_backup")
            override fun getDatabasePath(name: String): File = File(root, "databases/$name").also { check(it.parentFile!!.isDirectory || it.parentFile!!.mkdirs()) }
        }
        var helper = DatabaseHandler(context)
        val store = AndroidSecretStore.open(context, alias)
        var repository = CredentialRepository(helper, preferences, store)
        val passwords = mutableMapOf<Long, String>()
        init {
            val sql = instrumentation.context.assets.open("legacy/v2/contactsManager-v2.sql").bufferedReader().use { it.readText() }
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("contactsManager"), null).use { db ->
                sql.lineSequence().filterNot { it.trimStart().startsWith("--") }.joinToString("\n").split(';').map(String::trim).filter(String::isNotEmpty).forEach { db.execSQL(it) }
            }
            rows("contacts").forEach { passwords[it[0]!!.toLong()] = it[6]!! }
            preferences.edit(commit = true) {
                clear(); putString("hostPreferenceList", "42"); putBoolean("pref_usekeyauth", true)
                putString("setting_host", "fallback.local"); putString("setting_user", "fallback"); putString("setting_port", "22")
                putString("setting_pass", "NOT_REAL_default"); putString("pref_key_passphrase", "NOT_REAL_phrase")
                putString(AndroidPrivateKeys.LEGACY_PATH, "/NOT_REAL_missing/key")
            }
        }
        fun reopen() { helper.close(); helper = DatabaseHandler(context); repository = CredentialRepository(helper, preferences, store) }
        fun rows(table: String): List<List<String?>> = helper.readableDatabase.rawQuery("SELECT * FROM $table ORDER BY id", null).use { c -> buildList { while (c.moveToNext()) add((0 until c.columnCount).map(c::getString)) } }
        fun encryptedFiles() = File(context.noBackupFilesDir, "secrets").listFiles()!!.associate { it.name to it.readBytes().toList() }
        fun keystore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        fun deleteKey() { keystore().deleteEntry(alias) }
        fun assertNoPlaintext() {
            assertTrue(rows("contacts").all { it[6] == null && it[7] != null })
            assertFalse(preferences.contains("setting_pass")); assertFalse(preferences.contains("pref_key_passphrase"))
            File(context.noBackupFilesDir, "secrets").listFiles()!!.forEach {
                val text = it.readBytes().toString(Charsets.ISO_8859_1)
                assertFalse(text.contains("NOT_REAL_")); passwords.values.forEach { value -> assertFalse(text.contains(value)) }
            }
        }
        override fun close() {
            helper.close(); deleteKey()
            if (ui) restore(preferences, originalPreferences) else target.deleteSharedPreferences(preferenceName)
            if (root.exists()) root.walkBottomUp().forEach { assertTrue(it.delete()) }
        }
    }

    private fun fixture(action: (Fixture) -> Unit) = Fixture().use(action)
    private fun uiFixture(action: (Fixture) -> Unit) {
        val singleton = AndroidCredentials::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = singleton.get(AndroidCredentials)
        Fixture(ui = true).use { f ->
            singleton.set(AndroidCredentials, f.repository)
            try { action(f); barrier() } finally { singleton.set(AndroidCredentials, previous) }
        }
    }
    private fun restore(preferences: SharedPreferences, values: Map<String, *>) = preferences.edit(commit = true) {
        clear(); values.forEach { (key, value) -> when (value) {
            is String -> putString(key, value); is Boolean -> putBoolean(key, value); is Int -> putInt(key, value)
            is Long -> putLong(key, value); is Float -> putFloat(key, value); is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
    }
    private fun barrier() {
        assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
        val latch = CountDownLatch(1); AndroidCredentials.get(target).execute { latch.countDown() }
        assertTrue(latch.await(10, TimeUnit.SECONDS)); instrumentation.waitForIdleSync()
    }
    private fun editor(activity: ListEditActivity) = field(activity, "editing") as AlertDialog
    private fun secretDialog(activity: SettingsActivity): AlertDialog {
        return dialog(field(activity, "credentialsUi") as CredentialUi)
    }
    private fun dialog(ui: CredentialUi) = (field(ui, "dialogs") as Set<*>).filterIsInstance<AlertDialog>().last()
    private fun assertReentryIdle(ui: CredentialUi) {
        assertTrue((field(ui, "requests") as Map<*, *>).isEmpty())
        assertTrue((field(ui, "deadlines") as Set<*>).isEmpty())
        assertTrue((field(ui, "dialogs") as Set<*>).isEmpty())
        assertNull(field(ui, "keyRequest")); assertEquals(-1, field(ui, "pickerCode")); assertFalse(ui.hasPicker())
    }
    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private fun enter(input: EditText, value: String) { input.text.replace(0, input.text.length, value) }
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("Timed out waiting for asynchronous credential presentation", condition())
    }
    private fun findInput(dialog: AlertDialog): EditText {
        fun find(view: android.view.View): EditText? {
            if (view is EditText) return view
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        return find(dialog.window!!.decorView)!!
    }
    private inline fun <reified T : Throwable> expect(action: () -> Unit): T {
        try { action() } catch (failure: Throwable) { if (failure is T) return failure else throw failure }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }
}
