package net.droopia.xmouse_ng

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.droopia.xmouse_ng.connection.ConnectionState
import net.droopia.xmouse_ng.session.*
import net.droopia.xmouse_ng.secrets.AndroidSecretFileIo
import net.droopia.xmouse_ng.transport.*
import net.droopia.xmouse_ng.trust.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/** Actual Android main callers/observers, production owner + adapter + transport, synthetic backend. */
@RunWith(AndroidJUnit4::class)
class SessionControllerAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun eventually(assertion: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!assertion() && System.nanoTime() < end) Thread.sleep(5)
        assertTrue(assertion())
    }
    private fun mainIsLive() {
        val live = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post { live.countDown() }
        assertTrue("session work blocked main", live.await(500, TimeUnit.MILLISECONDS))
    }
    private class Fixture(stall: Boolean) : AutoCloseable {
        val directory = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "session-${UUID.randomUUID()}")
        val credentialsWorker = Executors.newSingleThreadExecutor { Thread(it, "synthetic-credential-worker") }
        val reads = AtomicInteger()
        val backend = RecordingSshBackend().apply {
            if (stall) { blockAt = "session.connect"; ignoreInterrupts = true }
        }
        val interactions = SessionInteractions(Executor { Handler(Looper.getMainLooper()).post(it) })
        val limits = SshLimits(200, 350, 200, 600, 250, 250, 450, 450, 150, 150, 2, 50, 150, 4096)
        val provider = SessionCredentialProvider { selection, _, current, callback -> credentialsWorker.execute {
            assertNotSame(Looper.getMainLooper().thread, Thread.currentThread())
            reads.incrementAndGet()
            if (current.asBoolean) callback.complete(SessionFixtures.material(SessionFixtures.host(selection.host.host!!,
                selection.host.id, selection.host.passwordReference!!, selection.host.port!!.toInt())), null)
            else callback.complete(null, null)
        } }
        val factory = SshSessionAttemptFactory(provider, HostKeyRepository(directory, AndroidSecretFileIo), interactions,
            limits, SshRuntime.SHARED, 2000, 1000, { _, _ -> backend })
        val controller = SessionController(factory)
        override fun close() {
            backend.release.countDown()
            controller.close()
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while ((!controller.current.closed || controller.current.connection.phase == ConnectionState.Phase.CLOSING) && System.nanoTime() < end) Thread.sleep(5)
            check(controller.current.closed)
            credentialsWorker.shutdown(); check(credentialsWorker.awaitTermination(5, TimeUnit.SECONDS))
            directory.deleteRecursively()
        }
    }

    @Test fun stalledHandshakeAndCleanupDoNotBlockMainAndUnconfirmedCloseCannotActivateAnotherHost() {
        Fixture(true).use { f ->
            val main = Handler(Looper.getMainLooper())
            val observed = LinkedBlockingQueue<ConnectionState.Phase>()
            val observer = SessionController.Observer {
                assertSame(Looper.getMainLooper().thread, Thread.currentThread())
                observed.add(it.connection.phase)
            }
            val subscription = f.controller.observe(Executor { main.post(it) }, observer)
            instrumentation.runOnMainSync { f.controller.connectIfNeeded(SessionFixtures.host()) }
            assertTrue(f.backend.entered.await(3, TimeUnit.SECONDS)); mainIsLive()
            instrumentation.runOnMainSync { f.controller.connectIfNeeded(SessionFixtures.host("other.invalid", 2)) }
            mainIsLive()
            eventually { f.controller.current.cleanupUnconfirmed }
            assertEquals(1, f.reads.get()); assertFalse(f.controller.current.connection.isReady)
            eventually { observed.contains(ConnectionState.Phase.ERROR) }
            assertTrue(f.backend.events.all { it.thread !== Looper.getMainLooper().thread })
            subscription.close(); mainIsLive()
        }
    }

    @Test fun mainObserverDetachAndReattachKeepsOneSessionWhileExplicitDisconnectSuppressesLateAutoConnect() {
        Fixture(false).use { f ->
            val host = SessionFixtures.host()
            val main = Handler(Looper.getMainLooper())
            val ready = CountDownLatch(1)
            val observer = SessionController.Observer {
                assertSame(Looper.getMainLooper().thread, Thread.currentThread())
                if (it.connection.isReady) ready.countDown()
            }
            val first = f.controller.observe(Executor { main.post(it) }, observer)
            instrumentation.runOnMainSync { repeat(20) { f.controller.connectIfNeeded(host) } }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); first.close()
            val reattached = CountDownLatch(1)
            val secondObserver = SessionController.Observer {
                assertSame(Looper.getMainLooper().thread, Thread.currentThread())
                if (it.connection.isReady) reattached.countDown()
            }
            val second = f.controller.observe(Executor { main.post(it) }, secondObserver)
            assertTrue(reattached.await(2, TimeUnit.SECONDS))
            assertEquals(1, f.reads.get()); assertEquals(1, f.backend.events.count { it.name == "create" })
            assertEquals(0, f.backend.events.count { it.name == "session.close" })
            val late = f.controller.automaticConnectPermit()
            instrumentation.runOnMainSync { f.controller.disconnect(); f.controller.disconnect() }
            eventually { f.controller.current.connection.phase == ConnectionState.Phase.DISCONNECTED }
            instrumentation.runOnMainSync { f.controller.connectAutomatically(host, late) }
            instrumentation.waitForIdleSync(); mainIsLive()
            assertNull(f.controller.current.desiredHost); assertEquals(1, f.reads.get())
            second.close()
        }
    }
}
