package com.stripe1.xmouse.session

import com.stripe1.xmouse.connection.*
import com.stripe1.xmouse.secrets.AuthenticationCallback
import com.stripe1.xmouse.transport.*
import com.stripe1.xmouse.trust.*
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Controller -> captured credentials -> production transport/JSch/strict trust -> synthetic MINA. */
class SessionPipelineIntegrationTest {
    private enum class ReviewInvalidator { CANCEL, DETACH, DISCONNECT, STALE, UNCERTAIN_CLOSE }

    private class Server(port: Int = 0, val hostKeyPair: KeyPair = newKeyPair()) : AutoCloseable {
        val authentications = AtomicInteger()
        val commands = AtomicInteger()
        val input = CountDownLatch(1)
        val shellCommands = CopyOnWriteArrayList<String>()
        val execCommands = CopyOnWriteArrayList<String>()
        val commandOrder = CopyOnWriteArrayList<String>()
        private val stopped = AtomicBoolean()
        val ssh = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"; this.port = port
            keyPairProvider = KeyPairProvider.wrap(hostKeyPair)
            setPasswordAuthenticator { user, password, _ ->
                authentications.incrementAndGet(); user == "NOT_REAL_user" && password == "NOT_REAL_password"
            }
            setShellFactory { command() }
            setCommandFactory { _, text -> command(text) }
            start()
        }
        private fun command(text: String = "shell"): Command {
            if (text == "shell") commands.incrementAndGet() else execCommands.add(text)
            return object : Command {
                private var source: InputStream? = null
                private var output: OutputStream? = null
                private var error: OutputStream? = null
                private var exit: ExitCallback? = null
                override fun setInputStream(input: InputStream) { source = input }
                override fun setOutputStream(output: OutputStream) { this.output = output }
                override fun setErrorStream(error: OutputStream) { this.error = error }
                override fun setExitCallback(callback: ExitCallback) { exit = callback }
                override fun start(channel: ChannelSession, env: Environment) {
                    if (text == "shell") {
                        Thread({
                            val line = ByteArrayOutputStream()
                            try {
                                while (true) {
                                    val next = source!!.read()
                                    if (next < 0) break
                                    input.countDown()
                                    if (next == '\n'.code) {
                                        val value = line.toByteArray().toString(Charsets.UTF_8).removeSuffix("\r")
                                        shellCommands.add(value)
                                        commandOrder.add("shell:$value")
                                        line.reset()
                                    } else line.write(next)
                                }
                            } catch (_: IOException) { }
                        }, "synthetic-session-input").apply { isDaemon = true; start() }
                    } else {
                        commandOrder.add("exec:$text")
                        output!!.write("out one\nout two\n".toByteArray())
                        error!!.write("err one\nerr two\n".toByteArray())
                        exit!!.onExit(0)
                    }
                }
                override fun destroy(channel: ChannelSession) { source?.close() }
            }
        }
        fun profile(id: Long = 1) = SessionFixtures.host("127.0.0.1", id, port = ssh.port).copy(initialCommand = "synthetic input, never executed")
        fun disconnectClients() { ssh.activeSessions.toList().forEach { it.close(true) } }
        override fun close() { if (stopped.compareAndSet(false, true)) ssh.stop(true) }
        companion object {
            fun newKeyPair() = KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
        }
    }

    /** Holds one real JSch session close so the factory/controller close barrier can be observed. */
    private class SessionCloseGate {
        private val armed = AtomicBoolean()
        private val failAfterClose = AtomicBoolean()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val physicalCloseCompleted = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()

        fun arm(fail: Boolean = false) {
            failAfterClose.set(fail)
            armed.set(true)
        }

        fun backend(trust: HostTrustRequests, trusted: Runnable): SshBackend {
            val delegate = JschSshBackend(HostTrustPolicy(trust, trusted))
            return object : SshBackend by delegate {
                override fun resolve(request: SshConnectionRequest): SshConnectionRequest {
                    events.add("resolve")
                    return delegate.resolve(request)
                }

                override fun create(
                    request: SshConnectionRequest,
                    authentication: com.stripe1.xmouse.secrets.AuthenticationMaterial,
                    current: java.util.function.BooleanSupplier,
                ): SshBackend.Session {
                    events.add("create")
                    val session = try { delegate.create(request, authentication, current) }
                    catch (failure: Exception) {
                        events.add("create-failed:${failure.javaClass.name}")
                        throw failure
                    }
                    events.add("created")
                    return object : SshBackend.Session by session {
                        override fun connect(timeoutMillis: Int) {
                            events.add("connect")
                            try { session.connect(timeoutMillis) }
                            catch (failure: Exception) {
                                events.add("connect-failed:${failure.javaClass.name}")
                                throw failure
                            }
                        }

                        override fun close() {
                            events.add("close")
                            if (armed.compareAndSet(true, false)) {
                                entered.countDown()
                                check(release.await(10, TimeUnit.SECONDS))
                            }
                            session.close()
                            physicalCloseCompleted.countDown()
                            if (failAfterClose.getAndSet(false)) throw IOException("synthetic cleanup uncertainty")
                        }
                    }
                }

                override fun replaceHostKey(mismatch: HostKeyMismatch, current: java.util.function.BooleanSupplier) {
                    events.add("replace")
                    try { delegate.replaceHostKey(mismatch, current) }
                    catch (failure: Exception) {
                        events.add("replace-failed:${failure.javaClass.name}")
                        throw failure
                    }
                }
            }
        }
    }

    private class Fixture(
        private val closeGate: SessionCloseGate? = null,
        closeTimeoutMillis: Long = 5_000,
        private val commandBackend: RecordingSshBackend? = null,
        healthIntervalMillis: Int = 100,
    ) : AutoCloseable, SessionInteractions.Listener {
        val directory = Files.createTempDirectory("xmouse-session-loopback-").toFile()
        val queue = SessionFixtures.Queue()
        val prompts = LinkedBlockingQueue<SessionInteractions.Prompt>()
        val replacementResults = LinkedBlockingQueue<Boolean>()
        val execResults = CopyOnWriteArrayList<Pair<String, String>>()
        val interactions = SessionInteractions(Executor { it.run() })
        val presentation = interactions.attach(this)
        val reads = AtomicInteger()
        private val worker = Executors.newSingleThreadExecutor { Thread(it, "synthetic-session-credentials") }
        private val credentials = SessionCredentialProvider { selection, _, current, callback: AuthenticationCallback ->
            worker.execute {
                reads.incrementAndGet()
                if (!current.asBoolean) callback.complete(null, null) else {
                    val host = SessionFixtures.host(selection.host.host!!, selection.host.id,
                        selection.host.passwordReference!!, selection.host.port!!.toInt())
                    callback.complete(SessionFixtures.material(host), null)
                }
            }
        }
        val runtime = SshRuntime()
        val limits = SshLimits(1000, 3000, 1000, 5000, 1000, 1000, 1000, 1000, 700, 1000, 3,
            healthIntervalMillis, 300, 4096)
        private val backendFactory: (HostTrustRequests, Runnable) -> SshBackend = closeGate?.let { gate ->
            { trust, trusted -> gate.backend(trust, trusted) }
        } ?: commandBackend?.let { backend -> { _, _ -> backend } }
            ?: { trust, trusted -> JschSshBackend(HostTrustPolicy(trust, trusted)) }
        val factory = SshSessionAttemptFactory(credentials, HostKeyRepository(directory, TrustFileIo()), interactions, limits, runtime,
            closeTimeoutMillis = closeTimeoutMillis, backendFactory = backendFactory)
        val controller = SessionController(factory, queue)
        override fun show(prompt: SessionInteractions.Prompt) { prompts.add(prompt) }
        override fun dismiss(prompt: SessionInteractions.Prompt) { }
        override fun execCompleted(command: String, output: String) { execResults.add(command to output) }
        override fun replacementFinished(mismatch: HostKeyMismatch, success: Boolean) { replacementResults.add(success) }
        fun await(assertion: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (System.nanoTime() < deadline) { queue.drain(); if (assertion()) return; Thread.sleep(5) }
            queue.drain(); assertTrue("condition timed out; snapshot=${controller.current}; prompts=${prompts.size}; replacement=${replacementResults}; backend=${closeGate?.events}", assertion())
        }
        fun connect(host: SessionHost) { controller.connectIfNeeded(host); queue.drain() }
        fun prompt(): HostTrustRequests.Request {
            await { prompts.isNotEmpty() }
            return (prompts.remove() as SessionInteractions.Prompt.Trust).request
        }
        fun mismatchPrompt(): SessionInteractions.Prompt.Mismatch {
            await { prompts.any { it is SessionInteractions.Prompt.Mismatch } }
            return (prompts.poll(5, TimeUnit.SECONDS) as? SessionInteractions.Prompt.Mismatch)
                ?: throw AssertionError("changed-host review did not arrive")
        }
        fun persistedKey(endpoint: Endpoint): ApprovedHostKey =
            requireNotNull(HostKeyRepository(directory, TrustFileIo()).find(endpoint.identity))
        override fun close() {
            controller.close(); await { queue.closed }
            interactions.detach(); worker.shutdown(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
            runtime.close(); directory.deleteRecursively()
        }
    }

    private fun approvedKey(keyPair: KeyPair): ApprovedHostKey {
        val encoded = PublicKeyEntry.toString(keyPair.public).substringAfter(' ')
        return ApprovedHostKey.parse(java.util.Base64.getDecoder().decode(encoded))
    }

    @Test fun actualPipelineReusesOneAuthenticatedSessionAcrossRepeatRequestsAndObserverRecreationThenSwitchesProfile() {
        Server().use { server -> Fixture().use { f ->
            val host = server.profile()
            f.connect(host)
            val prompt = f.prompt()
            f.await { f.controller.current.connection.phase == ConnectionState.Phase.AWAITING_TRUST }
            repeat(20) { f.connect(host) }
            assertEquals(1, f.reads.get()); assertEquals(0, server.authentications.get()); assertEquals(0, server.commands.get())
            prompt.accept()
            f.await { f.controller.current.connection.isReady }
            assertTrue(server.input.await(5, TimeUnit.SECONDS))
            val serverSession = server.ssh.activeSessions.single()
            val observer = SessionController.Observer { }
            f.controller.observe(Executor { it.run() }, observer).also { f.queue.drain(); it.close() }
            val replacement = f.controller.observe(Executor { it.run() }, observer)
            repeat(20) {
                f.controller.connectAutomatically(host, f.controller.automaticConnectPermit()); f.queue.drain()
            }
            assertSame(serverSession, server.ssh.activeSessions.single())
            assertEquals(1, f.reads.get()); assertEquals(1, server.authentications.get()); assertEquals(1, server.commands.get())
            replacement.close()
            val switched = server.profile(2)
            f.connect(switched)
            f.await { f.controller.current.connection.isReady && f.controller.current.attemptHost == switched }
            assertEquals(2, f.reads.get()); assertEquals(2, server.authentications.get())
            assertTrue(f.prompts.isEmpty()) // Same logical endpoint/key still goes through normal repository checking.
            f.await { !serverSession.isOpen && server.ssh.activeSessions.size == 1 }
            assertNotSame(serverSession, server.ssh.activeSessions.single())
        } }
    }

    @Test fun productionControllerJschAndMinaPreserveMixedCommandOrderAndNeverReplayAfterLoss() {
        val keyPair = Server.newKeyPair()
        val initialServer = Server(hostKeyPair = keyPair)
        val port = initialServer.ssh.port
        Fixture().use { f ->
            try {
                val host = initialServer.profile().copy(initialCommand = null)
                f.connect(host)
                f.prompt().accept()
                f.await { f.controller.current.connection.isReady }

                assertTrue(f.controller.executeShellCommand("first-shell-command"))
                assertTrue(f.controller.executeExecCommand("middle-exec-command", f.presentation))
                assertTrue(f.controller.executeShellCommand("last-shell-command"))
                f.await {
                    initialServer.shellCommands.contains("first-shell-command") &&
                        initialServer.execCommands.contains("middle-exec-command") &&
                        initialServer.shellCommands.contains("last-shell-command")
                }
                assertEquals("controller admission order must be preserved by the production SSH coordinator",
                    listOf("shell:first-shell-command", "exec:middle-exec-command", "shell:last-shell-command"),
                    initialServer.commandOrder.filter { it.endsWith("first-shell-command") ||
                        it.endsWith("middle-exec-command") || it.endsWith("last-shell-command") })

                // Close the actual MINA-backed connection, then admit commands behind its queued loss
                // event. The event queue is intentionally held so its order is deterministic.
                initialServer.disconnectClients()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (f.queue.pendingCount == 0 && System.nanoTime() < deadline) Thread.sleep(5)
                assertTrue("the actual MINA disconnect must publish a controller loss event", f.queue.pendingCount > 0)
                assertTrue(f.controller.executeShellCommand("unsent-shell-after-mina-loss"))
                assertTrue(f.controller.executeExecCommand("unsent-exec-after-mina-loss", f.presentation))
                f.queue.drain()
                f.await { f.controller.current.attemptHost == null &&
                    f.controller.current.connection.phase == ConnectionState.Phase.ERROR }
                assertTrue("the lost attempt must not publish its unsent exec result after loss",
                    f.execResults.none { it.first == "unsent-exec-after-mina-loss" })
                initialServer.close()

                Server(port, keyPair).use { reconnected ->
                    f.connect(host)
                    f.await { f.controller.current.connection.isReady }
                    assertEquals("a fresh session starts without replaying either command admitted behind loss",
                        0, reconnected.shellCommands.count { it == "unsent-shell-after-mina-loss" })
                    assertFalse(reconnected.execCommands.contains("unsent-exec-after-mina-loss"))
                    assertTrue(f.controller.executeShellCommand("fresh-session-only"))
                    f.await { reconnected.shellCommands.contains("fresh-session-only") }
                    assertFalse(reconnected.shellCommands.any { it.startsWith("unsent-") })
                    assertFalse(reconnected.execCommands.any { it.startsWith("unsent-") })
                    f.controller.disconnect()
                    f.await { f.controller.current.attemptHost == null &&
                        f.controller.current.connection.phase == ConnectionState.Phase.DISCONNECTED }
                }
            } finally {
                initialServer.close()
            }
        }
    }

    @Test fun realTransportDropsCommandsBehindAProviderCallThatFailsAndAReplacementAttemptDoesNotReplayThem() {
        val backend = RecordingSshBackend().apply { blockAt = "exec.connect"; ignoreInterrupts = true }
        Fixture(commandBackend = backend).use { f ->
            val host = SessionFixtures.host("synthetic.invalid")
            f.connect(host)
            f.await { f.controller.current.connection.isReady }

            assertTrue(f.controller.executeExecCommand("blocked-exec-attempt", f.presentation))
            f.queue.drain()
            assertTrue("the production transport did not reach its blocked provider call",
                backend.entered.await(5, TimeUnit.SECONDS))
            assertTrue("the mixed shell write should be admitted behind the exec operation",
                f.controller.executeShellCommand("unsent-shell-after-provider-loss"))
            f.queue.drain()
            backend.failAt = "exec.connect"
            backend.release.countDown()
            f.await { f.controller.current.attemptHost == null &&
                f.controller.current.connection.phase == ConnectionState.Phase.ERROR }
            assertTrue("a failed exec provider call cannot publish partial script output",
                f.execResults.none { it.first == "blocked-exec-attempt" })

            backend.failAt = null
            backend.blockAt = null
            f.connect(host)
            f.await { f.controller.current.connection.isReady }
            assertTrue(f.controller.executeShellCommand("fresh-session-only"))
            f.queue.drain()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (backend.writes.none { it.startsWith("fresh-session-only\r\n") } &&
                System.nanoTime() < deadline) Thread.sleep(5)
            assertTrue(backend.writes.any { it.startsWith("fresh-session-only\r\n") })
            assertFalse("an old command must not replay on the replacement transport",
                backend.writes.any { it.startsWith("unsent-shell-after-provider-loss") })
            f.controller.disconnect()
            f.await { f.controller.current.attemptHost == null &&
                f.controller.current.connection.phase == ConnectionState.Phase.DISCONNECTED }
        }
    }

    @Test fun cancelledInputQueuedBehindBlockedExecIsDroppedAtRealTransportAndOldHoldIsReleased() {
        val backend = RecordingSshBackend()
        Fixture(commandBackend = backend).use { f ->
            val host = SessionFixtures.host("synthetic.invalid")
            f.connect(host)
            f.await { f.controller.current.connection.isReady }
            val originalGeneration = f.controller.current.generation
            val surface = requireNotNull(f.controller.activateControlSurface(f.presentation))
            val gesture = requireNotNull(f.controller.beginInputGesture(surface))

            assertTrue(f.controller.executeInputCommand("xdotool mousedown 1", gesture))
            f.await { backend.writes.contains("xdotool mousedown 1\r\n") }

            // The production transport coordinator now waits for a real provider exec operation.
            backend.blockAt = "exec.connect"
            backend.ignoreInterrupts = true
            assertTrue(f.controller.executeExecCommand("synthetic blocked script", f.presentation))
            f.queue.drain()
            assertTrue("script did not reach the blocked provider operation", backend.entered.await(5, TimeUnit.SECONDS))

            val unsentMotion = "xdotool mousemove_relative -- 21 22"
            val unsentClick = "xdotool click 1"
            val unsentDown = "xdotool mousedown 2"
            assertTrue(f.controller.executeInputCommand(unsentMotion, gesture))
            assertTrue(f.controller.executeInputCommand(unsentClick, gesture))
            assertTrue(f.controller.executeInputCommand(unsentDown, gesture))
            f.queue.drain() // All three crossed SessionController and were accepted by SshTransport.
            assertEquals(listOf("xdotool mousedown 1\r\n"), backend.writes.toList())

            f.controller.cancelInputGesture(gesture)
            f.controller.deactivateControlSurface(surface)
            f.queue.drain()
            backend.release.countDown()

            f.await {
                backend.writes.contains("xdotool mouseup 1\r\n") &&
                    f.execResults.any { it.first == "synthetic blocked script" } &&
                    f.controller.current.connection.isReady
            }
            assertEquals("only the already-written DOWN gets its old-attempt release; revoked motion, click, and DOWN never reach provider write",
                listOf(
                "xdotool mousedown 1\r\n", "xdotool mouseup 1\r\n",
            ), backend.writes.toList())
            assertEquals(originalGeneration, f.controller.current.generation)
            assertEquals(1, backend.events.count { it.name == "session.connect" })

            val resumedSurface = requireNotNull(f.controller.activateControlSurface(f.presentation))
            val resumedGesture = requireNotNull(f.controller.beginInputGesture(resumedSurface))
            assertTrue(f.controller.executeInputCommand("xdotool mousemove_relative -- 1 2", resumedGesture))
            f.await { backend.writes.contains("xdotool mousemove_relative -- 1 2\r\n") }
            assertTrue(f.controller.current.connection.isReady)
            assertEquals("fresh post-resume input stays on the same SSH session", 1,
                backend.events.count { it.name == "session.connect" })
        }
    }

    @Test fun cancelledInputIsRecheckedAtProviderWriteAfterTransportDispatchWithoutLosingSession() {
        val backend = RecordingSshBackend()
        Fixture(commandBackend = backend).use { f ->
            val host = SessionFixtures.host("synthetic.invalid")
            f.connect(host)
            f.await { f.controller.current.connection.isReady }
            val originalGeneration = f.controller.current.generation
            val surface = requireNotNull(f.controller.activateControlSurface(f.presentation))
            val gesture = requireNotNull(f.controller.beginInputGesture(surface))

            // SshTransport has dequeued the input and submitted its operation, but healthy(scope)
            // is stopped at the provider boundary immediately before the guarded write call.
            backend.blockAt = "session.isConnected"
            backend.ignoreInterrupts = true
            assertTrue(f.controller.executeInputCommand("xdotool mousemove_relative -- 31 32", gesture))
            f.queue.drain()
            assertTrue("transport did not reach the provider-write gate", backend.entered.await(5, TimeUnit.SECONDS))

            f.controller.cancelInputGesture(gesture)
            f.controller.deactivateControlSurface(surface)
            f.queue.drain()
            val providerGate = backend.release
            backend.blockAt = null
            providerGate.countDown()

            f.await { backend.events.any { it.name == "shell.isClosed" } }
            assertTrue("the write-boundary revocation must not report session loss", f.controller.current.connection.isReady)
            assertEquals(originalGeneration, f.controller.current.generation)
            assertTrue("revoked input crossed the provider write boundary", backend.writes.isEmpty())
            assertFalse("revocation was misclassified as a failed provider write",
                backend.events.any { it.name == "session.close" })

            val freshSurface = requireNotNull(f.controller.activateControlSurface(f.presentation))
            val freshGesture = requireNotNull(f.controller.beginInputGesture(freshSurface))
            assertTrue(f.controller.executeInputCommand("xdotool mousemove_relative -- 3 4", freshGesture))
            f.await { backend.writes.contains("xdotool mousemove_relative -- 3 4\r\n") }
            assertTrue(f.controller.current.connection.isReady)
            assertEquals(1, backend.events.count { it.name == "session.connect" })
        }
    }

    @Test fun finishedGestureKeepsAdmittedCommandsValidUntilTheProviderWriteBoundary() {
        val backend = RecordingSshBackend()
        Fixture(commandBackend = backend, healthIntervalMillis = 60_000).use { f ->
            val host = SessionFixtures.host("synthetic.invalid")
            f.connect(host)
            f.await { f.controller.current.connection.isReady }
            val originalGeneration = f.controller.current.generation
            val surface = requireNotNull(f.controller.activateControlSurface(f.presentation))
            val gesture = requireNotNull(f.controller.beginInputGesture(surface))

            // Hold the already-dispatched transport operation immediately before its write guard.
            backend.blockAt = "shell.isClosed"
            backend.ignoreInterrupts = true
            assertTrue(f.controller.executeInputCommand("xdotool mousemove_relative -- 5 6", gesture))
            f.queue.drain()
            assertTrue("transport did not reach the provider-write gate", backend.entered.await(5, TimeUnit.SECONDS))

            f.controller.finishInputGesture(gesture)
            f.queue.drain()
            assertFalse("normal UP must end local gesture admission", f.controller.isInputGestureActive(gesture))

            val providerGate = backend.release
            backend.blockAt = null
            providerGate.countDown()
            f.await { backend.writes.contains("xdotool mousemove_relative -- 5 6\r\n") }

            assertEquals("a command admitted before normal UP must reach the provider in FIFO order",
                listOf("xdotool mousemove_relative -- 5 6\r\n"), backend.writes.toList())
            assertTrue(f.controller.current.connection.isReady)
            assertEquals(originalGeneration, f.controller.current.generation)
        }
    }

    @Test fun hostChangeWhileActualTrustWaitsCancelsExactOldRequestAndLateAcceptCannotAuthenticateIt() {
        Server().use { oldServer -> Server().use { nextServer -> Fixture().use { f ->
            f.connect(oldServer.profile()); val oldPrompt = f.prompt()
            f.connect(nextServer.profile(2)); val nextPrompt = f.prompt()
            oldPrompt.accept(); oldPrompt.reject(); oldPrompt.cancel()
            assertFalse(oldPrompt.isPending)
            assertEquals(0, oldServer.authentications.get()); assertEquals(0, oldServer.commands.get())
            assertEquals(0, nextServer.authentications.get())
            nextPrompt.accept()
            f.await { f.controller.current.connection.isReady }
            assertEquals(nextServer.profile(2).endpoint.identity, f.controller.current.connection.endpoint)
            assertEquals(0, oldServer.authentications.get()); assertEquals(1, nextServer.authentications.get())
        } } }
    }

    @Test fun observerDetachDuringActualTrustCancelsWithoutAutotrustAndExplicitDisconnectSuppressesLateAutoReturn() {
        Server().use { server -> Fixture().use { f ->
            val host = server.profile(); val permit = f.controller.automaticConnectPermit()
            f.connect(host); val prompt = f.prompt()
            f.interactions.detach()
            f.await { f.controller.current.connection.phase == ConnectionState.Phase.ERROR }
            prompt.accept(); assertEquals(0, server.authentications.get()); assertEquals(0, server.commands.get())
            f.controller.disconnect(); f.queue.drain()
            f.controller.connectAutomatically(host, permit); f.queue.drain()
            assertNull(f.controller.current.desiredHost); assertEquals(1, f.reads.get())
        } }
    }

    @Test fun actualFactoryControllerPublishesChangedHostReviewOnlyAfterConfirmedCloseAndReplacementDoesNotResumeHandshake() {
        val oldKey = Server.newKeyPair()
        val replacementKey = Server.newKeyPair()
        val closeGate = SessionCloseGate()
        val fixture = Fixture(closeGate)
        val original = Server(hostKeyPair = oldKey)
        val port = original.ssh.port
        try {
            val host = original.profile()
            val oldTrustPrompt = run {
                fixture.connect(host)
                fixture.prompt()
            }
            oldTrustPrompt.accept()
            fixture.await { fixture.controller.current.connection.isReady }
            assertTrue(original.input.await(5, TimeUnit.SECONDS))
            val endpoint = host.endpoint
            val expectedOldKey = approvedKey(oldKey)
            assertArrayEquals(expectedOldKey.bytes(), fixture.persistedKey(endpoint).bytes())

            fixture.controller.disconnect()
            fixture.await { fixture.controller.current.attemptHost == null &&
                fixture.controller.current.connection.phase == ConnectionState.Phase.DISCONNECTED }
            original.close() // The same logical endpoint/port is now served with a different SSH key.

            Server(port, replacementKey).use { changedServer ->
                closeGate.arm()
                fixture.connect(changedServer.profile())
                assertTrue("real JSch mismatch did not enter the physical-close gate",
                    closeGate.entered.await(10, TimeUnit.SECONDS))
                fixture.await { fixture.controller.current.connection.phase == ConnectionState.Phase.ERROR }
                assertFalse("replacement review was published before physical close was confirmed",
                    fixture.prompts.any { it is SessionInteractions.Prompt.Mismatch })

                closeGate.release.countDown()
                assertTrue("the real JSch session close did not complete", closeGate.physicalCloseCompleted.await(5, TimeUnit.SECONDS))
                val review = fixture.mismatchPrompt()
                val mismatch = review.mismatch
                val expectedReplacementKey = approvedKey(replacementKey)
                assertEquals(endpoint.identity, mismatch.endpoint.identity)
                assertArrayEquals("review must carry the entire previously approved key",
                    expectedOldKey.bytes(), mismatch.trustedKey.bytes())
                assertArrayEquals("review must carry the entire newly presented server key",
                    expectedReplacementKey.bytes(), mismatch.presentedKey.bytes())
                assertEquals(expectedOldKey.fingerprint, mismatch.trustedKey.fingerprint)
                assertEquals(expectedReplacementKey.fingerprint, mismatch.presentedKey.fingerprint)
                assertEquals(0, changedServer.authentications.get())
                assertEquals(0, changedServer.commands.get())

                assertTrue("the explicit replacement confirmation must be accepted", mismatch.confirm())
                review.replace()
                fixture.await { fixture.persistedKey(endpoint).matches(expectedReplacementKey) &&
                    fixture.prompts.none { it === review } }
                assertEquals("the explicit compare-and-swap replacement must report success", true,
                    fixture.replacementResults.poll(5, TimeUnit.SECONDS))
                assertArrayEquals(expectedReplacementKey.bytes(), fixture.persistedKey(endpoint).bytes())
                assertEquals("replacement consent must not resume the blocked handshake", 0,
                    changedServer.authentications.get())
                assertEquals(0, changedServer.commands.get())

                fixture.connect(host)
                fixture.await { fixture.controller.current.connection.isReady }
                assertTrue(changedServer.input.await(5, TimeUnit.SECONDS))
                assertEquals("a fresh Connect should accept the explicitly persisted replacement", 1,
                    changedServer.authentications.get())
                assertEquals(1, changedServer.commands.get())
                fixture.controller.disconnect()
                fixture.await { fixture.controller.current.attemptHost == null &&
                    fixture.controller.current.connection.phase == ConnectionState.Phase.DISCONNECTED }
            }

            Server(port, oldKey).use { oldKeyReturns ->
                fixture.connect(host)
                fixture.await { fixture.controller.current.connection.phase == ConnectionState.Phase.ERROR }
                val oldKeyReview = fixture.mismatchPrompt()
                assertEquals(endpoint.identity, oldKeyReview.mismatch.endpoint.identity)
                assertArrayEquals(approvedKey(replacementKey).bytes(), oldKeyReview.mismatch.trustedKey.bytes())
                assertArrayEquals(approvedKey(oldKey).bytes(), oldKeyReview.mismatch.presentedKey.bytes())
                assertEquals(0, oldKeyReturns.authentications.get())
                assertEquals(0, oldKeyReturns.commands.get())
                fixture.controller.disconnect()
                fixture.await { fixture.controller.current.attemptHost == null }
            }
        } finally {
            closeGate.release.countDown()
            original.close()
            fixture.close()
        }
    }

    @Test fun actualFactorySuppressesChangedKeyReviewAfterCancellationDetachDisconnectStalenessOrUnconfirmedClose() {
        for (invalidator in ReviewInvalidator.values()) {
            val oldKey = Server.newKeyPair()
            val replacementKey = Server.newKeyPair()
            val closeGate = SessionCloseGate()
            val fixture = Fixture(closeGate)
            val original = Server(hostKeyPair = oldKey)
            val port = original.ssh.port
            try {
                val host = original.profile()
                fixture.connect(host)
                fixture.prompt().accept()
                fixture.await { fixture.controller.current.connection.isReady }
                fixture.controller.disconnect()
                fixture.await { fixture.controller.current.attemptHost == null &&
                    fixture.controller.current.connection.phase == ConnectionState.Phase.DISCONNECTED }
                original.close()

                Server(port, replacementKey).use { changedServer ->
                    closeGate.arm(fail = invalidator == ReviewInvalidator.UNCERTAIN_CLOSE)
                    fixture.connect(changedServer.profile())
                    assertTrue("real JSch mismatch did not enter the close gate for $invalidator",
                        closeGate.entered.await(10, TimeUnit.SECONDS))
                    // The transport has reported the captured mismatch and the controller is now
                    // waiting for cancelAndClose; invalidate its review before releasing close.
                    fixture.await { fixture.controller.current.connection.phase == ConnectionState.Phase.ERROR }
                    val stalePort = if (port == 65535) port - 1 else port + 1
                    when (invalidator) {
                        ReviewInvalidator.CANCEL -> fixture.interactions.invalidate()
                        ReviewInvalidator.DETACH -> fixture.interactions.detach()
                        ReviewInvalidator.DISCONNECT -> fixture.controller.disconnect()
                        ReviewInvalidator.STALE -> {
                            fixture.connect(SessionFixtures.host("127.0.0.1", 2, port = stalePort))
                        }
                        ReviewInvalidator.UNCERTAIN_CLOSE -> Unit
                    }
                    closeGate.release.countDown()
                    assertTrue(closeGate.physicalCloseCompleted.await(5, TimeUnit.SECONDS))

                    when (invalidator) {
                        ReviewInvalidator.DISCONNECT -> fixture.await {
                            fixture.controller.current.desiredHost == null && fixture.controller.current.attemptHost == null &&
                                fixture.controller.current.connection.phase == ConnectionState.Phase.DISCONNECTED
                        }
                        ReviewInvalidator.STALE -> fixture.await {
                            fixture.controller.current.desiredHost?.endpoint?.port == stalePort &&
                                fixture.controller.current.attemptHost == null &&
                                fixture.controller.current.connection.phase == ConnectionState.Phase.ERROR
                        }
                        ReviewInvalidator.UNCERTAIN_CLOSE -> fixture.await { fixture.controller.current.cleanupUnconfirmed }
                        else -> fixture.await { fixture.controller.current.attemptHost == null }
                    }
                    if (invalidator == ReviewInvalidator.DETACH) fixture.interactions.attach(fixture)
                    Thread.sleep(100)
                    assertFalse("$invalidator must not publish a changed-key replacement review",
                        fixture.prompts.any { it is SessionInteractions.Prompt.Mismatch })
                    assertEquals(0, changedServer.authentications.get())
                    assertEquals(0, changedServer.commands.get())
                }
            } finally {
                closeGate.release.countDown()
                original.close()
                fixture.close()
            }
        }
    }
}
