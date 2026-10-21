package com.stripe1.xmouse

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stripe1.xmouse.transport.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.*

/** Actual main Looper + production transport; short injected budgets, synthetic blocking resources. */
@RunWith(AndroidJUnit4::class)
class SshDeadlineAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun limits() = SshLimits(200, 350, 200, 350, 250, 250, 450, 450, 150, 150, 2, 50, 150, 4096)
    private class Result<T> : SshTransport.Callback<T> {
        val done = CountDownLatch(1)
        var failure: SshTransport.Failure? = null
        override fun success(value: T?) { done.countDown() }
        override fun failure(failure: SshTransport.Failure) { this.failure = failure; done.countDown() }
        fun await() { assertTrue(done.await(4, TimeUnit.SECONDS)) }
    }
    private fun mainIsLive() {
        val done = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post { done.countDown() }
        assertTrue("main blocked by network wait/close", done.await(500, TimeUnit.MILLISECONDS))
    }
    private fun eventually(assertion: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!assertion() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(assertion())
    }

    @Test fun stalledHandshakeTimesOutWhileMainAndCoordinatorRemainLiveAndCleanupDoesNotWaitForProvider() {
        val backend = RecordingSshBackend().apply { blockAt = "session.connect"; ignoreInterrupts = true }
        val worker = SshTransport.newWorker()
        val transport = SshTransport(backend, worker, limits(), SshRuntime.SHARED)
        val result = Result<Void>()
        val uiDelivered = CountDownLatch(1)
        val main = Handler(Looper.getMainLooper())
        val callback = object : SshTransport.Callback<Void> {
            override fun success(value: Void?) { fail("stalled connection cannot succeed") }
            override fun failure(failure: SshTransport.Failure) {
                result.failure(failure)
                main.post { assertSame(Looper.getMainLooper().thread, Thread.currentThread()); uiDelivered.countDown() }
            }
        }
        try {
            instrumentation.runOnMainSync { transport.connect(RecordingSshBackend.request(initial = "must not send"), RecordingSshBackend.authentication(), callback) }
            assertTrue(backend.entered.await(2, TimeUnit.SECONDS)); mainIsLive()
            result.await(); assertEquals(SshTransport.Failure.CONNECTION_TIMEOUT, result.failure)
            assertTrue(uiDelivered.await(1, TimeUnit.SECONDS))
            assertFalse(transport.isConnected); assertFalse(transport.isConnecting)
            worker.submit {}.get(1, TimeUnit.SECONDS)
            eventually { backend.events.any { it.name == "session.close" } }
            mainIsLive()
            assertTrue(backend.writes.isEmpty())
            assertTrue(backend.events.all { it.thread !== Looper.getMainLooper().thread })
        } finally {
            backend.release.countDown(); instrumentation.runOnMainSync { transport.close() }
            assertTrue(worker.awaitTermination(4, TimeUnit.SECONDS))
        }
    }

    @Test fun blockedWriteAndCloseHaveFiniteOutcomesDropQueuedInputAndNeverBlockAndroidMain() {
        for (closing in listOf(false, true)) {
            val backend = RecordingSshBackend()
            val worker = SshTransport.newWorker()
            val transport = SshTransport(backend, worker, limits(), SshRuntime.SHARED)
            val losses = LinkedBlockingQueue<SshTransport.Failure>()
            transport.setLossListener { losses.add(it) }
            try {
                val connected = Result<Void>()
                instrumentation.runOnMainSync { transport.connect(RecordingSshBackend.request(), RecordingSshBackend.authentication(), connected) }
                connected.await(); assertNull(connected.failure)
                backend.blockAt = if (closing) "shell.input.close" else "shell.write"
                backend.ignoreInterrupts = true
                val result = Result<Void>()
                instrumentation.runOnMainSync {
                    if (closing) transport.disconnect(result) else {
                        assertTrue(transport.send("blocked", result))
                        assertTrue(transport.executeShellCommand("unsent input"))
                    }
                }
                assertTrue(backend.entered.await(2, TimeUnit.SECONDS)); mainIsLive()
                result.await()
                assertEquals(if (closing) SshTransport.Failure.CLEANUP_TIMEOUT else SshTransport.Failure.WRITE_TIMEOUT, result.failure)
                assertFalse(transport.isConnected)
                worker.submit {}.get(1, TimeUnit.SECONDS)
                eventually { backend.events.any { it.name == "session.close" } }
                if (closing) assertTrue(losses.isEmpty()) else assertEquals(SshTransport.Failure.WRITE_TIMEOUT, losses.poll(1, TimeUnit.SECONDS))
                assertFalse(backend.writes.any { "unsent" in it })
                mainIsLive()
                assertTrue(backend.events.all { it.thread !== Looper.getMainLooper().thread })
            } finally {
                backend.release.countDown(); instrumentation.runOnMainSync { transport.close() }
                assertTrue(worker.awaitTermination(4, TimeUnit.SECONDS))
            }
        }
    }
}
