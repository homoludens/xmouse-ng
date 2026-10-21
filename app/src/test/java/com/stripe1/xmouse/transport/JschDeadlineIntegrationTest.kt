package com.stripe1.xmouse.transport

import com.stripe1.xmouse.connection.Endpoint
import com.stripe1.xmouse.trust.*
import org.apache.sshd.common.channel.Channel
import org.apache.sshd.common.channel.ChannelListener
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.*
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.*
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Real maintained JSch/socket/SSHD behavior. Loopback only, generated keys, no shell processes. */
class JschDeadlineIntegrationTest {
    private class Client(val limits: SshLimits = limits()) : AutoCloseable, HostTrustRequests.Listener {
        val directory = Files.createTempDirectory("synthetic-ssh-deadlines").toFile()
        val requests = HostTrustRequests(HostKeyRepository(directory, TrustFileIo()), Executor { it.run() }, 2000).also { it.attach(this) }
        val worker = SshTransport.newWorker()
        val transport = SshTransport(JschSshBackend(HostTrustPolicy(requests)), worker, limits, SshRuntime.SHARED)
        val losses = LinkedBlockingQueue<SshTransport.Failure>()
        init { transport.setLossListener { losses.add(it) } }
        override fun show(request: HostTrustRequests.Request) { request.accept() } // Explicit synthetic fixture decision only.
        override fun dismiss(request: HostTrustRequests.Request) { }
        fun connect(port: Int, initial: String? = null): SshDeadlineTest.Result<Void> = SshDeadlineTest.Result<Void>().also {
            transport.connect(SshConnectionRequest(Endpoint.manual("desktop.local", port), "127.0.0.1", "", initial), RecordingSshBackend.authentication(), it)
        }
        override fun close() { requests.detach(); transport.close(); assertTrue(worker.awaitTermination(4, TimeUnit.SECONDS)); directory.deleteRecursively() }
    }

    private class Server(val mode: String = "normal") : AutoCloseable {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val inputReceived = CountDownLatch(1)
        val outputBytes = AtomicLong()
        val threads = CopyOnWriteArrayList<Thread>()
        @Volatile var shellExit: ExitCallback? = null
        private val stopped = AtomicBoolean()
        val ssh = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"; port = 0
            keyPairProvider = KeyPairProvider.wrap(KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair())
            setPasswordAuthenticator { _, _, _ ->
                if (mode == "auth") { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
                true
            }
            addChannelListener(object : ChannelListener {
                override fun channelInitialized(channel: Channel) {
                    if (mode == "channel") { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
                }
            })
            setShellFactory { command("shell") }
            setCommandFactory { _, text -> command(text) }
            start()
        }
        private fun launch(action: () -> Unit) {
            Thread({ try { action() } catch (_: IOException) { } catch (_: InterruptedException) { } }, "synthetic-deadline-server").also {
                it.isDaemon = true; threads.add(it); it.start()
            }
        }
        private fun command(text: String) = object : Command {
            lateinit var input: InputStream; lateinit var output: OutputStream; lateinit var error: OutputStream; lateinit var exit: ExitCallback
            override fun setInputStream(input: InputStream) { this.input = input }
            override fun setOutputStream(output: OutputStream) { this.output = output }
            override fun setErrorStream(error: OutputStream) { this.error = error }
            override fun setExitCallback(callback: ExitCallback) { exit = callback; if (text == "shell") shellExit = callback }
            override fun start(channel: ChannelSession, env: Environment) {
                when (text) {
                    "shell" -> {
                        if (mode != "write") launch { if (input.read() >= 0) inputReceived.countDown(); while (input.read() >= 0) { } }
                        if (mode == "output") {
                            for (stream in listOf(output, error)) launch {
                                val bytes = ByteArray(8192) { 'P'.code.toByte() }
                                while (!stopped.get()) { stream.write(bytes); stream.flush(); outputBytes.addAndGet(bytes.size.toLong()) }
                            }
                        }
                    }
                    "both", "flood" -> {
                        val complete = CountDownLatch(2)
                        for ((stream, marker) in listOf(output to 'O', error to 'E')) launch {
                            try {
                                val bytes = ByteArray(4096) { marker.code.toByte() }
                                val chunks = if (text == "flood") 2048 else 48
                                repeat(chunks) { stream.write(bytes); stream.flush(); outputBytes.addAndGet(bytes.size.toLong()) }
                            } finally { complete.countDown() }
                        }
                        launch { complete.await(); exit.onExit(0) }
                    }
                    "abrupt" -> launch { output.write("partial".toByteArray()); output.flush(); channel.close(false) }
                    // "stall" and "scp-stall" deliberately never exit/write anything.
                }
            }
            override fun destroy(channel: ChannelSession) { input.close() }
        }
        override fun close() {
            stopped.set(true); release.countDown(); ssh.stop(true)
            threads.forEach { it.join(2000); assertFalse("synthetic fixture thread leaked", it.isAlive) }
        }
    }

