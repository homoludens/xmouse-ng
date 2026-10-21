package com.stripe1.xmouse.session

import com.stripe1.xmouse.connection.*
import com.stripe1.xmouse.secrets.AuthenticationCallback
import com.stripe1.xmouse.transport.*
import com.stripe1.xmouse.trust.*
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/** Controller -> captured credentials -> production transport/JSch/strict trust -> synthetic MINA. */
class SessionPipelineIntegrationTest {
    private class Server : AutoCloseable {
        val authentications = AtomicInteger()
        val commands = AtomicInteger()
        val input = CountDownLatch(1)
        val ssh = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"; port = 0
            keyPairProvider = KeyPairProvider.wrap(KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair())
            setPasswordAuthenticator { user, password, _ ->
                authentications.incrementAndGet(); user == "NOT_REAL_user" && password == "NOT_REAL_password"
            }
            setShellFactory { command() }
            start()
        }
        private fun command(): Command {
            commands.incrementAndGet()
            return object : Command {
                private var source: InputStream? = null
                override fun setInputStream(input: InputStream) { source = input }
                override fun setOutputStream(output: OutputStream) { }
                override fun setErrorStream(error: OutputStream) { }
                override fun setExitCallback(callback: ExitCallback) { }
                override fun start(channel: ChannelSession, env: Environment) {
                    Thread({ try { if (source!!.read() >= 0) input.countDown() } catch (_: IOException) { } },
                        "synthetic-session-input").apply { isDaemon = true; start() }
                }
                override fun destroy(channel: ChannelSession) { source?.close() }
            }
        }
        fun profile(id: Long = 1) = SessionFixtures.host("127.0.0.1", id, port = ssh.port).copy(initialCommand = "synthetic input, never executed")
        override fun close() { ssh.stop(true) }
    }
    private class Fixture : AutoCloseable, SessionInteractions.Listener {
        val directory = Files.createTempDirectory("xmouse-session-loopback-").toFile()
        val queue = SessionFixtures.Queue()
        val prompts = LinkedBlockingQueue<SessionInteractions.Prompt>()
        val interactions = SessionInteractions(Executor { it.run() }).also { it.attach(this) }
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
        val limits = SshLimits(1000, 3000, 1000, 5000, 1000, 1000, 1000, 1000, 700, 1000, 3, 100, 300, 4096)
        val factory = SshSessionAttemptFactory(credentials, HostKeyRepository(directory, TrustFileIo()), interactions, limits, runtime)
        val controller = SessionController(factory, queue)
        override fun show(prompt: SessionInteractions.Prompt) { prompts.add(prompt) }
        override fun dismiss(prompt: SessionInteractions.Prompt) { }
        fun await(assertion: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (System.nanoTime() < deadline) { queue.drain(); if (assertion()) return; Thread.sleep(5) }
            queue.drain(); assertTrue(assertion())
        }
        fun connect(host: SessionHost) { controller.connectIfNeeded(host); queue.drain() }
        fun prompt(): HostTrustRequests.Request {
            await { prompts.isNotEmpty() }
            return (prompts.remove() as SessionInteractions.Prompt.Trust).request
        }
        override fun close() {
            controller.close(); await { queue.closed }
            interactions.detach(); worker.shutdown(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
            runtime.close(); directory.deleteRecursively()
        }
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
}
