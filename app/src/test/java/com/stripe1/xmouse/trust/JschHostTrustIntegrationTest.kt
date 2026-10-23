package com.stripe1.xmouse.trust

import android.net.ConnectivityManager
import android.net.Network
import com.stripe1.xmouse.connection.Endpoint
import com.stripe1.xmouse.connection.EndpointHost
import com.stripe1.xmouse.connection.EndpointLocator
import com.stripe1.xmouse.permissions.LocalNetworkPermissionCoordinator
import com.stripe1.xmouse.transport.*
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.channel.Channel
import org.apache.sshd.common.channel.ChannelListener
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.Service
import org.apache.sshd.common.SshConstants
import org.apache.sshd.common.session.Session
import org.apache.sshd.common.util.buffer.Buffer
import org.apache.sshd.server.session.ServerUserAuthService
import org.apache.sshd.server.session.ServerUserAuthServiceFactory
import org.apache.sshd.server.session.ServerConnectionServiceFactory
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.*
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BooleanSupplier

/** Actual maintained JSch against an in-process synthetic server, never sshd/user accounts/real hosts. */
class JschHostTrustIntegrationTest {
    private class Server(address: String = "127.0.0.1", port: Int = 0, val key: KeyPair = newKey(),
                         private val acceptPassword: Boolean = true) : AutoCloseable {
        val credentialAuth = AtomicInteger()
        val authenticationRequests = AtomicInteger()
        val channelRequests = AtomicInteger()
        val commandRequests = AtomicInteger()
        val inputReceived = CountDownLatch(1)
        var onCredential: () -> Unit = {}
        val ssh = SshServer.setUpDefaultServer().apply {
            host = address; this.port = port
            keyPairProvider = KeyPairProvider.wrap(key)
            serviceFactories = listOf(object : ServerUserAuthServiceFactory() {
                override fun create(session: Session): Service = object : ServerUserAuthService(session) {
                    override fun process(cmd: Int, buffer: Buffer) {
                        if (cmd == SshConstants.SSH_MSG_USERAUTH_REQUEST.toInt()) authenticationRequests.incrementAndGet()
                        super.process(cmd, buffer)
                    }
                }
            }, ServerConnectionServiceFactory.INSTANCE)
            setPasswordAuthenticator { user, password, _ ->
                credentialAuth.incrementAndGet(); onCredential()
                acceptPassword && user == "NOT_REAL_user" && password == "NOT_REAL_password"
            }
            setPublickeyAuthenticator { _, _, _ -> credentialAuth.incrementAndGet(); false }
            addChannelListener(object : ChannelListener {
                override fun channelInitialized(channel: Channel) { channelRequests.incrementAndGet() }
            })
            setShellFactory { command() }
            setCommandFactory { _, _ -> command() }
            start()
        }
        private fun command(): Command {
            commandRequests.incrementAndGet()
            return object : Command {
                private var input: InputStream? = null
                override fun setInputStream(input: InputStream) { this.input = input }
                override fun setOutputStream(output: OutputStream) { }
                override fun setErrorStream(error: OutputStream) { }
                override fun setExitCallback(callback: ExitCallback) { }
                override fun start(channel: ChannelSession, env: Environment) {
                    Thread({ try { if (input?.read() != -1) inputReceived.countDown() } catch (_: IOException) { } }, "synthetic-ssh-input").apply { isDaemon = true; start() }
                }
                override fun destroy(channel: ChannelSession) { input?.close() }
            }
        }
        override fun close() { ssh.stop(true) }
        companion object {
            fun newKey(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        }
    }
    private class Result : SshTransport.Callback<Void> {
        val done = CountDownLatch(1)
        var failure: SshTransport.Failure? = null
        var mismatch: HostKeyMismatch? = null
        override fun success(value: Void?) { done.countDown() }
        override fun failure(failure: SshTransport.Failure) { this.failure = failure; done.countDown() }
        override fun mismatch(mismatch: HostKeyMismatch) { this.mismatch = mismatch }
        fun await() { assertTrue(done.await(10, TimeUnit.SECONDS)) }
    }
    private class Fixture(timeout: Long = 5000, io: TrustFileIo = TrustFileIo(),
                          resolver: SshAddressResolver = SshAddressResolver.system()) : AutoCloseable, HostTrustRequests.Listener {
        val directory = Files.createTempDirectory("xmouse-actual-jsch-trust-").toFile()
        val repo = HostKeyRepository(directory, io)
        val prompts = LinkedBlockingQueue<HostTrustRequests.Request>()
        val requests = HostTrustRequests(repo, Executor { it.run() }, timeout).also { it.attach(this) }
        val worker = SshTransport.newWorker()
        val transport = SshTransport(JschSshBackend(HostTrustPolicy(requests), resolver), worker)
        override fun show(request: HostTrustRequests.Request) { prompts.add(request) }
        override fun dismiss(request: HostTrustRequests.Request) { }
        fun prompt() = requireNotNull(prompts.poll(10, TimeUnit.SECONDS))
        fun connect(
            server: Server,
            address: String = "127.0.0.1",
            keyAuth: Boolean = false,
            endpoint: Endpoint = Endpoint.manual("DESKTOP.local.", server.ssh.port),
        ): Result {
            val key = if (keyAuth) requireNotNull(javaClass.classLoader!!.getResourceAsStream("ssh-crypto/ecdsa-p256.pem")).use { it.readBytes() } else byteArrayOf()
            val authentication = RecordingSshBackend.authentication(keyAuth, key)
            return Result().also { transport.connect(SshConnectionRequest(endpoint, address, "", "synthetic initial command"), authentication, it) }
        }
        fun replace(mismatch: HostKeyMismatch): Result = Result().also { transport.replaceHostKey(mismatch, it) }
        override fun close() {
            requests.detach(); transport.close(); assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS)); directory.deleteRecursively()
        }
    }
    private fun noAuthenticationOrCommands(server: Server) {
        assertEquals(0, server.authenticationRequests.get())
        assertEquals(0, server.credentialAuth.get()); assertEquals(0, server.channelRequests.get()); assertEquals(0, server.commandRequests.get())
    }

    private fun approvedKey(key: KeyPair): ApprovedHostKey {
        val encoded = PublicKeyEntry.toString(key.public).substringAfter(' ')
        return ApprovedHostKey.parse(java.util.Base64.getDecoder().decode(encoded))
    }

    private fun resolver(addresses: AtomicReference<List<InetAddress>>, lookups: AtomicInteger) =
        SshAddressResolver { _, current ->
            if (!current.asBoolean) throw java.util.concurrent.CancellationException()
            lookups.incrementAndGet()
            val snapshot = addresses.get()
            object : SshAddressResolver.Resolution {
                override fun candidates(): List<InetAddress> = snapshot
            }
        }

    private fun directServiceCandidates(): SshAddressResolver = object : SshAddressResolver {
        override fun resolve(host: String, current: BooleanSupplier): SshAddressResolver.Resolution =
            SshAddressResolver.system().resolve(host, current)

        override fun resolve(
            endpoint: Endpoint,
            host: String,
            current: BooleanSupplier,
        ): SshAddressResolver.Resolution {
            if (endpoint.locator is EndpointLocator.DiscoveredService &&
                endpoint.locator.stableTargetHostname == null
            ) {
                if (!current.asBoolean) throw java.util.concurrent.CancellationException()
                return object : SshAddressResolver.Resolution {
                    override fun candidates(): List<InetAddress> = listOf(InetAddress.getByName(host))
                }
            }
            return resolve(host, current)
        }
    }

    @Test fun actualJschWaitsForDurableExplicitApprovalBeforePasswordAuthenticationAndInitialCommand() {
        Server().use { server -> Fixture().use { f ->
            val durableBeforeAuthentication = CountDownLatch(1)
            server.onCredential = {
                if (HostKeyRepository(f.directory, TrustFileIo()).find(Endpoint.manual("desktop.local", server.ssh.port).identity) != null) durableBeforeAuthentication.countDown()
            }
            val result = f.connect(server); val prompt = f.prompt()
            noAuthenticationOrCommands(server); assertFalse(f.transport.isConnected)
            assertEquals("ecdsa-sha2-nistp256", prompt.key.algorithm)
            assertNull(f.repo.find(prompt.endpoint.identity))
            prompt.accept(); result.await(); assertNull(result.failure)
            assertTrue(server.inputReceived.await(5, TimeUnit.SECONDS))
            assertEquals(0L, durableBeforeAuthentication.count)
            assertTrue(HostKeyRepository(f.directory, TrustFileIo()).find(prompt.endpoint.identity)!!.matches(prompt.key))
            assertEquals(1, server.credentialAuth.get()); assertEquals(1, server.channelRequests.get()); assertEquals(1, server.commandRequests.get())
        } }
    }
    @Test fun actualJschRejectAndCancelSendNeitherPasswordNorPublicKeyAuthenticationOrChannels() {
        for (keyAuth in listOf(false, true)) for (reject in listOf(false, true)) {
            Server().use { server -> Fixture().use { f ->
                val result = f.connect(server, keyAuth = keyAuth); val prompt = f.prompt()
                noAuthenticationOrCommands(server)
                if (reject) prompt.reject() else prompt.cancel()
                result.await(); assertEquals(SshTransport.Failure.HOST_TRUST, result.failure)
                noAuthenticationOrCommands(server); assertNull(f.repo.find(prompt.endpoint.identity))
            } }
        }
    }
    @Test fun actualJschDeadlineAndLeavingCancelBeforeCredentialAuthentication() {
        for (leave in listOf(false, true)) Server().use { server -> Fixture(if (leave) 5000 else 120).use { f ->
            val result = f.connect(server); val prompt = f.prompt()
            if (leave) f.requests.detach()
            result.await(); prompt.accept()
            assertEquals(SshTransport.Failure.HOST_TRUST, result.failure)
            noAuthenticationOrCommands(server); assertNull(f.repo.find(prompt.endpoint.identity))
        } }
    }
    @Test fun actualJschTrustSurvivesChangedDialAddressButNotAChangedKey() {
        val key = Server.newKey()
        Server(key = key).use { original -> Fixture().use { f ->
            val first = f.connect(original); f.prompt().accept(); first.await(); assertNull(first.failure)
            val stop = Result(); f.transport.disconnect(stop); stop.await()
            Server("127.0.0.2", original.ssh.port, key).use { changedAddress ->
                val next = f.connect(changedAddress, "127.0.0.2"); next.await(); assertNull(next.failure)
                assertTrue(f.prompts.isEmpty()); assertEquals(1, changedAddress.credentialAuth.get())
                val disconnected = Result(); f.transport.disconnect(disconnected); disconnected.await()
            }
            Server("127.0.0.2", original.ssh.port).use { changedKey ->
                val blocked = f.connect(changedKey, "127.0.0.2"); blocked.await()
                assertEquals(SshTransport.Failure.HOST_MISMATCH, blocked.failure)
                noAuthenticationOrCommands(changedKey); assertTrue(f.prompts.isEmpty())
            }
        } }
    }

    @Test fun actualJschConnectsManualLocalDnsAndIpEndpointsWithoutAnSshAdvertisement() {
        for (host in listOf("desktop.local", "linux.example.test", "192.0.2.42", "2001:db8::42")) {
            Server().use { server ->
                val endpoint = Endpoint.manual(host, server.ssh.port)
                val manualLocator = endpoint.locator as EndpointLocator.Manual
                val lookups = AtomicInteger()
                val resolver = SshAddressResolver { resolvedHost, current ->
                    assertTrue(current.asBoolean)
                    assertEquals(manualLocator.host.lookupHost, resolvedHost)
                    lookups.incrementAndGet()
                    object : SshAddressResolver.Resolution {
                        override fun candidates(): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1"))
                    }
                }
                Fixture(resolver = resolver).use { f ->
                    val result = f.connect(server, address = manualLocator.host.lookupHost, endpoint = endpoint)
                    val prompt = f.prompt()
                    assertEquals(endpoint.identity, prompt.endpoint.identity)
                    prompt.accept()
                    result.await()
                    assertNull("manual endpoint $host", result.failure)
                    assertEquals(1, lookups.get())
                    assertEquals(1, server.credentialAuth.get())
                    val disconnected = Result(); f.transport.disconnect(disconnected); disconnected.await()
                }
            }
        }
    }

    @Test fun actualJschUsesFreshServiceCandidatesAndTreatsChangedServiceKeyAsTerminal() {
        val trustedKey = Server.newKey()
        val changedKey = Server.newKey()
        Server("127.0.0.2", 0, trustedKey).use { trustedServer ->
            val locator = EndpointLocator.DiscoveredService("Office SSH", "_ssh._tcp", "local")
            val endpoint = Endpoint(locator, trustedServer.ssh.port)
            val candidates = AtomicReference(listOf(
                InetAddress.getByName("127.0.0.1"),
                InetAddress.getByName("127.0.0.2"),
            ))
            val lookups = AtomicInteger()
            val connectivity = mock(ConnectivityManager::class.java)
            val network = mock(Network::class.java)
            `when`(connectivity.activeNetwork).thenReturn(network)
            val serviceLookup = SshServiceCandidateLookup { requested, networkIdentity, current, checkCurrent ->
                assertEquals(locator, requested)
                assertEquals(network.networkHandle.toString(), networkIdentity)
                assertTrue(current.asBoolean)
                checkCurrent()
                lookups.incrementAndGet()
                candidates.get()
            }
            val resolver = AndroidNetworkAddressResolver(
                connectivity,
                LocalNetworkPermissionCoordinator({ 36 }, { false }),
                serviceLookup = serviceLookup,
            )

            Fixture(resolver = resolver).use { f ->
                f.repo.approve(endpoint.identity, approvedKey(trustedKey), BooleanSupplier { true })
                Server("127.0.0.1", trustedServer.ssh.port, changedKey).use { changedServer ->
                    val mismatch = f.connect(changedServer, address = locator.instanceName, endpoint = endpoint)
                    mismatch.await()
                    assertEquals(SshTransport.Failure.HOST_MISMATCH, mismatch.failure)
                    noAuthenticationOrCommands(changedServer)
                    assertEquals("a service-key mismatch never falls back to another advertised address", 0,
                        trustedServer.credentialAuth.get())
                    assertEquals(1, lookups.get())
                }

                // A new attempt does a new lookup and connects through the still-trusted candidate.
                candidates.set(listOf(InetAddress.getByName("127.0.0.2")))
                val accepted = f.connect(trustedServer, address = locator.instanceName, endpoint = endpoint)
                accepted.await()
                assertNull(accepted.failure)
                assertEquals(2, lookups.get())
                assertTrue(f.prompts.isEmpty())
                assertEquals(1, trustedServer.credentialAuth.get())
                val disconnected = Result(); f.transport.disconnect(disconnected); disconnected.await()
            }
        }
    }

    @Test fun actualJschFallsBackAfterTcpFailureAndResolvesAgainAfterNetworkChange() {
        val key = Server.newKey()
        val addresses = AtomicReference(listOf(InetAddress.getByName("127.0.0.254"), InetAddress.getByName("127.0.0.1")))
        val lookups = AtomicInteger()
        Server(key = key).use { first ->
            Server("127.0.0.2", first.ssh.port, key).use { moved ->
                Fixture(resolver = resolver(addresses, lookups)).use { f ->
                    // Keep the scope object itself: normalization/resolution must not strip its interface ID.
                    val scoped = Inet6Address.getByAddress(null, InetAddress.getByName("fe80::1").address, 7)
                    val scopedRequest = SshConnectionRequest(Endpoint.manual("desktop.local", first.ssh.port),
                        "desktop.local", "", null).resolved(scoped)
                    assertEquals(7, (scopedRequest.resolvedAddress as Inet6Address).scopeId)
                    val bounded = (1..12).map { octet ->
                        InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, octet.toByte()))
                    }
                    assertEquals(SshAddressResolver.MAX_CANDIDATES,
                        scopedRequest.resolved(bounded).resolvedAddresses.size)

                    val initial = f.connect(first)
                    f.prompt().accept() // The first refused TCP candidate did not start another SSH identity flow.
                    initial.await(); assertNull(initial.failure)
                    assertEquals(1, first.credentialAuth.get())
                    val stopped = Result(); f.transport.disconnect(stopped); stopped.await(); assertNull(stopped.failure)

                    // A subsequent attempt sees the moved host's fresh answer while trust stays keyed to desktop.local.
                    addresses.set(listOf(InetAddress.getByName("127.0.0.2")))
                    val next = f.connect(moved, address = "127.0.0.2")
                    next.await(); assertNull(next.failure)
                    assertEquals(2, lookups.get())
                    assertTrue(f.prompts.isEmpty())
                    assertEquals(1, moved.credentialAuth.get())
                    val disconnected = Result(); f.transport.disconnect(disconnected); disconnected.await()
                }
            }
        }
    }

    @Test fun actualJschTrustAuthenticationAndStaleRouteFailuresNeverFallThroughToAnotherAddress() {
        val trustedKey = Server.newKey()
        Server("127.0.0.2", 0, trustedKey).use { fallback ->
            val endpoint = Endpoint.manual("desktop.local", fallback.ssh.port)
            val addresses = AtomicReference(listOf(InetAddress.getByName("127.0.0.1"), InetAddress.getByName("127.0.0.2")))
            Fixture(resolver = resolver(addresses, AtomicInteger())).use { f ->
                f.repo.approve(endpoint.identity, approvedKey(trustedKey), BooleanSupplier { true })
                Server("127.0.0.1", fallback.ssh.port, Server.newKey()).use { changedKey ->
                    val mismatch = f.connect(changedKey, endpoint = endpoint)
                    mismatch.await()
                    assertEquals(SshTransport.Failure.HOST_MISMATCH, mismatch.failure)
                    assertNotNull(mismatch.mismatch)
                    noAuthenticationOrCommands(changedKey)
                    assertEquals("the trusted second address was not attempted after a key mismatch", 0,
                        fallback.credentialAuth.get())
                }

                Server("127.0.0.1", fallback.ssh.port, trustedKey, acceptPassword = false).use { rejectedPassword ->
                    val authentication = f.connect(rejectedPassword, endpoint = endpoint)
                    authentication.await()
                    assertEquals(SshTransport.Failure.CONNECTION, authentication.failure)
                    assertTrue(rejectedPassword.credentialAuth.get() > 0)
                    assertEquals("authentication failure is terminal, not an address-retry signal", 0,
                        fallback.credentialAuth.get())
                }
            }

            // A TCP refusal racing route invalidation must report the typed route failure, not
            // the socket error, and must not continue to the otherwise-valid second candidate.
            for (permissionRevoked in listOf(false, true)) {
                val checks = AtomicInteger()
                val staleResolver = SshAddressResolver { _, current ->
                    assertTrue(current.asBoolean)
                    object : SshAddressResolver.Resolution {
                        override fun candidates(): List<InetAddress> =
                            listOf(InetAddress.getByName("127.0.0.1"), InetAddress.getByName("127.0.0.2"))
                        override fun isCurrent(): Boolean = checks.incrementAndGet() < 5
                        override fun checkCurrent() {
                            if (!isCurrent()) {
                                if (permissionRevoked) throw SshAddressResolver.AccessDeniedException()
                                throw SshAddressResolver.NetworkChangedException()
                            }
                        }
                    }
                }
                Fixture(resolver = staleResolver).use { stale ->
                    stale.repo.approve(endpoint.identity, approvedKey(trustedKey), BooleanSupplier { true })
                    val staleResult = stale.connect(fallback, endpoint = endpoint)
                    staleResult.await()
                    assertEquals("route validity checks=${checks.get()}",
                        if (permissionRevoked) SshTransport.Failure.PERMISSION else SshTransport.Failure.RESOLUTION,
                        staleResult.failure)
                    assertEquals("stale candidates cannot connect to the second address", 0, fallback.credentialAuth.get())
                }
            }
        }
    }
    @Test fun actualJschBlocksChangedKeyAcrossReconnectAlternateAddressAndDiscoveredIdentitiesUntilConfirmedReplacement() {
        val oldKey = Server.newKey()
        val changedKey = Server.newKey()
        val port = Server(key = oldKey).use { original ->
            Fixture().use { f ->
                val endpoint = Endpoint.manual("desktop.local", original.ssh.port)
                val first = f.connect(original, endpoint = endpoint); f.prompt().accept(); first.await(); assertNull(first.failure)
                val oldApproved = requireNotNull(f.repo.find(endpoint.identity))
                val stopped = Result(); f.transport.disconnect(stopped); stopped.await()

                Server("127.0.0.2", original.ssh.port, changedKey).use { reconnectWithChangedKey ->
                    val blocked = f.connect(reconnectWithChangedKey, "127.0.0.2", endpoint = endpoint); blocked.await()
                    assertEquals(SshTransport.Failure.HOST_MISMATCH, blocked.failure)
                    assertNotNull(blocked.mismatch); noAuthenticationOrCommands(reconnectWithChangedKey)
                    assertTrue(f.prompts.isEmpty()); assertTrue(f.repo.find(endpoint.identity)!!.matches(oldApproved))
                }

                Server("127.0.0.3", original.ssh.port, changedKey).use { alternateAddress ->
                    val blocked = f.connect(alternateAddress, "127.0.0.3", endpoint = endpoint); blocked.await()
                    assertEquals(SshTransport.Failure.HOST_MISMATCH, blocked.failure)
                    assertNotNull(blocked.mismatch); noAuthenticationOrCommands(alternateAddress)
                    assertTrue(f.prompts.isEmpty())
                }

                val stableService = Endpoint(
                    EndpointLocator.DiscoveredService("Desktop SSH", "_ssh._tcp", "local", EndpointHost("desktop.local")),
                    original.ssh.port,
                )
                Server("127.0.0.4", original.ssh.port, changedKey).use { discoveredStableHost ->
                    val blocked = f.connect(discoveredStableHost, "127.0.0.4", endpoint = stableService); blocked.await()
                    assertEquals(SshTransport.Failure.HOST_MISMATCH, blocked.failure)
                    assertNotNull(blocked.mismatch); noAuthenticationOrCommands(discoveredStableHost)
                    assertTrue(f.prompts.isEmpty())
                    assertTrue(blocked.mismatch!!.confirm()) // Actual deliberate replacement action.
                    val replacement = f.replace(blocked.mismatch!!); replacement.await()
                    assertNull(replacement.failure)
                    assertTrue(HostKeyRepository(f.directory, TrustFileIo()).find(endpoint.identity)!!.matches(blocked.mismatch!!.presentedKey))

                    val fresh = f.connect(discoveredStableHost, "127.0.0.4", endpoint = stableService); fresh.await()
                    assertNull(fresh.failure)
                    assertEquals(1, discoveredStableHost.credentialAuth.get())
                    assertEquals(1, discoveredStableHost.channelRequests.get())
                    assertEquals(1, discoveredStableHost.commandRequests.get())
                    val disconnected = Result(); f.transport.disconnect(disconnected); disconnected.await()
                }

                Server("127.0.0.2", original.ssh.port, oldKey).use { oldKeyReturns ->
                    val blocked = f.connect(oldKeyReturns, "127.0.0.2", endpoint = endpoint); blocked.await()
                    assertEquals(SshTransport.Failure.HOST_MISMATCH, blocked.failure)
                    noAuthenticationOrCommands(oldKeyReturns); assertTrue(f.prompts.isEmpty())
                }
                original.ssh.port
            }
        }
        assertTrue(port in 1..65535)

        val serviceIdentity = Endpoint(EndpointLocator.DiscoveredService("Office SSH", "_ssh._tcp", "local"), port)
        val serviceKey = Server.newKey()
        Server("127.0.0.1", port, serviceKey).use { serviceFirst -> Fixture(resolver = directServiceCandidates()).use { f ->
            val first = f.connect(serviceFirst, endpoint = serviceIdentity); f.prompt().accept(); first.await(); assertNull(first.failure)
            val stopped = Result(); f.transport.disconnect(stopped); stopped.await()
            Server("127.0.0.2", port).use { serviceChangedKey ->
                val blocked = f.connect(serviceChangedKey, "127.0.0.2", endpoint = serviceIdentity); blocked.await()
                assertEquals(SshTransport.Failure.HOST_MISMATCH, blocked.failure)
                noAuthenticationOrCommands(serviceChangedKey); assertTrue(f.prompts.isEmpty())
                assertEquals(serviceIdentity.identity, blocked.mismatch!!.endpoint.identity)
            }
        } }
    }
    @Test fun actualJschCorruptRepositoryAndFailedPersistenceCannotAuthenticate() {
        for (corrupt in listOf(false, true)) Server().use { server -> Fixture(io = object : TrustFileIo() {
            override fun replace(source: File, destination: File) { throw IOException("NOT_REAL_storage_detail") }
        }).use { f ->
            if (corrupt) File(f.directory, HostKeyRepository.FILE).writeBytes(byteArrayOf(1))
            val result = f.connect(server)
            if (!corrupt) {
                val prompt = f.prompt()
                prompt.accept()
            }
            result.await(); assertEquals(SshTransport.Failure.TRUST_STORAGE, result.failure)
            noAuthenticationOrCommands(server)
        } }
    }
    @Test fun actualJschReplacementStorageFailureKeepsOldTrustedKeyAndNeverAuthenticates() {
        val failStagingSync = java.util.concurrent.atomic.AtomicBoolean(false)
        val io = object : TrustFileIo() {
            override fun sync(output: FileOutputStream) {
                if (failStagingSync.get()) throw IOException("NOT_REAL_storage_detail")
                super.sync(output)
            }
        }
        val oldKey = Server.newKey()
        val newKey = Server.newKey()
        Server(key = oldKey).use { original -> Fixture(io = io).use { f ->
            val endpoint = Endpoint.manual("desktop.local", original.ssh.port)
            val first = f.connect(original, endpoint = endpoint); f.prompt().accept(); first.await(); assertNull(first.failure)
            val trusted = requireNotNull(f.repo.find(endpoint.identity))
            val stopped = Result(); f.transport.disconnect(stopped); stopped.await()
            Server("127.0.0.2", original.ssh.port, newKey).use { changedKey ->
                val blocked = f.connect(changedKey, "127.0.0.2", endpoint = endpoint); blocked.await()
                assertEquals(SshTransport.Failure.HOST_MISMATCH, blocked.failure)
                noAuthenticationOrCommands(changedKey)
                val mismatch = requireNotNull(blocked.mismatch)
                assertTrue(mismatch.confirm())
                failStagingSync.set(true)
                val replacement = f.replace(mismatch); replacement.await()
                assertEquals(SshTransport.Failure.TRUST_STORAGE, replacement.failure)
                noAuthenticationOrCommands(changedKey)
                assertTrue(HostKeyRepository(f.directory, TrustFileIo()).find(endpoint.identity)!!.matches(trusted))
            }
        } }
    }
    @Test fun actualJschStopCancellationDuringReplacementStagingKeepsOldTrustAndNeverAuthenticates() {
        val blockNextSync = AtomicBoolean(false)
        val staged = CountDownLatch(1); val release = CountDownLatch(1)
        val io = object : TrustFileIo() {
            override fun sync(output: FileOutputStream) {
                super.sync(output)
                if (blockNextSync.compareAndSet(true, false)) {
                    staged.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            }
        }
        val oldKey = Server.newKey(); val newKey = Server.newKey()
        try {
            Server(key = oldKey).use { original -> Fixture(io = io).use { f ->
                val endpoint = Endpoint.manual("desktop.local", original.ssh.port)
                val first = f.connect(original, endpoint = endpoint); f.prompt().accept(); first.await()
                assertNull(first.failure)
                val trusted = requireNotNull(f.repo.find(endpoint.identity))
                val other = Endpoint.manual("other.local", 2200)
                val unrelated = syntheticHostKey(91)
                f.repo.approve(other.identity, unrelated, java.util.function.BooleanSupplier { true })
                val disconnected = Result(); f.transport.disconnect(disconnected); disconnected.await()

                Server("127.0.0.2", original.ssh.port, newKey).use { changedKey ->
                    val blocked = f.connect(changedKey, "127.0.0.2", endpoint = endpoint); blocked.await()
                    assertEquals(SshTransport.Failure.HOST_MISMATCH, blocked.failure)
                    noAuthenticationOrCommands(changedKey)
                    val mismatch = requireNotNull(blocked.mismatch)
                    assertTrue(mismatch.confirm())

                    blockNextSync.set(true)
                    val replacement = f.replace(mismatch)
                    assertTrue("replacement never reached staged fsync", staged.await(5, TimeUnit.SECONDS))
                    val start = System.nanoTime()
                    mismatch.cancel() // Same cancellation used by the dismissed Activity review.
                    assertTrue("cancellation waited for worker I/O", System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1))
                    assertFalse(mismatch.confirm())
                    release.countDown()

                    replacement.await()
                    assertEquals(SshTransport.Failure.HOST_MISMATCH, replacement.failure)
                    noAuthenticationOrCommands(changedKey)
                    val reopened = HostKeyRepository(f.directory, TrustFileIo())
                    assertTrue(reopened.find(endpoint.identity)!!.matches(trusted))
                    assertTrue(reopened.find(other.identity)!!.matches(unrelated))
                }
            } }
        } finally { release.countDown() }
    }
    @Test fun transportDisconnectWakesPendingTrustAndOldAcceptCannotApproveTheNextAttempt() {
        Server().use { server -> Fixture().use { f ->
            f.connect(server); val old = f.prompt()
            val disconnected = Result(); f.transport.disconnect(disconnected); disconnected.await()
            old.accept(); noAuthenticationOrCommands(server); assertNull(f.repo.find(old.endpoint.identity))
            val next = f.connect(server); val fresh = f.prompt()
            old.accept(); assertTrue(fresh.isPending); noAuthenticationOrCommands(server)
            fresh.reject(); next.await(); noAuthenticationOrCommands(server)
        } }
    }
}
