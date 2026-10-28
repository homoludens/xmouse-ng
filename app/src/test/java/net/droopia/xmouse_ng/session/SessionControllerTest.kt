package net.droopia.xmouse_ng.session

import net.droopia.xmouse_ng.connection.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SessionControllerTest {
    private val a = SessionFixtures.host()
    private val b = SessionFixtures.host("other.invalid", 2)
    private val loss = ConnectionFailure.Transport(ConnectionFailure.TransportReason.CONNECTION_LOST)
    private class Attempt(private val interactions: SessionInteractions) : SessionAttempt {
        lateinit var listener: SessionAttempt.Listener
        lateinit var closed: (ConnectionFailure?) -> Unit
        var closes = 0
        var shellCommandCountAtClose = -1
        var acceptShell = true
        private val commandResults = interactions.beginCommandResults()
        val shellCommands = mutableListOf<String>()
        val execCommands = mutableListOf<String>()
        val commandOrder = mutableListOf<String>()
        override fun start(listener: SessionAttempt.Listener) { this.listener = listener }
        override fun cancelAndClose(completion: (ConnectionFailure?) -> Unit) {
            closes++
            shellCommandCountAtClose = shellCommands.size
            closed = completion
        }
        override fun executeShellCommand(command: String): Boolean = executeShellCommand(command, null)
        override fun executeShellCommand(
            command: String,
            presentation: SessionInteractions.PresentationToken?,
        ): Boolean {
            if (!acceptShell) return false
            shellCommands.add(command); commandOrder.add("shell:$command")
            presentation?.let { interactions.recentCommand(commandResults, it, command) }
            return true
        }
        override fun executeExecCommand(command: String, presentation: SessionInteractions.PresentationToken): Boolean {
            execCommands.add(command); commandOrder.add("exec:$command"); return true
        }
    }
    private class Fixture : AutoCloseable {
        val queue = SessionFixtures.Queue()
        val attempts = mutableListOf<Attempt>()
        val interactions = SessionInteractions(Executor { it.run() })
        class Screen : SessionInteractions.Listener {
            val recent = mutableListOf<String>()
            override fun show(prompt: SessionInteractions.Prompt) { }
            override fun dismiss(prompt: SessionInteractions.Prompt) { }
            override fun recentCommand(command: String) { recent.add(command) }
        }
        var screen = Screen()
            private set
        var presentation = interactions.attach(screen)
            private set
        fun reattach(): Screen {
            interactions.detach()
            screen = Screen()
            presentation = interactions.attach(screen)
            return screen
        }
        val controller = SessionController({ Attempt(interactions).also(attempts::add) }, queue)
        fun connect(host: SessionHost) { controller.connectIfNeeded(host); queue.drain() }
        override fun close() {
            controller.close(); queue.drain()
            attempts.lastOrNull()?.takeIf { it.closes > 0 }?.closed?.invoke(null); queue.drain()
            interactions.detach()
        }
    }
    private class BlockingPostQueue : SessionEventQueue {
        private val work = java.util.ArrayDeque<Runnable>()
        val blockNextPost = AtomicBoolean()
        val postEntered = CountDownLatch(1)
        val allowPost = CountDownLatch(1)
        var closed = false
        override fun post(action: Runnable): Boolean {
            if (blockNextPost.compareAndSet(true, false)) {
                postEntered.countDown()
                check(allowPost.await(5, TimeUnit.SECONDS))
            }
            return synchronized(work) {
                if (closed) false else { work.addLast(action); true }
            }
        }
        fun drain() {
            repeat(1000) {
                val next = synchronized(work) { if (work.isEmpty()) null else work.removeFirst() } ?: return
                next.run()
            }
            error("blocking test queue did not settle")
        }
        override fun close() { closed = true }
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
            old.listener.ready(); f.queue.drain()
            old.listener.failed(loss); f.queue.drain()
            val recovery = requireNotNull(f.controller.foregroundRecoveryPermit())
            f.controller.disconnect(); f.controller.disconnect(); f.queue.drain()
            old.listener.ready(); old.closed(null); f.queue.drain()
            listOf(beforeConnect, beforeDisconnect, f.controller.automaticConnectPermit()).forEach { f.controller.connectAutomatically(a, it) }
            f.controller.recoverInForeground(recovery, { true })
            f.queue.drain(); assertEquals(1, f.attempts.size)
            assertTrue(f.controller.current.automaticConnectSuppressed)
            assertNull(f.controller.foregroundRecoveryPermit())
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
            assertTrue(f.controller.executeExecCommand("old exec", f.presentation))
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

    @Test fun inactiveSurfaceInvalidatesQueuedGestureInputBeforeOwnerDispatch() {
        Fixture().use { f ->
            f.connect(a)
            val attempt = f.attempts.single()
            attempt.listener.ready(); f.queue.drain()
            val surface = requireNotNull(f.controller.activateControlSurface(f.presentation))
            val gesture = requireNotNull(f.controller.beginInputGesture(surface))

            assertTrue(f.controller.executeInputCommand("xdotool mousemove_relative -- 4 5", gesture))
            f.controller.deactivateControlSurface(surface)
            assertFalse("a canceled gesture cannot submit more movement", f.controller.executeInputCommand(
                "xdotool mousemove_relative -- 6 7", gesture))
            f.queue.drain()

            assertTrue("input queued before focus loss is rejected before the active attempt writes it",
                attempt.shellCommands.isEmpty())
            assertFalse(f.controller.isControlSurfaceActive(surface))
        }
    }

    @Test fun trackedXAndYButtonsReleaseInOrderAndDuplicateCancellationIsHarmless() {
        for ((down, release) in listOf(
            "xdotool mousedown 1" to "xdotool mouseup 1",
            "ydotool click 0x41" to "ydotool click 0x81",
        )) Fixture().use { f ->
            f.connect(a)
            val attempt = f.attempts.single()
            attempt.listener.ready(); f.queue.drain()
            val surface = requireNotNull(f.controller.activateControlSurface(f.presentation))
            val gesture = requireNotNull(f.controller.beginInputGesture(surface))
            assertTrue(f.controller.executeInputCommand(down, gesture))
            f.queue.drain()
            assertEquals(listOf(down), attempt.shellCommands)

            f.controller.cancelInputGesture(gesture)
            f.controller.cancelInputGesture(gesture)
            f.controller.deactivateControlSurface(surface)
            f.controller.deactivateControlSurface(surface)
            f.queue.drain()

            assertEquals("the release uses the actual backend code and follows its DOWN exactly once",
                listOf(down, release), attempt.shellCommands)
        }
    }

    @Test fun inputGestureFinishWorkIsCoalescedAndOutstandingLeasesAreBounded() {
        Fixture().use { f ->
            f.connect(a)
            val attempt = f.attempts.single()
            attempt.listener.ready(); f.queue.drain()
            val surface = requireNotNull(f.controller.activateControlSurface(f.presentation))
            val gestures = (0 until 64).map {
                requireNotNull(f.controller.beginInputGesture(surface))
            }

            assertNull("a controller cannot retain more than its fixed active-gesture budget",
                f.controller.beginInputGesture(surface))
            gestures.forEach(f.controller::finishInputGesture)
            assertEquals("many gesture finishes occupy only one serialized cleanup event", 1,
                f.queue.pendingCount)
            assertFalse(f.controller.isInputGestureActive(gestures.first()))
            assertNull("finished leases stay bounded until their FIFO retirement runs",
                f.controller.beginInputGesture(surface))

            f.queue.drain()
            assertEquals(0, f.queue.pendingCount)
            assertNotNull("retiring finished leases returns their bounded capacity",
                f.controller.beginInputGesture(surface))
        }
    }

    @Test fun coalescedGestureRetirementStaysBehindEachGesturesAlreadyAdmittedInput() {
        Fixture().use { f ->
            f.connect(a)
            val attempt = f.attempts.single()
            attempt.listener.ready(); f.queue.drain()
            val surface = requireNotNull(f.controller.activateControlSurface(f.presentation))
            val first = requireNotNull(f.controller.beginInputGesture(surface))
            val second = requireNotNull(f.controller.beginInputGesture(surface))

            f.controller.finishInputGesture(first)
            assertTrue(f.controller.executeInputCommand("xdotool mousemove_relative -- 1 2", second))
            f.controller.finishInputGesture(second)
            assertEquals("a second completion is coalesced rather than posted ahead of input", 2,
                f.queue.pendingCount)
            f.queue.drain()

            assertEquals("the earlier cleanup cannot retire a later gesture before its admitted command",
                listOf("xdotool mousemove_relative -- 1 2"), attempt.shellCommands)
            assertFalse(f.controller.isInputGestureActive(first))
            assertFalse(f.controller.isInputGestureActive(second))
            assertEquals(0, f.queue.pendingCount)
        }
    }

    @Test fun explicitDisconnectReleasesTrackedButtonsBeforeTheOldAttemptCloseBarrier() {
        Fixture().use { f ->
            f.connect(a)
            val attempt = f.attempts.single()
            attempt.listener.ready(); f.queue.drain()
            val surface = requireNotNull(f.controller.activateControlSurface(f.presentation))
            val gesture = requireNotNull(f.controller.beginInputGesture(surface))
            assertTrue(f.controller.executeInputCommand("xdotool mousedown 1", gesture))
            f.queue.drain()

            f.controller.disconnect()
            f.queue.drain()
            assertEquals(listOf("xdotool mousedown 1", "xdotool mouseup 1"), attempt.shellCommands)
            assertEquals("release is attempted synchronously before transport cancellation", 2,
                attempt.shellCommandCountAtClose)
            assertFalse(f.controller.isControlSurfaceActive(surface))

            attempt.closed(null); f.queue.drain()
            f.connect(b)
            val replacement = f.attempts.last()
            replacement.listener.ready(); f.queue.drain()
            assertTrue("old held input must never replay on the replacement owner",
                replacement.shellCommands.isEmpty())
        }
    }

    @Test fun delayedRetirementReleasesOnlyItsSurfaceAndLateCancelCannotReleaseNewSurfaceHold() {
        val queue = BlockingPostQueue()
        val interactions = SessionInteractions(Executor { it.run() })
        val screen = Fixture.Screen()
        val presentation = interactions.attach(screen)
        val attempt = Attempt(interactions)
        val controller = SessionController({ attempt }, queue)
        try {
            controller.connectIfNeeded(a); queue.drain()
            attempt.listener.ready(); queue.drain()
            val surfaceA = requireNotNull(controller.activateControlSurface(presentation))
            val gestureA = requireNotNull(controller.beginInputGesture(surfaceA))
            assertTrue(controller.executeInputCommand("xdotool mousedown 1", gestureA))
            queue.drain()

            queue.blockNextPost.set(true)
            val cancelThread = Thread({ controller.cancelInputGesture(gestureA) }, "blocked-input-retirement")
            cancelThread.start()
            assertTrue("cancel did not claim its coalesced cleanup post", queue.postEntered.await(2, TimeUnit.SECONDS))

            // A's scheduler owns the cleanup flag but has not enqueued its work. B's DOWN is
            // therefore ordered before A's cleanup event, reproducing the cross-surface race.
            val surfaceB = requireNotNull(controller.activateControlSurface(presentation))
            val gestureB = requireNotNull(controller.beginInputGesture(surfaceB))
            assertTrue(controller.executeInputCommand("xdotool mousedown 3", gestureB))
            controller.cancelInputGesture(gestureA) // A late duplicate must not affect B.

            queue.allowPost.countDown()
            cancelThread.join(2_000)
            assertFalse("cleanup enqueue did not unblock", cancelThread.isAlive)
            queue.drain()
            assertEquals("retiring A must not clear or release B's already-dispatched hold",
                listOf("xdotool mousedown 1", "xdotool mousedown 3", "xdotool mouseup 1"),
                attempt.shellCommands)

            controller.deactivateControlSurface(surfaceB)
            queue.drain()
            assertEquals("B's own surface retirement releases its hold exactly once",
                listOf("xdotool mousedown 1", "xdotool mousedown 3", "xdotool mouseup 1", "xdotool mouseup 3"),
                attempt.shellCommands)
        } finally {
            queue.allowPost.countDown()
            controller.close(); queue.drain()
            if (attempt.closes > 0) { attempt.closed(null); queue.drain() }
            interactions.detach()
        }
    }

    @Test fun normalGestureFinishKeepsItsUpOrderedAndDoesNotRestoreAfterNewSurfaceAttach() {
        Fixture().use { f ->
            f.connect(a)
            val old = f.attempts.single()
            old.listener.ready(); f.queue.drain()
            val firstSurface = requireNotNull(f.controller.activateControlSurface(f.presentation))
            val oldGesture = requireNotNull(f.controller.beginInputGesture(firstSurface))
            assertTrue(f.controller.executeInputCommand("xdotool mousedown 3", oldGesture))
            f.queue.drain()
            assertTrue(f.controller.executeInputCommand("xdotool mouseup 3", oldGesture))
            f.controller.finishInputGesture(oldGesture)
            f.queue.drain()
            assertEquals(listOf("xdotool mousedown 3", "xdotool mouseup 3"), old.shellCommands)

            f.controller.deactivateControlSurface(firstSurface)
            f.controller.deactivateControlSurface(firstSurface)
            f.queue.drain()
            f.controller.connectIfNeeded(b); f.queue.drain()
            old.closed(null); f.queue.drain()
            val fresh = f.attempts.last()
            fresh.listener.ready(); f.queue.drain()
            val secondSurface = requireNotNull(f.controller.activateControlSurface(f.presentation))
            val freshGesture = requireNotNull(f.controller.beginInputGesture(secondSurface))

            assertFalse("old gesture movement cannot borrow a replacement host's active surface",
                f.controller.executeInputCommand("xdotool mousemove_relative -- 99 99", oldGesture))
            assertFalse("the old host's held button must not be released on the new session",
                fresh.shellCommands.any { it.contains("mouseup") })
            assertTrue(f.controller.executeInputCommand("xdotool mousemove_relative -- 1 2", freshGesture))
            f.queue.drain()
            assertEquals(listOf("xdotool mousemove_relative -- 1 2"), fresh.shellCommands)
        }
    }

    @Test fun rejectedReleaseClearsLedgerAndCannotReplayOnTheNextSurface() {
        Fixture().use { f ->
            f.connect(a)
            val attempt = f.attempts.single()
            attempt.listener.ready(); f.queue.drain()
            val surface = requireNotNull(f.controller.activateControlSurface(f.presentation))
            val gesture = requireNotNull(f.controller.beginInputGesture(surface))
            assertTrue(f.controller.executeInputCommand("xdotool mousedown 2", gesture))
            f.queue.drain()
            attempt.acceptShell = false
            f.controller.deactivateControlSurface(surface)
            f.queue.drain()
            attempt.acceptShell = true
            val nextSurface = requireNotNull(f.controller.activateControlSurface(f.presentation))
            assertNotNull(f.controller.beginInputGesture(nextSurface))
            f.controller.deactivateControlSurface(nextSurface)
            f.queue.drain()

            assertEquals("a failed best-effort release is not replayed later", 1, attempt.shellCommands.size)
        }
    }

    @Test fun explicitDisconnectInvalidatesAlreadyQueuedShellAndExecCommands() {
        Fixture().use { f ->
            f.connect(a)
            val active = f.attempts.single()
            active.listener.ready(); f.queue.drain()

            assertTrue(f.controller.executeShellCommand("queued before disconnect"))
            assertTrue(f.controller.executeExecCommand("queued before disconnect", f.presentation))
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

    @Test fun mixedCommandsKeepAdmissionOrderAndLossDropsQueuedWorkBeforeFreshGeneration() {
        Fixture().use { f ->
            f.connect(a)
            val old = f.attempts.single()
            old.listener.ready(); f.queue.drain()

            assertTrue(f.controller.executeShellCommand("A-shell-1"))
            assertTrue(f.controller.executeExecCommand("A-script-2", f.presentation))
            assertTrue(f.controller.executeShellCommand("A-shell-3"))
            f.queue.drain()
            assertEquals(listOf("shell:A-shell-1", "exec:A-script-2", "shell:A-shell-3"), old.commandOrder)

            // The loss event is serialized ahead of these already-admitted commands.
            old.listener.failed(loss)
            assertTrue(f.controller.executeShellCommand("A-unsent-shell"))
            assertTrue(f.controller.executeExecCommand("A-unsent-script", f.presentation))
            f.queue.drain()
            assertEquals(listOf("shell:A-shell-1", "exec:A-script-2", "shell:A-shell-3"), old.commandOrder)
            assertEquals(ConnectionState.Phase.ERROR, f.controller.current.connection.phase)
            old.closed(null); f.queue.drain()
            assertEquals(ConnectionState.Phase.ERROR, f.controller.current.connection.phase)
            assertFalse("commands are rejected while the session is lost", f.controller.executeShellCommand("not-ready"))

            f.connect(a)
            val fresh = f.attempts.last()
            fresh.listener.ready(); f.queue.drain()
            assertTrue(f.controller.executeShellCommand("NEWONLY")); f.queue.drain()
            assertEquals(listOf("shell:NEWONLY"), fresh.commandOrder)
            assertFalse(fresh.commandOrder.any { it.contains("A-unsent") })
        }
    }

    @Test fun acceptedShellFeedbackUsesItsAdmissionScreenAndNeverRefreshesAfterStaleOrRejectedInput() {
        Fixture().use { f ->
            f.connect(a)
            val oldAttempt = f.attempts.single()
            oldAttempt.listener.ready(); f.queue.drain()
            val oldPresentation = f.presentation

            // The same session accepts an already-queued command after its screen detaches. Its
            // callback must retain the old presentation rather than update the reattached screen.
            assertTrue(f.controller.executeShellCommand("queued old-screen command", oldPresentation))
            val newScreen = f.reattach()
            f.queue.drain()
            assertEquals(listOf("queued old-screen command"), oldAttempt.shellCommands)
            assertTrue("late old-screen acceptance must not update the new screen", newScreen.recent.isEmpty())

            // A host replacement queued ahead of input invalidates that command before dispatch.
            f.controller.connectIfNeeded(b)
            assertTrue(f.controller.executeShellCommand("queued old-host command", f.presentation))
            f.queue.drain()
            assertEquals("old-host input must not reach its retiring attempt",
                listOf("queued old-screen command"), oldAttempt.shellCommands)
            assertEquals("host replacement must invalidate the queued old command", 1, oldAttempt.closes)
            assertTrue(newScreen.recent.isEmpty())

            oldAttempt.closed(null); f.queue.drain()
            val newAttempt = f.attempts.last()
            newAttempt.listener.ready(); f.queue.drain()
            val newPresentation = f.presentation

            assertTrue(f.controller.executeShellCommand("accepted exact custom shell", newPresentation))
            f.queue.drain()
            assertEquals(listOf("accepted exact custom shell"), newScreen.recent)

            newAttempt.acceptShell = false
            assertTrue("controller acceptance is only local queue admission",
                f.controller.executeShellCommand("transport-rejected shell", newPresentation))
            f.queue.drain()
            assertEquals("a transport rejection must not be presented as accepted input",
                listOf("accepted exact custom shell"), newScreen.recent)

            newAttempt.acceptShell = true
            assertTrue(f.controller.executeShellCommand("queued immediately before disconnect", newPresentation))
            f.controller.disconnect()
            f.queue.drain()
            assertEquals("disconnect invalidates queued shell feedback", listOf("accepted exact custom shell"),
                newScreen.recent)
            assertFalse(f.controller.executeShellCommand("rejected after disconnect", newPresentation))
            assertEquals(listOf("accepted exact custom shell"), newScreen.recent)
        }
    }

    @Test fun controllerBoundsAdmittedButNotYetDispatchedInput() {
        Fixture().use { f ->
            f.connect(a)
            val attempt = f.attempts.single()
            attempt.listener.ready(); f.queue.drain()

            repeat(128) { index -> assertTrue(f.controller.executeShellCommand("queued-$index")) }
            assertFalse("finite owner-queue backpressure must reject rather than retain more commands",
                f.controller.executeExecCommand("overflow-script", f.presentation))
            f.queue.drain()
            assertEquals(128, attempt.shellCommands.size)
            assertFalse(attempt.execCommands.contains("overflow-script"))
        }
    }

    @Test fun involuntaryLossRetainsIntentAndAllowsOnlyOneFreshForegroundRecoveryUntilDeliberateRetry() {
        Fixture().use { f ->
            f.connect(a); val old = f.attempts.single(); old.listener.ready(); f.queue.drain()
            old.listener.failed(loss); f.queue.drain()
            assertFalse(f.controller.current.connection.isReady); assertEquals(a, f.controller.current.desiredHost)
            old.closed(null); f.queue.drain()
            assertEquals(ConnectionState.Error(a.endpoint.identity, loss), f.controller.current.connection)
            f.controller.connectAutomatically(a, f.controller.automaticConnectPermit()); f.queue.drain()
            assertEquals(1, f.attempts.size)
            val permit = requireNotNull(f.controller.foregroundRecoveryPermit())
            var visible = true
            f.controller.recoverInForeground(permit, { visible })
            visible = false
            f.queue.drain()
            assertEquals("a screen that left before dispatch cannot recover in the background", 1, f.attempts.size)
            f.controller.recoverInForeground(permit, { true }); f.queue.drain()
            assertEquals(2, f.attempts.size)
            assertEquals(a, f.controller.current.desiredHost)
            assertFalse(f.controller.current.connection.isReady)
            val recovery = f.attempts.last()
            recovery.listener.failed(loss); f.queue.drain(); recovery.closed(null); f.queue.drain()
            assertNull("failed recovery leaves manual Retry, not another foreground retry", f.controller.foregroundRecoveryPermit())
            repeat(10) {
                f.controller.recoverInForeground(permit, { true })
                f.controller.connectAutomatically(a, f.controller.automaticConnectPermit())
            }
            f.queue.drain(); assertEquals(2, f.attempts.size)
            assertEquals(ConnectionState.Error(a.endpoint.identity, loss), f.controller.current.connection)
            f.connect(a); assertEquals(3, f.attempts.size)
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
    @Test fun foregroundRecoveryDuringLossCleanupWaitsForExactOldBarrierAndStalePermitCannotChangeHosts() {
        Fixture().use { f ->
            f.connect(a); val old = f.attempts.single()
            old.listener.ready(); f.queue.drain(); f.controller.interrupted(); f.queue.drain()
            f.controller.connectAutomatically(a, f.controller.automaticConnectPermit()); f.queue.drain()
            assertEquals(1, f.attempts.size)
            val permit = requireNotNull(f.controller.foregroundRecoveryPermit())
            repeat(3) { f.controller.recoverInForeground(permit, { true }) }; f.queue.drain()
            assertEquals(1, f.attempts.size)
            old.closed(null); f.queue.drain()
            assertEquals(2, f.attempts.size)
            f.attempts.last().listener.ready(); f.queue.drain(); assertTrue(f.controller.current.connection.isReady)
            f.connect(b)
            f.controller.recoverInForeground(permit, { true }); f.queue.drain()
            assertEquals(b, f.controller.current.desiredHost)
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
