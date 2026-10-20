package com.stripe1.xmouse.secrets

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CredentialAccessTest {
    private val id = SecretId("host_42_password")
    private val secret = "NOT_REAL_credential_秘密".toByteArray()
    private val store = ReadOnlyTestStore()
    private val requests = mutableListOf<CredentialReentryRequest>()
    private val results = mutableListOf<CredentialReadResult>()
    private val presentation = CredentialReentryHandler { requests.add(it) }
    private val completion = CredentialReadCallback { results.add(it) }

    @Test
    fun readableCredentialIsReturnedWithoutReentryOrWrites() {
        store.value = secret
        assertNull(CredentialAccess(store).read(id, presentation, completion))
        val result = results.single() as CredentialReadResult.Available
        assertArrayEquals(secret, result.plaintext)
        assertEquals(CredentialReadResult.Origin.STORED, result.origin)
        assertEquals(listOf(id), store.reads)
        assertTrue(requests.isEmpty())
        assertFalse(result.toString().contains(String(secret)))
    }

    @Test
    fun missingRecordIsNotKeyLossAndDoesNotRequestReentry() {
        assertNull(CredentialAccess(store).read(id, presentation, completion))
        assertSame(CredentialReadResult.Missing, results.single())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun unavailableKeyActivelyRequestsReentryAndDoesNotBlockWaitingForPresentation() {
        store.reason = SecretStoreException.Reason.KEY_UNAVAILABLE
        val request = CredentialAccess(store).read(id, presentation, completion)!!
        assertSame(request, requests.single())
        assertEquals(id, request.id)
        assertEquals(SecretStoreException.Reason.KEY_UNAVAILABLE, request.reason)
        assertTrue(request.isPending)
        assertTrue(results.isEmpty())
    }

    @Test
    fun userReentryIsExplicitlyForOneAttemptAndDoesNotSaveOrRetryStore() {
        store.reason = SecretStoreException.Reason.KEY_UNAVAILABLE
        val request = CredentialAccess(store).read(id, presentation, completion)!!
        assertTrue(request.useForThisAttempt(secret))
        val result = results.single() as CredentialReadResult.Available
        assertEquals(CredentialReadResult.Origin.REENTERED_FOR_ATTEMPT, result.origin)
        assertArrayEquals(secret, result.plaintext)
        assertNotSame(secret, result.plaintext)
        assertEquals(listOf(id), store.reads)
        assertFalse(request.isPending)
        assertFalse(request.useForThisAttempt(secret))
        assertFalse(request.cancel())
        assertEquals(1, results.size)
    }

    @Test
    fun cancelCompletesWithoutCredentialAndRejectsLateReentry() {
        store.reason = SecretStoreException.Reason.KEY_UNAVAILABLE
        val request = CredentialAccess(store).read(id, presentation, completion)!!
        assertTrue(request.cancel())
        assertSame(CredentialReadResult.Cancelled, results.single())
        assertFalse(request.isPending)
        assertFalse(request.cancel())
        assertFalse(request.useForThisAttempt(secret))
        assertEquals(1, results.size)
    }

    @Test
    fun damageVersionEncryptionAndStorageFailuresNeverRequestIndiscriminateReset() {
        for (reason in SecretStoreException.Reason.entries.filter { it != SecretStoreException.Reason.KEY_UNAVAILABLE }) {
            store.reason = reason
            assertNull(CredentialAccess(store).read(id, presentation, completion))
            assertEquals(reason, (results.last() as CredentialReadResult.Failed).reason)
        }
        assertTrue(requests.isEmpty())
        assertEquals(4, results.size)
    }

    @Test
    fun oversizedReentryDoesNotResolveOrLosePendingRequest() {
        store.reason = SecretStoreException.Reason.KEY_UNAVAILABLE
        val request = CredentialAccess(store).read(id, presentation, completion)!!
        assertThrows(IllegalArgumentException::class.java) { request.useForThisAttempt(ByteArray(MAX_SECRET_BYTES + 1)) }
        assertTrue(request.isPending)
        assertTrue(results.isEmpty())
        assertTrue(request.cancel())
    }

    @Test
    fun concurrentCancelAndReentryCompleteExactlyOnce() {
        store.reason = SecretStoreException.Reason.KEY_UNAVAILABLE
        val request = CredentialAccess(store).read(id, presentation, completion)!!
        val executor = Executors.newFixedThreadPool(2)
        try {
            val choices = (1..40).map { index ->
                executor.submit<Boolean> { if (index % 2 == 0) request.cancel() else request.useForThisAttempt(secret) }
            }
            assertEquals(1, choices.count { it.get(5, TimeUnit.SECONDS) })
            assertEquals(1, results.size)
            assertFalse(request.isPending)
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun completionFailureIsNotReclassifiedOrUsedToTriggerReentry() {
        store.value = secret
        val failure = SecretStoreException(SecretStoreException.Reason.KEY_UNAVAILABLE)
        assertSame(failure, assertThrows(SecretStoreException::class.java) {
            CredentialAccess(store).read(id, presentation) { throw failure }
        })
        assertTrue(requests.isEmpty())
    }

    private class ReadOnlyTestStore : SecretStore {
        var value: ByteArray? = null
        var reason: SecretStoreException.Reason? = null
        val reads = mutableListOf<SecretId>()
        override fun read(id: SecretId): ByteArray? {
            reads.add(id)
            reason?.let { throw SecretStoreException(it) }
            return value
        }
        override fun write(id: SecretId, plaintext: ByteArray): Nothing = error("Re-entry must never write/reset storage")
    }
}
