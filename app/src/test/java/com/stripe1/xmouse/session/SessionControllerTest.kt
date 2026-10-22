package com.stripe1.xmouse.session

import com.stripe1.xmouse.connection.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class SessionControllerTest {
    private val a = SessionFixtures.host()
    private val b = SessionFixtures.host("other.invalid", 2)
    private val loss = ConnectionFailure.Transport(ConnectionFailure.TransportReason.CONNECTION_LOST)
    private class Attempt : SessionAttempt {
        lateinit var listener: SessionAttempt.Listener
        lateinit var closed: (ConnectionFailure?) -> Unit
        var closes = 0
        val shellCommands = mutableListOf<String>()
        val execCommands = mutableListOf<String>()
        override fun start(listener: SessionAttempt.Listener) { this.listener = listener }
        override fun cancelAndClose(completion: (ConnectionFailure?) -> Unit) { closes++; closed = completion }
        override fun executeShellCommand(command: String): Boolean { shellCommands.add(command); return true }
        override fun executeExecCommand(command: String): Boolean { execCommands.add(command); return true }
    }
    private class Fixture : AutoCloseable {
        val queue = SessionFixtures.Queue()
        val attempts = mutableListOf<Attempt>()
        val controller = SessionController({ Attempt().also(attempts::add) }, queue)
        fun connect(host: SessionHost) { controller.connectIfNeeded(host); queue.drain() }
        override fun close() { controller.close(); queue.drain(); attempts.lastOrNull()?.takeIf { it.closes > 0 }?.closed?.invoke(null); queue.drain() }
    }
    private val phases = listOf(ConnectionState.Phase.PREPARING_CREDENTIALS, ConnectionState.Phase.RESOLVING,
        ConnectionState.Phase.CONNECTING, ConnectionState.Phase.AWAITING_TRUST, ConnectionState.Phase.AUTHENTICATING,
        ConnectionState.Phase.CONNECTED)

    @Test fun repeatExplicitAndAutomaticConnectInEveryPhaseNeverToggleOrCreateAnotherAttempt() {
        for (phase in phases) Fixture().use { f ->
            f.connect(a)
            val attempt = f.attempts.single()
            if (phase == ConnectionState.Phase.CONNECTED) attempt.listener.ready() else attempt.listener.phase(phase)
            f.queue.drain()
            val before = f.controller.current
            repeat(20) {
                f.controller.connectIfNeeded(a)
                f.queue.drain()
                f.controller.connectAutomatically(a, f.controller.automaticConnectPermit())
            }
            f.queue.drain()
            assertEquals(1, f.attempts.size); assertEquals(0, attempt.closes)
            assertEquals(phase, f.controller.current.connection.phase)
            assertSame(before, f.controller.current)
            assertEquals(a, f.controller.current.desiredHost)
        }
    }
    @Test fun hostChangeAtEveryStageWaitsForExactOldCloseAndDropsAllOldCallbacks() {
        for (phase in phases) Fixture().use { f ->
            f.connect(a); val old = f.attempts.single()
            if (phase == ConnectionState.Phase.CONNECTED) old.listener.ready() else old.listener.phase(phase)
            f.queue.drain(); f.connect(b)
            assertEquals(ConnectionState.Phase.CLOSING, f.controller.current.connection.phase)
            assertEquals(b, f.controller.current.desiredHost); assertEquals(1, f.attempts.size)
            repeat(3) {
                old.listener.ready(); old.listener.failed(ConnectionFailure.Trust(ConnectionFailure.TrustReason.REJECTED))
                phases.forEach(old.listener::phase)
            }
            f.queue.drain(); assertEquals(1, old.closes); assertEquals(1, f.attempts.size)
            old.closed(null); f.queue.drain()
            val fresh = f.attempts.last(); assertEquals(2, f.attempts.size)
            fresh.listener.ready(); f.queue.drain()
            old.listener.failed(loss); old.listener.ready(); old.closed(loss); phases.forEach(old.listener::phase)
            f.queue.drain()
            assertEquals(ConnectionState.Connected(b.endpoint.identity), f.controller.current.connection)
            assertEquals(0, fresh.closes)
        }
    }
    @Test fun latestDesiredHostWinsDuringCleanupAndDisconnectCancelsTheReplacement() {
        Fixture().use { f ->
            f.connect(a); val old = f.attempts.single()
            f.connect(b); f.connect(a); f.connect(b)
            f.controller.disconnect(); f.queue.drain()
            assertNull(f.controller.current.desiredHost)
            old.closed(null); f.queue.drain()
            assertEquals(1, f.attempts.size); assertEquals(1, old.closes)
            assertEquals(ConnectionState.Phase.DISCONNECTED, f.controller.current.connection.phase)
        }
    }
    @Test fun explicitDisconnectInvalidatesSavedPreferenceAutoConnectEvenWhenPreparationReturnsLate() {
        Fixture().use { f ->
            val beforeConnect = f.controller.automaticConnectPermit()
            f.connect(a); val old = f.attempts.single()
            val beforeDisconnect = f.controller.automaticConnectPermit()
            f.controller.disconnect(); f.controller.disconnect(); f.queue.drain()
            old.listener.ready(); old.closed(null); f.queue.drain()
            listOf(beforeConnect, beforeDisconnect, f.controller.automaticConnectPermit()).forEach { f.controller.connectAutomatically(a, it) }
            f.queue.drain(); assertEquals(1, f.attempts.size)
            assertTrue(f.controller.current.automaticConnectSuppressed)
            f.connect(b)
            f.controller.connectAutomatically(a, beforeDisconnect); f.queue.drain()
            assertEquals(b, f.controller.current.desiredHost); assertEquals(2, f.attempts.size)
        }
    }
    @Test fun foregroundConnectEpochRejectsCallbackAlreadyQueuedBehindExplicitDisconnect() {
        Fixture().use { f ->
            val beforeDisconnect = f.controller.captureDisconnectEpoch()
            f.controller.disconnect()
            assertTrue("disconnect invalidates service callbacks synchronously",
                f.controller.captureDisconnectEpoch() > beforeDisconnect)
            f.controller.connectIfNotDisconnectedSince(a, beforeDisconnect)
            f.queue.drain()
            assertNull(f.controller.current.desiredHost)
            assertTrue(f.controller.current.automaticConnectSuppressed)
            assertTrue(f.attempts.isEmpty())

            f.controller.connectIfNotDisconnectedSince(b, f.controller.captureDisconnectEpoch())
            f.queue.drain()
            assertEquals(b, f.controller.current.desiredHost)
            assertEquals(1, f.attempts.size)
        }
    }
    @Test fun foregroundConnectGuardsAreRecheckedWhenTheSerializedQueueRuns() {
        Fixture().use { f ->
            val epoch = f.controller.captureDisconnectEpoch()
            val permit = f.controller.automaticConnectPermit()
            var isCurrent = true
            f.controller.connectIfNotDisconnectedSince(a, epoch, isCurrentRequest = { isCurrent })
            isCurrent = false
            f.queue.drain()
            assertTrue(f.attempts.isEmpty())

            isCurrent = true
            f.controller.connectAutomaticallyIfCurrent(a, permit, epoch,
                isCurrentRequest = { isCurrent }, onDecision = {})
            isCurrent = false
            f.queue.drain()
            assertTrue("superseded auto-connect must be rejected on the owner queue", f.attempts.isEmpty())

            f.controller.connectIfNotDisconnectedSince(b, f.controller.captureDisconnectEpoch())
            f.queue.drain()
            assertEquals(b, f.controller.current.desiredHost)
            assertEquals(1, f.attempts.size)
        }
    }

    @Test fun queuedShellAndExecCommandsAreDroppedAcrossHostReplacementAndRecovery() {
        Fixture().use { f ->
            f.connect(a)
            val old = f.attempts.single()
            old.listener.ready(); f.queue.drain()

            // The replacement event is ahead of both admitted A commands, but none has run yet.
            f.controller.connectIfNeeded(b)
            assertTrue(f.controller.executeShellCommand("old shell"))
            assertTrue(f.controller.executeExecCommand("old exec"))
            f.queue.drain()
            assertEquals(ConnectionState.Phase.CLOSING, f.controller.current.connection.phase)
            assertTrue(old.shellCommands.isEmpty())
            assertTrue(old.execCommands.isEmpty())

            old.closed(null); f.queue.drain()
            val replacement = f.attempts.last()
            assertEquals(b, f.controller.current.desiredHost)
            replacement.listener.ready(); f.queue.drain()
            assertTrue(f.controller.current.connection.isReady)
            assertTrue("old shell input must not be replayed on B", replacement.shellCommands.isEmpty())
            assertTrue("old exec input must not be replayed on B", replacement.execCommands.isEmpty())
        }
    }

    @Test fun explicitDisconnectInvalidatesAlreadyQueuedShellAndExecCommands() {
        Fixture().use { f ->
            f.connect(a)
            val active = f.attempts.single()
            active.listener.ready(); f.queue.drain()

            assertTrue(f.controller.executeShellCommand("queued before disconnect"))
            assertTrue(f.controller.executeExecCommand("queued before disconnect"))
            f.controller.disconnect() // Epoch invalidation is synchronous; the queue has not run yet.
            f.queue.drain()
            assertTrue(active.shellCommands.isEmpty())
            assertTrue(active.execCommands.isEmpty())
            assertEquals(ConnectionState.Phase.CLOSING, f.controller.current.connection.phase)
            active.closed(null); f.queue.drain()
            assertEquals(ConnectionState.Phase.DISCONNECTED, f.controller.current.connection.phase)
            assertTrue(active.shellCommands.isEmpty())
            assertTrue(active.execCommands.isEmpty())
        }
    }
    @Test fun involuntaryLossRetainsIntentButNeverPretendsReadyOrAutomaticallyRecovers() {
        Fixture().use { f ->
            f.connect(a); val old = f.attempts.single(); old.listener.ready(); f.queue.drain()
            old.listener.failed(loss); f.queue.drain()
            assertFalse(f.controller.current.connection.isReady); assertEquals(a, f.controller.current.desiredHost)
            old.closed(null); f.queue.drain()
            assertEquals(ConnectionState.Error(a.endpoint.identity, loss), f.controller.current.connection)
            f.controller.connectAutomatically(a, f.controller.automaticConnectPermit()); f.queue.drain()
            assertEquals(1, f.attempts.size)
            f.connect(a); assertEquals(2, f.attempts.size)
        }
    }
    @Test fun closeFailureOrDeadlineBlocksAnyNewHostAndNeverReportsTheOldOneClosed() {
        Fixture().use { f ->
            f.connect(a); val old = f.attempts.single(); f.connect(b)
            old.closed(loss); f.queue.drain()
            repeat(10) { f.connect(b); f.controller.disconnect(); f.queue.drain() }
            assertTrue(f.controller.current.cleanupUnconfirmed)
            assertEquals(a, f.controller.current.attemptHost); assertEquals(1, f.attempts.size)
            assertEquals(ConnectionFailure.Transport(ConnectionFailure.TransportReason.CLEANUP_UNCONFIRMED), f.controller.current.connection.failure)
        }
    }
    @Test fun deliberateRetryDuringLossCleanupWaitsForBarrierWhileAutomaticReturnCannotRequestRecovery() {
        Fixture().use { f ->
            f.connect(a); val old = f.attempts.single()
            old.listener.ready(); f.queue.drain(); old.listener.failed(loss); f.queue.drain()
            f.controller.connectAutomatically(a, f.controller.automaticConnectPermit()); f.queue.drain()
            assertEquals(1, f.attempts.size)
            f.connect(a); assertEquals(1, f.attempts.size)
            old.closed(null); f.queue.drain()
            assertEquals(2, f.attempts.size)
            f.attempts.last().listener.ready(); f.queue.drain(); assertTrue(f.controller.current.connection.isReady)
        }
    }
    @Test fun explicitDisconnectDuringLossCleanupFinishesDisconnectedRatherThanRestoringTheOldError() {
        Fixture().use { f ->
            f.connect(a); val old = f.attempts.single()
            old.listener.failed(loss); f.queue.drain()
            f.controller.disconnect(); f.queue.drain(); old.closed(null); f.queue.drain()
            assertEquals(ConnectionState.Disconnected(a.endpoint.identity), f.controller.current.connection)
            assertNull(f.controller.current.desiredHost)
        }
    }
    @Test fun profileIdUsernameCredentialReferenceAuthChoiceAndPortAreIdentityButDisplaySpellingIsNot() {
        Fixture().use { f ->
            f.connect(a)
            val spelling = a.copy(endpoint = Endpoint.manual("SYNTHETIC.invalid.", 2222),
                authentication = a.authentication.copy(host = a.authentication.host.copy(alias = "New label", host = "SYNTHETIC.invalid.")))
            f.connect(spelling); assertEquals(1, f.attempts.size); assertEquals(0, f.attempts[0].closes)
            val variants = listOf(a.copy(authentication = a.authentication.copy(host = a.authentication.host.copy(id = 2))),
                a.copy(authentication = a.authentication.copy(host = a.authentication.host.copy(user = "different"))),
                a.copy(authentication = a.authentication.copy(host = a.authentication.host.copy(passwordReference = "new_reference"))),
                a.copy(authentication = a.authentication.copy(useKey = true, privateKeyReference = "new_key")),
                a.copy(endpoint = Endpoint.manual("synthetic.invalid", 2223),
                    authentication = a.authentication.copy(host = a.authentication.host.copy(port = "2223"))))
            for (host in variants) {
                val old = f.attempts.last(); f.connect(host); assertEquals(1, old.closes)
                old.closed(null); f.queue.drain()
            }
            assertEquals(6, f.attempts.size)
        }
    }
    @Test fun observerDispatchDetachAndRecreationDoNotDisconnectOrDeliverOldQueuedObservations() {
        Fixture().use { f ->
            val ui = SessionFixtures.Queue(); val seen = mutableListOf<SessionSnapshot>()
            val listener = SessionController.Observer { seen.add(it) }
            val first = f.controller.observe(Executor { ui.post(it) }, listener)
            f.connect(a); f.attempts.single().listener.ready(); f.queue.drain()
            first.close(); ui.drain(); assertTrue(seen.isEmpty())
            val replacement = f.controller.observe(Executor { ui.post(it) }, listener)
            f.queue.drain(); ui.drain()
            assertEquals(1, seen.size); assertTrue(seen.single().connection.isReady)
            replacement.close(); assertEquals(0, f.attempts.single().closes)
        }
    }
    @Test fun terminalCloseCancelsPendingAndIgnoresEveryLaterConnectOrCallback() {
        val f = Fixture(); f.connect(a); val old = f.attempts.single()
        f.controller.close(); f.queue.drain()
        assertTrue(f.controller.current.closed); assertNull(f.controller.current.desiredHost)
        old.listener.ready(); f.queue.drain(); old.closed(null); f.queue.drain()
        f.controller.connectIfNeeded(b); old.listener.ready(); old.listener.failed(loss); f.queue.drain()
        assertEquals(1, f.attempts.size); assertTrue(f.queue.closed)
        assertEquals(ConnectionState.Phase.DISCONNECTED, f.controller.current.connection.phase)
    }

    @Test fun requestedEndpointCannotBePairedWithAnotherHostsCredentialSnapshot() {
        for (endpoint in listOf(Endpoint.manual("other.invalid", 2222), Endpoint.manual("synthetic.invalid", 2223))) {
            try { a.copy(endpoint = endpoint); fail("mismatched endpoint/profile accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
