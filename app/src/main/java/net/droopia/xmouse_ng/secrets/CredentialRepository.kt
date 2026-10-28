package net.droopia.xmouse_ng.secrets

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteException
import android.os.Looper
import android.preference.PreferenceManager
import androidx.core.database.sqlite.transaction
import net.droopia.xmouse_ng.DatabaseHandler
import net.droopia.xmouse_ng.connection.Endpoint
import net.droopia.xmouse_ng.connection.EndpointHost
import net.droopia.xmouse_ng.connection.EndpointLocator
import net.droopia.xmouse_ng.connection.ComputerDraft
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.Executors

/** One application owner/queue. No Activity, view, plaintext cache, or implicit key reset. */
object AndroidCredentials {
    @Volatile private var instance: CredentialRepository? = null
    @JvmStatic fun get(context: Context): CredentialRepository = instance ?: synchronized(this) {
        instance ?: context.applicationContext.let {
            CredentialRepository(DatabaseHandler(it), PreferenceManager.getDefaultSharedPreferences(it), AndroidSecretStore.open(it))
                .also { repository -> instance = repository }
        }
    }
}

class CredentialRepositoryException : Exception("Protected credentials unavailable; existing configuration was kept")

/** Contains only non-secret metadata and an opaque reference. */
data class CredentialHost(
    val id: Long, val alias: String?, val host: String?, val user: String?, val port: String?,
    val passwordReference: String?, val legacyPasswordPresent: Boolean,
    val credentialReentryRequired: Boolean = false,
    /** Stable saved service locator, if this profile was explicitly saved from discovery. */
    val serviceLocator: EndpointLocator.DiscoveredService? = null,
) {
    /** Reconstructs logical intent; observed addresses and network handles are never stored here. */
    fun endpoint(): Endpoint {
        val savedPort = Endpoint.parsePort(requireNotNull(port))
        return serviceLocator?.let { Endpoint(it, savedPort) }
            ?: Endpoint.manual(requireNotNull(host), savedPort)
    }

    fun displayEndpoint(): String = serviceLocator?.let { locator ->
        locator.stableTargetHostname?.original ?: "${locator.instanceName} (${locator.serviceType}.${locator.domain})"
    } ?: host.orEmpty()

    /** Never prefill the editable host field with a service's last DHCP address. */
    fun editableEndpoint(): String = serviceLocator?.stableTargetHostname?.original
        ?: if (serviceLocator == null) host.orEmpty() else ""
}

enum class CredentialKind { PASSWORD, PASSPHRASE, PRIVATE_KEY }
fun interface CredentialPrompts { fun request(kind: CredentialKind, request: CredentialReentryRequest) }
fun interface AuthenticationCallback { fun complete(material: AuthenticationMaterial?, error: String?) }

/** Attempt-local only. Never serialize this object; caller must close it. */
class AuthenticationMaterial(
    val host: CredentialHost, val useKey: Boolean, val password: ByteArray,
    val passphrase: ByteArray, val privateKey: ByteArray,
) : Closeable {
    override fun close() { password.fill(0); passphrase.fill(0); privateKey.fill(0) }
}

