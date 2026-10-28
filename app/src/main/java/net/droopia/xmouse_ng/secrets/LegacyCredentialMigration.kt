package net.droopia.xmouse_ng.secrets

/** Stable, non-secret mappings. These do not invent per-host private-key/authentication choices. */
object LegacyCredentialReferences {
    const val DEFAULT_PASSWORD = "setting_pass_credential_ref"
    const val GLOBAL_PASSPHRASE = "pref_key_passphrase_credential_ref"
    const val GLOBAL_PRIVATE_KEY = "pref_private_key_credential_ref"

    @JvmStatic
    fun hostPassword(hostId: Long): SecretId = SecretId("migration_host_${java.lang.Long.toHexString(hostId)}_password")

    @JvmStatic
    fun defaultPassword(): SecretId = SecretId("migration_default_password")

    @JvmStatic
    fun globalPassphrase(): SecretId = SecretId("migration_global_passphrase")
}

/** Safe diagnostics: never includes source plaintext, provider messages, or key paths. */
class CredentialMigrationException(val reason: Reason) : Exception(reason.name) {
    enum class Reason {
        INVALID_REFERENCE, MISSING_REPLACEMENT, REPLACEMENT_CONFLICT, SOURCE_CHANGED, STORAGE_FAILURE,
    }
}

internal sealed class LegacyCredentialSlot {
    class HostPassword(val id: Long) : LegacyCredentialSlot()
    data object DefaultPassword : LegacyCredentialSlot()
    data object GlobalPassphrase : LegacyCredentialSlot()

    fun replacementId(): SecretId = when (this) {
        is HostPassword -> LegacyCredentialReferences.hostPassword(id)
        DefaultPassword -> LegacyCredentialReferences.defaultPassword()
        GlobalPassphrase -> LegacyCredentialReferences.globalPassphrase()
    }
}

// Not a data class: accidental diagnostics must not render credentials.
internal class LegacyCredentialRecord(
    val slot: LegacyCredentialSlot,
    val plaintext: String?,
    val reference: String?,
)

internal enum class CredentialMigrationCheckpoint {
    BEFORE_SECRET_WRITE, AFTER_SECRET_WRITE, BEFORE_SECRET_VERIFICATION, AFTER_SECRET_VERIFICATION,
    BEFORE_DATABASE_UPDATE, AFTER_DATABASE_UPDATE, AFTER_DATABASE_COMMIT,
    BEFORE_PREFERENCES_COMMIT, AFTER_PREFERENCES_COMMIT,
}

internal fun interface CredentialMigrationProbe {
    fun at(checkpoint: CredentialMigrationCheckpoint, slot: LegacyCredentialSlot)
}

/** Only the legacy sources, not a general database/repository abstraction. */
internal interface LegacyCredentialSources {
    fun records(): List<LegacyCredentialRecord>
    fun replace(record: LegacyCredentialRecord, reference: SecretId, probe: CredentialMigrationProbe)
}

/**
 * Blocking, restartable preparation/verification/commit. Must own all credential writers while running.
 * Durable states are (source plaintext + optional encrypted preparation), then (reference + ciphertext).
 * No completion marker can authorize clearing a source without a fresh authenticated read-back.
 */
internal class LegacyCredentialMigration(
    private val sources: LegacyCredentialSources,
    private val secrets: SecretStore,
    private val probe: CredentialMigrationProbe = CredentialMigrationProbe { _, _ -> },
) {
    fun run() {
        sources.records().forEach { record ->
            val reference = record.reference?.let {
                try {
                    SecretId(it)
                } catch (_: IllegalArgumentException) {
                    migrationFail(CredentialMigrationException.Reason.INVALID_REFERENCE)
                }
            } ?: record.slot.replacementId()
            if (record.plaintext == null) {
                // Never declare an already-cleared source usable just because a reference exists.
                if (record.reference != null) {
                    verify(record, reference, null)
                    // Recommit preferences even after an earlier commit(false) changed only Android's
                    // memory cache. A readable cached reference alone does not prove durable cleanup.
                    if (record.slot !is LegacyCredentialSlot.HostPassword) sources.replace(record, reference, probe)
                }
                return@forEach
            }
            val expected = record.plaintext.toByteArray(Charsets.UTF_8)
            try {
                val previous = secrets.read(reference)
                if (previous != null) {
                    try {
                        // A stale/foreign preparation or an existing different credential is not consent
                        // to overwrite either copy. In particular, migration is not a key-reset API.
                        if (!previous.contentEquals(expected)) migrationFail(CredentialMigrationException.Reason.REPLACEMENT_CONFLICT)
                    } finally {
                        previous.fill(0)
                    }
                }
                // Rewriting equal preparation is intentional: a previous write may have renamed then
                // failed directory fsync. A fresh successful durable write reconciles that uncertainty.
                probe.at(CredentialMigrationCheckpoint.BEFORE_SECRET_WRITE, record.slot)
                secrets.write(reference, expected)
                probe.at(CredentialMigrationCheckpoint.AFTER_SECRET_WRITE, record.slot)
                verify(record, reference, expected)
                sources.replace(record, reference, probe)
            } finally {
                expected.fill(0)
            }
        }
    }

    private fun verify(record: LegacyCredentialRecord, reference: SecretId, expected: ByteArray?) {
        probe.at(CredentialMigrationCheckpoint.BEFORE_SECRET_VERIFICATION, record.slot)
        val actual = secrets.read(reference) ?: migrationFail(CredentialMigrationException.Reason.MISSING_REPLACEMENT)
        try {
            if (expected != null && !actual.contentEquals(expected)) migrationFail(CredentialMigrationException.Reason.REPLACEMENT_CONFLICT)
        } finally {
            actual.fill(0)
        }
        probe.at(CredentialMigrationCheckpoint.AFTER_SECRET_VERIFICATION, record.slot)
    }
}

internal fun migrationFail(reason: CredentialMigrationException.Reason): Nothing = throw CredentialMigrationException(reason)