    /** Plain socket accepts TCP, then stalls either identification or server KEXINIT. */
    private class StalledServer(val identify: Boolean) : AutoCloseable {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val closedByClient = CountDownLatch(1)
        @Volatile var socket: Socket? = null
        val thread = Thread({
            try {
                server.accept().use { connection ->
                    socket = connection
                    if (identify) { connection.getOutputStream().write("SSH-2.0-synthetic-stall\r\n".toByteArray()); connection.getOutputStream().flush() }
                    val bytes = ByteArray(4096)
                    while (connection.getInputStream().read(bytes) >= 0) { }
                    closedByClient.countDown()
                }
            } catch (_: IOException) { closedByClient.countDown() }
        }, "synthetic-stalled-ssh").apply { isDaemon = true; start() }
        override fun close() { socket?.close(); server.close(); thread.join(2000); assertFalse(thread.isAlive) }
    }

    /** Transparent during authentication, then drops traffic in BOTH directions without closing TCP. */
    private class Blackhole(port: Int) : AutoCloseable {
        val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val blackhole = AtomicBoolean()
        val droppedClientBytes = AtomicLong()
        val sockets = CopyOnWriteArrayList<Socket>()
        val pumps = CopyOnWriteArrayList<Thread>()
        val thread = Thread({
            try {
                val client = listener.accept().also(sockets::add)
                val server = Socket("127.0.0.1", port).also(sockets::add)
                for ((from, to) in listOf(client to server, server to client)) {
                    Thread({
                        try {
                            val bytes = ByteArray(8192)
                            while (true) {
                                val count = from.getInputStream().read(bytes); if (count < 0) break
                                if (!blackhole.get()) { to.getOutputStream().write(bytes, 0, count); to.getOutputStream().flush() }
                                else if (from === client) droppedClientBytes.addAndGet(count.toLong())
                            }
                        } catch (_: IOException) { }
                    }, "synthetic-blackhole-pump").apply { isDaemon = true; pumps.add(this); start() }
                }
            } catch (_: IOException) { }
        }, "synthetic-blackhole-accept").apply { isDaemon = true; start() }
        override fun close() { sockets.forEach { it.close() }; listener.close(); thread.join(2000); pumps.forEach { it.join(2000); assertFalse(it.isAlive) } }
    }

