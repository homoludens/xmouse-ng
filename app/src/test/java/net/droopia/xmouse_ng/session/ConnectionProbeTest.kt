package net.droopia.xmouse_ng.session

import net.droopia.xmouse_ng.connection.*
import net.droopia.xmouse_ng.transport.*
import net.droopia.xmouse_ng.trust.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

class ConnectionProbeTest {
    private class Observer : ConnectionProbe.Listener {
        val phases = mutableListOf<ConnectionState.Phase>()
        val results = mutableListOf<Pair<ConnectionFailure?, Boolean>>()
        override fun phase(phase: ConnectionState.Phase) { phases += phase }
        override fun finished(failure: ConnectionFailure?, cancelled: Boolean) { results += failure to cancelled }
    }
    private class Attempt : SessionAttempt {
        lateinit var listener: SessionAttempt.Listener
        var close: ((ConnectionFailure?) -> Unit)? = null
        override fun start(listener: SessionAttempt.Listener) { this.listener = listener }
        override fun cancelAndClose(completion: (ConnectionFailure?) -> Unit) { close = completion }
        override fun executeShellCommand(command: String) = error("Probe must not dispatch")
    }
    @Test fun successWaitsForCloseAndRejectsParallelTestAndCommands() {
        val queue = SessionFixtures.Queue(); val attempt = Attempt(); val observer = Observer()
        val probe = ConnectionProbe(SessionAttemptFactory { attempt }, Executor { queue.post(it) })
        probe.attach(observer)
        assertTrue(probe.start(SessionFixtures.host()))
        assertFalse(probe.start(SessionFixtures.host("other.invalid")))
        attempt.listener.ready(); queue.drain()
        assertTrue(observer.results.isEmpty()); assertTrue(probe.busy)
        attempt.close!!(null); queue.drain()
        assertEquals(listOf(null to false), observer.results); assertFalse(probe.busy)
        assertThrows(IllegalArgumentException::class.java) { probe.start(SessionFixtures.host().copy(initialCommand = "forbidden")) }
        assertThrows(IllegalArgumentException::class.java) { probe.start(SessionFixtures.host().copy(shell = "forbidden")) }
    }
    @Test fun cancellationInvalidatesQueuedReadyAndHostChangeCannotOverlapCleanup() {
        val queue = SessionFixtures.Queue(); val attempts = mutableListOf<Attempt>(); val observer = Observer()
        val probe = ConnectionProbe(SessionAttemptFactory { Attempt().also(attempts::add) }, Executor { queue.post(it) })
        probe.attach(observer); probe.start(SessionFixtures.host())
        attempts[0].listener.ready()
        probe.cancel(); queue.drain()
        assertFalse(probe.start(SessionFixtures.host("new.invalid")))
        attempts[0].close!!(null); queue.drain()
        assertEquals(listOf(null to true), observer.results)
        assertTrue(probe.start(SessionFixtures.host("new.invalid")))
        attempts[0].listener.ready(); queue.drain()
        assertEquals(1, observer.results.size)
        probe.cancel(); attempts[1].close!!(null); queue.drain()
    }
    @Test fun recreationAndLateCloseCannotPublishOldSuccessAndCleanupFailureBlocksRetry() {
        val queue = SessionFixtures.Queue(); val attempt = Attempt(); val old = Observer(); val next = Observer()
        val probe = ConnectionProbe(SessionAttemptFactory { attempt }, Executor { queue.post(it) })
        probe.attach(old); probe.start(SessionFixtures.host()); attempt.listener.ready(); queue.drain()
        probe.detach(); probe.attach(next)
        val failure = ConnectionFailure.Transport(ConnectionFailure.TransportReason.CLEANUP_UNCONFIRMED)
        attempt.close!!(failure); queue.drain()
        assertTrue(old.results.isEmpty()); assertEquals(listOf(failure to true), next.results)
        assertTrue(probe.blocked); assertFalse(probe.start(SessionFixtures.host()))
    }
    @Test fun unclaimedPasswordIsWipedAndClaimTransfersOnlyOnce() {
        val first = "NOT_REAL_form".toByteArray(); ProbePassword(first).close()
        assertTrue(first.all { it == 0.toByte() })
        val second = "NOT_REAL_form".toByteArray(); val pending = ProbePassword(second)
        assertSame(second, pending.take()); assertNull(pending.take()); pending.close()
        assertFalse(second.all { it == 0.toByte() }); second.fill(0)
    }

