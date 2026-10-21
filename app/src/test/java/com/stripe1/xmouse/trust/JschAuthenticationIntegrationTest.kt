package com.stripe1.xmouse.trust

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair as JschKeyPair
import com.stripe1.xmouse.connection.Endpoint
import com.stripe1.xmouse.secrets.AuthenticationMaterial
import com.stripe1.xmouse.secrets.CredentialHost
import com.stripe1.xmouse.transport.JschSshBackend
import com.stripe1.xmouse.transport.SshConnectionRequest
import com.stripe1.xmouse.transport.SshTransport
import org.apache.sshd.common.SshConstants
import org.apache.sshd.common.Service
import org.apache.sshd.common.channel.Channel
import org.apache.sshd.common.channel.ChannelListener
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.session.Session
import org.apache.sshd.common.util.buffer.Buffer
import org.apache.sshd.common.util.buffer.ByteArrayBuffer
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.session.ServerConnectionServiceFactory
import org.apache.sshd.server.session.ServerUserAuthService
import org.apache.sshd.server.session.ServerUserAuthServiceFactory
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Actual production transport/JSch against an in-process synthetic SSHD server; no user host or process. */
class JschAuthenticationIntegrationTest {
    private data class AuthRequest(
        val method: String,
        val signed: Boolean = false,
        val algorithm: String? = null,
        val signatureAlgorithm: String? = null,
        val publicKeyAlgorithm: String? = null,
    )

