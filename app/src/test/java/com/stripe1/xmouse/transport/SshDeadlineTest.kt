package com.stripe1.xmouse.transport

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/** Production coordinator/ownership, not a mocked timeout implementation. No Android objects. */
class SshDeadlineTest {
    private class Fixture(val backend: SshBackend = RecordingSshBackend(), val runtime: SshRuntime = SshRuntime.SHARED) : AutoCloseable {
        val worker = SshTransport.newWorker()
        val transport = SshTransport(backend, worker, shortLimits(), runtime)
        val losses = LinkedBlockingQueue<SshTransport.Failure>()
        init { transport.setLossListener { losses.add(it) } }
        fun connect(): Result<Void> = Result<Void>().also {
            transport.connect(RecordingSshBackend.request(initial = "initial"), RecordingSshBackend.authentication(), it)
        }
        override fun close() {
            (backend as? RecordingSshBackend)?.release?.countDown()
            transport.close()
            assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    @Test fun everyBlockedConnectionStageTimesOutWithoutHoldingCoordinatorOrCleanup() {
        for ((stage, expected) in listOf(
            "create" to SshTransport.Failure.CONNECTION_TIMEOUT,
            "session.connect" to SshTransport.Failure.CONNECTION_TIMEOUT,
            "session.clearAuthentication" to SshTransport.Failure.CONNECTION_TIMEOUT,
            "session.open" to SshTransport.Failure.CHANNEL_TIMEOUT,
            "shell.output" to SshTransport.Failure.CHANNEL_TIMEOUT,
            "shell.input" to SshTransport.Failure.CHANNEL_TIMEOUT,
            "shell.connect" to SshTransport.Failure.CHANNEL_TIMEOUT,
            "shell.write" to SshTransport.Failure.WRITE_TIMEOUT,
            "shell.flush" to SshTransport.Failure.WRITE_TIMEOUT,
        )) {
            val backend = RecordingSshBackend().apply { blockAt = stage; ignoreInterrupts = true }
            Fixture(backend).use { f ->
                val start = System.nanoTime()
                val result = f.connect()
                assertTrue(backend.entered.await(2, TimeUnit.SECONDS))
                result.await()
                assertEquals(stage, expected, result.failure)
                assertFalse(f.transport.isConnected); assertFalse(f.transport.isConnecting)
                assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2))
                f.worker.submit {}.get(1, TimeUnit.SECONDS) // Even while provider is STILL blocked.
                val disconnected = Result<Void>(); f.transport.disconnect(disconnected); disconnected.await()
                if (stage != "create") eventually { backend.events.any { it.name == "session.close" } }
                backend.release.countDown()
                eventually { SshRuntime.SHARED.activeOperations() == 0 }
                if (stage == "create") eventually { backend.events.any { it.name == "session.close" } }
            }
        }
    }

    @Test fun blockedNativeResolutionIsQuarantinedAndRepeatedAttemptsNeverGrowResolverCapacity() {
        val runtime = SshRuntime()
        val entered = CountDownLatch(2); val release = CountDownLatch(1)
        val recording = RecordingSshBackend()
        val backend = object : SshBackend by recording {
            override fun resolve(request: SshConnectionRequest): SshConnectionRequest {
                entered.countDown()
                while (true) try { release.await(); break } catch (_: InterruptedException) { }
                return request
            }
        }
        try {
            Fixture(backend, runtime).use { f ->
                repeat(2) { assertEquals(SshTransport.Failure.RESOLUTION_TIMEOUT, f.connect().await().failure) }
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                repeat(20) { assertEquals(SshTransport.Failure.CAPACITY, f.connect().await().failure) }
                assertEquals(2, runtime.activeResolvers())
                assertTrue(recording.events.isEmpty())
                val stopped = Result<Void>(); f.transport.disconnect(stopped); stopped.await()
                release.countDown()
                eventually { runtime.activeResolvers() == 0 }
                assertTrue(recording.events.isEmpty()) // Old DNS results never dial.
            }
        } finally { release.countDown(); runtime.close() }
    }

