package com.stripe1.xmouse.session

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.edit
import com.stripe1.xmouse.MainActivity
import com.stripe1.xmouse.XMouseApplication
import com.stripe1.xmouse.connection.ConnectionFailure
import com.stripe1.xmouse.connection.ConnectionState
import com.stripe1.xmouse.session.SessionForegroundService.Companion.NOTIFICATION_CHANNEL_ID
import com.stripe1.xmouse.session.SessionForegroundService.Companion.NOTIFICATION_ID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real target service/notification and system FGS checks; session work uses synthetic recording attempts. */
@RunWith(AndroidJUnit4::class)
class SessionForegroundServiceAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val defaultPreferences get() = context.getSharedPreferences(
        "${context.packageName}_preferences", Context.MODE_PRIVATE,
    )
    private var scenario: ActivityScenario<MainActivity>? = null
    private var ownerLease: AutoCloseable? = null
    private var owner: RecordingOwner? = null
    private var oldAutoConnect: Boolean? = null

    private class RecordingAttemptFactory : SessionAttemptFactory {
        val created = AtomicInteger()
        val closes = AtomicInteger()
        val failClose = AtomicBoolean()
        val createdHosts = CopyOnWriteArrayList<SessionHost>()
        override fun create(host: SessionHost): SessionAttempt {
            created.incrementAndGet()
            createdHosts.add(host)
            return object : SessionAttempt {
                override fun start(listener: SessionAttempt.Listener) {
                    listener.phase(ConnectionState.Phase.RESOLVING)
                    listener.ready()
                }

                override fun cancelAndClose(completion: (ConnectionFailure?) -> Unit) {
                    closes.incrementAndGet()
                    if (failClose.get()) {
                        completion(ConnectionFailure.Transport(ConnectionFailure.TransportReason.CLEANUP_UNCONFIRMED))
                    } else completion(null)
                }
            }
        }
    }

    /** Owner queue held by the test so selection completions can race before Connect is applied. */
    private class HeldSessionEventQueue : SessionEventQueue {
        private val work = ArrayDeque<Runnable>()
        @Volatile private var closed = false

        override fun post(action: Runnable): Boolean = synchronized(work) {
            if (closed) false else {
                work.addLast(action)
                true
            }
        }

        val pendingCount: Int get() = synchronized(work) { work.size }

        fun drain() {
            repeat(1000) {
                val next = synchronized(work) { if (work.isEmpty()) null else work.removeFirst() } ?: return
                next.run()
            }
            fail("controller event queue did not settle")
        }

        override fun close() {
            closed = true
        }
    }

    private class RecordingOwner(
        blockSelection: Boolean = false,
        private val manualSelections: Boolean = false,
        private val controllerQueue: SessionEventQueue? = null,
    ) : SessionServiceOwner, AutoCloseable {
        val attempts = RecordingAttemptFactory()
        override val controller = if (controllerQueue == null) SessionController(attempts)
            else SessionController(attempts, controllerQueue)
        override val interactions = SessionInteractions(Executor { it.run() })
        val selectionCalls = AtomicInteger()
        val selectionsCompleted = AtomicInteger()
        val selectionStarted = CountDownLatch(1)
        private val releaseSelection = CountDownLatch(if (blockSelection) 1 else 0)
        private val manualCompletions = mutableListOf<(SessionHost?) -> Unit>()
        private val selectionWorker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "synthetic-session-selection").apply { isDaemon = true }
        }

        override fun selectedHost(shell: String, initialCommand: String?, completion: (SessionHost?) -> Unit) {
            selectionCalls.incrementAndGet()
            if (manualSelections) {
                selectionStarted.countDown()
                synchronized(manualCompletions) { manualCompletions.add(completion) }
                return
            }
            selectionWorker.execute {
                selectionStarted.countDown()
                releaseSelection.await(5, TimeUnit.SECONDS)
                completion(SessionFixtures.host())
                selectionsCompleted.incrementAndGet()
            }
        }

        fun completeSelection(index: Int, host: SessionHost?) {
            val completion = synchronized(manualCompletions) { manualCompletions.removeAt(index) }
            completion(host)
            selectionsCompleted.incrementAndGet()
        }

        fun release() { releaseSelection.countDown() }

        override fun close() {
            release()
            controller.close()
            (controllerQueue as? HeldSessionEventQueue)?.drain()
            selectionWorker.shutdownNow()
            selectionWorker.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    private class BoundService(val binder: SessionForegroundService.SessionBinder, val connection: ServiceConnection)

    private fun install(owner: RecordingOwner) {
        this.owner = owner
        ownerLease = (context.applicationContext as XMouseApplication).sessionOwners.installOwnerForTests(owner)
    }

    private fun launchVisibleActivity(): ActivityScenario<MainActivity> {
        val preferences = defaultPreferences
        oldAutoConnect = preferences.getBoolean("autologin_checkbox", false)
        preferences.edit { putBoolean("autologin_checkbox", false) }
        return ActivityScenario.launch(MainActivity::class.java).also { scenario = it }
    }

    private fun bind(scenario: ActivityScenario<MainActivity>): BoundService {
        val connected = CountDownLatch(1)
        var binder: SessionForegroundService.SessionBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                binder = service as SessionForegroundService.SessionBinder
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { }
        }
        scenario.onActivity { activity ->
            assertTrue(activity.bindService(Intent(activity, SessionForegroundService::class.java), connection,
                Context.BIND_AUTO_CREATE))
        }
        assertTrue("service did not bind", connected.await(5, TimeUnit.SECONDS))
        return BoundService(requireNotNull(binder), connection)
    }

    private fun unbind(scenario: ActivityScenario<MainActivity>, binding: BoundService) {
        scenario.onActivity { it.unbindService(binding.connection) }
    }

    private fun requestConnect(scenario: ActivityScenario<MainActivity>, automatic: Boolean = false) {
        var result: ForegroundConnectRequestResult? = null
        scenario.onActivity { result = SessionForegroundService.requestConnect(it, automatic) }
        assertEquals(ForegroundConnectRequestResult.ACCEPTED, result)
    }

    private fun eventually(timeoutSeconds: Long = 5, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("condition did not become true", predicate())
    }

    private fun grantNotificationPermissionForDisclosureTest() {
        if (Build.VERSION.SDK_INT < 33) return
        val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
        assertTrue("POST_NOTIFICATIONS must be declared for the later permission flow",
            Manifest.permission.POST_NOTIFICATIONS in permissions)
        val notificationPermissionGranted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!notificationPermissionGranted) {
            // `pm grant` also updates the notification app-op on current Android; a raw test permission
            // grant can leave NotificationManager treating earlier denied FGS notices as blocked.
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(
                    "pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}",
                ),
            ).use { it.readBytes() }
        }
        assertEquals(PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        assertTrue("notification visibility must be enabled to inspect the FGS disclosure",
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled())
    }

    private fun assertForegroundDisclosure(binder: SessionForegroundService.SessionBinder) {
        assertTrue("service binder did not report foreground promotion", binder.isForeground)
        assertNull(binder.failure)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        eventually {
            notificationManager.activeNotifications.any { it.id == NOTIFICATION_ID }
        }
        val notification = notificationManager.activeNotifications.firstOrNull { it.id == NOTIFICATION_ID }?.notification
        assertNotNull("foreground notification was not published", notification)
        assertEquals(NOTIFICATION_CHANNEL_ID, notification!!.channelId)
        assertEquals("XMouse remote session", notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE))
        val text = notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
        assertTrue(text.contains("requested network-device connection"))
        assertFalse(text.contains("synthetic.invalid"))
        assertFalse(text.contains("NOT_REAL"))
        assertTrue(notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        assertNull("Open controls is added with the notification actions in 5.3", notification.contentIntent)
        assertTrue("notification actions are added in 5.3", notification.actions.isNullOrEmpty())
        val channel = notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel!!.importance)
    }

    @After
    fun cleanup() {
        owner?.release()
        context.stopService(Intent(context, SessionForegroundService::class.java))
        scenario?.close()
        scenario = null
        owner?.close()
        owner = null
        ownerLease?.close()
        ownerLease = null
        oldAutoConnect?.let { value -> defaultPreferences.edit { putBoolean("autologin_checkbox", value) } }
    }

    @Test
    fun bindOnlyNullAndInvalidStartsDoNotConnectAndBackgroundLauncherRejects() {
        val recordingOwner = RecordingOwner()
        install(recordingOwner)
        val activityScenario = launchVisibleActivity()
        val binding = bind(activityScenario)

        assertFalse(binding.binder.isForeground)
        assertEquals(ConnectionState.Phase.DISCONNECTED, binding.binder.snapshot.connection.phase)
        assertEquals(0, recordingOwner.selectionCalls.get())
        assertEquals(0, recordingOwner.attempts.created.get())

        var nullResult = 0
        instrumentation.runOnMainSync {
            nullResult = binding.binder.serviceForTests.onStartCommand(null, 0, 8101)
        }
        assertEquals(Service.START_NOT_STICKY, nullResult)
        scenario!!.onActivity { activity ->
            activity.startService(Intent(activity, SessionForegroundService::class.java).setAction("untrusted.invalid"))
        }
        Thread.sleep(100)
        assertFalse(binding.binder.isForeground)
        assertEquals(0, recordingOwner.selectionCalls.get())
        assertEquals(0, recordingOwner.attempts.created.get())

        var backgroundResult: ForegroundConnectRequestResult? = null
        activityScenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        activityScenario.onActivity { backgroundResult = SessionForegroundService.requestConnect(it) }
        assertEquals(ForegroundConnectRequestResult.ACTIVITY_NOT_VISIBLE, backgroundResult)
        assertEquals(0, recordingOwner.selectionCalls.get())
        assertEquals(0, recordingOwner.attempts.created.get())

        val serviceInfo = context.packageManager.getServiceInfo(
            ComponentName(context, SessionForegroundService::class.java), PackageManager.GET_META_DATA,
        )
        assertFalse("service must not be externally launchable", serviceInfo.exported)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, serviceInfo.foregroundServiceType)
        }
        val requested = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
        assertTrue(Manifest.permission.INTERNET in requested)
        assertTrue(Manifest.permission.ACCESS_NETWORK_STATE in requested)
        assertTrue(Manifest.permission.CHANGE_WIFI_MULTICAST_STATE in requested)
        assertTrue("android.permission.FOREGROUND_SERVICE" in requested)
        assertTrue("connected-device FGS permission missing",
            "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" in requested)
        val bootReceiver = context.packageManager.queryBroadcastReceivers(
            Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(context.packageName), PackageManager.MATCH_DISABLED_COMPONENTS,
        )
        assertTrue("a boot-triggered start path was introduced", bootReceiver.isEmpty())
        unbind(activityScenario, binding)
    }

    @Test
    fun visibleConnectPublishesDisclosureBeforeSelectionAndRebindAndRepeatDoNotDuplicatePipeline() {
        grantNotificationPermissionForDisclosureTest()
        val recordingOwner = RecordingOwner(blockSelection = true)
        install(recordingOwner)
        val activityScenario = launchVisibleActivity()

        requestConnect(activityScenario)
        val firstBinding = bind(activityScenario)
        assertTrue(recordingOwner.selectionStarted.await(3, TimeUnit.SECONDS))
        assertForegroundDisclosure(firstBinding.binder)
        assertEquals("credentials/endpoint selection must still be blocked", 0, recordingOwner.attempts.created.get())

        recordingOwner.release()
        eventually { firstBinding.binder.snapshot.connection.isReady }
        assertEquals(1, recordingOwner.attempts.created.get())

        repeat(5) { requestConnect(activityScenario) }
        eventually { recordingOwner.selectionCalls.get() == 6 }
        eventually { recordingOwner.selectionsCompleted.get() == 6 }
        instrumentation.waitForIdleSync()
        eventually { recordingOwner.attempts.created.get() == 1 }
        assertTrue(firstBinding.binder.snapshot.connection.isReady)

        val seen = CountDownLatch(1)
        val observer = SessionController.Observer { if (it.connection.isReady) seen.countDown() }
        val subscription = firstBinding.binder.observe(Executor { command -> android.os.Handler(Looper.getMainLooper()).post(command) }, observer)
        assertTrue(seen.await(2, TimeUnit.SECONDS))
        subscription.close()
        unbind(activityScenario, firstBinding)
        val firstService = firstBinding.binder.serviceForTests
        val rebound = bind(activityScenario)
        assertSame("binding recreation must reuse the started Service instance", firstService, rebound.binder.serviceForTests)
        assertTrue(rebound.binder.snapshot.connection.isReady)
        assertEquals(1, recordingOwner.attempts.created.get())
        unbind(activityScenario, rebound)

        // Simulate a service instance teardown while the process owner remains alive. A new instance must
        // reattach to that same controller instead of creating an owner to bypass its current session.
        context.stopService(Intent(context, SessionForegroundService::class.java))
        eventually {
            firstBinding.binder.destroyedForTests && !firstBinding.binder.isForeground &&
                recordingOwner.attempts.closes.get() == 1 &&
                recordingOwner.controller.current.connection.phase == ConnectionState.Phase.DISCONNECTED &&
                recordingOwner.controller.current.automaticConnectSuppressed
        }
        assertNull("destroyed service must not leave an active session without its disclosure",
            recordingOwner.controller.current.desiredHost)
        requestConnect(activityScenario)
        val recreatedBinding = bind(activityScenario)
        assertNotSame("service must be recreated after teardown", firstService, recreatedBinding.binder.serviceForTests)
        eventually { recreatedBinding.binder.snapshot.connection.isReady }
        eventually { recordingOwner.selectionCalls.get() == 7 }
        eventually { recordingOwner.selectionsCompleted.get() == 7 }
        instrumentation.waitForIdleSync()
        assertEquals("a new visible request may start a new attempt after confirmed cleanup", 2,
            recordingOwner.attempts.created.get())

        recordingOwner.controller.disconnect()
        eventually { recordingOwner.controller.current.connection.phase == ConnectionState.Phase.DISCONNECTED }
        assertTrue(recordingOwner.controller.current.automaticConnectSuppressed)
        unbind(activityScenario, recreatedBinding)
        context.stopService(Intent(context, SessionForegroundService::class.java))

        requestConnect(activityScenario, automatic = true)
        val autoBinding = bind(activityScenario)
        eventually { recordingOwner.selectionCalls.get() == 8 }
        eventually { recordingOwner.controller.current.automaticConnectSuppressed }
        eventually { !autoBinding.binder.isForeground }
        assertNull(recordingOwner.controller.current.desiredHost)
        assertEquals("saved auto-connect must not undo Disconnect", 2, recordingOwner.attempts.created.get())
        assertFalse(autoBinding.binder.isForeground)
        unbind(activityScenario, autoBinding)
    }

    @Test
    fun cleanupUnconfirmedOwnerRemainsFailClosedAcrossServiceRecreation() {
        val recordingOwner = RecordingOwner()
        install(recordingOwner)
        val activityScenario = launchVisibleActivity()
        requestConnect(activityScenario)
        val firstBinding = bind(activityScenario)
        eventually { recordingOwner.selectionCalls.get() == 1 }
        recordingOwner.release()
        eventually { firstBinding.binder.snapshot.connection.isReady }
        assertEquals(1, recordingOwner.attempts.created.get())

        recordingOwner.attempts.failClose.set(true)
        unbind(activityScenario, firstBinding)
        context.stopService(Intent(context, SessionForegroundService::class.java))
        eventually { firstBinding.binder.destroyedForTests }
        eventually {
            recordingOwner.controller.current.cleanupUnconfirmed &&
                recordingOwner.controller.current.automaticConnectSuppressed &&
                recordingOwner.controller.current.desiredHost == null
        }

        requestConnect(activityScenario, automatic = true)
        val autoRecreated = bind(activityScenario)
        eventually { recordingOwner.selectionCalls.get() == 2 }
        eventually { recordingOwner.selectionsCompleted.get() == 2 }
        eventually { !autoRecreated.binder.isForeground }
        assertTrue(recordingOwner.controller.current.cleanupUnconfirmed)
        assertTrue("service destruction's explicit-disconnect suppression must survive recreation",
            recordingOwner.controller.current.automaticConnectSuppressed)
        assertNull(recordingOwner.controller.current.desiredHost)
        assertEquals(1, recordingOwner.attempts.created.get())
        unbind(activityScenario, autoRecreated)
        eventually { autoRecreated.binder.destroyedForTests }

        requestConnect(activityScenario)
        val recreated = bind(activityScenario)
        eventually { recordingOwner.selectionCalls.get() == 3 }
        eventually { recordingOwner.selectionsCompleted.get() == 3 }
        eventually { recreated.binder.snapshot.cleanupUnconfirmed && recreated.binder.snapshot.desiredHost != null }
        assertEquals("a new service must not replace an owner with an uncertain old close", 1,
            recordingOwner.attempts.created.get())
        assertEquals(ConnectionState.Phase.ERROR, recreated.binder.snapshot.connection.phase)
        assertNotNull(recreated.binder.snapshot.connection.failure)
        unbind(activityScenario, recreated)
    }

    @Test
    fun destroyedServiceDiscardsLateHostSelectionInsteadOfStartingBackgroundWork() {
        val recordingOwner = RecordingOwner(blockSelection = true)
        install(recordingOwner)
        val activityScenario = launchVisibleActivity()
        requestConnect(activityScenario)
        val binding = bind(activityScenario)
        assertTrue(recordingOwner.selectionStarted.await(3, TimeUnit.SECONDS))
        assertTrue(binding.binder.isForeground)

        context.stopService(Intent(context, SessionForegroundService::class.java))
        unbind(activityScenario, binding)
        eventually { binding.binder.destroyedForTests }
        eventually { !binding.binder.isForeground }
        recordingOwner.release()
        eventually { recordingOwner.selectionsCompleted.get() == 1 }
        instrumentation.waitForIdleSync()

        assertEquals("stale selection from a destroyed service must not start a session", 0,
            recordingOwner.attempts.created.get())
        assertNull(recordingOwner.controller.current.desiredHost)
        assertTrue("service destruction must suppress auto-connect on the retained owner",
            recordingOwner.controller.current.automaticConnectSuppressed)
    }

    @Test
    fun outOfOrderHostSelectionSuccessAndFailureCannotOverrideNewestForegroundRequest() {
        val recordingOwner = RecordingOwner(manualSelections = true)
        install(recordingOwner)
        val activityScenario = launchVisibleActivity()
        requestConnect(activityScenario)
        val binding = bind(activityScenario)
        eventually { recordingOwner.selectionCalls.get() == 1 }
        val olderSuccess = SessionFixtures.host("older-success.invalid", 11, "ref_older_success", 2222)
        val newestSuccess = SessionFixtures.host("newest-success.invalid", 12, "ref_newest_success", 2223)

        requestConnect(activityScenario, automatic = true)
        eventually { recordingOwner.selectionCalls.get() == 2 }
        recordingOwner.completeSelection(1, newestSuccess)
        eventually {
            recordingOwner.controller.current.desiredHost?.sameRequest(newestSuccess) == true &&
                recordingOwner.controller.current.connection.isReady
        }
        recordingOwner.completeSelection(0, olderSuccess)
        eventually { recordingOwner.selectionsCompleted.get() == 2 }
        instrumentation.waitForIdleSync()
        assertTrue(recordingOwner.controller.current.desiredHost!!.sameRequest(newestSuccess))
        assertEquals(1, recordingOwner.attempts.created.get())
        assertEquals(0, recordingOwner.attempts.closes.get())
        assertTrue(binding.binder.isForeground)
        assertNull(binding.binder.failure)

        val newestAfterFailure = SessionFixtures.host("newest-after-failure.invalid", 14, "ref_newest_after_failure", 2225)
        requestConnect(activityScenario, automatic = true)
        eventually { recordingOwner.selectionCalls.get() == 3 }
        requestConnect(activityScenario)
        eventually { recordingOwner.selectionCalls.get() == 4 }
        recordingOwner.completeSelection(1, newestAfterFailure)
        eventually {
            recordingOwner.controller.current.desiredHost?.sameRequest(newestAfterFailure) == true &&
                recordingOwner.controller.current.connection.isReady
        }
        recordingOwner.completeSelection(0, null)
        eventually { recordingOwner.selectionsCompleted.get() == 4 }
        instrumentation.waitForIdleSync()

        assertTrue(recordingOwner.controller.current.desiredHost!!.sameRequest(newestAfterFailure))
        assertTrue(recordingOwner.controller.current.connection.isReady)
        assertEquals("stale success/failure callbacks must not create or close attempts", 2,
            recordingOwner.attempts.created.get())
        assertEquals(1, recordingOwner.attempts.closes.get())
        assertTrue("older failure must not stop the newest foreground service", binding.binder.isForeground)
        assertNull("older failure must not publish a service error", binding.binder.failure)

        recordingOwner.controller.disconnect()
        eventually { recordingOwner.controller.current.connection.phase == ConnectionState.Phase.DISCONNECTED }
        requestConnect(activityScenario)
        eventually { recordingOwner.selectionCalls.get() == 5 }
        requestConnect(activityScenario)
        eventually { recordingOwner.selectionCalls.get() == 6 }
        recordingOwner.completeSelection(1, null)
        eventually { binding.binder.failure == SessionServiceStartFailure.HOST_SELECTION_FAILED }
        assertTrue("idle stop must wait for the older lookup to retire", binding.binder.isForeground)
        recordingOwner.completeSelection(0, SessionFixtures.host("stale-after-failure.invalid", 15,
            "ref_stale_after_failure", 2226))
        eventually { recordingOwner.selectionsCompleted.get() == 6 && !binding.binder.isForeground }
        assertNull("stale success after latest failure must not select a host", recordingOwner.controller.current.desiredHost)
        assertEquals(2, recordingOwner.attempts.created.get())
    }

    private fun assertNewestQueuedConnectSurvivesOldSelection(oldHost: SessionHost?) {
        val queue = HeldSessionEventQueue()
        val recordingOwner = RecordingOwner(manualSelections = true, controllerQueue = queue)
        install(recordingOwner)
        val activityScenario = launchVisibleActivity()
        requestConnect(activityScenario)
        val binding = bind(activityScenario)
        eventually { recordingOwner.selectionCalls.get() == 1 }
        val newestHost = SessionFixtures.host("newest-held.invalid", 31, "ref_newest_held", 2231)

        requestConnect(activityScenario)
        eventually { recordingOwner.selectionCalls.get() == 2 }
        queue.drain() // Drain any setup/observer work; the following B Connect must remain held.
        assertEquals(0, recordingOwner.attempts.created.get())
        assertNull(recordingOwner.controller.current.desiredHost)
        recordingOwner.completeSelection(1, newestHost)
        instrumentation.waitForIdleSync()
        assertEquals("newest Connect must be queued but not yet applied", 1, queue.pendingCount)

        recordingOwner.completeSelection(0, oldHost)
        instrumentation.waitForIdleSync()
        assertEquals(2, recordingOwner.selectionsCompleted.get())
        assertEquals("retiring an old success/failure must leave only the newest Connect queued", 1,
            queue.pendingCount)
        assertEquals(0, recordingOwner.attempts.created.get())
        assertTrue("old callback must not stop the service for the queued newest Connect", binding.binder.isForeground)
        assertFalse("old callback must not destroy the service for the queued newest Connect",
            binding.binder.destroyedForTests)
        assertNull("old callback must not publish a failure for the newest request", binding.binder.failure)

        queue.drain()
        instrumentation.waitForIdleSync()
        eventually { binding.binder.snapshot.connection.isReady }
        assertTrue(recordingOwner.controller.current.desiredHost!!.sameRequest(newestHost))
        assertEquals(listOf(newestHost), recordingOwner.attempts.createdHosts.toList())
        assertTrue(binding.binder.isForeground)
        unbind(activityScenario, binding)
    }

    @Test
    fun oldSuccessCannotStopNewestConnectQueuedOnControllerOwnerQueue() {
        assertNewestQueuedConnectSurvivesOldSelection(
            SessionFixtures.host("older-held-success.invalid", 33, "ref_older_held_success", 2233),
        )
    }

    @Test
    fun oldFailureCannotStopNewestConnectQueuedOnControllerOwnerQueue() {
        assertNewestQueuedConnectSurvivesOldSelection(null)
    }

    @Test
    fun latestFailureWaitsForOlderValidSelectionToRetireBeforeStoppingService() {
        val queue = HeldSessionEventQueue()
        val recordingOwner = RecordingOwner(manualSelections = true, controllerQueue = queue)
        install(recordingOwner)
        val activityScenario = launchVisibleActivity()
        requestConnect(activityScenario)
        val binding = bind(activityScenario)
        eventually { recordingOwner.selectionCalls.get() == 1 }
        requestConnect(activityScenario)
        eventually { recordingOwner.selectionCalls.get() == 2 }
        queue.drain()
        assertEquals(0, recordingOwner.attempts.created.get())
        assertNull(recordingOwner.controller.current.desiredHost)

        recordingOwner.completeSelection(1, null)
        instrumentation.waitForIdleSync()
        assertEquals(SessionServiceStartFailure.HOST_SELECTION_FAILED, binding.binder.failure)
        assertTrue("latest failure must defer idle stop while the old lookup is pending", binding.binder.isForeground)
        assertEquals(0, recordingOwner.attempts.created.get())

        recordingOwner.completeSelection(0,
            SessionFixtures.host("stale-valid-after-failure.invalid", 34, "ref_stale_valid", 2234))
        instrumentation.waitForIdleSync()
        eventually { !binding.binder.isForeground }
        assertFalse(binding.binder.destroyedForTests)
        assertEquals("old success cannot overwrite the latest failure",
            SessionServiceStartFailure.HOST_SELECTION_FAILED, binding.binder.failure)
        assertNull(recordingOwner.controller.current.desiredHost)
        assertEquals(0, recordingOwner.attempts.created.get())
        assertEquals(0, queue.pendingCount)
        unbind(activityScenario, binding)
    }

    @Test
    fun explicitDisconnectDuringHostLookupInvalidatesItButANewVisibleRequestCanConnect() {
        val recordingOwner = RecordingOwner(manualSelections = true)
        install(recordingOwner)
        val activityScenario = launchVisibleActivity()
        requestConnect(activityScenario)
        val firstBinding = bind(activityScenario)
        eventually { recordingOwner.selectionCalls.get() == 1 }

        recordingOwner.controller.disconnect()
        eventually {
            recordingOwner.controller.current.automaticConnectSuppressed &&
                recordingOwner.controller.current.desiredHost == null
        }
        recordingOwner.completeSelection(0, SessionFixtures.host("cancelled.invalid", 21, "ref_cancelled", 2231))
        eventually { recordingOwner.selectionsCompleted.get() == 1 && !firstBinding.binder.isForeground }
        assertEquals("late metadata after explicit Disconnect must not start work", 0,
            recordingOwner.attempts.created.get())
        assertNull(recordingOwner.controller.current.desiredHost)
        assertTrue(recordingOwner.controller.current.automaticConnectSuppressed)
        unbind(activityScenario, firstBinding)
        eventually { firstBinding.binder.destroyedForTests }

        requestConnect(activityScenario)
        val newBinding = bind(activityScenario)
        eventually { recordingOwner.selectionCalls.get() == 2 }
        recordingOwner.completeSelection(0, SessionFixtures.host("deliberate-new.invalid", 22,
            "ref_deliberate_new", 2232))
        eventually { newBinding.binder.snapshot.connection.isReady }
        assertEquals("a fresh foreground request after Disconnect is deliberate", 1,
            recordingOwner.attempts.created.get())
        assertNotNull(newBinding.binder.snapshot.desiredHost)
        assertTrue(newBinding.binder.isForeground)
        unbind(activityScenario, newBinding)
    }
}
