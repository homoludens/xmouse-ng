package com.stripe1.xmouse.secrets

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import androidx.core.database.sqlite.transaction
import com.stripe1.xmouse.DatabaseHandler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * A versioned allowlist of non-secret app metadata for Android's system backup/transfer agent.
 * Never serializes password/key/passphrase bytes, credential references, raw key paths, or SQLite
 * pages. Credential-presence bits are restored as explicit re-entry requirements, not references.
 */
internal object CredentialMetadataBackup {
    const val HOST_REENTRY_IDS = "backup_credential_reentry_host_ids"
    const val DEFAULT_PASSWORD_REENTRY = "backup_default_password_reentry_required"
    const val PASSPHRASE_REENTRY = "backup_passphrase_reentry_required"
    const val PRIVATE_KEY_REENTRY = "backup_private_key_reentry_required"

    private const val MAGIC = 0x584d5342 // XMSB
    private const val VERSION = 1
    private const val MAX_SNAPSHOT_BYTES = 8 * 1024 * 1024
    private const val MAX_STRING_BYTES = 1024 * 1024
    private const val MAX_ROWS = 20_000
    private const val PENDING_REENTRY_TABLE = "credential_backup_restore_pending"

    private val stringPreferences = listOf(
        "setting_host", "setting_user", "setting_port", "hostPreferenceList",
        "setting_shell", "setting_dotool_initial", "setting_xdotool_initial",
        "sensitivity_list", "delay_list", "mdelay_list",
        "gyro_z_sensitivity_list", "gyro_y_sensitivity_list",
        "gyro_z_threshold_list", "gyro_y_threshold_list",
        "js_sensitivity_list", "js_size_list", "js_dead_zone_list",
    )
    private val booleanPreferences = listOf(
        "pref_usekeyauth", "autologin_checkbox", "keyboard_autoclear",
        "setting_keyboard_batch", "keyboard_layout_locked", "mouse_invert_scroll",
    )
    private val allowedPreferences = (stringPreferences + booleanPreferences).toSet()

    private data class Host(
        val id: Long,
        val time: String?,
        val alias: String?,
        val endpoint: String?,
        val username: String?,
        val port: String?,
        val locatorType: String?,
        val serviceInstance: String?,
        val serviceType: String?,
        val serviceDomain: String?,
        val stableTarget: String?,
        val credentialRequired: Boolean,
    )

    private data class Script(val id: Long, val time: String?, val alias: String?, val command: String?)
    private data class PreferenceValue(val key: String, val value: Any)
    private data class Snapshot(
        val hosts: List<Host>,
        val scripts: List<Script>,
        val preferences: List<PreferenceValue>,
        val defaultPasswordRequired: Boolean,
        val passphraseRequired: Boolean,
        val privateKeyRequired: Boolean,
    )