    @Test fun blockedExecWriteFlushAndScpHaveFiniteTypedOutcomesAndDropUnsentInput() {
        for ((stage, expected) in listOf(
            "shell.write" to SshTransport.Failure.WRITE_TIMEOUT,
            "shell.flush" to SshTransport.Failure.WRITE_TIMEOUT,
            "exec.connect" to SshTransport.Failure.EXEC_TIMEOUT,
            "exec.read" to SshTransport.Failure.SCP_TIMEOUT,
            "exec.write" to SshTransport.Failure.SCP_TIMEOUT,
        )) {
            val backend = RecordingSshBackend()
            Fixture(backend).use { f ->
                assertNull(f.connect().await().failure)
                backend.blockAt = stage; backend.ignoreInterrupts = true
                val result = Result<Any>()
                val file = Files.createTempFile("synthetic-scp-deadline", ".bin").toFile()
                try {
                    when {
                        stage.startsWith("shell") -> f.transport.send("blocked", result.void())
                        stage == "exec.connect" -> f.transport.exec("blocked", result.string())
                        else -> f.transport.downloadScp("blocked", file, result.void())
                    }
                    assertTrue(backend.entered.await(2, TimeUnit.SECONDS))
                    assertTrue(f.transport.executeShellCommand("unsent click"))
                    result.await(); assertEquals(stage, expected, result.failure)
                    assertEquals(expected, f.losses.poll(1, TimeUnit.SECONDS))
                    assertFalse(f.transport.isConnected)
                    f.worker.submit {}.get(1, TimeUnit.SECONDS)
                    assertFalse(backend.writes.any { "unsent" in it })
                    backend.release.countDown()
                    eventually { SshRuntime.SHARED.activeOperations() == 0 }
                    assertFalse(backend.writes.any { "unsent" in it })
                } finally { file.delete() }
            }
        }
    }

    @Test fun blockedCloseCannotPreventOtherResourcesOrNewConnectionAndEachCloseIsAttemptedOnce() {
        val backend = RecordingSshBackend()
        Fixture(backend).use { f ->
            assertNull(f.connect().await().failure)
            backend.blockAt = "shell.input.close"; backend.ignoreInterrupts = true
            val result = Result<Void>(); f.transport.disconnect(result)
            assertTrue(backend.entered.await(1, TimeUnit.SECONDS))
            result.await(); assertEquals(SshTransport.Failure.CLEANUP_TIMEOUT, result.failure)
            for (name in listOf("session.close", "shell.close", "shell.input.close")) {
                assertEquals(name, 1, backend.events.count { it.name == name })
            }
            assertTrue(f.losses.isEmpty())
            backend.channels.clear(); backend.blockAt = null
            assertNull(f.connect().await().failure)
            backend.release.countDown()
            Thread.sleep(150)
            assertTrue(f.transport.isConnected); assertTrue(f.losses.isEmpty())
        }
    }

    @Test fun disconnectAndLateOldProviderCompletionNeverCloseOrReportTheNewSession() {
        for (cancelBefore in listOf(false, true)) {
            val old = RecordingSshBackend().apply { blockAt = "session.connect"; ignoreInterrupts = true }
            val fresh = RecordingSshBackend()
            val creates = AtomicInteger()
            val backend = object : SshBackend {
                override fun create(request: SshConnectionRequest, authentication: com.stripe1.xmouse.secrets.AuthenticationMaterial) =
                    (if (creates.getAndIncrement() == 0) old else fresh).create(request, authentication)
            }
            try {
                Fixture(backend).use { f ->
                    val barrier = CountDownLatch(1); val unblock = CountDownLatch(1)
                    if (cancelBefore) { f.worker.execute { barrier.countDown(); unblock.await() }; assertTrue(barrier.await(1, TimeUnit.SECONDS)) }
                    val cancelled = f.connect()
                    if (!cancelBefore) assertTrue(old.entered.await(1, TimeUnit.SECONDS))
                    val disconnected = Result<Void>(); f.transport.disconnect(disconnected)
                    unblock.countDown(); disconnected.await()
                    if (cancelBefore) { assertEquals(0, creates.get()); creates.incrementAndGet() }
                    assertNull(f.connect().await().failure)
                    old.release.countDown()
                    Thread.sleep(600) // Old stage deadline and multiple health timer periods.
                    assertTrue(f.transport.isConnected)
                    assertEquals(1L, cancelled.done.count)
                    assertTrue(f.losses.isEmpty())
                    assertFalse(fresh.events.any { it.name == "session.close" })
                }
            } finally { old.release.countDown() }
        }
    }

    @Test fun idleRemoteChannelClosureIsObservedWithoutUserInputAndExplicitDisconnectIsSilent() {
        val backend = RecordingSshBackend()
        Fixture(backend).use { f ->
            assertNull(f.connect().await().failure)
            backend.channels.first().closed = true
            assertEquals(SshTransport.Failure.CHANNEL_CLOSED, f.losses.poll(2, TimeUnit.SECONDS))
            assertFalse(f.transport.isConnected)
            backend.channels.clear()
            assertNull(f.connect().await().failure)
            val result = Result<Void>(); f.transport.disconnect(result); result.await()
            Thread.sleep(200)
            assertTrue(f.losses.isEmpty())
        }
    }