    @Test fun automaticFailureWipesUnclaimedPasswordBeforeCloseAndLatePhaseCannotRestoreOldState() {
        val queue = SessionFixtures.Queue(); val attempt = Attempt(); val observer = Observer()
        val bytes = "NOT_REAL_queued_form_password".toByteArray(); val pending = ProbePassword(bytes)
        val probe = ConnectionProbe(SessionAttemptFactory { attempt }, Executor { queue.post(it) }) { pending.close() }
        probe.attach(observer); probe.start(SessionFixtures.host())
        attempt.listener.failed(ConnectionFailure.Authentication(ConnectionFailure.AuthenticationReason.TIMED_OUT)); queue.drain()
        assertTrue(bytes.all { it == 0.toByte() }); assertTrue(observer.results.isEmpty())
        attempt.close!!(null); queue.drain()
        val phases = observer.phases.toList()
        attempt.listener.phase(ConnectionState.Phase.AUTHENTICATING); attempt.listener.ready(); queue.drain()
        assertEquals(phases, observer.phases); assertEquals(1, observer.results.size)
    }

    @Test fun productionAttemptProbeClosesWithoutCommandsAndLeavesHealthyControlTransportIndependent() {
        val directory = Files.createTempDirectory("xmouse-probe-").toFile()
        val runtime = SshRuntime(); val controlBackend = RecordingSshBackend(); val backend = RecordingSshBackend()
        val control = SshTransport(controlBackend, SshTransport.newWorker(), limits(), runtime)
        val queue = SessionFixtures.Queue(); val observer = Observer()
        val interactions = SessionInteractions(Executor { queue.post(it) })
        val provider = SessionCredentialProvider { selection, _, _, callback ->
            callback.complete(SessionFixtures.material(SessionFixtures.host().copy(authentication = selection)), null)
        }
        val factory = SshSessionAttemptFactory(provider, HostKeyRepository(directory, TrustFileIo()), interactions,
            limits(), runtime, backendFactory = { _, _ -> backend })
        val probe = ConnectionProbe(factory, Executor { queue.post(it) })
        try {
            var controlReady = false
            control.connect(RecordingSshBackend.request(), RecordingSshBackend.authentication(), object : SshTransport.Callback<Void> {
                override fun success(value: Void?) { controlReady = true }
                override fun failure(failure: SshTransport.Failure) { error("Unexpected control failure: $failure") }
            })
            await(queue) { controlReady }
            probe.attach(observer); assertTrue(probe.start(SessionFixtures.host()))
            await(queue) { observer.results.isNotEmpty() }
            assertEquals(null to false, observer.results.single())
            assertTrue(backend.channels.single().closed)
            assertTrue(backend.writes.isEmpty()); assertTrue(backend.channels.all { it.command == null })
            assertTrue(control.isConnected); assertFalse(controlBackend.events.any { it.name == "session.close" })
            assertTrue(control.executeShellCommand("NOT_REAL_deliberate_control"))
            await(queue) { controlBackend.writes.isNotEmpty() }
            assertTrue(backend.writes.isEmpty())
        } finally {
            probe.detach(); control.close(); runtime.close(); directory.deleteRecursively()
        }
    }
    @Test fun productionAttemptClassifiesChannelFailureAndCredentialTimeoutWithoutFakeSuccess() {
        val directory = Files.createTempDirectory("xmouse-probe-failure-").toFile(); val runtime = SshRuntime()
        try {
            for (timeout in listOf(false, true)) {
                val queue = SessionFixtures.Queue(); val observer = Observer(); val backend = RecordingSshBackend().apply { failAt = "shell.connect" }
                val factory = SshSessionAttemptFactory(SessionCredentialProvider { selection, _, _, callback ->
                    if (!timeout) callback.complete(SessionFixtures.material(SessionFixtures.host().copy(authentication = selection)), null)
                }, HostKeyRepository(directory, TrustFileIo()), SessionInteractions(Executor { queue.post(it) }),
                    limits(), runtime, credentialTimeoutMillis = 60, closeTimeoutMillis = 100,
                    backendFactory = { _, _ -> backend })
                val probe = ConnectionProbe(factory, Executor { queue.post(it) }); probe.attach(observer)
                probe.start(SessionFixtures.host()); await(queue) { observer.results.isNotEmpty() }
                assertNotNull(observer.results.single().first)
                if (timeout) assertEquals(ConnectionFailure.Transport(ConnectionFailure.TransportReason.CLEANUP_UNCONFIRMED), observer.results.single().first)
                else assertEquals(ConnectionFailure.Category.TRANSPORT, observer.results.single().first!!.category)
                assertTrue(backend.writes.isEmpty()); probe.detach()
            }
        } finally { runtime.close(); directory.deleteRecursively() }
    }
    companion object {
        fun limits() = SshLimits(500, 500, 500, 1000, 500, 500, 500, 500, 200, 500, 3, 100, 100, 4096)
        fun await(queue: SessionFixtures.Queue, condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!condition() && System.nanoTime() < deadline) { queue.drain(); Thread.sleep(5) }
            queue.drain(); assertTrue("Probe did not settle", condition())
        }
    }
}