    private class SyntheticServer(authorizedKeys: Set<String>) : AutoCloseable {
        val authenticationRequests = AtomicInteger()
        val passwordAuthenticatorCalls = AtomicInteger()
        val publicKeyAuthenticatorCalls = AtomicInteger()
        val channelRequests = AtomicInteger()
        val commandRequests = AtomicInteger()
        val inputReceived = CountDownLatch(1)
        val authRequests = CopyOnWriteArrayList<AuthRequest>()
        val allowedSignatureAlgorithms = SERVER_SIGNATURES
        val ssh = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = KeyPairProvider.wrap(newHostKey())
            // This is the server-side signature allowlist: ssh-rsa (SHA-1), DSA, and other algorithms
            // are not available for user authentication. RSA-SHA2 remains available.
            setSignatureFactoriesNames(SERVER_SIGNATURES)
            serviceFactories = listOf(object : ServerUserAuthServiceFactory() {
                override fun create(session: Session): Service = object : ServerUserAuthService(session) {
                    override fun process(cmd: Int, buffer: Buffer) {
                        if (cmd == SshConstants.SSH_MSG_USERAUTH_REQUEST.toInt()) {
                            authenticationRequests.incrementAndGet()
                            authRequests += parseAuthRequest(buffer)
                        }
                        super.process(cmd, buffer)
                    }
                }
            }, ServerConnectionServiceFactory.INSTANCE)
            setPasswordAuthenticator { user, password, _ ->
                passwordAuthenticatorCalls.incrementAndGet()
                user == TEST_USER && password == VALID_PASSWORD
            }
            setPublickeyAuthenticator { user, key, _ ->
                publicKeyAuthenticatorCalls.incrementAndGet()
                user == TEST_USER && PublicKeyEntry.toString(key) in authorizedKeys
            }
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
                    Thread({ try { if (input?.read() != -1) inputReceived.countDown() } catch (_: IOException) { } },
                        "synthetic-auth-input").apply { isDaemon = true; start() }
                }
                override fun destroy(channel: ChannelSession) { input?.close() }
            }
        }

        override fun close() { ssh.stop(true) }

        companion object {
            private val SERVER_SIGNATURES = listOf("rsa-sha2-256", "rsa-sha2-512", "ecdsa-sha2-nistp256")

            private fun newHostKey(): java.security.KeyPair = java.security.KeyPairGenerator.getInstance("EC").apply {
                initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()

            private fun parseAuthRequest(source: Buffer): AuthRequest {
                // Parse a copy so instrumentation does not change the buffer consumed by MINA. The
                // password field is deliberately not copied or retained.
                val packet = ByteArrayBuffer(source.array().copyOfRange(source.rpos(), source.wpos()))
                packet.getString() // username; not retained
                packet.getString() // service
                val method = packet.getString()
                if (method != "publickey") return AuthRequest(method)
                val signed = packet.getBoolean()
                val algorithm = packet.getString()
                val key = ByteArrayBuffer(packet.getBytes())
                val keyAlgorithm = key.getString()
                val signatureAlgorithm = if (signed) ByteArrayBuffer(packet.getBytes()).getString() else null
                return AuthRequest(method, signed, algorithm, signatureAlgorithm, keyAlgorithm)
            }
        }
    }

    private data class ClientKey(val privateBytes: ByteArray, val publicEntry: String, val publicKeyAlgorithm: String)

    private class Result : SshTransport.Callback<Void> {
        val done = CountDownLatch(1)
        var failure: SshTransport.Failure? = null
        override fun success(value: Void?) { done.countDown() }
        override fun failure(failure: SshTransport.Failure) { this.failure = failure; done.countDown() }
        override fun mismatch(mismatch: HostKeyMismatch) { }
        fun await() { assertTrue("SSH operation did not finish", done.await(10, TimeUnit.SECONDS)) }
    }

    private class Fixture : AutoCloseable, HostTrustRequests.Listener {
        val directory = Files.createTempDirectory("xmouse-ssh-auth-").toFile()
        val repository = HostKeyRepository(directory, TrustFileIo())
        val prompts = CopyOnWriteArrayList<HostTrustRequests.Request>()
        val requests = HostTrustRequests(repository, Executor { it.run() }, 5000).also { it.attach(this) }
        val worker = SshTransport.newWorker()
        val transport = SshTransport(JschSshBackend(HostTrustPolicy(requests)), worker)
        override fun show(request: HostTrustRequests.Request) { prompts += request }
        override fun dismiss(request: HostTrustRequests.Request) { }

        fun prompt(): HostTrustRequests.Request {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (System.nanoTime() < until) prompts.firstOrNull()?.let { return it } ?: Thread.sleep(5)
            throw AssertionError("SSH host-trust prompt was not delivered")
        }

        fun connect(server: SyntheticServer, authentication: AuthenticationMaterial): Result {
            val endpoint = Endpoint.manual("desktop.local", server.ssh.port)
            return Result().also {
                transport.connect(
                    SshConnectionRequest(endpoint, "127.0.0.1", "", "synthetic initial command"),
                    authentication,
                    it,
                )
            }
        }

        fun disconnect() {
            val result = Result()
            transport.disconnect(result)
            result.await()
            assertNull(result.failure)
        }

        override fun close() {
            requests.detach()
            transport.close()
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS))
            directory.deleteRecursively()
        }
    }

    @Test fun actualPasswordAuthenticationSucceedsAndWrongPasswordPreservesLogicalHostTrust() {
        SyntheticServer(emptySet()).use { server -> Fixture().use { fixture ->
            val sourcePassword = VALID_PASSWORD.toByteArray()
            val stableHost = host(server)
            val result = fixture.connect(server, passwordMaterial(server, sourcePassword, stableHost))
            val prompt = fixture.prompt()
            assertEquals(Endpoint.manual("desktop.local", server.ssh.port).identity, prompt.endpoint.identity)
            assertNull(fixture.repository.find(prompt.endpoint.identity))
            prompt.accept()
            result.await()
            assertNull("valid selected password did not authenticate", result.failure)
            assertTrue(server.inputReceived.await(5, TimeUnit.SECONDS))
            assertEquals(1, server.passwordAuthenticatorCalls.get())
            assertEquals(1, server.channelRequests.get())
            assertEquals(1, server.commandRequests.get())
            assertTrue(server.authRequests.any { it.method == "password" })
            assertFalse(server.authRequests.any { it.method == "publickey" })

            val approved = requireNotNull(fixture.repository.find(prompt.endpoint.identity))
            val trustSnapshot = File(fixture.directory, HostKeyRepository.FILE).readBytes()
            fixture.disconnect()

            val requestsBefore = server.authRequests.size
            val passwordCallsBefore = server.passwordAuthenticatorCalls.get()
            val wrong = passwordMaterial(server, "NOT_REAL_wrong_password".toByteArray(), stableHost)
            val wrongResult = fixture.connect(server, wrong)
            wrongResult.await()
            assertEquals(SshTransport.Failure.CONNECTION, wrongResult.failure)
            assertFalse(fixture.transport.isConnected)
            val failedRequests = server.authRequests.drop(requestsBefore)
            assertTrue(failedRequests.any { it.method == "password" })
            assertFalse(failedRequests.any { it.method == "publickey" })
            assertTrue(server.passwordAuthenticatorCalls.get() > passwordCallsBefore)
            assertEquals(1, server.channelRequests.get())
            assertEquals(1, server.commandRequests.get())
            assertEquals(false, wrong.useKey)
            assertSame(stableHost, wrong.host)
            assertArrayEquals(sourcePassword, VALID_PASSWORD.toByteArray())
            assertTrue(approved.matches(requireNotNull(fixture.repository.find(prompt.endpoint.identity))))
            assertArrayEquals(trustSnapshot, File(fixture.directory, HostKeyRepository.FILE).readBytes())
            assertTrue(wrong.password.all { it == 0.toByte() })
        } }
    }

    @Test fun encryptedRsaAuthenticatesWithSha2WhenServerHasNoSha1RsaAndSelectedKeyNeverFallsBack() {
        val phrase = FIXTURE_PASSPHRASE.toByteArray()
        val rsa = generatedRsa(phrase)
        val notAuthorized = generatedEcdsa(byteArrayOf())
        SyntheticServer(setOf(rsa.publicEntry)).use { server -> Fixture().use { fixture ->
            establishPasswordTrust(fixture, server)
            fixture.disconnect()
            val endpoint = Endpoint.manual("desktop.local", server.ssh.port)
            val trusted = requireNotNull(fixture.repository.find(endpoint.identity))
            val trustSnapshot = File(fixture.directory, HostKeyRepository.FILE).readBytes()
            val sourceSnapshot = rsa.privateBytes.copyOf()
            assertTrue(server.allowedSignatureAlgorithms.contains("rsa-sha2-256"))
            assertTrue(server.allowedSignatureAlgorithms.contains("rsa-sha2-512"))
            assertFalse("server SHA-1 RSA signatures are disabled", server.allowedSignatureAlgorithms.contains("ssh-rsa"))

            val rsaStart = server.authRequests.size
            val rsaAuth = keyMaterial(server, rsa.privateBytes, phrase)
            val rsaResult = fixture.connect(server, rsaAuth)
            rsaResult.await()
            assertNull("encrypted 2048-bit RSA key did not authenticate", rsaResult.failure)
            val signed = server.authRequests.drop(rsaStart).filter { it.method == "publickey" && it.signed }
            assertEquals("server must observe one completed signed public-key request", 1, signed.size)
            assertTrue("RSA user-auth must use SHA-2, not ssh-rsa/SHA-1: $signed",
                signed.single().algorithm in setOf("rsa-sha2-256", "rsa-sha2-512"))
            assertEquals(signed.single().algorithm, signed.single().signatureAlgorithm)
            // The key blob remains ssh-rsa; the independently recorded request/signature algorithm proves
            // that the signature itself used RSA-SHA2.
            assertEquals("ssh-rsa", signed.single().publicKeyAlgorithm)
            assertFalse(server.authRequests.drop(rsaStart).any { it.method == "password" || it.method == "keyboard-interactive" })
            assertTrue(server.inputReceived.await(5, TimeUnit.SECONDS))
            assertTrue(rsa.privateBytes.contentEquals(sourceSnapshot))
            assertTrue(trusted.matches(requireNotNull(fixture.repository.find(endpoint.identity))))
            assertArrayEquals(trustSnapshot, File(fixture.directory, HostKeyRepository.FILE).readBytes())
            assertTrue(rsaAuth.privateKey.all { it == 0.toByte() })
            val establishedChannels = server.channelRequests.get()
            val establishedCommands = server.commandRequests.get()
            fixture.disconnect()

            val beforeWrongPhrase = server.authenticationRequests.get()
            val wrongPhraseAuthStart = server.authRequests.size
            val passwordCalls = server.passwordAuthenticatorCalls.get()
            val publicKeyCalls = server.publicKeyAuthenticatorCalls.get()
            val wrongPhrase = keyMaterial(server, rsa.privateBytes, "NOT_REAL_wrong_phrase".toByteArray(), password = VALID_PASSWORD.toByteArray())
            val wrongPhraseResult = fixture.connect(server, wrongPhrase)
            wrongPhraseResult.await()
            assertEquals(SshTransport.Failure.PRIVATE_KEY, wrongPhraseResult.failure)
            assertEquals("bad local passphrase must fail before any SSH auth packet", beforeWrongPhrase, server.authenticationRequests.get())
            assertEquals(passwordCalls, server.passwordAuthenticatorCalls.get())
            assertEquals(publicKeyCalls, server.publicKeyAuthenticatorCalls.get())
            assertTrue(server.authRequests.drop(wrongPhraseAuthStart).none {
                it.method == "password" || it.method == "keyboard-interactive"
            })
            assertTrue(trusted.matches(requireNotNull(fixture.repository.find(endpoint.identity))))
            assertArrayEquals(trustSnapshot, File(fixture.directory, HostKeyRepository.FILE).readBytes())
            assertTrue(rsa.privateBytes.contentEquals(sourceSnapshot))
            assertTrue(wrongPhrase.privateKey.all { it == 0.toByte() })
            assertTrue(wrongPhrase.passphrase.all { it == 0.toByte() })

            val beforeRejectedKey = server.authRequests.size
            val rejection = keyMaterial(server, notAuthorized.privateBytes, byteArrayOf(), password = VALID_PASSWORD.toByteArray())
            val rejectionResult = fixture.connect(server, rejection)
            rejectionResult.await()
            assertEquals(SshTransport.Failure.CONNECTION, rejectionResult.failure)
            val rejectedRequests = server.authRequests.drop(beforeRejectedKey)
            assertTrue(rejectedRequests.any { it.method == "publickey" })
            assertFalse("selected-key failure must not fall back to password", rejectedRequests.any {
                it.method == "password" || it.method == "keyboard-interactive"
            })
            assertEquals(passwordCalls, server.passwordAuthenticatorCalls.get())
            assertEquals(establishedChannels, server.channelRequests.get())
            assertEquals(establishedCommands, server.commandRequests.get())
            assertTrue(trusted.matches(requireNotNull(fixture.repository.find(endpoint.identity))))
            assertArrayEquals(trustSnapshot, File(fixture.directory, HostKeyRepository.FILE).readBytes())
            sourceSnapshot.fill(0)
            phrase.fill(0)
            rsa.privateBytes.fill(0)
            notAuthorized.privateBytes.fill(0)
        } }
    }

    @Test fun encryptedEcdsaAuthenticatesAndWrongPassphraseFailsBeforeServerAuthentication() {
        val source = resource("ssh-crypto/ecdsa-p256-encrypted.pem")
        val phrase = FIXTURE_PASSPHRASE.toByteArray()
        val ecdsa = loadClientKey(source, phrase)
        SyntheticServer(setOf(ecdsa.publicEntry)).use { server -> Fixture().use { fixture ->
            establishPasswordTrust(fixture, server)
            fixture.disconnect()
            val endpoint = Endpoint.manual("desktop.local", server.ssh.port)
            val trusted = requireNotNull(fixture.repository.find(endpoint.identity))
            val trustSnapshot = File(fixture.directory, HostKeyRepository.FILE).readBytes()
            val sourceSnapshot = source.copyOf()

            val start = server.authRequests.size
            val auth = keyMaterial(server, source, phrase)
            val result = fixture.connect(server, auth)
            result.await()
            assertNull("encrypted ECDSA P-256 key did not authenticate", result.failure)
            val signed = server.authRequests.drop(start).filter { it.method == "publickey" && it.signed }
            assertEquals(1, signed.size)
            assertEquals("ecdsa-sha2-nistp256", signed.single().algorithm)
            assertEquals(signed.single().algorithm, signed.single().signatureAlgorithm)
            assertEquals("ecdsa-sha2-nistp256", signed.single().publicKeyAlgorithm)
            assertFalse(server.authRequests.drop(start).any { it.method == "password" || it.method == "keyboard-interactive" })
            assertTrue(source.contentEquals(sourceSnapshot))
            assertTrue(trusted.matches(requireNotNull(fixture.repository.find(endpoint.identity))))
            assertArrayEquals(trustSnapshot, File(fixture.directory, HostKeyRepository.FILE).readBytes())
            assertTrue(auth.privateKey.all { it == 0.toByte() })
            val establishedChannels = server.channelRequests.get()
            val establishedCommands = server.commandRequests.get()
            fixture.disconnect()

            val before = server.authenticationRequests.get()
            val wrongPhraseAuthStart = server.authRequests.size
            val passwordCalls = server.passwordAuthenticatorCalls.get()
            val wrong = keyMaterial(server, source, "NOT_REAL_wrong_phrase".toByteArray(), password = VALID_PASSWORD.toByteArray())
            val failed = fixture.connect(server, wrong)
            failed.await()
            assertEquals(SshTransport.Failure.PRIVATE_KEY, failed.failure)
            assertEquals(before, server.authenticationRequests.get())
            assertEquals(passwordCalls, server.passwordAuthenticatorCalls.get())
            assertEquals(establishedChannels, server.channelRequests.get())
            assertEquals(establishedCommands, server.commandRequests.get())
            assertTrue(server.authRequests.drop(wrongPhraseAuthStart).none {
                it.method == "password" || it.method == "keyboard-interactive"
            })
            assertTrue(trusted.matches(requireNotNull(fixture.repository.find(endpoint.identity))))
            assertArrayEquals(trustSnapshot, File(fixture.directory, HostKeyRepository.FILE).readBytes())
            assertTrue(source.contentEquals(sourceSnapshot))
            assertTrue(wrong.privateKey.all { it == 0.toByte() })
            assertTrue(wrong.passphrase.all { it == 0.toByte() })
            sourceSnapshot.fill(0)
            source.fill(0)
            phrase.fill(0)
            ecdsa.privateBytes.fill(0)
        } }
    }

    private fun establishPasswordTrust(fixture: Fixture, server: SyntheticServer) {
        val result = fixture.connect(server, passwordMaterial(server))
        val prompt = fixture.prompt()
        assertNull(fixture.repository.find(prompt.endpoint.identity))
        prompt.accept()
        result.await()
        assertNull(result.failure)
        assertTrue(server.inputReceived.await(5, TimeUnit.SECONDS))
        assertTrue(fixture.repository.find(prompt.endpoint.identity)!!.matches(prompt.key))
    }

    private fun passwordMaterial(
        server: SyntheticServer,
        password: ByteArray = VALID_PASSWORD.toByteArray(),
        selectedHost: CredentialHost = host(server),
    ) = AuthenticationMaterial(selectedHost, false, password.copyOf(), byteArrayOf(), byteArrayOf())

    private fun keyMaterial(
        server: SyntheticServer,
        key: ByteArray,
        phrase: ByteArray,
        password: ByteArray = VALID_PASSWORD.toByteArray(),
        selectedHost: CredentialHost = host(server),
    ) = AuthenticationMaterial(selectedHost, true, password.copyOf(), phrase.copyOf(), key.copyOf())

    private fun host(server: SyntheticServer) = CredentialHost(
        41L, "NOT_REAL synthetic host", "desktop.local", TEST_USER, server.ssh.port.toString(),
        "synthetic-password-reference", false,
    )

    private fun generatedRsa(phrase: ByteArray): ClientKey {
        val key = JschKeyPair.genKeyPair(JSch(), JschKeyPair.RSA, 2048)
        try {
            assertEquals(2048, key.keySize)
            return writeClientKey(key, phrase)
        } finally { key.dispose() }
    }

    private fun generatedEcdsa(phrase: ByteArray): ClientKey {
        val key = JschKeyPair.genKeyPair(JSch(), JschKeyPair.ECDSA, 256)
        try { return writeClientKey(key, phrase) } finally { key.dispose() }
    }

    private fun loadClientKey(privateBytes: ByteArray, phrase: ByteArray): ClientKey {
        val normalized = com.stripe1.xmouse.secrets.BoundedSshKey.normalize(privateBytes, phrase)
        val key = try { JschKeyPair.load(JSch(), normalized, null) } catch (failure: Exception) {
            normalized.fill(0)
            throw failure
        }
        try { return clientKey(key, privateBytes.copyOf()) }
        finally { key.dispose(); normalized.fill(0) }
    }

    private fun writeClientKey(key: JschKeyPair, phrase: ByteArray): ClientKey {
        val bytes = ByteArrayOutputStream().also {
            if (phrase.isEmpty()) key.writePrivateKey(it) else key.writePrivateKey(it, phrase)
        }.toByteArray()
        return clientKey(key, bytes)
    }

    private fun clientKey(key: JschKeyPair, privateBytes: ByteArray): ClientKey {
        val blob = requireNotNull(key.publicKeyBlob).copyOf()
        val algorithm = ByteArrayBuffer(blob).getString()
        val publicEntry = "$algorithm ${Base64.getEncoder().encodeToString(blob)}"
        blob.fill(0)
        return ClientKey(privateBytes, publicEntry, algorithm)
    }

    private fun resource(path: String): ByteArray = requireNotNull(javaClass.classLoader!!.getResourceAsStream(path)).use { it.readBytes() }

    companion object {
        private const val TEST_USER = "NOT_REAL_user"
        private const val VALID_PASSWORD = "NOT_REAL_password"
        private const val FIXTURE_PASSPHRASE = "NOT_REAL_XMOUSE_FIXTURE"
    }
}