/** Blocking methods run on execute's serialized worker, including migration and key imports. */
class CredentialRepository internal constructor(
    private val database: DatabaseHandler,
    internal val preferences: SharedPreferences,
    internal val secrets: SecretStore,
) {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile var isWritable = false
        private set
    fun execute(action: Runnable) = worker.execute(action)

    /** Retry explicitly on screen entry. A failure never enables writes or legacy secret fallback. */
    fun prepare(): Boolean {
        offMain()
        isWritable = false
        return try {
            // Published references are authoritative, including when unexpected legacy text remains.
            // Never let a restart recreate a missing protected record from that text. Unreferenced
            // migration preparations still resume through 3.4's verified-write checkpoints below.
            hosts().forEach { host -> host.passwordReference?.let { readStored(it, false).fill(0) } }
            listOf(LegacyCredentialReferences.DEFAULT_PASSWORD, LegacyCredentialReferences.GLOBAL_PASSPHRASE,
                LegacyCredentialReferences.GLOBAL_PRIVATE_KEY).forEach { key ->
                preferences.getString(key, null)?.let { readStored(it, false).fill(0) }
            }
            AndroidCredentialMigration.run(database, preferences, secrets)
            isWritable = true
            true
        } catch (_: SecretStoreException) { false }
        catch (_: CredentialMigrationException) { false }
        catch (_: CredentialRepositoryException) { false }
        catch (_: SQLiteException) { false }
        catch (_: ClassCastException) { false }
    }

    fun hosts(): List<CredentialHost> {
        offMain()
        val reentryIds = preferences.getStringSet(CredentialMetadataBackup.HOST_REENTRY_IDS, emptySet())?.toSet().orEmpty()
        return database.readableDatabase.rawQuery(
            "SELECT id, Alias, Host, Username, Port, PasswordCredentialRef, Password IS NOT NULL, " +
                "LocatorType, ServiceInstanceName, ServiceType, ServiceDomain, StableTargetHostname " +
                "FROM contacts ORDER BY id", null,
        ).use { c -> buildList {
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val reference = c.getString(5)
                val restoredReentry = reference == null && c.getInt(6) == 0 && id.toString() in reentryIds
                add(CredentialHost(id, c.getString(1), c.getString(2), c.getString(3), c.getString(4), reference,
                    c.getInt(6) != 0 || restoredReentry, restoredReentry,
                    readServiceLocator(c.getString(7), c.getString(8), c.getString(9), c.getString(10), c.getString(11))))
            }
        } }
    }

    fun selectedHost(): CredentialHost {
        val selected = preferences.getString("hostPreferenceList", "0")?.toLongOrNull()
        return hosts().firstOrNull { it.id == selected } ?: CredentialHost(
            0, null, preferences.getString("setting_host", ""), preferences.getString("setting_user", ""),
            preferences.getString("setting_port", "22"), preferences.getString(LegacyCredentialReferences.DEFAULT_PASSWORD, null),
            preferences.contains("setting_pass") || preferences.getBoolean(CredentialMetadataBackup.DEFAULT_PASSWORD_REENTRY, false),
            preferences.getString(LegacyCredentialReferences.DEFAULT_PASSWORD, null) == null &&
                !preferences.contains("setting_pass") &&
                preferences.getBoolean(CredentialMetadataBackup.DEFAULT_PASSWORD_REENTRY, false),
        )
    }

    fun selectHost(id: Long): CredentialHost {
        offMain()
        val host = hosts().firstOrNull { it.id == id } ?: throw CredentialRepositoryException()
        // Selection is non-secret recovery configuration; never copy a host password to the fallback.
        val committed = preferences.edit().putString("hostPreferenceList", id.toString()).commit()
        if (!committed) throw CredentialRepositoryException()
        return host
    }

    fun readPassword(host: CredentialHost): ByteArray = readStored(host.passwordReference, host.legacyPasswordPresent)
    fun readPassphrase(): ByteArray = readStored(
        preferences.getString(LegacyCredentialReferences.GLOBAL_PASSPHRASE, null),
        preferences.contains("pref_key_passphrase") || preferences.getBoolean(CredentialMetadataBackup.PASSPHRASE_REENTRY, false),
    )

    /**
     * Settings may replace a known restore marker only when the backup intentionally omitted the
     * record. A present reference or legacy value always goes through the normal strict read path.
     */
    fun readPassphraseForEdit(): ByteArray {
        offMain()
        val reference = preferences.getString(LegacyCredentialReferences.GLOBAL_PASSPHRASE, null)
        if (reference != null) return readStored(reference, false)
        if (preferences.contains("pref_key_passphrase")) return readStored(null, true)
        if (preferences.getBoolean(CredentialMetadataBackup.PASSPHRASE_REENTRY, false)) return byteArrayOf()
        return byteArrayOf()
    }

    /** Called by the system BackupAgent only on this repository's serialized worker. */
    internal fun snapshotForBackup(): ByteArray {
        offMain()
        return CredentialMetadataBackup.capture(database, preferences)
    }

    /** Fresh records per edit/add avoid clobbering old credentials, even after SQLite ID reuse. */
    fun saveHost(expected: CredentialHost?, alias: String, host: String, user: String, port: String, password: ByteArray): Long {
        // Validate before reading or writing secrets so a malformed edit cannot replace a good profile.
        val endpoint = Endpoint.manual(host, port)
        return saveHost(expected, alias, host, user, port, password, endpoint)
    }

    /** Save only the logical discovery locator; its current IPs and Android network are attempt-local. */
    fun saveDiscoveredHost(
        expected: CredentialHost?,
        alias: String,
        locator: EndpointLocator.DiscoveredService,
        user: String,
        port: String,
        password: ByteArray,
    ): Long {
        val endpoint = Endpoint(locator, Endpoint.parsePort(port))
        val savedHost = locator.stableTargetHostname?.original
        return saveHost(expected, alias, savedHost, user, port, password, endpoint)
    }

    private fun saveHost(
        expected: CredentialHost?, alias: String, host: String?, user: String, port: String,
        password: ByteArray, endpoint: Endpoint,
    ): Long {
        ComputerDraft.requireUsername(user)
        requireWritable()
        expected?.takeUnless { it.credentialReentryRequired }?.let { readPassword(it).fill(0) }
        val reference = verifiedReplacement(password)
        val db = database.writableDatabase
        val savedId = db.transaction {
            val values = ContentValues().apply {
                put("Alias", alias)
                if (host == null) putNull("Host") else put("Host", host)
                put("Username", user); put("Port", port)
                putNull("Password"); put(DatabaseHandler.PASSWORD_CREDENTIAL_REF, reference.value)
                when (val locator = endpoint.locator) {
                    is EndpointLocator.Manual -> {
                        put(DatabaseHandler.LOCATOR_TYPE, DatabaseHandler.LOCATOR_MANUAL)
                        putNull(DatabaseHandler.SERVICE_INSTANCE_NAME)
                        putNull(DatabaseHandler.SERVICE_TYPE)
                        putNull(DatabaseHandler.SERVICE_DOMAIN)
                        putNull(DatabaseHandler.STABLE_TARGET_HOSTNAME)
                    }
                    is EndpointLocator.DiscoveredService -> {
                        put(DatabaseHandler.LOCATOR_TYPE, DatabaseHandler.LOCATOR_DISCOVERED_SERVICE)
                        put(DatabaseHandler.SERVICE_INSTANCE_NAME, locator.instanceName)
                        put(DatabaseHandler.SERVICE_TYPE, locator.serviceType)
                        put(DatabaseHandler.SERVICE_DOMAIN, locator.domain)
                        locator.stableTargetHostname?.original?.let { put(DatabaseHandler.STABLE_TARGET_HOSTNAME, it) }
                            ?: putNull(DatabaseHandler.STABLE_TARGET_HOSTNAME)
                    }
                }
            }
            if (expected == null) {
                putTimestamp(values)
                db.insertOrThrow("contacts", null, values)
            } else {
                if (db.update("contacts", values, "id=? AND PasswordCredentialRef IS ?",
                        arrayOf(expected.id.toString(), expected.passwordReference)) != 1) throw CredentialRepositoryException()
                expected.id
            }
        }
        clearHostReentryMarker(savedId)
        return savedId
    }

    /** Explicit unchanged-password intent. No prepare/migration, secret reads/writes or preference
     * changes: even a restore/re-entry marker or unavailable device key must survive this edit.
     * Only an existing, unchanged metadata snapshot may be updated. */
    fun updateHostMetadata(expected: CredentialHost, alias: String, endpoint: Endpoint, user: String): Long {
        offMain()
        ComputerDraft.requireUsername(user)
        if (hosts().firstOrNull { it.id == expected.id } != expected) throw CredentialRepositoryException()
        val values = ContentValues().apply {
            put("Alias", alias); put("Username", user); put("Port", endpoint.port.toString())
            when (val locator = endpoint.locator) {
                is EndpointLocator.Manual -> {
                    put("Host", locator.host.original)
                    put(DatabaseHandler.LOCATOR_TYPE, DatabaseHandler.LOCATOR_MANUAL)
                    putNull(DatabaseHandler.SERVICE_INSTANCE_NAME); putNull(DatabaseHandler.SERVICE_TYPE)
                    putNull(DatabaseHandler.SERVICE_DOMAIN); putNull(DatabaseHandler.STABLE_TARGET_HOSTNAME)
                }
                is EndpointLocator.DiscoveredService -> {
                    locator.stableTargetHostname?.original?.let { put("Host", it) } ?: putNull("Host")
                    put(DatabaseHandler.LOCATOR_TYPE, DatabaseHandler.LOCATOR_DISCOVERED_SERVICE)
                    put(DatabaseHandler.SERVICE_INSTANCE_NAME, locator.instanceName)
                    put(DatabaseHandler.SERVICE_TYPE, locator.serviceType); put(DatabaseHandler.SERVICE_DOMAIN, locator.domain)
                    locator.stableTargetHostname?.original?.let { put(DatabaseHandler.STABLE_TARGET_HOSTNAME, it) }
                        ?: putNull(DatabaseHandler.STABLE_TARGET_HOSTNAME)
                }
            }
        }
        if (database.writableDatabase.update("contacts", values, "id=? AND PasswordCredentialRef IS ?",
                arrayOf(expected.id.toString(), expected.passwordReference)) != 1) throw CredentialRepositoryException()
        return expected.id
    }

    private fun readServiceLocator(
        locatorType: String?, instanceName: String?, serviceType: String?, domain: String?, stableTarget: String?,
    ): EndpointLocator.DiscoveredService? = when (locatorType ?: DatabaseHandler.LOCATOR_MANUAL) {
        DatabaseHandler.LOCATOR_MANUAL -> null
        DatabaseHandler.LOCATOR_DISCOVERED_SERVICE -> try {
            EndpointLocator.DiscoveredService(
                requireNotNull(instanceName), requireNotNull(serviceType), requireNotNull(domain),
                stableTarget?.let(::EndpointHost),
            )
        } catch (_: IllegalArgumentException) {
            throw CredentialRepositoryException()
        } catch (_: IllegalStateException) {
            throw CredentialRepositoryException()
        }
        else -> throw CredentialRepositoryException()
    }

    fun deleteHost(expected: CredentialHost) {
        requireWritable()
        // Retain ciphertext; deleting metadata is not consent to reset unreadable device keys.
        if (database.writableDatabase.delete("contacts", "id=? AND PasswordCredentialRef IS ?",
                arrayOf(expected.id.toString(), expected.passwordReference)) != 1) throw CredentialRepositoryException()
    }

    fun savePassphrase(plaintext: ByteArray) = savePreference(LegacyCredentialReferences.GLOBAL_PASSPHRASE, "pref_key_passphrase", plaintext)
    fun saveDefaultPassword(plaintext: ByteArray) = savePreference(LegacyCredentialReferences.DEFAULT_PASSWORD, "setting_pass", plaintext)

    private fun savePreference(key: String, legacy: String, plaintext: ByteArray) {
        requireWritable()
        readStored(preferences.getString(key, null), preferences.contains(legacy)).fill(0)
        val id = verifiedReplacement(plaintext)
        val reentryMarker = when (key) {
            LegacyCredentialReferences.DEFAULT_PASSWORD -> CredentialMetadataBackup.DEFAULT_PASSWORD_REENTRY
            LegacyCredentialReferences.GLOBAL_PASSPHRASE -> CredentialMetadataBackup.PASSPHRASE_REENTRY
            else -> null
        }
        val editor = preferences.edit().putString(key, id.value).remove(legacy)
        reentryMarker?.let(editor::remove)
        val committed = editor.commit()
        if (!committed) { isWritable = false; throw CredentialRepositoryException() }
    }

    private fun clearHostReentryMarker(id: Long) {
        val existing = preferences.getStringSet(CredentialMetadataBackup.HOST_REENTRY_IDS, emptySet())?.toMutableSet()
            ?: return
        if (!existing.remove(id.toString())) return
        if (!preferences.edit().putStringSet(CredentialMetadataBackup.HOST_REENTRY_IDS, existing).commit()) {
            isWritable = false
            throw CredentialRepositoryException()
        }
    }

    private fun verifiedReplacement(bytes: ByteArray): SecretId {
        val id = SecretId("credential_${UUID.randomUUID().toString().replace("-", "")}")
        secrets.write(id, bytes)
        val verified = secrets.read(id) ?: throw CredentialRepositoryException()
        try { if (!bytes.contentEquals(verified)) throw CredentialRepositoryException() } finally { verified.fill(0) }
        return id
    }

    private fun readStored(reference: String?, legacy: Boolean): ByteArray {
        offMain()
        if (reference == null) {
            if (legacy) throw CredentialRepositoryException()
            return byteArrayOf() // Genuinely absent optional/empty credential, never failed storage.
        }
        return secrets.read(id(reference)) ?: throw CredentialRepositoryException()
    }

    /** All asynchronous continuations return to this worker; no worker blocks waiting for a dialog. */
    fun authenticate(prompts: CredentialPrompts, completion: AuthenticationCallback) {
        authenticate(authenticationSelection(), prompts, completion)
    }

    /** Capture after prepare, on this worker. Includes profile ID and fresh opaque secret references. */
    fun authenticationSelection(): AuthenticationSelection {
        return authenticationSelection(selectedHost())
    }

    /** Candidate snapshot: never selects/saves the candidate, prepares migration, or imports a key. */
    fun authenticationSelection(host: CredentialHost): AuthenticationSelection {
        offMain()
        return AuthenticationSelection(host, preferences.getBoolean("pref_usekeyauth", false),
            preferences.getString(LegacyCredentialReferences.GLOBAL_PASSPHRASE, null),
            preferences.contains("pref_key_passphrase"),
            preferences.getBoolean(CredentialMetadataBackup.PASSPHRASE_REENTRY, false),
            preferences.getString(LegacyCredentialReferences.GLOBAL_PRIVATE_KEY, null),
            preferences.getBoolean(CredentialMetadataBackup.PRIVATE_KEY_REENTRY, false),
            preferences.getString(AndroidPrivateKeys.LEGACY_PATH, null))
    }

    fun authenticate(selection: AuthenticationSelection, prompts: CredentialPrompts, completion: AuthenticationCallback) {
        authenticate(selection, prompts, completion, false)
    }

    /** Read-only attempt material. Null replacement means the captured saved password reference;
     * an explicit empty array is a new computer's empty password, not a metadata replacement.
     * Legacy key import belongs to Settings/normal connection, never an unsaved test. */
    fun authenticateProbe(selection: AuthenticationSelection, replacement: ByteArray?, prompts: CredentialPrompts,
                          completion: AuthenticationCallback) {
        offMain()
        if (!selection.useKey && replacement != null) {
            completion.complete(AuthenticationMaterial(selection.host, false, replacement.copyOf(), byteArrayOf(), byteArrayOf()), null)
        } else authenticate(selection, prompts, completion, true)
    }

    private fun authenticate(selection: AuthenticationSelection, prompts: CredentialPrompts,
                             completion: AuthenticationCallback, readOnly: Boolean) {
        offMain()
        val host = selection.host
        val useKey = selection.useKey
        val keyRef = selection.privateKeyReference
        val reference = if (useKey) selection.passphraseReference else host.passwordReference
        val legacy = if (useKey) selection.legacyPassphrasePresent || selection.passphraseReentry else host.legacyPasswordPresent
        val restoredReentry = if (useKey) selection.passphraseReentry else host.credentialReentryRequired
        readAttempt(reference, legacy, if (useKey) CredentialKind.PASSPHRASE else CredentialKind.PASSWORD, prompts,
            done = { credential, error ->
            if (credential == null) { completion.complete(null, error); return@readAttempt }
            if (!useKey) {
                completion.complete(AuthenticationMaterial(host, false, credential, byteArrayOf(), byteArrayOf()), null)
            } else {
                if (keyRef != null) {
                    readAttempt(keyRef, false, CredentialKind.PRIVATE_KEY, prompts, done = { key, keyError ->
                        if (key == null) { credential.fill(0); completion.complete(null, keyError) }
                        else completeKeyAuthentication(host, credential, key, completion)
                    })
                } else if (selection.privateKeyReentry) {
                    requestMemoryOnly(CredentialKind.PRIVATE_KEY, prompts) { key, keyError ->
                        if (key == null) { credential.fill(0); completion.complete(null, keyError) }
                        else completeKeyAuthentication(host, credential, key, completion)
                    }
                } else {
                    if (readOnly) {
                        credential.fill(0)
                        completion.complete(null, "Private key unavailable. Open Settings > Private Key; existing configuration was kept.")
                        return@readAttempt
                    }
                    try {
                        // The approved importer validates/encrypts readable legacy bytes before use.
                        // Do not substitute a newly selected/imported key for this captured attempt.
                        if (preferences.getString(LegacyCredentialReferences.GLOBAL_PRIVATE_KEY, null) != keyRef ||
                            preferences.getString(AndroidPrivateKeys.LEGACY_PATH, null) != selection.legacyPrivateKeyPath) {
                            throw CredentialRepositoryException()
                        }
                        val key = AndroidPrivateKeys.open(preferences, secrets).readForAuthentication(credential)
                        completion.complete(AuthenticationMaterial(host, true, byteArrayOf(), credential, key), null)
                    } catch (_: Exception) {
                        credential.fill(0)
                        completion.complete(null, "Private key unavailable. Open Settings > Private Key to re-import; existing configuration was kept.")
                    }
                }
            }
        }, forceReentry = restoredReentry)
    }

    private fun completeKeyAuthentication(host: CredentialHost, phrase: ByteArray, key: ByteArray, completion: AuthenticationCallback) {
        val failure = try { BoundedSshKey.validate(key, phrase); null } catch (failure: PrivateKeyImportException) { failure }
        if (failure != null) {
            phrase.fill(0); key.fill(0)
            completion.complete(null, "Private key unavailable or invalid (${failure.reason.name}). Check Optional Key Passphrase or re-import; existing configuration was kept.")
        } else completion.complete(AuthenticationMaterial(host, true, byteArrayOf(), phrase, key), null)
    }

    private fun readAttempt(reference: String?, legacy: Boolean, kind: CredentialKind, prompts: CredentialPrompts,
                            done: (ByteArray?, String?) -> Unit, forceReentry: Boolean = false) {
        if (reference == null) {
            if (forceReentry) requestMemoryOnly(kind, prompts, done)
            else if (legacy) done(null, RECOVERY_MESSAGE) else done(byteArrayOf(), null)
            return
        }
        val secretId = try { id(reference) } catch (_: CredentialRepositoryException) { done(null, RECOVERY_MESSAGE); return }
        CredentialAccess(secrets).read(secretId, { prompts.request(kind, it) }) { result ->
            execute {
                when (result) {
                    is CredentialReadResult.Available -> done(result.plaintext, null)
                    CredentialReadResult.Cancelled -> done(null, null)
                    else -> done(null, RECOVERY_MESSAGE)
                }
            }
        }
    }

    private fun requestMemoryOnly(kind: CredentialKind, prompts: CredentialPrompts, done: (ByteArray?, String?) -> Unit) {
        val requestId = SecretId("restore_reentry_${UUID.randomUUID().toString().replace("-", "")}")
        val request = CredentialReentryRequest(requestId) { result ->
            execute {
                when (result) {
                    is CredentialReadResult.Available -> done(result.plaintext, null)
                    CredentialReadResult.Cancelled -> done(null, null)
                    else -> done(null, RECOVERY_MESSAGE)
                }
            }
        }
        prompts.request(kind, request)
    }

    private fun requireWritable() { offMain(); if (!isWritable || !prepare()) throw CredentialRepositoryException() }
    private fun id(value: String): SecretId = try { SecretId(value) } catch (_: IllegalArgumentException) { throw CredentialRepositoryException() }
    private fun putTimestamp(values: ContentValues) { values.put("time", database.dateFormat.format(java.util.Date())) }
    private fun offMain() { check(Looper.myLooper() != Looper.getMainLooper()) { "Credential I/O requires a worker" } }

    companion object {
        const val RECOVERY_MESSAGE = "Protected credentials unavailable. Existing hosts, scripts and encrypted records were kept. Restore storage/key access and retry; credentials were not reset."
    }
}