    @Test fun outputLimitIsSharedAcrossBothStreamsAndNeverReturnsPartialSuccess() {
        val backend = RecordingSshBackend().apply { stdout = ByteArray(3000); stderr = ByteArray(3000) }
        Fixture(backend).use { f ->
            assertNull(f.connect().await().failure)
            val result = Result<String>(); assertTrue(f.transport.exec("synthetic output", result))
            result.await(); assertEquals(SshTransport.Failure.OUTPUT_LIMIT, result.failure)
            assertNull(result.value); assertFalse(f.transport.isConnected)
        }
    }

    @Test fun nonInterruptibleOperationsHaveFixedCapacityAndLateResourceHandoffsAreClosedOnce() {
        val runtime = SshRuntime()
        val backends = CopyOnWriteArrayList<RecordingSshBackend>()
        val backend = object : SshBackend {
            override fun create(request: SshConnectionRequest, authentication: com.stripe1.xmouse.secrets.AuthenticationMaterial): SshBackend.Session {
                val recording = RecordingSshBackend().apply { blockAt = "create"; ignoreInterrupts = true }
                backends.add(recording)
                return recording.create(request, authentication)
            }
        }
        try {
            Fixture(backend, runtime).use { f ->
                repeat(4) { assertEquals(SshTransport.Failure.CONNECTION_TIMEOUT, f.connect().await().failure) }
                repeat(20) { assertEquals(SshTransport.Failure.CAPACITY, f.connect().await().failure) }
                assertEquals(4, runtime.activeOperations()); assertEquals(4, runtime.retainedOwners())
                assertEquals(4, backends.size); assertEquals(0, runtime.queuedCleanup())
                backends.forEach { it.release.countDown() }
                eventually { runtime.activeOperations() == 0 && runtime.retainedOwners() == 0 }
                backends.forEach { recording -> assertEquals(1, recording.events.count { it.name == "session.close" }) }
            }
        } finally { backends.forEach { it.release.countDown() }; runtime.close() }
    }

    @Test fun aCapturedOldHealthTimerCannotInspectCloseOrReportTheReplacementSession() {
        val tasks = CopyOnWriteArrayList<Runnable>()
        val scheduler = object : ScheduledThreadPoolExecutor(1) {
            override fun scheduleWithFixedDelay(command: Runnable, initialDelay: Long, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
                tasks.add(command)
                return super.scheduleWithFixedDelay(command, initialDelay, delay, unit)
            }
        }
        val runtime = SshRuntime(System::nanoTime, scheduler)
        val backend = RecordingSshBackend()
        try {
            Fixture(backend, runtime).use { f ->
                assertNull(f.connect().await().failure)
                val oldTimer = tasks.first()
                val stop = Result<Void>(); f.transport.disconnect(stop); stop.await()
                backend.channels.clear()
                assertNull(f.connect().await().failure)
                val closes = backend.events.count { it.name.endsWith("close") }
                repeat(10) { oldTimer.run() }
                f.worker.submit {}.get(1, TimeUnit.SECONDS)
                assertTrue(f.transport.isConnected); assertTrue(f.losses.isEmpty())
                assertEquals(closes, backend.events.count { it.name.endsWith("close") })
            }
        } finally { runtime.close() }
    }

    @Test fun repeatedBlockedClosesCannotStarveAnyRetainedOwnersIndependentResources() {
        val runtime = SshRuntime()
        val recordings = CopyOnWriteArrayList<RecordingSshBackend>()
        val backend = object : SshBackend {
            override fun create(request: SshConnectionRequest, authentication: com.stripe1.xmouse.secrets.AuthenticationMaterial): SshBackend.Session =
                RecordingSshBackend().also(recordings::add).create(request, authentication)
        }
        try {
            Fixture(backend, runtime).use { f ->
                repeat(4) {
                    assertNull(f.connect().await().failure)
                    val recording = recordings.last().apply { blockAllCloses = true; ignoreInterrupts = true }
                    val result = Result<Void>(); f.transport.disconnect(result); result.await()
                    assertEquals(SshTransport.Failure.CLEANUP_TIMEOUT, result.failure)
                    // These calls all remain blocked until the final release, across ALL four owners.
                    eventually { recording.events.count { event -> event.name.endsWith("close") } == 3 }
                }
                assertEquals(4, runtime.retainedOwners())
                assertEquals(12, recordings.sumOf { recording -> recording.events.count { it.name.endsWith("close") } })
                assertEquals(0, runtime.queuedCleanup())
                assertEquals(SshTransport.Failure.CAPACITY, f.connect().await().failure)
                assertEquals(4, recordings.size)
                recordings.forEach { it.release.countDown() }
                eventually { runtime.retainedOwners() == 0 }
                assertNull(f.connect().await().failure)
                recordings.take(4).forEach { recording ->
                    for (name in listOf("session.close", "shell.close", "shell.input.close")) assertEquals(1, recording.events.count { it.name == name })
                }
            }
        } finally { recordings.forEach { it.release.countDown() }; runtime.close() }
    }

