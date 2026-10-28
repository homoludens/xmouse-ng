package net.droopia.xmouse_ng.trust

import net.droopia.xmouse_ng.connection.Endpoint
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BooleanSupplier

class HostTrustRequestsTest {
    private class Observer : HostTrustRequests.Listener {
        val shown = LinkedBlockingQueue<HostTrustRequests.Request>()
        val dismissed = LinkedBlockingQueue<HostTrustRequests.Request>()
        override fun show(request: HostTrustRequests.Request) { shown.add(request) }
        override fun dismiss(request: HostTrustRequests.Request) { dismissed.add(request) }
        fun next() = requireNotNull(shown.poll(5, TimeUnit.SECONDS))
    }
    private fun fixture(timeout: Long = 5000, action: (HostKeyRepository, HostTrustRequests, Observer, ExecutorService) -> Unit) {
        val directory = Files.createTempDirectory("xmouse-trust-request-").toFile()
        val repo = HostKeyRepository(directory, TrustFileIo())
        val requests = HostTrustRequests(repo, Executor { it.run() }, timeout)
        val observer = Observer(); requests.attach(observer)
        val worker = Executors.newSingleThreadExecutor()
        try { action(repo, requests, observer, worker) }
        finally { requests.detach(); worker.shutdownNow(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)); directory.deleteRecursively() }
    }
    private fun verify(worker: ExecutorService, requests: HostTrustRequests, host: String = "desk.local", current: AtomicBoolean = AtomicBoolean(true)): Future<*> =
        worker.submit { requests.verify(Endpoint.manual(host, 22), syntheticHostKey(), BooleanSupplier { current.get() }) }
    private fun failure(future: Future<*>): HostTrustException = assertThrows(ExecutionException::class.java) { future.get(5, TimeUnit.SECONDS) }.cause as HostTrustException

    @Test fun acceptedRequestPersistsBeforeReturningAndReconnectDoesNotReprompt() = fixture { repo, requests, observer, worker ->
        val future = verify(worker, requests); val request = observer.next()
        assertFalse(future.isDone); assertNull(repo.find(request.endpoint.identity))
        request.accept(); future.get(5, TimeUnit.SECONDS)
        assertTrue(repo.find(request.endpoint.identity)!!.matches(request.key))
        verify(worker, requests).get(5, TimeUnit.SECONDS); assertTrue(observer.shown.isEmpty())
    }
    @Test fun rejectCancelAndNoVisibleObserverFailWithoutPersisting() = fixture { repo, requests, observer, worker ->
        var future = verify(worker, requests); var request = observer.next(); request.reject()
        assertEquals(HostTrustException.Reason.REJECTED, failure(future).reason); assertNull(repo.find(request.endpoint.identity))
        future = verify(worker, requests); request = observer.next(); request.cancel()
        assertEquals(HostTrustException.Reason.CANCELLED, failure(future).reason); assertNull(repo.find(request.endpoint.identity))
        requests.detach(); future = verify(worker, requests)
        assertEquals(HostTrustException.Reason.CANCELLED, failure(future).reason)
    }
    @Test fun finiteDeadlineInvalidatesLateAcceptance() = fixture(80) { repo, requests, observer, worker ->
        val future = verify(worker, requests); val request = observer.next()
        assertEquals(HostTrustException.Reason.EXPIRED, failure(future).reason)
        request.accept(); assertNull(repo.find(request.endpoint.identity)); assertFalse(request.isPending)
    }
    @Test fun detachAndNewEndpointInvalidateOldCallbacksWithoutCancellingNewPrompt() = fixture { repo, requests, observer, worker ->
        val first = verify(worker, requests); val old = observer.next()
        requests.detach(); assertEquals(HostTrustException.Reason.CANCELLED, failure(first).reason)
        requests.attach(observer)
        val second = verify(worker, requests, "other.local"); val fresh = observer.next()
        old.accept(); old.reject(); old.cancel()
        assertTrue(fresh.isPending); assertFalse(second.isDone)
        fresh.accept(); second.get(5, TimeUnit.SECONDS)
        assertNull(repo.find(old.endpoint.identity)); assertTrue(repo.find(fresh.endpoint.identity)!!.matches(fresh.key))
    }
    @Test fun staleAttemptEvenBeforePromptCreationCannotApproveAnotherKey() = fixture { repo, requests, observer, worker ->
        val current = AtomicBoolean(true)
        val future = verify(worker, requests, current = current); val request = observer.next()
        current.set(false); request.accept()
        assertEquals(HostTrustException.Reason.CANCELLED, failure(future).reason); assertNull(repo.find(request.endpoint.identity))
        assertEquals(HostTrustException.Reason.CANCELLED, failure(verify(worker, requests, current = current)).reason)
        assertTrue(observer.shown.isEmpty())
    }
    @Test fun changedKeyBlocksWithoutFirstTrustReplacementPrompt() = fixture { repo, requests, observer, worker ->
        val endpoint = Endpoint.manual("desk.local", 22)
        val trusted = syntheticHostKey(2); val candidate = syntheticHostKey(3)
        repo.approve(endpoint.identity, trusted, BooleanSupplier { true })
        val mismatchError = failure(worker.submit {
            requests.verify(endpoint, candidate, BooleanSupplier { true })
        })
        assertEquals(HostTrustException.Reason.MISMATCH, mismatchError.reason)
        val mismatch = requireNotNull(mismatchError.mismatch)
        assertEquals(endpoint, mismatch.endpoint)
        assertTrue(mismatch.trustedKey.matches(trusted)); assertTrue(mismatch.presentedKey.matches(candidate))
        assertTrue(observer.shown.isEmpty()); assertTrue(repo.find(endpoint.identity)!!.matches(trusted))
        assertTrue(mismatch.confirm())
        requests.replace(mismatch, BooleanSupplier { true })
        assertTrue(repo.find(endpoint.identity)!!.matches(candidate))
    }
    @Test fun cancelledOrStaleMismatchReviewCannotReplaceCurrentTrustedKey() = fixture { repo, requests, observer, worker ->
        val endpoint = Endpoint.manual("desk.local", 22)
        val original = syntheticHostKey(1); val candidate = syntheticHostKey(2); val concurrent = syntheticHostKey(3)
        repo.approve(endpoint.identity, original, BooleanSupplier { true })
        val cancelled = failure(worker.submit { requests.verify(endpoint, candidate, BooleanSupplier { true }) }).mismatch!!
        cancelled.cancel()
        assertFalse(cancelled.confirm())
        assertEquals(HostTrustException.Reason.CANCELLED, assertThrows(HostTrustException::class.java) {
            requests.replace(cancelled, BooleanSupplier { true })
        }.reason)
        assertTrue(repo.find(endpoint.identity)!!.matches(original))

        val stale = failure(worker.submit { requests.verify(endpoint, candidate, BooleanSupplier { true }) }).mismatch!!
        assertTrue(stale.confirm())
        repo.replace(endpoint.identity, original, concurrent, BooleanSupplier { true })
        assertEquals(HostTrustException.Reason.MISMATCH, assertThrows(HostTrustException::class.java) {
            requests.replace(stale, BooleanSupplier { true })
        }.reason)
        assertTrue(repo.find(endpoint.identity)!!.matches(concurrent))
        assertTrue(observer.shown.isEmpty())
    }
    @Test fun cancellingClaimedReplacementDuringStagingReturnsImmediatelyAndKeepsOldRecords() {
        val directory = Files.createTempDirectory("xmouse-trust-replacement-cancel-").toFile()
        val staged = CountDownLatch(1); val release = CountDownLatch(1)
        val blockNextSync = AtomicBoolean(false)
        val io = object : TrustFileIo() {
            override fun sync(output: java.io.FileOutputStream) {
                super.sync(output)
                if (blockNextSync.compareAndSet(true, false)) {
                    staged.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            }
        }
        val repository = HostKeyRepository(directory, io)
        val requests = HostTrustRequests(repository, Executor { it.run() })
        val worker = Executors.newSingleThreadExecutor()
        val endpoint = Endpoint.manual("desk.local", 22)
        val other = Endpoint.manual("other.local", 2200)
        val old = syntheticHostKey(11); val candidate = syntheticHostKey(12); val unrelated = syntheticHostKey(13)
        val current = BooleanSupplier { true }
        try {
            repository.approve(endpoint.identity, old, current)
            repository.approve(other.identity, unrelated, current)
            val mismatch = assertThrows(HostTrustException::class.java) {
                requests.verify(endpoint, candidate, current)
            }.mismatch!!
            assertTrue(mismatch.confirm())
            assertFalse(mismatch.confirm()) // A second confirmation cannot claim the same review.

            blockNextSync.set(true)
            val replacement = worker.submit { requests.replace(mismatch, current) }
            assertTrue("replacement never reached staged fsync", staged.await(5, TimeUnit.SECONDS))
            assertFalse(mismatch.confirm()) // The worker has claimed the one confirmed candidate.
            val start = System.nanoTime()
            mismatch.cancel()
            assertTrue("cancellation waited for worker I/O", System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1))
            assertFalse(replacement.isDone)
            release.countDown()

            assertEquals(HostTrustException.Reason.CANCELLED, failure(replacement).reason)
            assertFalse(mismatch.isCurrent()); assertFalse(mismatch.confirm())
            assertEquals(HostTrustException.Reason.CANCELLED, assertThrows(HostTrustException::class.java) {
                requests.replace(mismatch, current)
            }.reason)
            val reopened = HostKeyRepository(directory, TrustFileIo())
            assertTrue(reopened.find(endpoint.identity)!!.matches(old))
            assertTrue(reopened.find(other.identity)!!.matches(unrelated))
        } finally {
            release.countDown(); requests.detach(); worker.shutdownNow()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)); directory.deleteRecursively()
        }
    }

    @Test fun cancellationAfterAtomicReplacementMayKeepOnlyTheConfirmedPairButCannotReportSuccess() {
        val directory = Files.createTempDirectory("xmouse-trust-replacement-commit-race-").toFile()
        val committed = CountDownLatch(1); val release = CountDownLatch(1)
        val blockAfterRename = AtomicBoolean(false)
        val io = object : TrustFileIo() {
            override fun replace(source: java.io.File, destination: java.io.File) {
                super.replace(source, destination)
                if (blockAfterRename.compareAndSet(true, false)) {
                    committed.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            }
        }
        val repository = HostKeyRepository(directory, io)
        val requests = HostTrustRequests(repository, Executor { it.run() })
        val worker = Executors.newSingleThreadExecutor()
        val endpoint = Endpoint.manual("desk.local", 22)
        val other = Endpoint.manual("other.local", 2200)
        val old = syntheticHostKey(21); val candidate = syntheticHostKey(22); val unrelated = syntheticHostKey(23)
        val current = BooleanSupplier { true }
        try {
            repository.approve(endpoint.identity, old, current)
            repository.approve(other.identity, unrelated, current)
            val mismatch = assertThrows(HostTrustException::class.java) {
                requests.verify(endpoint, candidate, current)
            }.mismatch!!
            assertTrue(mismatch.confirm())
            blockAfterRename.set(true)
            val replacement = worker.submit { requests.replace(mismatch, current) }
            assertTrue("atomic replacement was not reached", committed.await(5, TimeUnit.SECONDS))

            mismatch.cancel() // Rename has committed; cancellation must not attempt an unsafe rollback.
            assertFalse(mismatch.confirm())
            release.countDown()

            assertEquals(HostTrustException.Reason.CANCELLED, failure(replacement).reason)
            val reopened = HostKeyRepository(directory, TrustFileIo())
            assertTrue(reopened.find(endpoint.identity)!!.matches(candidate))
            assertTrue(reopened.find(other.identity)!!.matches(unrelated))
            assertFalse(reopened.find(endpoint.identity)!!.matches(old))
        } finally {
            release.countDown(); requests.detach(); worker.shutdownNow()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)); directory.deleteRecursively()
        }
    }
    @Test fun detachDoesNotBlockOnPersistenceAndCancelsBeforePublication() {
        val directory = Files.createTempDirectory("xmouse-trust-cancel-write-").toFile()
        val staged = CountDownLatch(1); val release = CountDownLatch(1)
        val io = object : TrustFileIo() {
            override fun sync(output: java.io.FileOutputStream) {
                super.sync(output); staged.countDown(); check(release.await(5, TimeUnit.SECONDS))
            }
        }
        val repo = HostKeyRepository(directory, io)
        val requests = HostTrustRequests(repo, Executor { it.run() })
        val observer = Observer(); requests.attach(observer)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val future = verify(worker, requests); val request = observer.next(); request.accept()
            assertTrue(staged.await(5, TimeUnit.SECONDS))
            requests.detach() // Returns while the worker remains blocked in staging fsync.
            assertFalse(future.isDone); release.countDown()
            assertEquals(HostTrustException.Reason.CANCELLED, failure(future).reason)
            assertNull(HostKeyRepository(directory, TrustFileIo()).find(request.endpoint.identity))
        } finally { release.countDown(); requests.detach(); worker.shutdownNow(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)); directory.deleteRecursively() }
    }
    @Test fun queuedOldUiDeliveryAndLateAcceptanceCannotApproveANewKeyForTheSameEndpoint() {
        val directory = Files.createTempDirectory("xmouse-trust-queued-ui-").toFile()
        val queue = LinkedBlockingQueue<Runnable>()
        val repo = HostKeyRepository(directory, TrustFileIo())
        val requests = HostTrustRequests(repo, Executor { queue.add(it) })
        val observer = Observer(); requests.attach(observer)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val first = verify(worker, requests)
            val oldDelivery = requireNotNull(queue.poll(5, TimeUnit.SECONDS))
            requests.detach(); requests.attach(observer)
            oldDelivery.run(); assertTrue(observer.shown.isEmpty())
            assertEquals(HostTrustException.Reason.CANCELLED, failure(first).reason)
            // Drop the queued old dismissal. It must not be delivered to the new attachment either.
            requireNotNull(queue.poll(5, TimeUnit.SECONDS)).run(); assertTrue(observer.dismissed.isEmpty())
            val endpoint = Endpoint.manual("desk.local", 22); val differentKey = syntheticHostKey(2)
            val second = worker.submit { requests.verify(endpoint, differentKey, BooleanSupplier { true }) }
            requireNotNull(queue.poll(5, TimeUnit.SECONDS)).run()
            val fresh = observer.next(); assertTrue(fresh.key.matches(differentKey))
            fresh.accept(); second.get(5, TimeUnit.SECONDS)
            assertTrue(repo.find(endpoint.identity)!!.matches(differentKey))
            assertFalse(repo.find(endpoint.identity)!!.matches(syntheticHostKey()))
        } finally { requests.detach(); worker.shutdownNow(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)); directory.deleteRecursively() }
    }
}
