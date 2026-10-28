package net.droopia.xmouse_ng

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jcraft.jsch.JSch
import net.droopia.xmouse_ng.connection.Endpoint
import net.droopia.xmouse_ng.secrets.AndroidSecretFileIo
import net.droopia.xmouse_ng.secrets.AuthenticationMaterial
import net.droopia.xmouse_ng.secrets.CredentialHost
import net.droopia.xmouse_ng.transport.JschSshBackend
import net.droopia.xmouse_ng.transport.SshConnectionRequest
import net.droopia.xmouse_ng.transport.SshTransport
import net.droopia.xmouse_ng.trust.HostKeyRepository
import net.droopia.xmouse_ng.trust.HostTrustPolicy
import net.droopia.xmouse_ng.trust.HostTrustRequests
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exercises the shipped JSch backend on-device without adding the JVM-only MINA server to the APK. */
@RunWith(AndroidJUnit4::class)
class SshAuthenticationAndroidTest {
    private val target get() = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private class Result : SshTransport.Callback<Void> {
        val done = CountDownLatch(1)
        var failure: SshTransport.Failure? = null
        override fun success(value: Void?) { done.countDown() }
        override fun failure(failure: SshTransport.Failure) { this.failure = failure; done.countDown() }
        override fun mismatch(mismatch: net.droopia.xmouse_ng.trust.HostKeyMismatch) { }
    }

    @Test fun actualAndroidBackendKeepsSelectedAuthModeAndRejectsWrongEncryptedPhraseBeforeConnection() {
        val directory = File(target.cacheDir, "ssh-auth-${java.util.UUID.randomUUID()}")
        val repository = HostKeyRepository(directory, AndroidSecretFileIo)
        val requests = HostTrustRequests(repository, Executor { it.run() }, 5000)
        val capturedJsch = AtomicReference<JSch>()
        val capturedSession = AtomicReference<com.jcraft.jsch.Session>()
        val backend = JschSshBackend(object : JschSshBackend.SessionPolicy {
            override fun configure(jsch: JSch, session: com.jcraft.jsch.Session, endpoint: Endpoint) {
                HostTrustPolicy(requests).configure(jsch, session, endpoint)
                capturedJsch.set(jsch)
                capturedSession.set(session)
            }
        })
        val endpoint = Endpoint.manual("ssh-authentication.invalid", 2222)
        val request = SshConnectionRequest(endpoint, "127.0.0.1", "", "NOT_REAL synthetic command")
        val host = CredentialHost(91L, "NOT_REAL synthetic host", "ssh-authentication.invalid", "NOT_REAL_user", "2222",
            "synthetic-password-reference", false)
        val configBefore = JSch.getConfig()
        val providersBefore = java.security.Security.getProviders().map { it.name to it.javaClass.name }
        val password = AuthenticationMaterial(host, false, "NOT_REAL_password".toByteArray(), byteArrayOf(), byteArrayOf())
        var passwordSession: net.droopia.xmouse_ng.transport.SshBackend.Session? = null
        var keySession: net.droopia.xmouse_ng.transport.SshBackend.Session? = null
        var transport: SshTransport? = null
        var worker: java.util.concurrent.ExecutorService? = null
        val source = instrumentation.context.assets.open("ssh-crypto/ecdsa-p256-encrypted.pem").use { it.readBytes() }
        val sourceSnapshot = source.copyOf()
        val correctPhrase = "NOT_REAL_XMOUSE_FIXTURE".toByteArray()
        try {
            passwordSession = backend.create(request, password)
            assertEquals("yes", capturedSession.get().getConfig("StrictHostKeyChecking"))
            assertEquals("password,keyboard-interactive", capturedSession.get().getConfig("PreferredAuthentications"))
            assertTrue(capturedJsch.get().identityRepository.identities.isEmpty())
            passwordSession.close()
            passwordSession = null
            password.close()

            val key = AuthenticationMaterial(host, true, byteArrayOf(), correctPhrase.copyOf(), source.copyOf())
            keySession = backend.create(request, key)
            assertEquals("yes", capturedSession.get().getConfig("StrictHostKeyChecking"))
            assertEquals("publickey", capturedSession.get().getConfig("PreferredAuthentications"))
            assertEquals(1, capturedJsch.get().identityRepository.identities.size)
            keySession.clearAuthentication()
            assertTrue(capturedJsch.get().identityRepository.identities.isEmpty())
            keySession.close()
            keySession = null
            key.close()

            val wrong = AuthenticationMaterial(
                host, true, "NOT_REAL_password".toByteArray(), "NOT_REAL_wrong_phrase".toByteArray(), source.copyOf(),
            )
            val result = Result()
            worker = SshTransport.newWorker()
            transport = SshTransport(backend, worker)
            transport.connect(request, wrong, result)
            assertTrue("invalid local passphrase should fail before an SSH socket attempt",
                result.done.await(5, TimeUnit.SECONDS))
            assertEquals(SshTransport.Failure.PRIVATE_KEY, result.failure)
            assertNull(repository.find(endpoint.identity))
            assertTrue(wrong.privateKey.all { it == 0.toByte() })
            assertTrue(wrong.passphrase.all { it == 0.toByte() })
            assertTrue(wrong.password.all { it == 0.toByte() })
            assertTrue("the retained source fixture is not rewritten", source.contentEquals(sourceSnapshot))
            assertEquals(91L, host.id)
            assertEquals("ssh-authentication.invalid", host.host)
            assertEquals(configBefore, JSch.getConfig())
            assertEquals(providersBefore, java.security.Security.getProviders().map { it.name to it.javaClass.name })
        } finally {
            password.close()
            passwordSession?.close()
            keySession?.close()
            source.fill(0)
            sourceSnapshot.fill(0)
            correctPhrase.fill(0)
            transport?.close()
            worker?.let { assertTrue(it.awaitTermination(10, TimeUnit.SECONDS)) }
            requests.detach()
            directory.deleteRecursively()
        }
    }
}
