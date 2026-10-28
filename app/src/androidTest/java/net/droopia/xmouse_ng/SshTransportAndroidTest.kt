package net.droopia.xmouse_ng

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.edit
import net.droopia.xmouse_ng.secrets.AndroidCredentials
import net.droopia.xmouse_ng.transport.RecordingSshBackend
import net.droopia.xmouse_ng.transport.SshTransport
import net.droopia.xmouse_ng.transport.TransportPresentation
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Calls the actual production boundary from Android main; all backend resources are synthetic. */
@RunWith(AndroidJUnit4::class)
class SshTransportAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val target get() = ApplicationProvider.getApplicationContext<Context>()
    private class Listener : TransportPresentation.Listener {
        val events = CopyOnWriteArrayList<String>()
        val threads = CopyOnWriteArrayList<Thread>()
        var done = CountDownLatch(1)
        fun event(value: String) { events.add(value); threads.add(Thread.currentThread()); done.countDown() }
        override fun connected(initialCommand: String?) = event("connected")
        override fun recentCommand(command: String) = event("recent")
        override fun execCompleted(command: String, output: String) = event("exec:$output")
        override fun execFailed(command: String, failure: SshTransport.Failure) = event("exec-failed")
        override fun scpCompleted() = event("scp")
        override fun disconnected() = event("disconnected")
        override fun failed(operation: SshTransport.Operation, failure: SshTransport.Failure) = event("failed")
        fun await() { assertTrue(done.await(10, TimeUnit.SECONDS)); done = CountDownLatch(1) }
    }

    @Test fun mainThreadConnectOrderedInputExecScpAndDisconnectDispatchAllIoToWorker() {
        val backend = RecordingSshBackend()
        val worker = SshTransport.newWorker()
        val transport = SshTransport(backend, worker)
        val main = Handler(Looper.getMainLooper())
        val presentation = TransportPresentation { main.post(it) }
        val listener = Listener()
        val file = File(target.cacheDir, "synthetic-scp-${UUID.randomUUID()}.jpg")
        try {
            instrumentation.runOnMainSync {
                presentation.attach(listener)
                transport.connect(RecordingSshBackend.request(initial = "synthetic init"), RecordingSshBackend.authentication(), presentation.connection("synthetic init"))
            }
            listener.await()
            instrumentation.runOnMainSync {
                assertTrue(transport.executeShellCommand("synthetic first"))
                assertTrue(transport.executeShellCommand("synthetic second"))
                assertTrue(transport.exec("synthetic requested exec", presentation.exec("synthetic requested exec")))
            }
            listener.await()
            instrumentation.runOnMainSync {
                assertTrue(transport.downloadScp("scp -f test.jpg", file, presentation.completion(SshTransport.Operation.SCP)))
            }
            listener.await()
            instrumentation.runOnMainSync { transport.disconnect(presentation.completion(SshTransport.Operation.DISCONNECT)) }
            listener.await()
            assertEquals(listOf("connected", "exec:err oneerr twoout oneout two", "scp", "disconnected"), listener.events)
            assertEquals(listOf("synthetic init\r\n", "synthetic first\r\n", "synthetic second\r\n"), backend.writes)
            assertArrayEquals("abc".toByteArray(), file.readBytes())
            assertTrue(backend.events.isNotEmpty())
            assertTrue(backend.events.all { it.thread !== Looper.getMainLooper().thread && it.thread.name == "xmouse-ssh-io" })
            assertTrue(listener.threads.all { it === Looper.getMainLooper().thread })
            for (operation in listOf("session.connect", "session.open", "shell.write", "shell.flush", "exec.command", "exec.read", "exec.write", "exec.close", "shell.input.close", "session.close")) {
                assertTrue("Missing recorded operation: $operation", backend.events.any { it.name == operation })
            }
        } finally {
            instrumentation.runOnMainSync { presentation.detach(); transport.close() }
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS))
            if (file.exists()) assertTrue(file.delete())
        }
    }

    @Test fun detachAndTerminalCloseDuringBlockedConnectNeverUpdateAnOldOrReplacementScreen() {
        val backend = RecordingSshBackend().apply { blockAt = "session.connect" }
        val worker = SshTransport.newWorker()
        val transport = SshTransport(backend, worker)
        val main = Handler(Looper.getMainLooper())
        val presentation = TransportPresentation { main.post(it) }
        val old = Listener(); val replacement = Listener()
        try {
            instrumentation.runOnMainSync {
                presentation.attach(old)
                transport.connect(RecordingSshBackend.request(initial = "must not be sent after close"), RecordingSshBackend.authentication(), presentation.connection("init"))
            }
            assertTrue(backend.entered.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                presentation.detach(); presentation.attach(replacement)
                transport.close() // Returns while connect remains blocked; close I/O cannot run on main.
            }
            backend.release.countDown()
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            assertTrue(old.events.isEmpty())
            assertTrue(replacement.events.isEmpty())
            assertTrue(backend.writes.isEmpty())
            assertTrue(backend.events.any { it.name == "session.close" })
            assertTrue(backend.events.all { it.thread !== Looper.getMainLooper().thread })
        } finally {
            backend.release.countDown()
            instrumentation.runOnMainSync { presentation.detach(); transport.close() }
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test fun actualLegacyPresenterKeepsRecentCommandAndRequestedOutputOnMainWithoutDoingIo() {
        val preferences = PreferenceManager.getDefaultSharedPreferences(target)
        val oldAuto = preferences.all["autologin_checkbox"] as Boolean?
        val backend = RecordingSshBackend()
        val worker = SshTransport.newWorker()
        val transport = SshTransport(backend, worker)
        var presenter: MyConnectionHandler? = null
        val callbacks = CopyOnWriteArrayList<Thread>()
        val recent = CopyOnWriteArrayList<String>()
        val connected = CountDownLatch(1)
        val screen = object : MyInterface {
            override fun performCallback() { callbacks.add(Thread.currentThread()); connected.countDown() }
            override fun performCallback2(i: Int?) { }
            override fun showRecentCommand(command: String) { recent.add(command); callbacks.add(Thread.currentThread()) }
        }
        try {
            preferences.edit(commit = true) { putBoolean("autologin_checkbox", false) }
            assertFalse(preferences.getBoolean("autologin_checkbox", true))
            ActivityScenario.launch<MainActivity>(Intent(target, MainActivity::class.java)).use { scenario ->
                scenario.onActivity { activity ->
                    val handler = MyConnectionHandler(activity, screen, transport)
                    presenter = handler
                    handler.beginSsh(RecordingSshBackend.authentication(), RecordingSshBackend.request(initial = "synthetic init"))
                }
                assertTrue(connected.await(10, TimeUnit.SECONDS))
                scenario.onActivity {
                    val handler = requireNotNull(presenter)
                    assertTrue(handler.executeShellCommand("synthetic typed text"))
                    assertTrue(handler.executeExecCommand("synthetic requested output"))
                }
                worker.submit {}.get(10, TimeUnit.SECONDS)
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    val dialog = MyConnectionHandler::class.java.getDeclaredField("output").apply { isAccessible = true }.get(presenter) as AlertDialog
                    assertTrue(dialog.isShowing)
                    assertEquals("err oneerr twoout oneout two", dialog.findViewById<TextView>(android.R.id.message).text.toString())
                    assertEquals("Copy", dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
                    presenter!!.destroy()
                }
                assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS))
                assertEquals(listOf("synthetic init", "synthetic typed text"), recent)
                assertTrue(callbacks.all { it === Looper.getMainLooper().thread })
                assertTrue(backend.events.all { it.thread !== Looper.getMainLooper().thread })
            }
        } finally {
            instrumentation.runOnMainSync { presenter?.destroy(); transport.close() }
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS))
            val idle = CountDownLatch(1); AndroidCredentials.get(target).execute { idle.countDown() }
            assertTrue(idle.await(10, TimeUnit.SECONDS))
            preferences.edit(commit = true) {
                if (oldAuto == null) remove("autologin_checkbox") else putBoolean("autologin_checkbox", oldAuto)
            }
            assertEquals(oldAuto, preferences.all["autologin_checkbox"])
        }
    }
}