    /** Called only on CredentialRepository's serialized worker. Queries never select secret bytes. */
    fun capture(database: DatabaseHandler, preferences: SharedPreferences): ByteArray {
        val db = database.readableDatabase
        check(!db.inTransaction()) { "Backup snapshot requires a stable SQLite transaction boundary" }
        val reentryIds = preferences.getStringSet(HOST_REENTRY_IDS, emptySet())?.toSet().orEmpty()
        val hosts = db.rawQuery(
            "SELECT id, time, Alias, Host, Username, Port, LocatorType, ServiceInstanceName, " +
                "ServiceType, ServiceDomain, StableTargetHostname, " +
                "(Password IS NOT NULL OR PasswordCredentialRef IS NOT NULL) FROM contacts ORDER BY id",
            null,
        ).use { cursor -> buildList {
            while (cursor.moveToNext()) {
                if (size >= MAX_ROWS) throw IOException("Configuration exceeds the safe backup limit")
                val id = cursor.getLong(0)
                add(Host(
                    id, cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getString(4),
                    cursor.getString(5), cursor.getString(6), cursor.getString(7), cursor.getString(8),
                    cursor.getString(9), cursor.getString(10),
                    cursor.getInt(11) != 0 || id.toString() in reentryIds,
                ))
            }
        } }
        val scripts = db.rawQuery("SELECT id, time, Alias, Script FROM scripts ORDER BY id", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    if (size >= MAX_ROWS) throw IOException("Configuration exceeds the safe backup limit")
                    add(Script(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getString(3)))
                }
            }
        }

        // Read only explicitly allowlisted, non-secret keys. Do not call SharedPreferences.all:
        // it would materialize legacy password/passphrase values in this snapshot operation.
        val preferenceValues = buildList {
            stringPreferences.forEach { key ->
                if (preferences.contains(key)) {
                    add(PreferenceValue(key, preferences.getString(key, null)
                        ?: throw IOException("Unsupported saved configuration value")))
                }
            }
            booleanPreferences.forEach { key ->
                if (preferences.contains(key)) add(PreferenceValue(key, preferences.getBoolean(key, false)))
            }
        }
        val snapshot = Snapshot(
            hosts,
            scripts,
            preferenceValues,
            credentialPresent(preferences, "setting_pass", LegacyCredentialReferences.DEFAULT_PASSWORD, DEFAULT_PASSWORD_REENTRY),
            credentialPresent(preferences, "pref_key_passphrase", LegacyCredentialReferences.GLOBAL_PASSPHRASE, PASSPHRASE_REENTRY),
            preferences.contains(LegacyCredentialReferences.GLOBAL_PRIVATE_KEY) ||
                preferences.contains(AndroidPrivateKeys.LEGACY_PATH) || preferences.getBoolean(PRIVATE_KEY_REENTRY, false),
        )
        return encode(snapshot)
    }

    private fun credentialPresent(preferences: SharedPreferences, legacy: String, reference: String, marker: String) =
        preferences.contains(legacy) || preferences.contains(reference) || preferences.getBoolean(marker, false)

    fun restore(context: Context, bytes: ByteArray) = restore(
        context, bytes, android.preference.PreferenceManager.getDefaultSharedPreferences(context),
    )

    /** SharedPreferences is injectable so the SQLite/preferences failure boundary is testable. */
    internal fun restore(context: Context, bytes: ByteArray, prefs: SharedPreferences) {
        val snapshot = decode(bytes)
        val database = DatabaseHandler(context)
        try {
            val db = database.writableDatabase
            val pendingCredentialHosts = linkedSetOf<String>()
            db.transaction {
                // This journal is committed atomically with metadata imports. If the separate
                // preferences commit fails, retry can recover only IDs this restore actually added.
                db.execSQL("CREATE TABLE IF NOT EXISTS $PENDING_REENTRY_TABLE (host_id INTEGER PRIMARY KEY)")
                snapshot.hosts.forEach { host ->
                    val values = ContentValues().apply {
                        put("id", host.id); put("time", host.time)
                        put("Alias", host.alias); put("Host", host.endpoint); put("Username", host.username)
                        put("Port", host.port); putNull("Password"); putNull(DatabaseHandler.PASSWORD_CREDENTIAL_REF)
                        put(DatabaseHandler.LOCATOR_TYPE, host.locatorType ?: DatabaseHandler.LOCATOR_MANUAL)
                        put(DatabaseHandler.SERVICE_INSTANCE_NAME, host.serviceInstance)
                        put(DatabaseHandler.SERVICE_TYPE, host.serviceType)
                        put(DatabaseHandler.SERVICE_DOMAIN, host.serviceDomain)
                        put(DatabaseHandler.STABLE_TARGET_HOSTNAME, host.stableTarget)
                    }
                    val inserted = db.insertWithOnConflict("contacts", null, values, SQLiteDatabase.CONFLICT_IGNORE)
                    if (inserted != -1L && host.credentialRequired) {
                        db.insertWithOnConflict(PENDING_REENTRY_TABLE, null,
                            ContentValues().apply { put("host_id", host.id) }, SQLiteDatabase.CONFLICT_IGNORE)
                    }
                }
                snapshot.scripts.forEach { script ->
                    val values = ContentValues().apply {
                        put("id", script.id); put("time", script.time); put("Alias", script.alias); put("Script", script.command)
                    }
                    db.insertWithOnConflict("scripts", null, values, SQLiteDatabase.CONFLICT_IGNORE)
                }
                db.rawQuery(
                    "SELECT pending.host_id FROM $PENDING_REENTRY_TABLE AS pending " +
                        "JOIN contacts ON contacts.id=pending.host_id " +
                        "WHERE contacts.Password IS NULL AND contacts.PasswordCredentialRef IS NULL",
                    null,
                ).use { cursor -> while (cursor.moveToNext()) pendingCredentialHosts.add(cursor.getLong(0).toString()) }
            }

            val existingHostMarkers = prefs.getStringSet(HOST_REENTRY_IDS, emptySet())?.toMutableSet()
                ?: linkedSetOf()
            existingHostMarkers.addAll(pendingCredentialHosts)
            val editor = prefs.edit().putStringSet(HOST_REENTRY_IDS, existingHostMarkers)
            snapshot.preferences.forEach { value ->
                if (!prefs.contains(value.key)) when (value.value) {
                    is String -> editor.putString(value.key, value.value)
                    is Boolean -> editor.putBoolean(value.key, value.value)
                    else -> throw IOException("Unsupported saved configuration value")
                }
            }
            if (snapshot.defaultPasswordRequired && !hasCredential(prefs, "setting_pass", LegacyCredentialReferences.DEFAULT_PASSWORD)) {
                editor.putBoolean(DEFAULT_PASSWORD_REENTRY, true)
            }
            if (snapshot.passphraseRequired && !hasCredential(prefs, "pref_key_passphrase", LegacyCredentialReferences.GLOBAL_PASSPHRASE)) {
                editor.putBoolean(PASSPHRASE_REENTRY, true)
            }
            if (snapshot.privateKeyRequired && !prefs.contains(LegacyCredentialReferences.GLOBAL_PRIVATE_KEY) &&
                !prefs.contains(AndroidPrivateKeys.LEGACY_PATH)) {
                editor.putBoolean(PRIVATE_KEY_REENTRY, true)
            }
            if (!editor.commit()) throw IOException("Restored configuration could not be committed")

            // Clear only after durable marker commit. If deletion fails, replaying these markers is
            // idempotent; credentialed rows are filtered out before they can receive a marker.
            db.transaction {
                db.delete(PENDING_REENTRY_TABLE, null, null)
            }
        } finally {
            database.close()
        }
    }

    private fun hasCredential(preferences: SharedPreferences, legacy: String, reference: String) =
        preferences.contains(legacy) || preferences.contains(reference)

    private fun encode(snapshot: Snapshot): ByteArray {
        val bounded = BoundedByteArrayOutputStream(MAX_SNAPSHOT_BYTES)
        DataOutputStream(bounded).use { out ->
            out.writeInt(MAGIC); out.writeInt(VERSION)
            out.writeInt(snapshot.hosts.size)
            snapshot.hosts.forEach { host ->
                out.writeLong(host.id); out.writeBoolean(host.credentialRequired)
                listOf(host.time, host.alias, host.endpoint, host.username, host.port, host.locatorType,
                    host.serviceInstance, host.serviceType, host.serviceDomain, host.stableTarget).forEach { out.writeNullableString(it) }
            }
            out.writeInt(snapshot.scripts.size)
            snapshot.scripts.forEach { script ->
                out.writeLong(script.id); out.writeNullableString(script.time)
                out.writeNullableString(script.alias); out.writeNullableString(script.command)
            }
            out.writeInt(snapshot.preferences.size)
            snapshot.preferences.forEach { value ->
                out.writeNullableString(value.key)
                when (val item = value.value) {
                    is String -> { out.writeByte(1); out.writeNullableString(item) }
                    is Boolean -> { out.writeByte(2); out.writeBoolean(item) }
                    else -> throw IOException("Unsupported saved configuration value")
                }
            }
            out.writeBoolean(snapshot.defaultPasswordRequired)
            out.writeBoolean(snapshot.passphraseRequired)
            out.writeBoolean(snapshot.privateKeyRequired)
        }
        return bounded.toByteArray()
    }

    private fun decode(bytes: ByteArray): Snapshot {
        if (bytes.size > MAX_SNAPSHOT_BYTES) throw IOException("Restored configuration exceeds the safe limit")
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            if (input.readInt() != MAGIC || input.readInt() != VERSION) throw IOException("Unsupported configuration backup")
            val hostCount = input.readCount()
            val hosts = ArrayList<Host>(hostCount)
            val hostIds = HashSet<Long>()
            repeat(hostCount) {
                val id = input.readLong()
                if (!hostIds.add(id)) throw IOException("Invalid configuration backup")
                val credentialRequired = input.readBoolean()
                hosts.add(Host(id, input.readNullableString(), input.readNullableString(), input.readNullableString(),
                    input.readNullableString(), input.readNullableString(), input.readNullableString(),
                    input.readNullableString(), input.readNullableString(), input.readNullableString(),
                    input.readNullableString(), credentialRequired))
            }
            val scriptCount = input.readCount()
            val scripts = ArrayList<Script>(scriptCount)
            val scriptIds = HashSet<Long>()
            repeat(scriptCount) {
                val id = input.readLong()
                if (!scriptIds.add(id)) throw IOException("Invalid configuration backup")
                scripts.add(Script(id, input.readNullableString(), input.readNullableString(), input.readNullableString()))
            }
            val preferenceCount = input.readCount()
            val preferences = ArrayList<PreferenceValue>(preferenceCount)
            val preferenceKeys = HashSet<String>()
            repeat(preferenceCount) {
                val key = input.readNullableString() ?: throw IOException("Invalid configuration backup")
                if (key !in allowedPreferences || !preferenceKeys.add(key)) throw IOException("Invalid configuration backup")
                val value = when (input.readUnsignedByte()) {
                    1 -> input.readNullableString() ?: throw IOException("Invalid configuration backup")
                    2 -> input.readBoolean()
                    else -> throw IOException("Invalid configuration backup")
                }
                if ((key in stringPreferences) != (value is String)) throw IOException("Invalid configuration backup")
                preferences.add(PreferenceValue(key, value))
            }
            val snapshot = Snapshot(hosts, scripts, preferences, input.readBoolean(), input.readBoolean(), input.readBoolean())
            if (input.read() != -1) throw IOException("Invalid configuration backup")
            return snapshot
        }
    }

    private fun DataInputStream.readCount(): Int = readInt().also {
        if (it < 0 || it > MAX_ROWS) throw IOException("Invalid configuration backup")
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        if (value == null) { writeInt(-1); return }
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        if (encoded.size > MAX_STRING_BYTES) throw IOException("Configuration value exceeds the safe backup limit")
        writeInt(encoded.size); write(encoded)
    }

    private fun DataInputStream.readNullableString(): String? {
        val size = readInt()
        if (size == -1) return null
        if (size < 0 || size > MAX_STRING_BYTES || size > available()) throw IOException("Invalid configuration backup")
        val encoded = ByteArray(size)
        readFully(encoded)
        return String(encoded, StandardCharsets.UTF_8)
    }

    private class BoundedByteArrayOutputStream(private val maximum: Int) : ByteArrayOutputStream() {
        override fun write(value: Int) {
            if (count >= maximum) throw IOException("Configuration exceeds the safe backup limit")
            super.write(value)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (length < 0 || length > maximum - count) throw IOException("Configuration exceeds the safe backup limit")
            super.write(buffer, offset, length)
        }
    }
}
