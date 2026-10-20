package com.stripe1.xmouse.secrets

import android.content.ContentValues
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.core.database.sqlite.transaction
import com.stripe1.xmouse.DatabaseHandler

/**
 * Task-3.5's application credential repository calls this on its serialized worker, before enabling
 * credential writes. Never expose legacy secret readers/writers alongside this migration.
 * Does not close the caller-owned helper. Failures stop migration; retry with the same sources/store.
 */
object AndroidCredentialMigration {
    private const val DATABASE_COMPACTION_COMPLETE = "credential_database_compacted_v1"

    /** False is only "no pending raw path", not a private-key validation/availability guarantee. */
    class Result(val legacyKeyImportRequired: Boolean)

    @JvmStatic
    @Throws(CredentialMigrationException::class, SecretStoreException::class)
    fun run(database: DatabaseHandler, preferences: SharedPreferences, secrets: SecretStore): Result =
        run(database, preferences, secrets, CredentialMigrationProbe { _, _ -> })

    internal fun run(
        database: DatabaseHandler,
        preferences: SharedPreferences,
        secrets: SecretStore,
        probe: CredentialMigrationProbe,
    ): Result = synchronized(lock) {
        try {
            val db = database.writableDatabase
            check(!db.inTransaction()) { "Migration requires its own transaction boundary" }
            LegacyCredentialMigration(AndroidLegacyCredentialSources(db, preferences), secrets, probe).run()
            if (!preferences.getBoolean(DATABASE_COMPACTION_COMPLETE, false)) {
                database.checkpointAndCompactAfterCredentialMigration()
                if (!preferences.edit().putBoolean(DATABASE_COMPACTION_COMPLETE, true).commit()) {
                    migrationFail(CredentialMigrationException.Reason.STORAGE_FAILURE)
                }
            }
            // Raw paths are configuration context, never private-key bytes. Keep even readable paths
            // until the validated import boundary (4.7) has durably replaced them. Do not open files,
            // infer per-host key choices, clear the path, or switch off pref_usekeyauth here.
            Result(!preferences.getString("pref_addkeybutton", "").isNullOrEmpty())
        } catch (_: SQLiteException) {
            migrationFail(CredentialMigrationException.Reason.STORAGE_FAILURE)
        } catch (_: ClassCastException) {
            migrationFail(CredentialMigrationException.Reason.SOURCE_CHANGED)
        }
    }

    private val lock = Any()
}

internal class AndroidLegacyCredentialSources(
    private val database: SQLiteDatabase,
    private val preferences: SharedPreferences,
) : LegacyCredentialSources {
    override fun records(): List<LegacyCredentialRecord> = buildList {
        database.rawQuery("SELECT id, Password, ${DatabaseHandler.PASSWORD_CREDENTIAL_REF} FROM contacts ORDER BY id", null).use { cursor ->
            while (cursor.moveToNext()) add(LegacyCredentialRecord(
                LegacyCredentialSlot.HostPassword(cursor.getLong(0)), cursor.getString(1), cursor.getString(2),
            ))
        }
        add(preferenceRecord(LegacyCredentialSlot.DefaultPassword))
        add(preferenceRecord(LegacyCredentialSlot.GlobalPassphrase))
    }

    override fun replace(record: LegacyCredentialRecord, reference: SecretId, probe: CredentialMigrationProbe) {
        val slot = record.slot
        if (slot is LegacyCredentialSlot.HostPassword) {
            database.transaction {
                probe.at(CredentialMigrationCheckpoint.BEFORE_DATABASE_UPDATE, slot)
                database.rawQuery("SELECT Password, ${DatabaseHandler.PASSWORD_CREDENTIAL_REF} FROM contacts WHERE id=?", arrayOf(slot.id.toString())).use {
                    if (!it.moveToFirst() || it.getString(0) != record.plaintext || it.getString(1) != record.reference) {
                        migrationFail(CredentialMigrationException.Reason.SOURCE_CHANGED)
                    }
                }
                val values = ContentValues().apply {
                    putNull("Password")
                    put(DatabaseHandler.PASSWORD_CREDENTIAL_REF, reference.value)
                }
                if (database.update("contacts", values, "id=?", arrayOf(slot.id.toString())) != 1) {
                    migrationFail(CredentialMigrationException.Reason.SOURCE_CHANGED)
                }
                probe.at(CredentialMigrationCheckpoint.AFTER_DATABASE_UPDATE, slot)
            }
            probe.at(CredentialMigrationCheckpoint.AFTER_DATABASE_COMMIT, slot)
        } else {
            probe.at(CredentialMigrationCheckpoint.BEFORE_PREFERENCES_COMMIT, slot)
            val current = preferenceRecord(slot)
            if (current.plaintext != record.plaintext || current.reference != record.reference) {
                migrationFail(CredentialMigrationException.Reason.SOURCE_CHANGED)
            }
            val (plaintextKey, referenceKey) = preferenceKeys(slot)
            // One atomic preferences file replacement contains BOTH the reference and removal. No
            // apply(), separate journal marker, or non-secret rewrite. A false return is NOT success:
            // Android may already have changed its memory cache, or the disk commit may be uncertain.
            // Both possible durable states still retain plaintext OR the verified encrypted record.
            val editor = preferences.edit()
            editor.putString(referenceKey, reference.value)
            editor.remove(plaintextKey)
            // KTX edit(commit = true) returns Unit, losing the failure result required here.
            val committed = editor.commit()
            if (!committed) {
                migrationFail(CredentialMigrationException.Reason.STORAGE_FAILURE)
            }
            probe.at(CredentialMigrationCheckpoint.AFTER_PREFERENCES_COMMIT, slot)
        }
    }

    private fun preferenceRecord(slot: LegacyCredentialSlot): LegacyCredentialRecord {
        val (plaintextKey, referenceKey) = preferenceKeys(slot)
        return LegacyCredentialRecord(slot, preferences.getString(plaintextKey, null), preferences.getString(referenceKey, null))
    }

    private fun preferenceKeys(slot: LegacyCredentialSlot): Pair<String, String> = when (slot) {
        LegacyCredentialSlot.DefaultPassword -> "setting_pass" to LegacyCredentialReferences.DEFAULT_PASSWORD
        LegacyCredentialSlot.GlobalPassphrase -> "pref_key_passphrase" to LegacyCredentialReferences.GLOBAL_PASSPHRASE
        is LegacyCredentialSlot.HostPassword -> error("Not a preference credential")
    }
}
