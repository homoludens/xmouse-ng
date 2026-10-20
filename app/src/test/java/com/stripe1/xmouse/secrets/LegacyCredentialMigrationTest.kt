package com.stripe1.xmouse.secrets

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyCredentialMigrationTest {
    private val slots = listOf(
        LegacyCredentialSlot.HostPassword(7), LegacyCredentialSlot.HostPassword(42),
        LegacyCredentialSlot.DefaultPassword, LegacyCredentialSlot.GlobalPassphrase,
    )

    @Test
    fun stableIdsAreOpaqueDistinctAndAcceptTheEntireSqliteIdRange() {
        val ids = listOf(Long.MIN_VALUE, -1, 0, 7, 42, Long.MAX_VALUE).map(LegacyCredentialReferences::hostPassword)
        assertEquals(6, ids.toSet().size)
        assertEquals(LegacyCredentialReferences.hostPassword(42), LegacyCredentialReferences.hostPassword(42))
        assertFalse(ids.contains(LegacyCredentialReferences.defaultPassword()))
        assertFalse(ids.contains(LegacyCredentialReferences.globalPassphrase()))
    }

    @Test
    fun migratesEverySlotExactlyAndRepeatsWithoutDuplicatingRecords() {
        val sources = Sources()
        val secrets = Secrets()
        repeat(3) { LegacyCredentialMigration(sources, secrets).run() }
        assertEquals(4, sources.values.size)
        assertEquals(4, secrets.values.size)
        slots.forEachIndexed { index, slot ->
            assertNull(sources.values[index].plaintext)
            assertEquals(slot.replacementId().value, sources.values[index].reference)
            assertArrayEquals(secret(index).toByteArray(), secrets.read(slot.replacementId()))
        }
    }

    @Test
    fun interruptionsBeforeAfterWriteAndVerificationRestartEveryCredentialWithoutLosingEitherCopy() {
        val checkpoints = listOf(
            CredentialMigrationCheckpoint.BEFORE_SECRET_WRITE, CredentialMigrationCheckpoint.AFTER_SECRET_WRITE,
            CredentialMigrationCheckpoint.BEFORE_SECRET_VERIFICATION, CredentialMigrationCheckpoint.AFTER_SECRET_VERIFICATION,
        )
        slots.indices.forEach { target -> checkpoints.forEach { checkpoint ->
            val sources = Sources()
            val secrets = Secrets()
            val probe = CredentialMigrationProbe { point, slot ->
                if (point == checkpoint && slot === slots[target]) throw Interruption()
            }
            expect<Interruption> { LegacyCredentialMigration(sources, secrets, probe).run() }
            assertUsable(sources, secrets)
            LegacyCredentialMigration(sources, secrets).run()
            assertUsable(sources, secrets)
            assertEquals(4, secrets.values.size)
        } }
    }

    @Test
    fun failedAndUncertainWritesNeverClearPlaintextAndRetryRewritesEqualPreparationDurably() {
        listOf(false, true).forEach { persisted ->
            val sources = Sources()
            val secrets = Secrets()
            val failed = object : SecretStore by secrets {
                override fun write(id: SecretId, plaintext: ByteArray) {
                    if (persisted) secrets.write(id, plaintext)
                    throw SecretStoreException(SecretStoreException.Reason.STORAGE_FAILURE)
                }
            }
            expect<SecretStoreException> { LegacyCredentialMigration(sources, failed).run() }
            assertEquals(secret(0), sources.values[0].plaintext)
            LegacyCredentialMigration(sources, secrets).run()
            assertEquals(if (persisted) 5 else 4, secrets.writes)
        }
    }

    @Test
    fun everyStoreReadFailurePropagatesWithoutClearingOrResetting() {
        SecretStoreException.Reason.entries.forEach { reason ->
            val sources = Sources()
            val secrets = Secrets()
            val failed = object : SecretStore by secrets {
                override fun read(id: SecretId): ByteArray? = throw SecretStoreException(reason)
            }
            assertEquals(reason, expect<SecretStoreException> { LegacyCredentialMigration(sources, failed).run() }.reason)
            assertEquals(secret(0), sources.values[0].plaintext)
            assertEquals(0, secrets.writes)
        }
    }

    @Test
    fun missingMismatchedAndFailedReadbackNeverAuthorizeClearing() {
        repeat(3) { fault ->
            val sources = Sources()
            val secrets = Secrets()
            val failed = object : SecretStore by secrets {
                override fun read(id: SecretId): ByteArray? {
                    if (secrets.writes == 0) return null
                    return when (fault) {
                        0 -> null
                        1 -> "NOT_REAL_wrong".toByteArray()
                        else -> throw SecretStoreException(SecretStoreException.Reason.KEY_UNAVAILABLE)
                    }
                }
            }
            expect<Exception> { LegacyCredentialMigration(sources, failed).run() }
            assertEquals(secret(0), sources.values[0].plaintext)
            assertArrayEquals(secret(0).toByteArray(), secrets.read(slots[0].replacementId()))
        }
    }

    @Test
    fun existingReferencesAreRetainedAndMissingRecordsCanBePreparedFromRetainedPlaintext() {
        val sources = Sources()
        sources.values[0] = LegacyCredentialRecord(slots[0], secret(0), "existing_opaque_reference")
        val secrets = Secrets()
        LegacyCredentialMigration(sources, secrets).run()
        assertEquals("existing_opaque_reference", sources.values[0].reference)
        assertFalse(secrets.values.containsKey(slots[0].replacementId()))
    }

    @Test
    fun conflictsNeverOverwriteEitherRepresentation() {
        val sources = Sources()
        val secrets = Secrets()
        val old = "NOT_REAL_previous_credential".toByteArray()
        secrets.write(slots[0].replacementId(), old)
        assertEquals(CredentialMigrationException.Reason.REPLACEMENT_CONFLICT,
            expect<CredentialMigrationException> { LegacyCredentialMigration(sources, secrets).run() }.reason)
        assertEquals(secret(0), sources.values[0].plaintext)
        assertArrayEquals(old, secrets.read(slots[0].replacementId()))
        assertEquals(1, secrets.writes)
    }

    @Test
    fun invalidReferencesAreNotReplacedWithInventedMappings() {
        val sources = Sources()
        sources.values[0] = LegacyCredentialRecord(slots[0], secret(0), "invalid/path")
        val secrets = Secrets()
        assertEquals(CredentialMigrationException.Reason.INVALID_REFERENCE,
            expect<CredentialMigrationException> { LegacyCredentialMigration(sources, secrets).run() }.reason)
        assertTrue(secrets.values.isEmpty())
        assertEquals(secret(0), sources.values[0].plaintext)
    }

    @Test
    fun alreadyClearedMissingReferenceFailsInsteadOfCreatingAnEmptyCredential() {
        val sources = Sources()
        sources.values[0] = LegacyCredentialRecord(slots[0], null, slots[0].replacementId().value)
        val secrets = Secrets()
        assertEquals(CredentialMigrationException.Reason.MISSING_REPLACEMENT,
            expect<CredentialMigrationException> { LegacyCredentialMigration(sources, secrets).run() }.reason)
        assertEquals(0, secrets.writes)
    }

    @Test
    fun absentSourcesAreNotInventedButExplicitEmptySecretsAreMigrated() {
        val sources = Sources()
        sources.values.clear()
        sources.values.add(LegacyCredentialRecord(slots[0], null, null))
        sources.values.add(LegacyCredentialRecord(slots[1], "", null))
        val secrets = Secrets()
        LegacyCredentialMigration(sources, secrets).run()
        assertEquals(1, secrets.values.size)
        assertArrayEquals(byteArrayOf(), secrets.read(slots[1].replacementId()))
    }

    @Test
    fun cachedClearedPreferencesAreVerifiedAndRecommittedAfterAnUncertainCommit() {
        val sources = Sources()
        val secrets = Secrets()
        LegacyCredentialMigration(sources, secrets).run()
        sources.commits = 0
        LegacyCredentialMigration(sources, secrets).run()
        assertEquals(2, sources.commits) // Only preferences need uncertainty reconciliation, not DB rows.
        assertEquals(4, secrets.writes)
    }

    @Test
    fun diagnosticsNeverRenderSyntheticPlaintext() {
        val record = LegacyCredentialRecord(slots[0], secret(0), null)
        assertFalse(record.toString().contains(secret(0)))
        CredentialMigrationException.Reason.entries.forEach {
            assertEquals(it.name, CredentialMigrationException(it).message)
            assertNull(CredentialMigrationException(it).cause)
        }
    }

    private fun secret(index: Int) = "NOT_REAL_fixture_${index}_秘密_\u0000"
    private fun assertUsable(sources: Sources, secrets: Secrets) {
        sources.values.forEachIndexed { index, record ->
            if (record.plaintext != null) assertEquals(secret(index), record.plaintext)
            else assertArrayEquals(secret(index).toByteArray(), secrets.read(SecretId(record.reference!!)))
        }
    }

    private inner class Sources : LegacyCredentialSources {
        val values = slots.mapIndexed { index, slot -> LegacyCredentialRecord(slot, secret(index), null) }.toMutableList()
        var commits = 0
        override fun records() = values.toList()
        override fun replace(record: LegacyCredentialRecord, reference: SecretId, probe: CredentialMigrationProbe) {
            val index = values.indexOf(record)
            check(index >= 0)
            values[index] = LegacyCredentialRecord(record.slot, null, reference.value)
            commits++
        }
    }

    private class Secrets : SecretStore {
        val values = mutableMapOf<SecretId, ByteArray>()
        var writes = 0
        override fun read(id: SecretId) = values[id]?.copyOf()
        override fun write(id: SecretId, plaintext: ByteArray) {
            writes++
            values[id] = plaintext.copyOf()
        }
    }

    private class Interruption : RuntimeException()
    private inline fun <reified T : Throwable> expect(block: () -> Unit): T {
        try { block() } catch (error: Throwable) {
            if (error is T) return error
            throw error
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }
}
