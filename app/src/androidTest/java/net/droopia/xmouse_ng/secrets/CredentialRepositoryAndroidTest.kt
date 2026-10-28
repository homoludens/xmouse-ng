package net.droopia.xmouse_ng.secrets

import android.Manifest
import android.app.AlertDialog
import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Looper
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.preference.PreferenceManager
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.preference.EditTextPreference
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import net.droopia.xmouse_ng.DatabaseHandler
import net.droopia.xmouse_ng.ComputerEditorActivity
import com.google.android.material.textfield.TextInputLayout
import net.droopia.xmouse_ng.ListEditActivity
import net.droopia.xmouse_ng.MainActivity
import net.droopia.xmouse_ng.R
import net.droopia.xmouse_ng.SettingsActivity
import net.droopia.xmouse_ng.XMouseApplication
import net.droopia.xmouse_ng.session.SessionAttemptFactory
import net.droopia.xmouse_ng.session.SessionController
import net.droopia.xmouse_ng.session.SessionForegroundService
import net.droopia.xmouse_ng.session.SessionInteractions
import net.droopia.xmouse_ng.session.SessionServiceOwner
import net.droopia.xmouse_ng.connection.ConnectionState
import net.droopia.xmouse_ng.connection.ConnectionFailure
import net.droopia.xmouse_ng.connection.Endpoint
import net.droopia.xmouse_ng.connection.EndpointLocator
import net.droopia.xmouse_ng.discovery.SshServiceDiscovery
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real SQLite, SharedPreferences, Keystore, production repository, and existing Activities. */
@RunWith(AndroidJUnit4::class)
class CredentialRepositoryAndroidTest {
    private val target get() = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /** The navigation test exercises host selection without ever opening a production SSH transport. */
    private class NavigationOnlySessionOwner : SessionServiceOwner, AutoCloseable {
        val selectionCalls = AtomicInteger()
        val attemptCreations = AtomicInteger()
        val selectionCompleted = CountDownLatch(1)
        override val interactions = SessionInteractions(Executor { it.run() })
        override val controller = SessionController(SessionAttemptFactory {
            attemptCreations.incrementAndGet()
            error("navigation-only credential test must not create an SSH attempt")
        })

        override fun selectedHost(shell: String, initialCommand: String?, completion: (net.droopia.xmouse_ng.session.SessionHost?) -> Unit) {
            selectionCalls.incrementAndGet()
            completion(null)
            selectionCompleted.countDown()
        }

        override fun close() { controller.close() }
    }

    private class RecordingDiscoveryFactory : ListEditActivity.DiscoveryFactory {
        class Session(val listener: SshServiceDiscovery.Listener) : ListEditActivity.DiscoveryHandle {
            var starts = 0
            var closes = 0
            override fun start() { starts++ }
            override fun close() { closes++ }
        }

        val sessions = mutableListOf<Session>()
        override fun create(context: Context, listener: SshServiceDiscovery.Listener): ListEditActivity.DiscoveryHandle =
            Session(listener).also(sessions::add)
    }

