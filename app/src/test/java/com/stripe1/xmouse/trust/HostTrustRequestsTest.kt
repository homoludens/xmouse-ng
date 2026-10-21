package com.stripe1.xmouse.trust

import com.stripe1.xmouse.connection.Endpoint
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
        repo.approve(endpoint.identity, syntheticHostKey(2), BooleanSupplier { true })
        assertEquals(HostTrustException.Reason.MISMATCH, failure(verify(worker, requests)).reason)
        assertTrue(observer.shown.isEmpty()); assertTrue(repo.find(endpoint.identity)!!.matches(syntheticHostKey(2)))
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
