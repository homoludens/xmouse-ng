package com.stripe1.xmouse.trust

import com.stripe1.xmouse.connection.Endpoint
import com.stripe1.xmouse.transport.*
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
import java.io.*
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/** Actual maintained JSch against an in-process synthetic server, never sshd/user accounts/real hosts. */
class JschHostTrustIntegrationTest {
    private class Server(address: String = "127.0.0.1", port: Int = 0, key: KeyPair = newKey()) : AutoCloseable {
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
            setPasswordAuthenticator { user, password, _ -> credentialAuth.incrementAndGet(); onCredential(); user == "NOT_REAL_user" && password == "NOT_REAL_password" }
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
        override fun success(value: Void?) { done.countDown() }
        override fun failure(failure: SshTransport.Failure) { this.failure = failure; done.countDown() }
        fun await() { assertTrue(done.await(10, TimeUnit.SECONDS)) }
    }
    private class Fixture(timeout: Long = 5000, io: TrustFileIo = TrustFileIo()) : AutoCloseable, HostTrustRequests.Listener {
        val directory = Files.createTempDirectory("xmouse-actual-jsch-trust-").toFile()
        val repo = HostKeyRepository(directory, io)
        val prompts = LinkedBlockingQueue<HostTrustRequests.Request>()
        val requests = HostTrustRequests(repo, Executor { it.run() }, timeout).also { it.attach(this) }
        val worker = SshTransport.newWorker()
        val transport = SshTransport(JschSshBackend(HostTrustPolicy(requests)), worker)
        override fun show(request: HostTrustRequests.Request) { prompts.add(request) }
        override fun dismiss(request: HostTrustRequests.Request) { }
        fun prompt() = requireNotNull(prompts.poll(10, TimeUnit.SECONDS))
        fun connect(server: Server, address: String = "127.0.0.1", keyAuth: Boolean = false): Result {
            val key = if (keyAuth) requireNotNull(javaClass.classLoader!!.getResourceAsStream("ssh-crypto/ecdsa-p256.pem")).use { it.readBytes() } else byteArrayOf()
            val authentication = RecordingSshBackend.authentication(keyAuth, key)
            return Result().also { transport.connect(SshConnectionRequest(Endpoint.manual("DESKTOP.local.", server.ssh.port), address, "", "synthetic initial command"), authentication, it) }
        }
        override fun close() {
            requests.detach(); transport.close(); assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS)); directory.deleteRecursively()
        }
    }
    private fun noAuthenticationOrCommands(server: Server) {
        assertEquals(0, server.authenticationRequests.get())
        assertEquals(0, server.credentialAuth.get()); assertEquals(0, server.channelRequests.get()); assertEquals(0, server.commandRequests.get())
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
