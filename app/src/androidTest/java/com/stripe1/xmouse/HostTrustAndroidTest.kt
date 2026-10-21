package com.stripe1.xmouse

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.widget.TextView
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stripe1.xmouse.connection.Endpoint
import com.stripe1.xmouse.secrets.AndroidCredentials
import com.stripe1.xmouse.secrets.AndroidSecretFileIo
import com.stripe1.xmouse.trust.*
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.*
import java.util.function.BooleanSupplier

/** Real dialog/presenter/lifecycle and durable Android files; no real hosts or credentials. */
@RunWith(AndroidJUnit4::class)
class HostTrustAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val target get() = ApplicationProvider.getApplicationContext<Context>()
    private fun <T> field(owner: Any, name: String): T {
        @Suppress("UNCHECKED_CAST")
        return owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as T
    }
    private class Fixture(val directory: File, val timeout: Long) {
        val repo = HostKeyRepository(directory, AndroidSecretFileIo)
        val main = Handler(Looper.getMainLooper())
        val requests = HostTrustRequests(repo, { main.post(it) }, timeout)
        val worker = Executors.newSingleThreadExecutor { Thread(it, "synthetic-host-trust-worker") }
        val key = ApprovedHostKey.parse(OpenSSHPublicKeyUtil.encodePublicKey(Ed25519PrivateKeyParameters(ByteArray(32) { (it + 1).toByte() }, 0).generatePublicKey()))
        val endpoint = Endpoint.manual("Desktop.LOCAL.", 2222)
        lateinit var presenter: MyConnectionHandler
        fun destroyPresenter() { if (::presenter.isInitialized) presenter.destroy() }
        var thread: Thread? = null
        fun verify(host: Endpoint = endpoint): Future<*> = worker.submit {
            thread = Thread.currentThread(); requests.verify(host, key, BooleanSupplier { true })
        }
    }
    private fun fixture(timeout: Long = 10_000, action: (Fixture, ActivityScenario<MainActivity>) -> Unit) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(target)
        val oldAuto = preferences.all["autologin_checkbox"] as Boolean?
        val f = Fixture(File(target.noBackupFilesDir, "synthetic-host-trust-${UUID.randomUUID()}"), timeout)
        try {
            preferences.edit(commit = true) { putBoolean("autologin_checkbox", false) }
            ActivityScenario.launch<MainActivity>(Intent(target, MainActivity::class.java)).use { scenario ->
                scenario.onActivity { activity ->
                    val slot = MainActivity::class.java.getDeclaredField("screenConnection").apply { isAccessible = true }
                    (slot.get(activity) as MyConnectionHandler).destroy()
                    f.presenter = MyConnectionHandler(activity, activity, f.requests)
                    slot.set(activity, f.presenter); MainActivity.conn = f.presenter
                    f.presenter.startCredentialUi()
                }
                action(f, scenario)
            }
            instrumentation.waitForIdleSync()
            assertNull(field<Any?>(f.requests, "pending"))
            assertNull(field<WeakReference<*>>(f.requests, "listener").get())
            assertNull(field<Any?>(f.presenter, "trustUi"))
            assertNull(field<Any?>(f.presenter, "activity"))
        } finally {
            instrumentation.runOnMainSync { f.destroyPresenter(); f.requests.detach() }
            f.worker.shutdownNow(); assertTrue(f.worker.awaitTermination(10, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            val idle = CountDownLatch(1); AndroidCredentials.get(target).execute { idle.countDown() }; assertTrue(idle.await(10, TimeUnit.SECONDS))
            preferences.edit(commit = true) { if (oldAuto == null) remove("autologin_checkbox") else putBoolean("autologin_checkbox", oldAuto) }
            if (f.directory.exists()) assertTrue(f.directory.deleteRecursively())
        }
    }
    private fun prompt(f: Fixture): Pair<AlertDialog, HostTrustRequests.Request> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            var result: Pair<AlertDialog, HostTrustRequests.Request>? = null
            instrumentation.runOnMainSync {
                val ui = field<HostTrustUi>(f.presenter, "trustUi")
                val dialog = field<AlertDialog?>(ui, "dialog")
                val request = field<HostTrustRequests.Request?>(ui, "request")
                if (dialog?.isShowing == true && request != null) result = dialog to request
            }
            result?.let { return it }; Thread.sleep(10)
        }
        throw AssertionError("No first-trust prompt")
    }
    private fun failed(future: Future<*>): HostTrustException = assertThrows(ExecutionException::class.java) { future.get(10, TimeUnit.SECONDS) }.cause as HostTrustException

    @Test fun actualDialogAcceptShowsLogicalIdentityAlgorithmFingerprintAndPersistsBeforeCompletion() = fixture { f, _ ->
        val future = f.verify(); val (dialog, request) = prompt(f)
        assertFalse(future.isDone); assertNotSame(Looper.getMainLooper().thread, f.thread)
        instrumentation.runOnMainSync {
            val message = dialog.findViewById<TextView>(android.R.id.message).text.toString()
            assertTrue(message.contains("Desktop.LOCAL.")); assertTrue(message.contains("2222"))
            assertTrue(message.contains("ssh-ed25519")); assertTrue(message.contains(f.key.fingerprint))
            assertEquals("Accept and save", dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
            assertEquals("Reject", dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString())
            assertEquals("Cancel", dialog.getButton(AlertDialog.BUTTON_NEUTRAL).text.toString())
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        future.get(10, TimeUnit.SECONDS); instrumentation.waitForIdleSync()
        assertTrue(HostKeyRepository(f.directory, AndroidSecretFileIo).find(f.endpoint.identity)!!.matches(request.key))
        assertEquals(0x1c0, android.system.Os.stat(f.directory.path).st_mode and 0x1ff)
        assertEquals(0x180, android.system.Os.stat(File(f.directory, "approved-v1.bin").path).st_mode and 0x1ff)
        assertFalse(dialog.isShowing)
        f.verify().get(10, TimeUnit.SECONDS) // Reconnect consults saved trust, no reusable approval token.
    }
    @Test fun actualDialogRejectAndCancelNeverPersistTrustAndInvalidateLateButtons() = fixture { f, _ ->
        for ((button, reason) in listOf(AlertDialog.BUTTON_NEGATIVE to HostTrustException.Reason.REJECTED, AlertDialog.BUTTON_NEUTRAL to HostTrustException.Reason.CANCELLED)) {
            val future = f.verify(); val (dialog, request) = prompt(f)
            instrumentation.runOnMainSync { dialog.getButton(button).performClick() }
            assertEquals(reason, failed(future).reason); request.accept()
            assertNull(f.repo.find(f.endpoint.identity))
        }
    }
    @Test fun leavingActualActivityCancelsPromptAndOldCallbackCannotTrustNewEndpointAfterReturn() = fixture { f, scenario ->
        val first = f.verify(); val (dialog, old) = prompt(f)
        scenario.moveToState(Lifecycle.State.CREATED)
        assertEquals(HostTrustException.Reason.CANCELLED, failed(first).reason)
        instrumentation.waitForIdleSync(); assertFalse(dialog.isShowing)
        assertNull(field<WeakReference<*>>(f.requests, "listener").get())
        scenario.moveToState(Lifecycle.State.RESUMED)
        val other = Endpoint.manual("other.local", 2222)
        val second = f.verify(other); val (freshDialog, fresh) = prompt(f)
        instrumentation.runOnMainSync { old.accept(); old.reject(); old.cancel(); assertTrue(fresh.isPending) }
        assertFalse(second.isDone); assertNull(f.repo.find(f.endpoint.identity))
        instrumentation.runOnMainSync { freshDialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick() }
        assertEquals(HostTrustException.Reason.REJECTED, failed(second).reason); assertNull(f.repo.find(other.identity))
    }
    @Test fun actualDialogDeadlineWaitsOnlyOffMainAndDismissesWithoutTrust() = fixture(1200) { f, _ ->
        val future = f.verify(); val (dialog, request) = prompt(f)
        // This actual main-thread callback completes while the worker is still awaiting approval.
        instrumentation.runOnMainSync { assertFalse(future.isDone); assertSame(Looper.getMainLooper(), Looper.myLooper()) }
        assertNotSame(Looper.getMainLooper().thread, f.thread)
        assertEquals(HostTrustException.Reason.EXPIRED, failed(future).reason)
        instrumentation.waitForIdleSync(); assertFalse(dialog.isShowing)
        request.accept(); assertNull(f.repo.find(f.endpoint.identity))
    }
}
