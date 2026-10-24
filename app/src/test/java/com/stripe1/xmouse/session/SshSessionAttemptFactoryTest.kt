package com.stripe1.xmouse.session

import com.stripe1.xmouse.connection.*
import com.stripe1.xmouse.secrets.*
import com.stripe1.xmouse.transport.*
import com.stripe1.xmouse.trust.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.*
import java.util.function.BooleanSupplier

class SshSessionAttemptFactoryTest {
    private val a = SessionFixtures.host()
    private val b = SessionFixtures.host("other.invalid", 2)
    private fun eventually(queue: SessionFixtures.Queue, assertion: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) { queue.drain(); if (assertion()) return; Thread.sleep(5) }
        queue.drain(); assertTrue(assertion())
    }
    private class Fixture(val block: String? = null, val fail: String? = null, closeMillis: Long = 1500,
                          credentialMillis: Long = 2000) : AutoCloseable {
        val directory = Files.createTempDirectory("xmouse-session-pipeline-").toFile()
        val queue = SessionFixtures.Queue()
        val credentials = SessionFixtures.Credentials()
        val backends = CopyOnWriteArrayList<RecordingSshBackend>()
        val identities = CopyOnWriteArrayList<Pair<EndpointIdentity, CredentialHost>>()
        val requests = CopyOnWriteArrayList<SshConnectionRequest>()
        val diagnosticEvents = CopyOnWriteArrayList<SshDiagnosticEvent>()
        private val diagnostics = SshDiagnostics { diagnosticEvents.add(it) }
        val runtime = SshRuntime()
        val limits = SshLimits(800, 800, 800, 1200, 800, 500, 500, 500, 400, 300, 3, 50, 200, 4096)
        val interactions = SessionInteractions(Executor { it.run() })
        @Volatile var permissionDenied = false
        val factory = SshSessionAttemptFactory(credentials, HostKeyRepository(directory, TrustFileIo()), interactions,
            limits, runtime, credentialMillis, closeMillis, { _, trusted ->
                val backend = RecordingSshBackend().apply {
                    if (backends.isEmpty()) { blockAt = block; failAt = fail; ignoreInterrupts = true }
                }
                backends.add(backend)
                object : SshBackend by backend {
                    override fun resolve(request: SshConnectionRequest): SshConnectionRequest {
                        requests.add(request)
                        backend.record("resolve")
                        return request
                    }
                    override fun create(request: SshConnectionRequest, authentication: AuthenticationMaterial): SshBackend.Session {
                        identities.add(request.endpoint.identity to authentication.host)
                        val created = backend.create(request, authentication)
                        if (fail != "auth_rejected") return created
                        return object : SshBackend.Session by created {
                            override fun connect(timeoutMillis: Int) {
                                backend.record("session.connect")
                                trusted.run() // Preserve the production trust-before-auth ordering.
                                throw SshBackend.AuthenticationRejectedException()
                            }
                        }
                    }
                    override fun create(request: SshConnectionRequest, authentication: AuthenticationMaterial, current: BooleanSupplier) =
                        create(request, authentication)
                }
            }, permissionFailure = {
                if (permissionDenied) ConnectionFailure.Permission(ConnectionFailure.PermissionReason.DENIED) else null
            }, diagnostics = diagnostics)
        val controller = SessionController(factory, queue)
        fun connect(host: SessionHost) { controller.connectIfNeeded(host); queue.drain() }
        override fun close() {
            backends.forEach { it.release.countDown() }
            controller.close(); queue.drain()
            credentials.reads.forEach { it.callback.complete(null, null) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!queue.closed && System.nanoTime() < deadline) { queue.drain(); Thread.sleep(5) }
            check(queue.closed)
            runtime.close(); directory.deleteRecursively()
        }
    }

    @Test fun realTransportRepeatedRequestsHaveOneCredentialReadOneCreationOneReadyAndOffCallerIo() {
        Fixture().use { f ->
            f.connect(a)
            repeat(20) { f.connect(a) }; assertEquals(1, f.credentials.reads.size)
            val material = f.credentials.succeed(0, a)
            eventually(f.queue) { f.controller.current.connection.isReady }
            repeat(20) { f.connect(a) }
            assertEquals(1, f.credentials.reads.size)
            assertEquals(1, f.identities.size)
            assertEquals(a.endpoint.identity to a.authentication.host, f.identities.single())
            assertTrue(material.password.all { it == 0.toByte() })
            assertTrue(f.backends.single().events.all { it.thread !== Thread.currentThread() })
            assertTrue(f.backends.single().events.all { it.thread.name in listOf("xmouse-ssh-io", "xmouse-ssh-dns") })
            val duplicate = SessionFixtures.material(a)
            f.credentials.reads.single().callback.complete(duplicate, null)
            f.credentials.fail(0); f.queue.drain()
            assertTrue(duplicate.password.all { it == 0.toByte() })
            assertTrue(f.controller.current.connection.isReady); assertEquals(1, f.identities.size)
        }
    }

    @Test fun hostChangeDuringCredentialsClosesAndWipesLateOldSuccessBeforeReadingTheNextSnapshot() {
        for (success in listOf(false, true)) Fixture().use { f ->
            f.connect(a); f.connect(b)
            assertFalse(f.credentials.reads[0].current.asBoolean)
            assertEquals(1, f.credentials.reads.size); assertTrue(f.identities.isEmpty())
            val late = if (success) f.credentials.succeed(0, a) else { f.credentials.fail(0); null }
            eventually(f.queue) { f.credentials.reads.size == 2 }
            assertTrue(late == null || late.password.all { it == 0.toByte() })
            f.credentials.succeed(1, b)
            eventually(f.queue) { f.controller.current.connection.isReady }
            assertEquals(listOf(b.endpoint.identity to b.authentication.host), f.identities.toList())
            f.credentials.succeed(0, a); f.credentials.fail(0); f.queue.drain()
            assertEquals(ConnectionState.Connected(b.endpoint.identity), f.controller.current.connection)
        }
    }

    @Test fun hostChangesAtResolutionCreateHandshakeChannelAndReadyNeverOverlapOrUseAnotherProfilesMaterial() {
        for (stage in listOf("resolve", "create", "session.connect", "session.open", "shell.connect", null)) Fixture(stage).use { f ->
            f.connect(a); val oldMaterial = f.credentials.succeed(0, a)
            val old = f.backends.single()
            if (stage == null) eventually(f.queue) { f.controller.current.connection.isReady }
            else assertTrue("did not reach $stage", old.entered.await(3, TimeUnit.SECONDS))
            f.connect(b)
            assertEquals(1, f.credentials.reads.size)
            assertFalse(f.controller.current.connection.isReady)
            old.release.countDown()
            eventually(f.queue) { f.credentials.reads.size == 2 }
            assertTrue(oldMaterial.password.all { it == 0.toByte() })
            if (stage != "resolve") assertEquals(1, old.events.count { it.name == "session.close" })
            f.credentials.succeed(1, b)
            eventually(f.queue) { f.controller.current.connection.isReady }
            assertEquals(b.endpoint.identity to b.authentication.host, f.identities.last())
            assertEquals(1, f.backends.last().events.count { it.name == "create" })
            assertFalse(f.controller.current.cleanupUnconfirmed)
        }
    }

    @Test fun failingOrNoninterruptibleCleanupBlocksNewHostAndRetainsHonestOldOwnership() {
        for (stalled in listOf(false, true)) Fixture(if (stalled) "shell.input.close" else null,
            if (stalled) null else "session.close").use { f ->
            f.connect(a); f.credentials.succeed(0, a)
            eventually(f.queue) { f.controller.current.connection.isReady }
            f.connect(b)
            eventually(f.queue) { f.controller.current.cleanupUnconfirmed }
            assertEquals(1, f.credentials.reads.size); assertEquals(1, f.identities.size)
            assertEquals(a, f.controller.current.attemptHost); assertEquals(b, f.controller.current.desiredHost)
            assertFalse(f.controller.current.connection.isReady)
            f.backends.single().release.countDown()
            f.connect(b); assertEquals(1, f.credentials.reads.size)
        }
    }

    @Test fun timedOutCredentialCancellationStillWipesLateBytesAndCannotUndoDisconnectOrStartAnotherHost() {
        Fixture(closeMillis = 80).use { f ->
            f.connect(a); val permit = f.controller.automaticConnectPermit()
            f.controller.disconnect(); f.queue.drain()
            eventually(f.queue) { f.controller.current.cleanupUnconfirmed }
            val late = f.credentials.succeed(0, a)
            assertTrue(late.password.all { it == 0.toByte() })
            f.controller.connectAutomatically(a, permit); f.queue.drain()
            f.connect(b)
            assertEquals(1, f.credentials.reads.size); assertTrue(f.identities.isEmpty())
        }
    }

    @Test fun credentialPreparationHasAnActualFiniteDeadlineAndLateSuccessCannotReviveIt() {
        Fixture(closeMillis = 250, credentialMillis = 60).use { f ->
            f.connect(a)
            eventually(f.queue) { f.controller.current.connection.failure ==
                ConnectionFailure.Authentication(ConnectionFailure.AuthenticationReason.TIMED_OUT) }
            assertFalse(f.credentials.reads.single().current.asBoolean)
            assertEquals(a, f.controller.current.desiredHost)
            eventually(f.queue) { f.controller.current.cleanupUnconfirmed }
            val late = f.credentials.succeed(0, a)
            assertTrue(late.password.all { it == 0.toByte() }); assertTrue(f.identities.isEmpty())
        }
    }

    @Test fun wrongHostMaterialAndPermissionLossFailHonestlyAndForegroundRecoveryRequiresAccess() {
        Fixture().use { f ->
            f.permissionDenied = true
            f.connect(a)
            eventually(f.queue) { f.controller.current.attemptHost == null }
            assertTrue(f.credentials.reads.isEmpty())
            assertTrue(f.controller.current.connection.failure is ConnectionFailure.Permission)
            f.permissionDenied = false
            f.connect(a); val wrong = f.credentials.succeed(0, b)
            eventually(f.queue) { f.controller.current.connection.phase == ConnectionState.Phase.ERROR }
            assertTrue(f.identities.isEmpty()); assertTrue(wrong.password.all { it == 0.toByte() })
            eventually(f.queue) { f.controller.current.attemptHost == null }
            f.connect(b); f.credentials.succeed(1, b)
            eventually(f.queue) { f.controller.current.connection.isReady }
            f.permissionDenied = true
            eventually(f.queue) { f.controller.current.attemptHost == null &&
                f.controller.current.connection.failure == ConnectionFailure.Permission(ConnectionFailure.PermissionReason.REVOKED) }
            assertFalse(f.controller.current.connection.isReady)
            assertEquals(b, f.controller.current.desiredHost); assertEquals(2, f.credentials.reads.size)
            val attemptsBeforeRecovery = f.backends.size
            val permit = requireNotNull(f.controller.foregroundRecoveryPermit())
            f.controller.recoverInForeground(permit, { true }); f.queue.drain()
            assertEquals("missing permission cannot even read credentials or resolve", 2, f.credentials.reads.size)
            assertEquals(attemptsBeforeRecovery, f.backends.size)
            f.permissionDenied = false
            f.controller.recoverInForeground(permit, { true }); f.queue.drain()
            assertEquals(3, f.credentials.reads.size)
            f.credentials.succeed(2, b)
            eventually(f.queue) { f.controller.current.connection.isReady }
            assertEquals(2, f.backends.sumOf { backend -> backend.events.count { it.name == "resolve" } })
            assertEquals(2, f.identities.size)
        }
    }

    @Test fun typedAuthenticationRejectionMapsToRejectedWithoutRetryAndDiagnosticsStayAllowlisted() {
        Fixture(fail = "auth_rejected").use { f ->
            f.connect(a)
            f.credentials.succeed(0, a)
            val rejected = ConnectionFailure.Authentication(ConnectionFailure.AuthenticationReason.REJECTED)
            eventually(f.queue) { f.controller.current.connection.failure == rejected }

            assertEquals(rejected, f.controller.current.connection.failure)
            assertEquals("authentication rejection is terminal for this attempt", 1, f.credentials.reads.size)
            assertEquals(1, f.backends.size)
            assertFalse(f.backends.single().events.any { it.name == "session.open" || it.name == "shell.connect" })
            assertTrue(f.diagnosticEvents.any { it.stage == SshDiagnosticStage.HOST_TRUSTED })
            assertTrue(f.diagnosticEvents.any {
                it.stage == SshDiagnosticStage.AUTH_METHOD_CONFIGURED && it.authMethod == SshAuthenticationMethod.PASSWORD
            })
            assertTrue(f.diagnosticEvents.any {
                it.stage == SshDiagnosticStage.AUTH_METHOD_CONFIGURED &&
                    it.authMethod == SshAuthenticationMethod.KEYBOARD_INTERACTIVE
            })
            val failureEvent = f.diagnosticEvents.single { it.failure != null }
            assertEquals(SshDiagnosticStage.AUTHENTICATION_REJECTED, failureEvent.stage)
            assertEquals(rejected, failureEvent.failure)
            val safeOutput = f.diagnosticEvents.joinToString("\n") { it.toSafeLogMessage() + " " + it }
            assertFalse(safeOutput.contains("NOT_REAL_provider_password_key_typed_content"))
            assertFalse(safeOutput.contains("NOT_REAL_user"))
            assertFalse(safeOutput.contains("synthetic.invalid"))
        }
    }

    @Test fun transportRefusalAndUnreachableMapToDistinctTypedFailures() {
        assertEquals(ConnectionFailure.Transport(ConnectionFailure.TransportReason.CONNECTION_REFUSED),
            SshTransport.Failure.CONNECTION_REFUSED.toConnectionFailure())
        assertEquals(ConnectionFailure.Transport(ConnectionFailure.TransportReason.UNREACHABLE),
            SshTransport.Failure.UNREACHABLE.toConnectionFailure())
        assertEquals(ConnectionFailure.Authentication(ConnectionFailure.AuthenticationReason.REJECTED),
            SshTransport.Failure.AUTHENTICATION.toConnectionFailure())
    }

    @Test fun serviceOnlyProfileKeepsItsLocatorAndUsesItsExistingUserAuthentication() {
        Fixture().use { f ->
            val locator = EndpointLocator.DiscoveredService("Office SSH", "_ssh._tcp", "local")
            val endpoint = Endpoint(locator, 2222)
            val baseHost = a.authentication.host.copy(alias = "Office SSH", host = null, port = "2222")
            val selected = SessionHost(endpoint, a.authentication.copy(host = baseHost), a.shell, a.initialCommand)

            f.connect(selected)
            val material = f.credentials.succeed(0, selected)
            eventually(f.queue) { f.controller.current.connection.isReady }

            assertEquals(endpoint.identity, f.requests.single().endpoint.identity)
            assertEquals("service instance is not replaced with a fabricated hostname", locator.instanceName,
                f.requests.single().address)
            assertNull((f.requests.single().endpoint.locator as EndpointLocator.DiscoveredService).stableTargetHostname)
            assertEquals(endpoint.identity to baseHost, f.identities.single())
            assertEquals("NOT_REAL_user", baseHost.user)
            assertTrue(material.password.all { it == 0.toByte() })
        }
    }

    @Test fun missingUiCancelsCredentialReentryAndDetachedOrStalePromptsNeverApproveOrReplaceNewRequests() {
        val ui = SessionFixtures.Queue()
        val interactions = SessionInteractions(Executor { ui.post(it) })
        val shown = mutableListOf<SessionInteractions.Prompt>()
        val listener = object : SessionInteractions.Listener {
            override fun show(prompt: SessionInteractions.Prompt) { shown.add(prompt) }
            override fun dismiss(prompt: SessionInteractions.Prompt) { }
        }
        fun request() = CredentialReentryRequest(SecretId("synthetic_reentry")) { result ->
            if (result is CredentialReadResult.Available) result.plaintext.fill(0)
        }
        val missing = request()
        interactions.show(Any(), SessionInteractions.Prompt.Credential(CredentialKind.PASSWORD, missing), BooleanSupplier { true })
        ui.drain(); assertFalse(missing.isPending)
        interactions.attach(listener)
        val oldOwner = Any(); val old = request()
        interactions.show(oldOwner, SessionInteractions.Prompt.Credential(CredentialKind.PASSWORD, old), BooleanSupplier { true })
        interactions.detach(); interactions.attach(listener)
        val fresh = request(); val freshOwner = Any()
        interactions.show(freshOwner, SessionInteractions.Prompt.Credential(CredentialKind.PASSWORD, fresh), BooleanSupplier { true })
        val stale = request()
        interactions.show(oldOwner, SessionInteractions.Prompt.Credential(CredentialKind.PASSWORD, stale), BooleanSupplier { false })
        interactions.clear(oldOwner); ui.drain()
        assertFalse(old.isPending); assertFalse(stale.isPending); assertTrue(fresh.isPending)
        assertEquals(1, shown.size)
        assertSame(fresh, (shown.single() as SessionInteractions.Prompt.Credential).request)
        assertFalse(old.useForThisAttempt("NOT_REAL".toByteArray()))
        interactions.detach(); assertFalse(fresh.isPending)
    }
}
