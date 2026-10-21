package com.stripe1.xmouse.transport

import com.jcraft.jsch.JSch
import com.stripe1.xmouse.XDoTool
import com.stripe1.xmouse.YDoTool
import com.stripe1.xmouse.secrets.BoundedKeyFixtures
import com.stripe1.xmouse.secrets.PrivateKeyImportException
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** No Activity, Context, View, Android Looper/Handler, Android mocks, or network in these tests. */
class SshTransportTest {
    private class Result<T> : SshTransport.Callback<T> {
        val done = CountDownLatch(1)
        var value: T? = null
        var failure: SshTransport.Failure? = null
        var thread: Thread? = null
        override fun success(value: T?) { this.value = value; thread = Thread.currentThread(); done.countDown() }
        override fun failure(failure: SshTransport.Failure) { this.failure = failure; thread = Thread.currentThread(); done.countDown() }
        fun await(): Result<T> { assertTrue(done.await(10, TimeUnit.SECONDS)); return this }
    }
    private class Fixture : AutoCloseable {
        val backend = RecordingSshBackend()
        val worker = SshTransport.newWorker()
        val transport = SshTransport(backend, worker)
        fun connect(shell: String = "", initial: String? = null): Result<Void> = Result<Void>().also {
            transport.connect(RecordingSshBackend.request(shell, initial), RecordingSshBackend.authentication(), it)
            it.await(); assertNull(it.failure)
        }
        fun barrier() = worker.submit {}.get(10, TimeUnit.SECONDS)
        override fun close() {
            backend.release.countDown()
            transport.close()
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test fun connectOrderedWritesAndJavaAdaptersUseOnlyOwnedWorker() = Fixture().use { f ->
        val caller = Thread.currentThread()
        val authentication = RecordingSshBackend.authentication()
        val connected = Result<Void>()
        f.transport.connect(RecordingSshBackend.request("", "export DISPLAY=:0.0"), authentication, connected)
        connected.await()
        assertNull(connected.failure)
        assertTrue(f.transport.isConnected)
        assertTrue(authentication.password.all { it == 0.toByte() })
        val beforeSnapshot = f.backend.events.size
        repeat(10) { assertTrue(f.transport.isConnected) }
        assertEquals("UI status must not consult network resources", beforeSnapshot, f.backend.events.size)
        XDoTool(f.transport).key("Return")
        YDoTool(f.transport).mouseClick(1)
        assertTrue(f.transport.executeShellCommand("printf 'synthetic Ω'"))
        f.barrier()
        assertEquals(listOf("export DISPLAY=:0.0\r\n", "xdotool key Return\r\n", "ydotool click 0xC0\r\n", "printf 'synthetic Ω'\r\n"), f.backend.writes)
        val disconnected = Result<Void>()
        f.transport.disconnect(disconnected)
        assertFalse(f.transport.isConnected)
        disconnected.await()
        assertNull(disconnected.failure)
        assertEquals(5000, f.backend.channels.first().timeout)
        assertEquals(setOf("shell.input.close", "shell.close", "session.close"), f.backend.events.takeLast(3).map { it.name }.toSet())
        assertTrue(f.backend.events.all { it.thread !== caller && it.thread.name == "xmouse-ssh-io" })
        assertNotSame(caller, connected.thread)
        assertNotSame(caller, disconnected.thread)
    }

    @Test fun configuredShellStillUsesExecWithUnchangedCommand() = Fixture().use { f ->
        f.connect("bash --noprofile")
        assertEquals("exec", f.backend.channels.single().type)
        assertEquals("bash --noprofile", f.backend.channels.single().command)
    }

    @Test fun execRetainsRequestedOutputSemanticsAndClosesTemporaryChannel() = Fixture().use { f ->
        f.connect()
        val result = Result<String>()
        assertTrue(f.transport.exec("printf synthetic", result))
        result.await()
        assertNull(result.failure)
        assertEquals("err oneerr twoout oneout two", result.value)
        assertEquals("printf synthetic", f.backend.channels.last().command)
        assertTrue(f.backend.channels.last().closed)
        assertTrue(f.transport.isConnected)
        assertTrue(f.backend.events.filter { it.name.startsWith("exec.") }.all { it.thread.name == "xmouse-ssh-io" })
    }

    @Test fun scpReceivesLegacyFileAndAcknowledgesAndClosesEveryChannelStream() = Fixture().use { f ->
        val destination = Files.createTempFile("xmouse-synthetic-scp", ".jpg").toFile()
        try {
            f.connect()
            val result = Result<Void>()
            assertTrue(f.transport.downloadScp("scp -f test.jpg", destination, result))
            result.await()
            assertNull(result.failure)
            assertArrayEquals("abc".toByteArray(), destination.readBytes())
            val channel = f.backend.channels.last()
            assertEquals("scp -f test.jpg", channel.command)
            assertArrayEquals(byteArrayOf(0, 0, 0), channel.inputBytes.toByteArray())
            assertTrue(channel.closed)
            assertTrue(f.backend.events.any { it.name == "exec.input.close" })
            assertTrue(f.backend.events.any { it.name == "exec.output.close" })
        } finally { assertTrue(destination.delete()) }
    }

    @Test fun failedConnectionAtEveryResourceBoundaryClosesWhatWasEstablishedAndWipesAuthentication() {
        val stages = listOf("create", "session.connect", "session.clearAuthentication", "session.open",
            "shell.output", "shell.input", "shell.connect", "shell.write", "shell.flush")
        for (stage in stages) Fixture().use { f ->
            f.backend.failAt = stage
            val authentication = RecordingSshBackend.authentication()
            val result = Result<Void>()
            f.transport.connect(RecordingSshBackend.request(initial = "initial"), authentication, result)
            result.await()
            assertEquals(stage, SshTransport.Failure.CONNECTION, result.failure)
            assertFalse(f.transport.isConnected)
            assertFalse(f.transport.isConnecting)
            assertTrue(authentication.password.all { it == 0.toByte() })
            val names = f.backend.events.map { it.name }
            if (stage != "create") assertTrue(stage, "session.close" in names)
            if (f.backend.channels.isNotEmpty()) assertTrue(stage, "shell.close" in names)
            if (stage in listOf("shell.connect", "shell.write", "shell.flush")) assertTrue(stage, "shell.input.close" in names)
            assertTrue(f.backend.events.all { it.thread !== Thread.currentThread() })
        }
    }

    @Test fun writeFailureDiscardsQueuedInputAndNeverReplaysAfterReconnect() = Fixture().use { f ->
        f.connect()
        f.backend.blockAt = "shell.write"
        f.backend.failAt = "shell.write"
        val failed = Result<Void>()
        assertTrue(f.transport.send("failed", failed))
        assertTrue(f.backend.entered.await(10, TimeUnit.SECONDS))
        assertTrue(f.transport.executeShellCommand("discarded click"))
        f.backend.release.countDown()
        failed.await(); f.barrier()
        assertEquals(SshTransport.Failure.IO, failed.failure)
        assertFalse(f.transport.isConnected)
        assertTrue(f.backend.writes.isEmpty())
        assertTrue(f.backend.events.any { it.name == "session.close" })
        f.backend.channels.clear(); f.backend.failAt = null; f.backend.blockAt = null
        f.connect()
        assertTrue(f.transport.executeShellCommand("fresh")); f.barrier()
        assertEquals(listOf("fresh\r\n"), f.backend.writes)
    }

    @Test fun execFailureClosesTemporaryAndEstablishedResourcesWithoutProviderDetails() {
        for (stage in listOf("exec.command", "exec.output", "exec.connect", "exec.isClosed", "exec.close")) Fixture().use { f ->
            f.connect(); f.backend.failAt = stage
            val result = Result<String>()
            assertTrue(f.transport.exec("NOT_REAL_typed_text", result))
            result.await()
            assertEquals(SshTransport.Failure.IO, result.failure)
            assertNull(result.value)
            assertFalse(f.transport.isConnected)
            assertTrue(f.backend.events.any { it.name == "exec.close" })
            assertTrue(f.backend.events.any { it.name == "session.close" })
        }
    }

    @Test fun scpMalformedHeadersErrorsAndTruncationFailClosedWithoutHanging() {
        for (source in listOf("", "\u0001NOT_REAL_error\n", "C0644 x test.jpg\n", "C0644 3 test.jpg\na", "C0644 0 test.jpg\n\u0002", "C" + "x".repeat(1025))) Fixture().use { f ->
            val destination = Files.createTempFile("xmouse-synthetic-failed-scp", ".jpg").toFile()
            try {
                f.connect(); f.backend.scp = source.toByteArray()
                val result = Result<Void>()
                assertTrue(f.transport.downloadScp("scp -f test.jpg", destination, result))
                result.await()
                assertEquals(SshTransport.Failure.IO, result.failure)
                assertFalse(f.transport.isConnected)
                assertTrue(f.backend.events.any { it.name == "exec.close" })
                assertTrue(f.backend.events.any { it.name == "exec.input.close" })
                assertTrue(f.backend.events.any { it.name == "exec.output.close" })
                assertTrue(f.backend.events.any { it.name == "session.close" })
            } finally { assertTrue(destination.delete()) }
        }
    }

    @Test fun disconnectAttemptsAllResourcesEvenWhenCloseFailsAndIsIdempotent() {
        for (stage in listOf("shell.input.close", "shell.close", "session.close")) Fixture().use { f ->
            f.connect(); f.backend.failAt = stage
            val result = Result<Void>()
            f.transport.disconnect(result)
            result.await()
            assertEquals(SshTransport.Failure.IO, result.failure)
            assertEquals(setOf("shell.input.close", "shell.close", "session.close"), f.backend.events.takeLast(3).map { it.name }.toSet())
            val eventCount = f.backend.events.size
            val again = Result<Void>(); f.transport.disconnect(again); again.await()
            assertNull(again.failure)
            assertEquals(eventCount, f.backend.events.size)
        }
    }

    @Test fun terminalCloseDuringHandshakeDoesNotBlockCallerOrPublishLateConnection() = Fixture().use { f ->
        f.backend.blockAt = "session.connect"
        val authentication = RecordingSshBackend.authentication()
        val result = Result<Void>()
        f.transport.connect(RecordingSshBackend.request(), authentication, result)
        assertTrue(f.backend.entered.await(10, TimeUnit.SECONDS))
        f.transport.close() // Must return despite the recording backend remaining blocked.
        assertFalse(f.transport.isConnected)
        f.backend.release.countDown()
        assertTrue(f.worker.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(1L, result.done.count)
        assertTrue(authentication.password.all { it == 0.toByte() })
        assertTrue(f.backend.events.any { it.name == "session.close" })
        val rejected = RecordingSshBackend.authentication()
        val rejectedResult = Result<Void>()
        f.transport.connect(RecordingSshBackend.request(), rejected, rejectedResult)
        rejectedResult.await()
        assertEquals(SshTransport.Failure.CLOSED, rejectedResult.failure)
        assertTrue(rejected.password.all { it == 0.toByte() })
    }

    @Test fun disconnectInvalidatesQueuedWritesBeforeTheyCanReachNetwork() = Fixture().use { f ->
        f.connect()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        f.worker.execute { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        assertTrue(f.transport.executeShellCommand("discard before disconnect"))
        val result = Result<Void>(); f.transport.disconnect(result)
        assertFalse(f.transport.executeShellCommand("after disconnect"))
        release.countDown(); result.await()
        assertTrue(f.backend.writes.isEmpty())
    }

    @Test fun productionJschAdapterRequiresInjectedEndpointPolicyAndKeepsAuthChoices() {
        var configured: com.jcraft.jsch.Session? = null
        var intended: com.stripe1.xmouse.connection.Endpoint? = null
        val backend = JschSshBackend { _, session, endpoint -> configured = session; intended = endpoint }
        val request = RecordingSshBackend.request()
        val authentication = RecordingSshBackend.authentication()
        backend.create(request, authentication).useSession {
            assertSame(request.endpoint, intended)
            val created = requireNotNull(configured)
            assertEquals("192.0.2.10", created.host)
            assertEquals(2222, created.port)
            assertEquals("password,keyboard-interactive", created.getConfig("PreferredAuthentications"))
            assertEquals("ask", created.getConfig("StrictHostKeyChecking"))
        }
        authentication.close()
    }

    @Test fun productionJschAdapterRejectsMalformedKeyThroughBoundedFinalHandoffWithoutConnecting() {
        val authentication = RecordingSshBackend.authentication(true, BoundedKeyFixtures.pem("RSA PRIVATE KEY", BoundedKeyFixtures.malformedDer()))
        val backend = JschSshBackend { _, _, _ -> }
        try {
            assertThrows(PrivateKeyImportException::class.java) { backend.create(RecordingSshBackend.request(), authentication) }
        } finally { authentication.close() }
    }

    @Test fun productionJschAdapterPublishesCanonicalKeyOnlyAndClearsIdentity() {
        val bytes = requireNotNull(javaClass.classLoader!!.getResourceAsStream("ssh-crypto/ecdsa-p256.pem")).use { it.readBytes() }
        val authentication = RecordingSshBackend.authentication(true, bytes)
        var jsch: JSch? = null
        var session: com.jcraft.jsch.Session? = null
        val backend = JschSshBackend { client, created, _ -> jsch = client; session = created }
        try {
            backend.create(RecordingSshBackend.request(), authentication).useSession { resource ->
                assertEquals("publickey", session!!.getConfig("PreferredAuthentications"))
                val client = requireNotNull(jsch)
                assertEquals(1, client.identityRepository.identities.size)
                resource.clearAuthentication()
                assertTrue(client.identityRepository.identities.isEmpty())
            }
        } finally { authentication.close() }
    }

    private fun SshBackend.Session.useSession(action: (SshBackend.Session) -> Unit) {
        try { action(this) } finally { close() }
    }
}