    @Test fun actualJschIdentificationAndKexStallsAreFiniteAndCloseTheSocket() {
        for (identify in listOf(false, true)) StalledServer(identify).use { server -> Client().use { client ->
            val start = System.nanoTime()
            assertEquals(SshTransport.Failure.CONNECTION_TIMEOUT, client.connect(server.server.localPort).await().failure)
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(4))
            assertFalse(client.transport.isConnected)
            assertTrue(server.closedByClient.await(1, TimeUnit.SECONDS))
        } }
    }
    @Test fun actualJschTcpConnectHasItsOwnFiniteBudgetWhenTheLoopbackAcceptQueueIsFull() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            val fillers = mutableListOf<Socket>()
            try {
                var saturated = false
                repeat(8) {
                    if (!saturated) {
                        val socket = Socket()
                        try {
                            socket.connect(InetSocketAddress("127.0.0.1", listener.localPort), 100)
                            fillers += socket
                        } catch (_: SocketTimeoutException) { saturated = true; socket.close() }
                    }
                }
                assertTrue("synthetic loopback accept queue did not saturate", saturated)
                Client().use { client ->
                    val start = System.nanoTime()
                    assertEquals(SshTransport.Failure.CONNECTION_TIMEOUT, client.connect(listener.localPort).await().failure)
                    // TCP's 700ms budget, not the 2200ms overall SSH budget, must win.
                    assertTrue(System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(1800))
                    assertFalse(client.transport.isConnected)
                }
            } finally { fillers.forEach { it.close() } }
        }
    }
    @Test fun actualJschStalledAuthenticationAndChannelSetupAreFinite() {
        for ((mode, expected) in listOf("auth" to SshTransport.Failure.CONNECTION_TIMEOUT, "channel" to SshTransport.Failure.CHANNEL_TIMEOUT)) {
            Server(mode).use { server -> Client().use { client ->
                val result = client.connect(server.ssh.port)
                assertTrue(server.entered.await(2, TimeUnit.SECONDS))
                result.await(); assertEquals(mode, expected, result.failure)
                assertFalse(client.transport.isConnected)
                server.release.countDown()
            } }
        }
    }
    @Test fun actualJschExecAndScpStallsAndFlowControlledWritesHaveFiniteOutcomes() {
        for (operation in listOf("exec", "scp", "write")) Server(if (operation == "write") "write" else "normal").use { server -> Client().use { client ->
            assertNull(client.connect(server.ssh.port).await().failure)
            val result = SshDeadlineTest.Result<Any>()
            val file = Files.createTempFile("synthetic-jsch-scp", ".bin").toFile()
            try {
                when (operation) {
                    "exec" -> client.transport.exec("stall", result.string())
                    "scp" -> client.transport.downloadScp("scp-stall", file, result.void())
                    else -> client.transport.send("W".repeat(4 * 1024 * 1024), result.void())
                }
                result.await()
                assertEquals(when (operation) { "exec" -> SshTransport.Failure.EXEC_TIMEOUT; "scp" -> SshTransport.Failure.SCP_TIMEOUT; else -> SshTransport.Failure.WRITE_TIMEOUT }, result.failure)
                assertFalse(client.transport.isConnected)
            } finally { file.delete() }
        } }
    }
    @Test fun actualJschDrainsConcurrentStdoutAndStderrAndPreservesCompleteOutputOrder() {
        Server().use { server -> Client().use { client ->
            assertNull(client.connect(server.ssh.port).await().failure)
            val result = SshDeadlineTest.Result<String>(); client.transport.exec("both", result)
            result.await(); assertNull(result.failure)
            assertEquals("E".repeat(48 * 4096) + "O".repeat(48 * 4096), result.value)
            assertTrue(client.transport.isConnected)
        } }
    }
    @Test fun actualJschSustainedExecOutputFailsAtLimitAndPersistentOutputCannotBlockInput() {
        Server("output").use { server -> Client().use { client ->
            assertNull(client.connect(server.ssh.port).await().failure)
            SshDeadlineTest.eventually { server.outputBytes.get() > 2 * 1024 * 1024 }
            val sent = SshDeadlineTest.Result<Void>(); client.transport.send("synthetic input", sent)
            sent.await(); assertNull(sent.failure); assertTrue(server.inputReceived.await(1, TimeUnit.SECONDS))
            val result = SshDeadlineTest.Result<String>(); client.transport.exec("flood", result)
            result.await(); assertEquals(SshTransport.Failure.OUTPUT_LIMIT, result.failure)
            assertNull(result.value); assertFalse(client.transport.isConnected)
        } }
    }
    @Test fun actualJschIdleChannelAndSessionClosureUpdateStatusWithoutAnotherCommand() {
        for (session in listOf(false, true)) Server().use { server -> Client().use { client ->
            assertNull(client.connect(server.ssh.port).await().failure)
            if (session) server.ssh.activeSessions.forEach { it.close(true) } else requireNotNull(server.shellExit).onExit(0)
            val lost = client.losses.poll(2, TimeUnit.SECONDS)
            assertEquals(if (session) SshTransport.Failure.SESSION_LOST else SshTransport.Failure.CHANNEL_CLOSED, lost)
            assertFalse(client.transport.isConnected)
        } }
    }
    @Test fun actualJschHalfOpenKeepaliveFailureIsFiniteAndExplicitDisconnectSuppressesLoss() {
        for (disconnect in listOf(false, true)) Server().use { server -> Blackhole(server.ssh.port).use { proxy -> Client().use { client ->
            assertNull(client.connect(proxy.listener.localPort).await().failure)
            proxy.blackhole.set(true)
            val start = System.nanoTime()
            if (disconnect) {
                val stopped = SshDeadlineTest.Result<Void>(); client.transport.disconnect(stopped); stopped.await()
                assertNull(stopped.failure)
                assertNull(client.losses.poll(1100, TimeUnit.MILLISECONDS))
            } else {
                assertEquals(SshTransport.Failure.SESSION_LOST, client.losses.poll(2, TimeUnit.SECONDS))
                assertTrue("actual JSch keepalive traffic was not sent", proxy.droppedClientBytes.get() > 0)
                assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2))
                assertFalse(client.transport.isConnected)
            }
        } } }
    }
    @Test fun actualJschHealthyKeepaliveRepliesDoNotCauseFalseIdleFailure() {
        Server().use { server -> Client().use { client ->
            assertNull(client.connect(server.ssh.port).await().failure)
            assertNull(client.losses.poll(1100, TimeUnit.MILLISECONDS)) // Beyond the 600ms silence budget.
            assertTrue(client.transport.isConnected)
        } }
    }
    @Test fun actualJschAbruptExecCloseDoesNotReportPartialOutputAsSuccess() {
        Server().use { server -> Client().use { client ->
            assertNull(client.connect(server.ssh.port).await().failure)
            val result = SshDeadlineTest.Result<String>(); client.transport.exec("abrupt", result)
            result.await(); assertEquals(SshTransport.Failure.CHANNEL_CLOSED, result.failure); assertNull(result.value)
        } }
    }
    companion object {
        private fun limits() = SshLimits(500, 2500, 700, 2200, 600, 700, 1500, 900, 500, 200, 2, 50, 200, 1024 * 1024)
    }
}