    @Test fun capturedAuthenticationSelectionCannotReadAnotherProfileOrNewCredentialAfterSelectionChanges() = fixture { f ->
        assertTrue(f.repository.prepare())
        f.preferences.edit(commit = true) { putBoolean("pref_usekeyauth", false) }
        val snapshot = f.repository.authenticationSelection()
        assertEquals(42L, snapshot.host.id)
        val originalPassword = f.repository.readPassword(snapshot.host)
        f.repository.saveHost(snapshot.host, "edited", snapshot.host.host!!, snapshot.host.user!!, snapshot.host.port!!,
            "NOT_REAL_replacement_password".toByteArray())
        f.repository.selectHost(7)
        f.preferences.edit(commit = true) { putBoolean("pref_usekeyauth", true) }
        val choices = f.preferences.all
        val done = CountDownLatch(1)
        val result = AtomicReference<AuthenticationMaterial?>()
        f.repository.execute {
            f.repository.authenticate(snapshot, { _, request -> request.cancel() }) { material, _ -> result.set(material); done.countDown() }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        val material = requireNotNull(result.get())
        try {
            assertEquals(snapshot.host, material.host); assertFalse(material.useKey)
            assertArrayEquals(originalPassword, material.password)
            assertEquals(7L, f.repository.selectedHost().id)
            assertEquals(choices, f.preferences.all)
        } finally { originalPassword.fill(0); material.close() }
    }

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

    @Test fun explicitlySavedDiscoveredProfileReopensWithItsServiceLocatorRatherThanObservedAddress() = fixture { f ->
        assertTrue(f.repository.prepare())
        val locator = EndpointLocator.DiscoveredService("Office workstation", "_ssh._tcp", "local")
        val expectedIdentity = Endpoint(locator, 2222).identity

        // This repository API is the explicit Save boundary: no observed address or network handle is accepted.
        val id = f.repository.saveDiscoveredHost(null, "Office workstation", locator,
            "fixture-user", "2222", byteArrayOf())
        val saved = f.repository.hosts().single { it.id == id }
        assertNull(saved.host)
        assertEquals(locator, saved.serviceLocator)
        assertEquals(expectedIdentity, saved.endpoint().identity)

        f.reopen()
        assertTrue(f.repository.prepare())
        val reopened = f.repository.hosts().single { it.id == id }
        assertNull(reopened.host)
        assertEquals(locator, reopened.serviceLocator)
        assertEquals(2222, reopened.endpoint().port)
        assertEquals(expectedIdentity, reopened.endpoint().identity)
        assertEquals("Office workstation (_ssh._tcp.local)", reopened.displayEndpoint())
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
            val jsch = JSch(); BoundedSshKey.addIdentity(jsch, material.privateKey, material.passphrase)
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

    @Test fun savedKeyWrongPassphraseFailsBeforeAuthenticationWithoutRewritingCredentials() = fixture { f ->
        assertTrue(f.repository.prepare())
        val phrase = f.repository.readPassphrase()
        val bytes = instrumentation.context.assets.open("ssh-crypto/rsa1024-encrypted.pem").use { it.readBytes() }
        val correct = "NOT_REAL_XMOUSE_FIXTURE".toByteArray()
        try {
            val keys = AndroidPrivateKeys.open(f.preferences, f.store)
            keys.importKey(keys.beginImport(), ByteArrayInputStream(bytes), correct)
            // The retained global phrase deliberately does not unlock this encrypted key.
            val before = f.preferences.all
            val files = f.encryptedFiles()
            val auth = authentication(f.repository, expectPrompt = false)
            assertTrue(auth.done.await(10, TimeUnit.SECONDS))
            assertNull(auth.result.get()); assertTrue(auth.error.get()!!.contains("PASSPHRASE_REQUIRED_OR_INVALID"))
            assertEquals(before, f.preferences.all); assertEquals(files, f.encryptedFiles())
            assertArrayEquals(bytes, keys.readForAuthentication(correct))
        } finally { bytes.fill(0); phrase.fill(0); correct.fill(0) }
    }

    @Test fun malformedAttemptOnlyKeyCannotBypassValidationOrResetLostDeviceKey() = fixture { f ->
        assertTrue(f.repository.prepare())
        val phrase = f.repository.readPassphrase()
        val valid = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 1024)
        val bytes = try { ByteArrayOutputStream().also { valid.writePrivateKey(it, phrase) }.toByteArray() } finally { valid.dispose() }
        val keys = AndroidPrivateKeys.open(f.preferences, f.store)
        keys.importKey(keys.beginImport(), ByteArrayInputStream(bytes), phrase)
        val before = f.preferences.all
        val files = f.encryptedFiles()
        f.deleteKey()
        val auth = authentication(f.repository)
        assertEquals(CredentialKind.PASSPHRASE, auth.kind.get())
        assertTrue(auth.request.get()!!.useForThisAttempt(phrase))
        waitFor { auth.kind.get() == CredentialKind.PRIVATE_KEY }
        val candidate = BoundedKeyFixtures.encryptedMalformed(phrase)
        try {
            assertTrue(auth.request.get()!!.useForThisAttempt(candidate))
            assertTrue(auth.done.await(10, TimeUnit.SECONDS))
            assertNull(auth.result.get()); assertNotNull(auth.error.get())
            assertEquals(before, f.preferences.all); assertEquals(files, f.encryptedFiles())
            assertFalse(f.keystore().containsAlias(f.alias))
        } finally { bytes.fill(0); phrase.fill(0); candidate.fill(0) }
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

    @Test fun metadataOnlyComputerEditPreservesCiphertextReferenceAndBackupFlagsWithoutAnySecretAccess() = fixture { f ->
        assertTrue(f.repository.prepare())
        val expected = f.repository.hosts().first()
        val files = f.encryptedFiles(); val prefs = f.preferences.all
        var reads = 0; var writes = 0
        val protected = object : SecretStore by f.store {
            override fun read(id: SecretId): ByteArray? { reads++; error("Metadata must not decrypt") }
            override fun write(id: SecretId, plaintext: ByteArray) { writes++; error("Metadata must not replace") }
        }
        val repository = CredentialRepository(f.helper, f.preferences, protected)
        repository.updateHostMetadata(expected, "Renamed", Endpoint.manual("renamed.local", 2222), "new-user")
        val edited = repository.hosts().first()
        assertEquals(expected.passwordReference, edited.passwordReference)
        assertEquals(expected.credentialReentryRequired, edited.credentialReentryRequired)
        assertEquals(expected.legacyPasswordPresent, edited.legacyPasswordPresent)
        assertEquals("2222", edited.port); assertEquals("new-user", edited.user)
        assertEquals(0, reads); assertEquals(0, writes); assertEquals(files, f.encryptedFiles()); assertEquals(prefs, f.preferences.all)
        assertArrayEquals(f.passwords.getValue(expected.id).toByteArray(), f.repository.readPassword(edited))
        expect<CredentialRepositoryException> { repository.updateHostMetadata(expected, "Stale", Endpoint.manual("stale.local", 22), "u") }
    }

    @Test fun unsavedCandidateProtectedAuthenticationAndInvalidSavesNeverChangeHostRowsReferencesOrPreferences() = fixture { f ->
        assertTrue(f.repository.prepare())
        f.preferences.edit(commit = true) { putBoolean("pref_usekeyauth", false) }
        val saved = f.repository.hosts().first()
        val candidate = saved.copy(alias = "Unsaved", host = "candidate.local", port = "2222", user = "candidate-user")
        val rows = f.rows("contacts"); val files = f.encryptedFiles(); val prefs = f.preferences.all
        val done = CountDownLatch(1); val material = AtomicReference<AuthenticationMaterial?>()
        f.repository.execute {
            f.repository.authenticateProbe(f.repository.authenticationSelection(candidate), null, { _, request -> request.cancel() }) { result, _ ->
                material.set(result); done.countDown()
            }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        material.get()!!.use { assertEquals(candidate, it.host); assertArrayEquals(f.passwords.getValue(saved.id).toByteArray(), it.password) }
        for (user in listOf("", " user", "user ")) expect<IllegalArgumentException> {
            f.repository.saveHost(saved, "invalid", "candidate.local", user, "22", byteArrayOf())
        }
        expect<IllegalArgumentException> { f.repository.saveHost(null, "invalid", "candidate.local", "u", "65536", byteArrayOf()) }
        assertEquals(rows, f.rows("contacts")); assertEquals(files, f.encryptedFiles()); assertEquals(prefs, f.preferences.all)
    }

    @Test fun nativeComputerEditorUsesPersistentLabelsBlankPasswordAdvancedValidationRecreationAndConfirmedDelete() = uiFixture { f ->
        assertTrue(f.repository.prepare())
        val host = f.repository.hosts().first(); val files = f.encryptedFiles()
        ActivityScenario.launch<ComputerEditorActivity>(ComputerEditorActivity.intent(target, host.id)).use { scenario ->
            barrier()
            scenario.onActivity { activity ->
                val toolbar = activity.findViewById<androidx.appcompat.widget.Toolbar>(R.id.computer_editor_toolbar)
                val toolbarTitle = requireNotNull(toolbarTitleView(toolbar))
                val expectedHeadingSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 20f,
                    activity.resources.displayMetrics)
                assertEquals("Computer editor toolbar uses the compact shared settings heading",
                    expectedHeadingSize, toolbarTitle.textSize, 1f)
                assertEquals("Hostname or IP address", activity.findViewById<TextView>(R.id.computer_host_label).text.toString())
                assertEquals("Username", activity.findViewById<TextView>(R.id.computer_user_label).text.toString())
                val password = activity.findViewById<EditText>(R.id.new_host_password)
                assertEquals("", password.text.toString()); assertFalse(password.isSaveEnabled)
                assertTrue(activity.findViewById<TextInputLayout>(R.id.computer_password_field).helperText.toString().contains("unchanged"))
                assertEquals(host.port, activity.findViewById<EditText>(R.id.new_host_port).text.toString())
                activity.findViewById<View>(R.id.computer_advanced).performClick()
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.computer_port_field).visibility)
                enter(activity.findViewById(R.id.new_host_username), " user ")
                // Invalid local input must stop Test before permission, authentication, DNS, or SSH.
                activity.findViewById<View>(R.id.computer_test).performClick()
                assertNotNull(activity.findViewById<TextInputLayout>(R.id.computer_user_field).error)
                activity.findViewById<View>(R.id.computer_save).performClick()
                assertNotNull(activity.findViewById<TextInputLayout>(R.id.computer_user_field).error)
                enter(activity.findViewById(R.id.new_host_username), "new-user")
                enter(activity.findViewById(R.id.new_host_port), "65536")
                activity.findViewById<View>(R.id.computer_test).performClick()
                assertNotNull(activity.findViewById<TextInputLayout>(R.id.computer_port_field).error)
                activity.findViewById<View>(R.id.computer_save).performClick()
                assertNotNull(activity.findViewById<TextInputLayout>(R.id.computer_port_field).error)
                enter(activity.findViewById(R.id.new_host_port), "2222")
                enter(activity.findViewById(R.id.new_host_alias), "Native edited")
                enter(password, "NOT_REAL_not_bundled")
                assertTrue(activity.findViewById<View>(R.id.computer_auth_settings).performClick())
            }
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            assertTrue("Computer form opens the unified Authentication & SSH section",
                device.wait(Until.hasObject(By.text("Authentication & SSH")), 5_000))
            device.pressBack()
            device.waitForIdle()
            scenario.onActivity { activity ->
                assertEquals("Leaving the computer form clears its unsaved password as before",
                    "", activity.findViewById<EditText>(R.id.new_host_password).text.toString())
            }
            assertEquals(host, f.repository.hosts().first())
            scenario.recreate(); barrier()
            scenario.onActivity { activity ->
                assertEquals("", activity.findViewById<EditText>(R.id.new_host_password).text.toString())
                assertEquals("Native edited", activity.findViewById<EditText>(R.id.new_host_alias).text.toString())
                activity.findViewById<View>(R.id.computer_save).performClick()
            }
            barrier(); barrier()
        }
        val edited = f.repository.hosts().first()
        assertEquals("2222", edited.port); assertEquals("new-user", edited.user); assertEquals(host.passwordReference, edited.passwordReference)
        assertEquals(files, f.encryptedFiles())
        ActivityScenario.launch<ComputerEditorActivity>(ComputerEditorActivity.intent(target, host.id)).use { scenario ->
            barrier()
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.computer_delete).performClick()
                val confirmation = field(activity, "deleteDialog") as AlertDialog
                assertTrue(confirmation.isShowing); confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            }
            assertEquals(edited, f.repository.hosts().first())
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.computer_delete).performClick()
                (field(activity, "deleteDialog") as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            barrier(); barrier()
        }
        assertFalse(f.repository.hosts().any { it.id == host.id }); assertEquals(files, f.encryptedFiles())
    }

    @Test fun nativeDiscoveredComputerFormRetainsLogicalServiceAndPortWithoutSavingOnOpenOrBack() = uiFixture { f ->
        val candidate = SshServiceDiscovery.Candidate(
            SshServiceDiscovery.Identity("fixture-network", "office", "_ssh._tcp", "local"),
            EndpointLocator.DiscoveredService("Office", "_ssh._tcp", "local"), 2222,
            listOf(InetAddress.getByName("192.0.2.23")),
        )
        val rows = f.rows("contacts"); val prefs = f.preferences.all
        ActivityScenario.launch<ComputerEditorActivity>(ComputerEditorActivity.discoveredIntent(target, candidate)).use { scenario ->
            barrier()
            scenario.onActivity { activity ->
                assertEquals("Office", activity.findViewById<EditText>(R.id.new_host_alias).text.toString())
                assertEquals("", activity.findViewById<EditText>(R.id.new_host_ip).text.toString())
                assertEquals("2222", activity.findViewById<EditText>(R.id.new_host_port).text.toString())
                assertEquals("", activity.findViewById<EditText>(R.id.new_host_username).text.toString())
                assertEquals("", activity.findViewById<EditText>(R.id.new_host_password).text.toString())
                activity.onBackPressedDispatcher.onBackPressed()
            }
        }
        assertEquals(rows, f.rows("contacts")); assertEquals(prefs, f.preferences.all)
    }

    @Test fun actualHostScreenPreservesManualEndpointNamesAndRejectsInvalidEdits() = uiFixture { f ->
        assertTrue(f.repository.prepare())
        val endpoints = listOf("192.0.2.42", "2001:db8::42", "workstation.example", "desktop.local")
        val initialCount = f.passwords.size
        ActivityScenario.launch<ListEditActivity>(Intent(target, ListEditActivity::class.java)).use { scenario ->
            barrier()
            scenario.onActivity { it.xMouseManage(0) }
            barrier()
            scenario.onActivity { activity ->
                val dialog = editor(activity)
                assertEquals("Delete", dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString())
                assertEquals("Cancel", dialog.getButton(AlertDialog.BUTTON_NEUTRAL).text.toString())
                val input = dialog.findViewById<EditText>(R.id.new_host_password)
                assertEquals("", input.text.toString())
                assertFalse(input.isSaveEnabled)
                enter(input, "NOT_REAL_ui_edit")
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            barrier(); barrier()

            endpoints.forEachIndexed { index, endpoint ->
                scenario.onActivity { it.xMouseManage(null) }
                barrier()
                scenario.onActivity { activity ->
                    val dialog = editor(activity)
                    if (index == 0) {
                        assertEquals("Cancel", dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString())
                        assertFalse(dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.visibility == android.view.View.VISIBLE)
                    }
                    enter(dialog.findViewById(R.id.new_host_alias), "UI added $index")
                    enter(dialog.findViewById(R.id.new_host_ip), endpoint)
                    enter(dialog.findViewById(R.id.new_host_username), "u")
                    enter(dialog.findViewById(R.id.new_host_port), "2222")
                    enter(dialog.findViewById(R.id.new_host_password), "NOT_REAL_ui_add_$index")
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                }
                barrier(); barrier()
                assertEquals(endpoint, f.repository.hosts()[initialCount + index].host)
            }

            scenario.recreate(); barrier()
            endpoints.forEachIndexed { index, endpoint ->
                val profileIndex = initialCount + index
                scenario.onActivity { it.xMouseManage(profileIndex) }
                barrier()
                scenario.onActivity { activity ->
                    val dialog = editor(activity)
                    assertEquals(endpoint, dialog.findViewById<EditText>(R.id.new_host_ip).text.toString())
                    assertEquals("2222", dialog.findViewById<EditText>(R.id.new_host_port).text.toString())
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                }
                barrier(); barrier()
                assertEquals(endpoint, f.repository.hosts()[profileIndex].host)
            }

            val invalidIndex = initialCount + endpoints.lastIndex
            val invalidTarget = f.repository.hosts()[invalidIndex]
            scenario.onActivity { it.xMouseManage(invalidIndex) }
            barrier()
            scenario.onActivity { activity ->
                val dialog = editor(activity)
                val endpoint = dialog.findViewById<EditText>(R.id.new_host_ip)
                assertEquals("Hostname or IP address", dialog.findViewById<android.widget.TextView>(R.id.new_host_endpoint_label).text.toString())
                assertEquals(endpoints.last(), endpoint.text.toString())
                enter(endpoint, "")
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertTrue(dialog.isShowing)
                assertNotNull(endpoint.error)
            }
            barrier()
            assertEquals(invalidTarget, f.repository.hosts()[invalidIndex])
            scenario.onActivity { activity ->
                val dialog = editor(activity)
                val endpoint = dialog.findViewById<EditText>(R.id.new_host_ip)
                val port = dialog.findViewById<EditText>(R.id.new_host_port)
                enter(endpoint, endpoints.last())
                enter(port, "65536")
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertTrue(dialog.isShowing)
                assertNotNull(port.error)
            }
            barrier()
            assertEquals(invalidTarget, f.repository.hosts()[invalidIndex])
            expect<IllegalArgumentException> {
                f.repository.saveHost(invalidTarget, "invalid", "", "u", "2222", byteArrayOf())
            }
            assertEquals(invalidTarget, f.repository.hosts()[invalidIndex])
            scenario.onActivity { editor(it).getButton(AlertDialog.BUTTON_NEUTRAL).performClick() }
            barrier()

            scenario.onActivity { it.xMouseManage(0) }; barrier()
            scenario.onActivity { activity ->
                val dialog = editor(activity)
                assertEquals("", dialog.findViewById<EditText>(R.id.new_host_password).text.toString())
                enter(dialog.findViewById(R.id.new_host_password), "NOT_REAL_cancelled")
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            }
            barrier()
            assertArrayEquals("NOT_REAL_ui_edit".toByteArray(), f.repository.readPassword(f.repository.hosts().first()))
            endpoints.forEachIndexed { index, _ ->
                val password = f.repository.readPassword(f.repository.hosts()[initialCount + index])
                try { assertArrayEquals("NOT_REAL_ui_add_$index".toByteArray(), password) }
                finally { password.fill(0) }
            }
            assertEquals(initialCount + endpoints.size, f.repository.hosts().size)
            f.assertNoPlaintext()
        }
    }

    @Test fun actualHostDiscoveryShowsEmptyAndTypedErrorsAndKeepsSelectionTransient() = uiFixture { f ->
        val factory = RecordingDiscoveryFactory()
        val editorIntents = mutableListOf<Intent>()
        val editorMonitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.component?.className != ComputerEditorActivity::class.java.name) return null
                editorIntents += Intent(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(editorMonitor)
        ListEditActivity.setDiscoveryFactoryForTests(factory)
        ListEditActivity.setPermissionRequiredForTests(false)
        try {
            ActivityScenario.launch<ListEditActivity>(Intent(target, ListEditActivity::class.java)).use { scenario ->
                barrier()
                val savedRows = f.rows("contacts")
                val savedFiles = f.encryptedFiles()
                val savedHosts = f.onRepositoryWorker(f.repository) { f.repository.hosts() }

                scenario.onActivity { activity ->
                    assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.find_computers).visibility)
                    activity.findViewById<Button>(R.id.find_computers).performClick()
                }
                barrier()
                assertEquals(1, factory.sessions.size)
                assertEquals(1, factory.sessions.single().starts)
                scenario.onActivity { activity ->
                    val dialog = discoveryDialog(activity)
                    assertTrue(dialog.findViewById<TextView>(R.id.find_computers_status).text
                        .contains("Running SSH alone may not advertise"))
                    factory.sessions.single().listener.onFailure(
                        SshServiceDiscovery.Failure(SshServiceDiscovery.Operation.START, 3, null, null),
                    )
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val dialog = discoveryDialog(activity)
                    assertTrue(dialog.findViewById<TextView>(R.id.find_computers_status).text
                        .contains("Could not search for advertised SSH computers"))
                    factory.sessions.single().listener.onFailure(
                        SshServiceDiscovery.Failure(
                            SshServiceDiscovery.Operation.PERMISSION,
                            null,
                            null,
                            ConnectionFailure.Permission(ConnectionFailure.PermissionReason.DENIED),
                        ),
                    )
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val dialog = discoveryDialog(activity)
                    assertTrue(dialog.findViewById<TextView>(R.id.find_computers_status).text
                        .contains("Local network access was denied"))
                    assertEquals("Manual entry", dialog.getButton(AlertDialog.BUTTON_NEUTRAL).text.toString())
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
                    assertEquals(1, editorIntents.size)
                    assertFalse(editorIntents.last().hasExtra("computer_id"))
                    assertFalse(editorIntents.last().hasExtra("service_name"))
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { assertEquals(1, factory.sessions.single().closes) }

                scenario.onActivity { activity -> activity.findViewById<Button>(R.id.find_computers).performClick() }
                barrier()
                val selected = SshServiceDiscovery.Candidate(
                    SshServiceDiscovery.Identity("test-network", "office", "_ssh._tcp", "local"),
                    EndpointLocator.DiscoveredService("Office", "_ssh._tcp.", "local."),
                    2222,
                    listOf(InetAddress.getByName("192.0.2.23")),
                )
                scenario.onActivity { activity ->
                    factory.sessions.last().listener.onServiceFound(selected)
                    val dialog = discoveryDialog(activity)
                    val results = dialog.findViewById<ListView>(R.id.find_computers_results)
                    assertEquals(1, results.adapter.count)
                    assertTrue(results.adapter.getItem(0).toString().contains("Office — SSH port 2222"))
                    results.performItemClick(results.adapter.getView(0, null, results), 0, 0L)
                }
                barrier()
                scenario.onActivity { activity ->
                    assertFalse(discoveryDialogOrNull(activity)?.isShowing == true)
                    assertNull(field(activity, "transientDiscoveryCandidate"))
                    assertEquals(2, editorIntents.size)
                    val route = editorIntents.last()
                    assertEquals("Office", route.getStringExtra("service_name"))
                    assertEquals("2222", route.getStringExtra("computer_port"))
                    assertNull(route.getStringExtra("stable_host")) // Never persist last DHCP address.
                    assertFalse(route.extras.toString().contains("192.0.2.23"))
                    assertFalse(SessionForegroundService.hasRetainedSession(activity))
                }
                barrier()
                assertEquals(savedRows, f.rows("contacts"))
                assertEquals(savedFiles, f.encryptedFiles())
                assertEquals(savedHosts, f.onRepositoryWorker(f.repository) { f.repository.hosts() })

                ActivityScenario.launch<ListEditActivity>(Intent(target, ListEditActivity::class.java)
                    .putExtra("intVariableType", 1)).use { scripts ->
                    scripts.onActivity { activity ->
                        val discovery = activity.findViewById<View>(R.id.find_computers)
                        assertTrue("Scripts manager must not expose host discovery",
                            discovery == null || discovery.visibility == View.GONE)
                    }
                    assertEquals(2, factory.sessions.size)
                }
            }
        } finally {
            instrumentation.removeMonitor(editorMonitor)
            ListEditActivity.setDiscoveryFactoryForTests(null)
            ListEditActivity.setPermissionRequiredForTests(null)
        }
    }

    @Test fun actualSettingsRetiresFrameworkSecretPersistenceAndSecureEditorSaveCancelRecreation() = uiFixture { f ->
        ActivityScenario.launch<SettingsActivity>(Intent(target, SettingsActivity::class.java)).use { scenario ->
            barrier()
            scenario.onActivity { activity ->
                val pref = activity.findPreference("pref_key_passphrase")
                assertFalse(pref is EditTextPreference); assertFalse(pref.isPersistent)
                assertEquals("For an encrypted key, set its passphrase before importing. Import never changes this choice.", pref.summary)
                // The former plaintext fallback is no longer part of the active settings screen.
                assertNull(activity.settingsFragmentForTesting().findSettingPreference("setting_pass"))
                requireNotNull(pref.onPreferenceClickListener).onPreferenceClick(pref)
            }
            barrier()
            scenario.onActivity { activity ->
                val dialog = secretDialog(activity)
                val input = dialog.findViewById<EditText>(android.R.id.edit) ?: findInput(dialog)
                assertEquals("NOT_REAL_phrase", input.text.toString()); assertFalse(input.isSaveEnabled)
                enter(input, "NOT_REAL_ui_phrase"); dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            barrier()
            scenario.onActivity { activity -> activity.findPreference("pref_key_passphrase").let {
                requireNotNull(it.onPreferenceClickListener).onPreferenceClick(it)
            } }
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

    @Test fun restoredCredentialsAllowAttemptOnlyAuthSettingsReplacementAndBlankHostPasswordPreservation() = uiFixture { restored ->
        Fixture().use { source ->
            source.preferences.edit(commit = true) { putBoolean("pref_usekeyauth", false) }
            val snapshotRef = AtomicReference<ByteArray?>()
            val snapshotError = AtomicReference<Throwable?>()
            val snapshotDone = CountDownLatch(1)
            source.repository.execute {
                try { snapshotRef.set(source.repository.snapshotForBackup()) }
                catch (failure: Throwable) { snapshotError.set(failure) }
                finally { snapshotDone.countDown() }
            }
            assertTrue(snapshotDone.await(10, TimeUnit.SECONDS))
            snapshotError.get()?.let { throw AssertionError(it) }
            val snapshot = snapshotRef.get()!!
            try {
                restored.context.assertDatabasePath(restored.helper.writableDatabase, "contactsManager")
                restored.helper.writableDatabase.apply {
                    delete("contacts", null, null)
                    delete("scripts", null, null)
                }
                restored.preferences.edit(commit = true) { clear() }
                CredentialMetadataBackup.restore(restored.context, snapshot)
                restored.context.assertDatabasePath(restored.helper.writableDatabase, "contactsManager")
                assertTrue(restored.preferences.getBoolean(CredentialMetadataBackup.PASSPHRASE_REENTRY, false))
                assertEquals(setOf("7", "42"), restored.preferences.getStringSet(CredentialMetadataBackup.HOST_REENTRY_IDS, emptySet()))
                assertTrue(restored.onRepositoryWorker(restored.repository) { restored.repository.prepare() })

                ActivityScenario.launch<SettingsActivity>(Intent(target, SettingsActivity::class.java)).use { scenario ->
                    lateinit var ui: CredentialUi
                    scenario.onActivity { activity -> ui = requireNotNull(activity.settingsFragmentForTesting().credentialUiForTesting) }

                    // Authentication remains attempt-only after restore and must not persist this entry.
                    val material = AtomicReference<AuthenticationMaterial?>()
                    val done = CountDownLatch(1)
                    restored.repository.execute {
                        restored.repository.authenticate(ui) { value, _ -> material.set(value); done.countDown() }
                    }
                    barrier(); barrier()
                    scenario.onActivity { activity ->
                        val dialog = secretDialog(activity)
                        assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString().contains("once"))
                        enter(findInput(dialog), "NOT_REAL_restored_attempt_only")
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    }
                    assertTrue(done.await(10, TimeUnit.SECONDS))
                    material.get()!!.use { assertArrayEquals("NOT_REAL_restored_attempt_only".toByteArray(), it.password) }
                    assertNull(restored.preferences.getString(LegacyCredentialReferences.DEFAULT_PASSWORD, null))
                    assertTrue(restored.preferences.getBoolean(CredentialMetadataBackup.DEFAULT_PASSWORD_REENTRY, false))

                    // Settings presents an empty editor only for the known restore marker; Save creates
                    // a new encrypted reference and durably clears the marker.
                    scenario.onActivity { activity ->
                        val preference = activity.findPreference("pref_key_passphrase")
                        requireNotNull(preference.onPreferenceClickListener).onPreferenceClick(preference)
                    }
                    barrier()
                    scenario.onActivity { activity ->
                        val dialog = secretDialog(activity)
                        val input = findInput(dialog)
                        assertEquals("", input.text.toString())
                        enter(input, "NOT_REAL_restored_passphrase_saved")
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    }
                    barrier(); barrier()
                    assertFalse(restored.preferences.getBoolean(CredentialMetadataBackup.PASSPHRASE_REENTRY, false))
                    val passphraseReference = restored.preferences.getString(LegacyCredentialReferences.GLOBAL_PASSPHRASE, null)
                    assertNotNull(passphraseReference)
                    val storedPhrase = restored.onRepositoryWorker(restored.repository) { restored.repository.readPassphrase() }
                    try { assertArrayEquals("NOT_REAL_restored_passphrase_saved".toByteArray(), storedPhrase) }
                    finally { storedPhrase.fill(0) }
                    assertFalse(restored.preferences.all.values.any {
                        it is String && it.contains("NOT_REAL_restored_passphrase_saved")
                    })
                }

                ActivityScenario.launch<ListEditActivity>(Intent(target, ListEditActivity::class.java)).use { scenario ->
                    barrier()
                    scenario.onActivity { it.xMouseManage(0) }
                    barrier()
                    scenario.onActivity { activity ->
                        val dialog = editor(activity)
                        val password = dialog.findViewById<EditText>(R.id.new_host_password)
                        assertEquals("", password.text.toString())
                        assertTrue(password.hint.toString().contains("unchanged"))
                        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
                    }
                    barrier()
                    assertTrue("Canceling metadata edit keeps re-entry required",
                        "7" in restored.preferences.getStringSet(CredentialMetadataBackup.HOST_REENTRY_IDS, emptySet()).orEmpty())
                    assertNull(restored.repository.hosts().first { it.id == 7L }.passwordReference)

                    scenario.onActivity { activity -> activity.xMouseManage(0) }
                    barrier()
                    scenario.onActivity { activity ->
                        val dialog = editor(activity)
                        assertEquals("", dialog.findViewById<EditText>(R.id.new_host_password).text.toString())
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    }
                    barrier(); barrier()
                    assertTrue("Blank password Save preserves the restore marker",
                        "7" in restored.preferences.getStringSet(CredentialMetadataBackup.HOST_REENTRY_IDS, emptySet()).orEmpty())
                    assertNull(restored.repository.hosts().first { it.id == 7L }.passwordReference)
                    scenario.onActivity { activity -> activity.xMouseManage(0) }; barrier()
                    scenario.onActivity { activity ->
                        val dialog = editor(activity)
                        enter(dialog.findViewById(R.id.new_host_password), "NOT_REAL_restored_replacement")
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    }
                    barrier(); barrier()
                    assertFalse("Explicit nonblank replacement clears the restore marker",
                        "7" in restored.preferences.getStringSet(CredentialMetadataBackup.HOST_REENTRY_IDS, emptySet()).orEmpty())
                    val savedHost = restored.repository.hosts().first { it.id == 7L }
                    assertFalse(savedHost.credentialReentryRequired)
                    val savedPassword = restored.onRepositoryWorker(restored.repository) { restored.repository.readPassword(savedHost) }
                    try { assertArrayEquals("NOT_REAL_restored_replacement".toByteArray(), savedPassword) } finally { savedPassword.fill(0) }
                }
            } finally { snapshot.fill(0) }
        }
    }

    @Test fun passphraseSettingsReadDoesNotTurnMissingReferencedCiphertextIntoEmptyRestoreEntry() = fixture { f ->
        val missingReference = "missing_restored_passphrase_record"
        f.preferences.edit(commit = true) {
            clear()
            putString(LegacyCredentialReferences.GLOBAL_PASSPHRASE, missingReference)
            putBoolean(CredentialMetadataBackup.PASSPHRASE_REENTRY, true)
        }
        val error = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        f.repository.execute {
            try { f.repository.readPassphraseForEdit() } catch (failure: Throwable) { error.set(failure) }
            finally { done.countDown() }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertTrue("Existing unreadable records must still fail closed", error.get() is CredentialRepositoryException)
        assertEquals(missingReference, f.preferences.getString(LegacyCredentialReferences.GLOBAL_PASSPHRASE, null))
        assertTrue(f.preferences.getBoolean(CredentialMetadataBackup.PASSPHRASE_REENTRY, false))

        val tampered = SecretStoreException(SecretStoreException.Reason.INVALID_RECORD)
        val damagedStore = object : SecretStore by f.store {
            override fun read(id: SecretId): ByteArray? = throw tampered
        }
        val damagedRepository = CredentialRepository(f.helper, f.preferences, damagedStore)
        val damagedError = AtomicReference<Throwable?>()
        val damagedDone = CountDownLatch(1)
        damagedRepository.execute {
            try { damagedRepository.readPassphraseForEdit() } catch (failure: Throwable) { damagedError.set(failure) }
            finally { damagedDone.countDown() }
        }
        assertTrue(damagedDone.await(10, TimeUnit.SECONDS))
        assertSame("Tampered existing ciphertext must not be presented as an empty replacement", tampered, damagedError.get())
    }

    @Test fun actualMainPreferencesAndHostNavigationKeepFallbackIndependentAndPersistSelection() = uiFixture { f ->
        // The installed navigation-only owner prevents all network/SSH work; the saved form is valid.
        assertTrue(f.repository.prepare())
        val blank = f.repository.saveHost(null, "storage-only", "storage-only.invalid", "fixture-user", "22", "NOT_REAL_navigation".toByteArray())
        val fallback = f.preferences.getString(LegacyCredentialReferences.DEFAULT_PASSWORD, null)
        val owners = (target.applicationContext as XMouseApplication).sessionOwners
        owners.clearIdleOwnerForTests()
        val owner = NavigationOnlySessionOwner()
        val ownerLease = owners.installOwnerForTests(owner)
        try {
            grantNotificationPermissionForTest()
            ActivityScenario.launch<MainActivity>(Intent(target, MainActivity::class.java)).use { scenario ->
                barrier()
                scenario.onActivity { activity ->
                    MainActivity.db = f.helper
                    activity.getPreferences()
                    assertEquals("42", f.preferences.getString("hostPreferenceList", null))
                    activity.selectHostAndConnect(blank.toInt())
                }
                barrier()
                scenario.onActivity { activity -> activity.getPreferences(); assertEquals("storage-only.invalid", MainActivity.setting_host) }
                assertEquals(blank.toString(), f.preferences.getString("hostPreferenceList", null))
                assertEquals(fallback, f.preferences.getString(LegacyCredentialReferences.DEFAULT_PASSWORD, null))
                assertArrayEquals("NOT_REAL_default".toByteArray(), f.store.read(SecretId(fallback!!)))
                assertTrue(f.preferences.getBoolean("pref_usekeyauth", false)); f.assertNoPlaintext()
            }
            assertTrue("host navigation must dispatch selection through the session service",
                owner.selectionCompleted.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            assertEquals(1, owner.selectionCalls.get())
            assertEquals("navigation-only fixture must not create any SSH attempt", 0, owner.attemptCreations.get())
            assertNull(owner.controller.current.desiredHost)
            assertEquals(ConnectionState.Phase.DISCONNECTED, owner.controller.current.connection.phase)
            assertFalse(SessionForegroundService.hasPendingStart(target))
        } finally {
            target.stopService(Intent(target, SessionForegroundService::class.java))
            instrumentation.waitForIdleSync()
            owner.close()
            ownerLease.close()
        }
    }

    @Test fun actualPasswordReentryDialogIsExplicitMemoryOnlyAndLeavingCancelsPendingAttempt() = uiFixture { f ->
        assertTrue(f.repository.prepare()); f.preferences.edit(commit = true) { putBoolean("pref_usekeyauth", false) }
        f.deleteKey(); val files = f.encryptedFiles(); val rows = f.rows("contacts")
        ActivityScenario.launch<SettingsActivity>(Intent(target, SettingsActivity::class.java)).use { scenario ->
            barrier()
            val material = AtomicReference<AuthenticationMaterial?>(); val done = CountDownLatch(1)
            lateinit var ui: CredentialUi
            scenario.onActivity { activity -> ui = requireNotNull(activity.settingsFragmentForTesting().credentialUiForTesting) }
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

    @Test fun actualMigrationFailureKeepsMetadataAvailableButRejectsCredentialReplacement() = uiFixture { f ->
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
                assertEquals("", dialog.findViewById<EditText>(R.id.new_host_password).text.toString())
                enter(dialog.findViewById(R.id.new_host_password), "NOT_REAL_cannot_save")
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            barrier(); barrier()
            scenario.onActivity { activity ->
                val dialog = editor(activity)
                assertTrue(dialog.isShowing)
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            }
        }
        ActivityScenario.launch<SettingsActivity>(Intent(target, SettingsActivity::class.java)).use { scenario ->
            barrier()
            scenario.onActivity { activity ->
                assertTrue(activity.findPreference("pref_usekeyauth").isEnabled)
                assertFalse(activity.findPreference("pref_key_passphrase").isPersistent)
                assertNotNull(activity.findPreference("pref_addkeybutton"))
                assertNull(activity.settingsFragmentForTesting().findSettingPreference("setting_pass"))
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
                scenario.onActivity { activity -> MainActivity.db = f.helper; activity.getPreferences(); ui = field(activity, "credentialUi") as CredentialUi }
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
                scenario.onActivity { ui = field(it, "credentialUi") as CredentialUi }
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
                scenario.onActivity { ui = field(it, "credentialUi") as CredentialUi }
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
        val context = object : IsolatedDatabaseContext(target, File(root, "databases")) {
            override fun getNoBackupFilesDir(): File = File(root, "no_backup")
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
            context.assertDatabasePath(helper.writableDatabase, "contactsManager")
            rows("contacts").forEach { passwords[it[0]!!.toLong()] = it[6]!! }
            preferences.edit(commit = true) {
                clear(); putString("hostPreferenceList", "42"); putBoolean("pref_usekeyauth", true)
                putString("setting_host", "fallback.local"); putString("setting_user", "fallback"); putString("setting_port", "22")
                putString("setting_pass", "NOT_REAL_default"); putString("pref_key_passphrase", "NOT_REAL_phrase")
                putString(AndroidPrivateKeys.LEGACY_PATH, "/NOT_REAL_missing/key")
            }
        }
        fun reopen() {
            helper.close()
            helper = DatabaseHandler(context)
            context.assertDatabasePath(helper.writableDatabase, "contactsManager")
            repository = CredentialRepository(helper, preferences, store)
        }
        fun rows(table: String): List<List<String?>> = helper.readableDatabase.rawQuery("SELECT * FROM $table ORDER BY id", null).use { c -> buildList { while (c.moveToNext()) add((0 until c.columnCount).map(c::getString)) } }
        fun encryptedFiles() = File(context.noBackupFilesDir, "secrets").listFiles().orEmpty()
            .associate { it.name to it.readBytes().toList() }
        fun keystore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        fun deleteKey() { keystore().deleteEntry(alias) }
        fun assertNoPlaintext() {
            assertTrue(rows("contacts").all { it[6] == null && it[7] != null })
            assertFalse(preferences.contains("setting_pass")); assertFalse(preferences.contains("pref_key_passphrase"))
            File(context.noBackupFilesDir, "secrets").listFiles().orEmpty().forEach {
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
        // Lifecycle/picker main callbacks can enqueue repository work. Drain them BEFORE placing the
        // worker barrier, so fixture teardown never closes SQLite under a queued onResume prepare.
        instrumentation.waitForIdleSync()
        val latch = CountDownLatch(1); AndroidCredentials.get(target).execute { latch.countDown() }
        assertTrue(latch.await(10, TimeUnit.SECONDS)); instrumentation.waitForIdleSync()
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

    private fun grantNotificationPermissionForTest() {
        if (Build.VERSION.SDK_INT < 33) return
        val permission = Manifest.permission.POST_NOTIFICATIONS
        val isGranted = target.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        if (!isGranted) {
            // Revocation force-stops the target process, including this instrumentation runner.
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("pm grant ${target.packageName} $permission"),
            ).use { it.readBytes() }
        }
        assertEquals("notification permission fixture state", PackageManager.PERMISSION_GRANTED,
            target.checkSelfPermission(permission))
    }
    private fun toolbarTitleView(toolbar: androidx.appcompat.widget.Toolbar): TextView? {
        val expected = toolbar.title?.toString()?.takeIf(String::isNotEmpty) ?: return null
        fun findTitle(view: View): TextView? {
            if (view is TextView && view.text?.toString() == expected) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) {
                findTitle(view.getChildAt(index))?.let { return it }
            }
            return null
        }
        return findTitle(toolbar)
    }
    private fun editor(activity: ListEditActivity) = field(activity, "editing") as AlertDialog
    private fun discoveryDialog(activity: ListEditActivity) = field(activity, "discoveryDialog") as AlertDialog
    private fun discoveryDialogOrNull(activity: ListEditActivity) = field(activity, "discoveryDialog") as AlertDialog?
    private fun secretDialog(activity: SettingsActivity): AlertDialog {
        return dialog(requireNotNull(activity.settingsFragmentForTesting().credentialUiForTesting))
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