    @Test fun disconnectDuringResolutionDiscardsItsLateAddressAndDoesNotDelayTheFreshAttempt() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val resolves = AtomicInteger()
        val staleResultObserved = java.util.concurrent.atomic.AtomicBoolean()
        val createdAddresses = CopyOnWriteArrayList<java.net.InetAddress>()
        val recording = RecordingSshBackend()
        val backend = object : SshBackend by recording {
            override fun resolve(request: SshConnectionRequest, current: java.util.function.BooleanSupplier): SshConnectionRequest {
                if (resolves.getAndIncrement() == 0) {
                    entered.countDown()
                    while (true) try { release.await(); break } catch (_: InterruptedException) { }
                }
                if (!current.asBoolean) {
                    staleResultObserved.set(true)
                    return request.resolved(java.net.InetAddress.getByName("203.0.113.99"))
                }
                return request.resolved(java.net.InetAddress.getByName("127.0.0.1"))
            }
            override fun create(request: SshConnectionRequest,
                                authentication: com.stripe1.xmouse.secrets.AuthenticationMaterial): SshBackend.Session {
                createdAddresses.add(requireNotNull(request.resolvedAddress))
                return recording.create(request, authentication)
            }
        }
        try {
            Fixture(backend).use { f ->
                val cancelled = f.connect(); assertTrue(entered.await(1, TimeUnit.SECONDS))
                val stopped = Result<Void>(); f.transport.disconnect(stopped); stopped.await()
                assertNull(f.connect().await().failure)
                release.countDown(); Thread.sleep(250)
                assertTrue(f.transport.isConnected); assertTrue(f.losses.isEmpty())
                assertEquals(1L, cancelled.done.count)
                assertEquals(1, recording.events.count { it.name == "create" })
                assertTrue("the late lookup observed cancellation", staleResultObserved.get())
                assertEquals(listOf(java.net.InetAddress.getByName("127.0.0.1")), createdAddresses)
            }
        } finally { release.countDown() }
    }

    @Test fun resolutionAndTcpHandshakeShareOneOverallConnectionDeadline() {
        val recording = RecordingSshBackend().apply { blockAt = "session.connect"; ignoreInterrupts = true }
        val backend = object : SshBackend by recording {
            override fun resolve(request: SshConnectionRequest,
                                current: java.util.function.BooleanSupplier): SshConnectionRequest {
                Thread.sleep(130)
                return request
            }
        }
        Fixture(backend).use { f ->
            val started = System.nanoTime()
            val result = f.connect()
            assertTrue(recording.entered.await(1, TimeUnit.SECONDS))
            result.await()
            assertEquals(SshTransport.Failure.CONNECTION_TIMEOUT, result.failure)
            val elapsed = System.nanoTime() - started
            assertTrue("resolution and handshake share the 350ms attempt budget", elapsed < TimeUnit.MILLISECONDS.toNanos(800))
            recording.release.countDown()
        }
    }

    @Test fun queueWaitingConsumesTheOperationBudgetRatherThanSendingExpiredInteraction() {
        val backend = RecordingSshBackend()
        Fixture(backend).use { f ->
            assertNull(f.connect().await().failure)
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            f.worker.execute { entered.countDown(); release.await(2, TimeUnit.SECONDS) }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val result = Result<Void>(); assertTrue(f.transport.send("expired interaction", result))
            Thread.sleep(280); release.countDown(); result.await()
            assertEquals(SshTransport.Failure.WRITE_TIMEOUT, result.failure)
            assertFalse(backend.writes.any { "expired" in it }); assertFalse(f.transport.isConnected)
        }
    }

    companion object {
        fun shortLimits() = SshLimits(180, 220, 220, 350, 220, 220, 450, 450, 120, 100, 2, 40, 120, 4096)
        fun eventually(assertion: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!assertion() && System.nanoTime() < deadline) Thread.sleep(5)
            assertTrue(assertion())
        }
    }
    class Result<T> : SshTransport.Callback<T> {
        val done = CountDownLatch(1)
        var failure: SshTransport.Failure? = null
        var value: T? = null
        override fun success(value: T?) { this.value = value; done.countDown() }
        override fun failure(failure: SshTransport.Failure) { this.failure = failure; done.countDown() }
        fun await(): Result<T> { assertTrue("finite outcome missing", done.await(3, TimeUnit.SECONDS)); return this }
        @Suppress("UNCHECKED_CAST") fun void() = this as SshTransport.Callback<Void>
        @Suppress("UNCHECKED_CAST") fun string() = this as SshTransport.Callback<String>
    }
}
