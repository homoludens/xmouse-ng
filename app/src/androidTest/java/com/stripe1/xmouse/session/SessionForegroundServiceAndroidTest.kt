package com.stripe1.xmouse.session

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
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
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.core.content.edit
import androidx.appcompat.widget.Toolbar
import com.stripe1.xmouse.MainActivity
import com.stripe1.xmouse.MyConnectionHandler
import com.stripe1.xmouse.R
import com.stripe1.xmouse.XMouseApplication
import com.stripe1.xmouse.connection.ConnectionFailure
import com.stripe1.xmouse.connection.ConnectionState
import com.stripe1.xmouse.secrets.AndroidSecretFileIo
import com.stripe1.xmouse.transport.RecordingSshBackend
import com.stripe1.xmouse.transport.SshLimits
import com.stripe1.xmouse.transport.SshRuntime
import com.stripe1.xmouse.trust.HostKeyRepository
import com.stripe1.xmouse.session.SessionForegroundService.Companion.NOTIFICATION_CHANNEL_ID
import com.stripe1.xmouse.session.SessionForegroundService.Companion.NOTIFICATION_ID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real target service/notification and system FGS checks; session work uses synthetic recording attempts. */
@RunWith(AndroidJUnit4::class)
class SessionForegroundServiceAndroidTest {
    private companion object { const val POST_NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS" }

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val defaultPreferences get() = context.getSharedPreferences(
        "${context.packageName}_preferences", Context.MODE_PRIVATE,
    )
    private var scenario: ActivityScenario<MainActivity>? = null
    private var ownerLease: AutoCloseable? = null
    private var owner: RecordingOwner? = null
    private var transportOwner: RecordingTransportOwner? = null
    private var oldAutoConnect: Boolean? = null
    private var oldPermissionRequestedPreference: Boolean? = null
    private var legacyConnectProbeLease: AutoCloseable? = null
    private val device get() = UiDevice.getInstance(instrumentation)

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
                synchronized(manualCompletions) { manualCompletions.add(completion) }
                selectionStarted.countDown()
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

    /** Production controller/attempt/transport with only SSH resources replaced by a recording backend. */
    private class RecordingTransportOwner(
        private val manualSelections: Boolean = false,
        blockCloses: Boolean = false,
    ) : SessionServiceOwner, AutoCloseable {
        val directory = File(ApplicationProvider.getApplicationContext<Context>().cacheDir,
            "notification-session-${java.util.UUID.randomUUID()}")
        val backend = RecordingSshBackend().apply {
            if (blockCloses) { blockAllCloses = true; ignoreInterrupts = true }
        }
        val selectionCalls = AtomicInteger()
        val selectionsCompleted = AtomicInteger()
        val attemptsCreated get() = backend.events.count { it.name == "create" }
        val selectionStarted = CountDownLatch(1)
        private val pendingSelections = mutableListOf<(SessionHost?) -> Unit>()
        private val selectionWorker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "synthetic-notification-selection").apply { isDaemon = true }
        }
        private val credentialWorker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "synthetic-notification-credentials").apply { isDaemon = true }
        }
        override val interactions = SessionInteractions(Executor { android.os.Handler(Looper.getMainLooper()).post(it) })
        private val credentialProvider = SessionCredentialProvider { selection, _, current, callback ->
            credentialWorker.execute {
                if (current.asBoolean) {
                    val host = SessionFixtures.host(selection.host.host ?: "synthetic.invalid", selection.host.id,
                        selection.host.passwordReference ?: "synthetic_password", selection.host.port?.toInt() ?: 2222)
                    callback.complete(SessionFixtures.material(host), null)
                } else callback.complete(null, null)
            }
        }
        private val attemptFactory = SshSessionAttemptFactory(
            credentialProvider,
            HostKeyRepository(directory, AndroidSecretFileIo),
            interactions,
            SshLimits(200, 350, 200, 600, 250, 250, 450, 450, 150, 150, 2, 50, 150, 4096),
            SshRuntime.SHARED,
            credentialTimeoutMillis = 2_000,
            closeTimeoutMillis = 1_000,
            backendFactory = { _, _ -> backend },
        )
        override val controller = SessionController(attemptFactory)

        override fun selectedHost(shell: String, initialCommand: String?, completion: (SessionHost?) -> Unit) {
            selectionCalls.incrementAndGet()
            if (manualSelections) {
                synchronized(pendingSelections) { pendingSelections.add(completion) }
                selectionStarted.countDown()
            } else {
                selectionWorker.execute {
                    completion(SessionFixtures.host())
                    selectionsCompleted.incrementAndGet()
                }
            }
        }

        fun completeSelection(index: Int, host: SessionHost?) {
            val completion = synchronized(pendingSelections) { pendingSelections.removeAt(index) }
            completion(host)
            selectionsCompleted.incrementAndGet()
        }

        override fun close() {
            backend.release.countDown()
            controller.close()
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while ((!controller.current.closed || controller.current.connection.phase == ConnectionState.Phase.CLOSING) &&
                System.nanoTime() < end) Thread.sleep(5)
            check(controller.current.closed)
            selectionWorker.shutdownNow()
            credentialWorker.shutdownNow()
            check(selectionWorker.awaitTermination(5, TimeUnit.SECONDS))
            check(credentialWorker.awaitTermination(5, TimeUnit.SECONDS))
            directory.deleteRecursively()
        }
    }

    private class BoundService(val binder: SessionForegroundService.SessionBinder, val connection: ServiceConnection)

    private fun install(owner: RecordingOwner) {
        this.owner = owner
        ownerLease = (context.applicationContext as XMouseApplication).sessionOwners.installOwnerForTests(owner)
    }

    private fun install(owner: RecordingTransportOwner) {
        this.transportOwner = owner
        ownerLease = (context.applicationContext as XMouseApplication).sessionOwners.installOwnerForTests(owner)
    }

    private fun launchVisibleActivity(autoConnect: Boolean = false): ActivityScenario<MainActivity> {
        val preferences = defaultPreferences
        oldAutoConnect = preferences.getBoolean("autologin_checkbox", false)
        preferences.edit { putBoolean("autologin_checkbox", autoConnect) }
        return ActivityScenario.launch(MainActivity::class.java).also { scenario = it }
    }

    private fun invokeLegacyToolbarConnect(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            val toolbar = activity.findViewById<Toolbar>(R.id.toolbar)
            if (toolbar.menu.findItem(R.id.action_conn_disc) == null) {
                activity.menuInflater.inflate(R.menu.main, toolbar.menu)
            }
            activity.onOptionsItemSelected(toolbar.menu.findItem(R.id.action_conn_disc))
        }
    }

    private fun assertSameActivity(scenario: ActivityScenario<MainActivity>, expected: MainActivity) {
        scenario.onActivity { assertSame(expected, it) }
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

    private fun activityStringField(activity: MainActivity, name: String): String? =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as String?

    private fun setActivityStringField(activity: MainActivity, name: String, value: String?) {
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }

    /** Exercises the same private callback boundary registered with ActivityResultContracts.RequestPermission. */
    private fun deliverPermissionResult(scenario: ActivityScenario<MainActivity>, granted: Boolean) {
        scenario.onActivity { activity ->
            MainActivity::class.java.getDeclaredMethod("onNotificationPermissionResult", java.lang.Boolean.TYPE)
                .apply { isAccessible = true }.invoke(activity, granted)
        }
        instrumentation.waitForIdleSync()
    }

    /** Delivers a result tagged with an earlier request, even while a newer reservation is current. */
    private fun deliverPermissionResultForTicket(
        scenario: ActivityScenario<MainActivity>, ticket: String, granted: Boolean,
    ) {
        scenario.onActivity { activity ->
            MainActivity::class.java.getDeclaredMethod(
                "handleNotificationPermissionResult", String::class.java, java.lang.Boolean.TYPE,
            ).apply { isAccessible = true }.invoke(activity, ticket, granted)
        }
        instrumentation.waitForIdleSync()
    }

    private fun showSessionControlsFromMenu(scenario: ActivityScenario<MainActivity>) {
        val handled = AtomicBoolean()
        scenario.onActivity { activity ->
            val toolbar = activity.findViewById<Toolbar>(R.id.toolbar)
            if (toolbar.menu.findItem(R.id.action_session_controls) == null) {
                activity.menuInflater.inflate(R.menu.main, toolbar.menu)
            }
            handled.set(activity.onOptionsItemSelected(toolbar.menu.findItem(R.id.action_session_controls)))
        }
        assertTrue("Session controls menu action was not handled", handled.get())
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
        assertTrue("POST_NOTIFICATIONS must be declared for the runtime flow",
            POST_NOTIFICATIONS_PERMISSION in permissions)
        setNotificationPermission(true)
        assertEquals(PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(POST_NOTIFICATIONS_PERMISSION))
        assertTrue("notification visibility must be enabled to inspect the FGS disclosure",
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled())
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command),
    ).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun setNotificationPermission(granted: Boolean) {
        if (Build.VERSION.SDK_INT < 33) return
        if (oldPermissionRequestedPreference == null) {
            oldPermissionRequestedPreference = defaultPreferences.getBoolean("session_notification_permission_requested", false)
        }
        val currentlyGranted = context.checkSelfPermission(POST_NOTIFICATIONS_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
        if (currentlyGranted == granted) return
        val verb = if (granted) "grant" else "revoke"
        shell("pm $verb ${context.packageName} $POST_NOTIFICATIONS_PERMISSION")
        assertEquals(if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(POST_NOTIFICATIONS_PERMISSION))
    }

    private fun resetNotificationPermissionPrompt() {
        setNotificationPermission(false)
        shell("pm clear-permission-flags ${context.packageName} $POST_NOTIFICATIONS_PERMISSION user-set")
        shell("pm clear-permission-flags ${context.packageName} $POST_NOTIFICATIONS_PERMISSION user-fixed")
        defaultPreferences.edit { putBoolean("session_notification_permission_requested", false) }
    }

    private fun normalizedUiText(value: CharSequence?): String = value?.toString().orEmpty()
        .filter(Char::isLetterOrDigit)
        .lowercase(Locale.ROOT)

    private fun waitForText(text: String, timeoutMillis: Long = 5_000) {
        val expected = normalizedUiText(text)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val visibleText = mutableListOf<String>()
        do {
            visibleText.clear()
            visibleText += device.findObjects(By.clazz("android.widget.TextView"))
                .mapNotNull { it.text?.toString() }
            listOf(
                "android.widget.Button",
                "androidx.appcompat.widget.AppCompatButton",
                "com.google.android.material.button.MaterialButton",
            ).forEach { className ->
                visibleText += device.findObjects(By.clazz(className)).mapNotNull { it.text?.toString() }
            }
            if (visibleText.any { normalizedUiText(it) == expected }) return
            Thread.sleep(50)
        } while (System.nanoTime() < deadline)
        fail("UI text did not appear: $text; accessibility text=$visibleText")
    }

    private fun waitForTextContains(text: String, timeoutMillis: Long = 5_000) {
        val expected = normalizedUiText(text)
        val appeared = device.wait(Until.findObject(By.textContains(text)), timeoutMillis) != null ||
            device.findObjects(By.clazz("android.widget.TextView"))
                .any { normalizedUiText(it.text).contains(expected) }
        assertTrue("UI text containing '$text' did not appear", appeared)
    }

    private fun clickText(text: String) {
        val expected = normalizedUiText(text)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var target = device.findObjects(By.clickable(true))
            .firstOrNull { normalizedUiText(it.text) == expected }
        while (target == null && System.nanoTime() < deadline) {
            Thread.sleep(50)
            target = device.findObjects(By.clickable(true))
                .firstOrNull { normalizedUiText(it.text) == expected }
        }
        if (target == null) {
            val nodes = device.findObjects(By.clickable(true)).mapNotNull { node ->
                runCatching {
                    "${node.applicationPackage}/${node.className} text=${node.text} desc=${node.contentDescription} id=${node.resourceName}"
                }.getOrNull()
            }
            fail("Clickable UI text did not appear (case-insensitive): $text; " +
                "currentPackage=${device.currentPackageName}; clickableNodes=$nodes")
        }
        target!!.click()
    }

    private fun assertImmutable(pendingIntent: PendingIntent) {
        // PendingIntent.isImmutable() was added in API 31. Creation uses FLAG_IMMUTABLE on the API 26
        // floor too; API 31+ additionally verifies the framework-reported bit at runtime.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) assertTrue(pendingIntent.isImmutable)
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
        assertNotNull("the disclosure opens the existing control surface", notification.contentIntent)
        assertImmutable(notification.contentIntent!!)
        val actions = notification.actions
        assertNotNull("Open controls and Disconnect actions must be published", actions)
        assertEquals(2, actions!!.size)
        assertEquals("Open controls", actions[0].title.toString())
        assertEquals("Disconnect", actions[1].title.toString())
        actions.forEach { assertImmutable(it.actionIntent) }
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
        legacyConnectProbeLease?.close()
        legacyConnectProbeLease = null
        owner?.close()
        owner = null
        transportOwner?.close()
        transportOwner = null
        ownerLease?.close()
        ownerLease = null
        oldAutoConnect?.let { value -> defaultPreferences.edit { putBoolean("autologin_checkbox", value) } }
        // Do not restore POST_NOTIFICATIONS with `pm revoke` from inside instrumentation: Android
        // terminates the target process on revocation, which also terminates this runner. The outer
        // verification invocation manages the device permission state between test runs.
        oldPermissionRequestedPreference?.let { requested ->
            defaultPreferences.edit { putBoolean("session_notification_permission_requested", requested) }
        }
        oldPermissionRequestedPreference = null
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
    fun notificationActionsOpenExistingControlsAndDisconnectTransportFromBackground() {
        grantNotificationPermissionForDisclosureTest()
        val recordingOwner = RecordingTransportOwner()
        install(recordingOwner)
        val activityScenario = launchVisibleActivity()
        requestConnect(activityScenario)
        val binding = bind(activityScenario)
        eventually { binding.binder.snapshot.connection.isReady }
        assertForegroundDisclosure(binding.binder)

        val notification = context.getSystemService(NotificationManager::class.java).activeNotifications
            .first { it.id == NOTIFICATION_ID }.notification
        val openControls = requireNotNull(notification.contentIntent)
        val disconnect = notification.actions[1].actionIntent
        assertImmutable(openControls)
        assertImmutable(disconnect)
        val originalActivity = AtomicReference<MainActivity>()
        activityScenario.onActivity { originalActivity.set(it) }

        // Simulate a saved legacy auto-connect preference before notification navigation. Open is only
        // a task-navigation action; it must not toggle the legacy handler or issue a second service start.
        defaultPreferences.edit { putBoolean("autologin_checkbox", true) }
        activityScenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        openControls.send()
        Thread.sleep(250)
        instrumentation.waitForIdleSync()
        activityScenario.onActivity { assertSame("Open controls must reuse the existing MainActivity", originalActivity.get(), it) }
        assertEquals("Open controls must not create another selected-host request", 1, recordingOwner.selectionCalls.get())
        assertEquals("Open controls must not create a second transport", 1, recordingOwner.attemptsCreated)
        assertTrue(recordingOwner.controller.current.connection.isReady)

        unbind(activityScenario, binding)
        disconnect.send()
        eventually {
            recordingOwner.controller.current.connection.phase == ConnectionState.Phase.DISCONNECTED &&
                recordingOwner.controller.current.automaticConnectSuppressed &&
                binding.binder.destroyedForTests
        }
        assertNull(recordingOwner.controller.current.desiredHost)
        assertEquals("notification Disconnect must close the production transport exactly once", 1,
            recordingOwner.backend.events.count { it.name == "session.close" })
        assertTrue(recordingOwner.backend.channels.isNotEmpty())
        assertTrue("all recording channels must close", recordingOwner.backend.channels.all { it.closed })
        assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications
            .any { it.id == NOTIFICATION_ID })

        // A repeated stale action is still only Disconnect: it cannot create an owner, reconnect, or
        // clear the controller's explicit-disconnect suppression.
        try { disconnect.send() } catch (_: PendingIntent.CanceledException) { }
        eventually { recordingOwner.controller.current.automaticConnectSuppressed }
        assertEquals(1, recordingOwner.attemptsCreated)
        assertEquals(1, recordingOwner.backend.events.count { it.name == "session.close" })
        assertTrue(recordingOwner.controller.current.automaticConnectSuppressed)
    }

    @Test
    fun notificationDisconnectSuppressesOrdinaryAutoConnectButExplicitSessionStartStillWorks() {
        grantNotificationPermissionForDisclosureTest()
        val recordingOwner = RecordingTransportOwner()
        install(recordingOwner)
        val legacyAttempts = AtomicInteger()
        legacyConnectProbeLease = MyConnectionHandler.installLegacyConnectAttemptProbeForTests {
            legacyAttempts.incrementAndGet()
            true // Observe and intercept immediately before the legacy toggle/credential pipeline.
        }
        val activityScenario = launchVisibleActivity(autoConnect = true)
        eventually { legacyAttempts.get() == 1 }
        legacyAttempts.set(0)
        val originalActivity = AtomicReference<MainActivity>()
        activityScenario.onActivity { originalActivity.set(it) }

        showSessionControlsFromMenu(activityScenario)
        clickText("Start session")
        eventually { recordingOwner.controller.current.connection.isReady }
        val binding = bind(activityScenario)
        eventually { binding.binder.snapshot.connection.isReady }
        assertEquals(1, recordingOwner.selectionCalls.get())
        assertEquals(1, recordingOwner.attemptsCreated)
        val disconnect = context.getSystemService(NotificationManager::class.java).activeNotifications
            .first { it.id == NOTIFICATION_ID }.notification.actions[1].actionIntent
        val createdBeforeDisconnect = SessionForegroundService.serviceInstancesCreatedForTests

        unbind(activityScenario, binding)
        disconnect.send()
        eventually {
            binding.binder.destroyedForTests &&
                recordingOwner.controller.current.connection.phase == ConnectionState.Phase.DISCONNECTED &&
                recordingOwner.controller.current.automaticConnectSuppressed
        }
        assertNull(recordingOwner.controller.current.desiredHost)
        assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications
            .any { it.id == NOTIFICATION_ID })
        val createdAfterDisconnect = SessionForegroundService.serviceInstancesCreatedForTests
        assertEquals(createdBeforeDisconnect, createdAfterDisconnect)

        // Ordinary lifecycle return must not undo an explicit notification Disconnect via saved auto-login.
        activityScenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        activityScenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        instrumentation.waitForIdleSync()
        Thread.sleep(250)
        assertSameActivity(activityScenario, originalActivity.get())
        assertEquals("ordinary resume must not enter the legacy credential/transport path", 0,
            legacyAttempts.get())
        assertEquals(1, recordingOwner.selectionCalls.get())
        assertEquals(1, recordingOwner.attemptsCreated)
        assertEquals("ordinary resume must not create another service instance", createdAfterDisconnect,
            SessionForegroundService.serviceInstancesCreatedForTests)
        assertTrue(recordingOwner.controller.current.automaticConnectSuppressed)

        // Exercise the actual launcher entry point, not the notification's Open-controls action.
        val launcherIntent = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        assertEquals(Intent.ACTION_MAIN, launcherIntent.action)
        launcherIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_SINGLE_TOP)
        context.startActivity(launcherIntent)
        instrumentation.waitForIdleSync()
        Thread.sleep(250)
        assertSameActivity(activityScenario, originalActivity.get())
        assertEquals(0, legacyAttempts.get())
        assertEquals(1, recordingOwner.selectionCalls.get())
        assertEquals(1, recordingOwner.attemptsCreated)
        assertEquals(createdAfterDisconnect, SessionForegroundService.serviceInstancesCreatedForTests)
        assertTrue(recordingOwner.controller.current.automaticConnectSuppressed)

        // Suppression is for automatic reconnect only: the explicit Session controls Start remains usable.
        showSessionControlsFromMenu(activityScenario)
        clickText("Start session")
        eventually { recordingOwner.selectionCalls.get() == 2 }
        val startDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!recordingOwner.controller.current.connection.isReady && System.nanoTime() < startDeadline) {
            Thread.sleep(10)
        }
        assertTrue("explicit service restart did not become ready: snapshot=${recordingOwner.controller.current}, " +
            "events=${recordingOwner.backend.events.map { it.name }}",
            recordingOwner.controller.current.connection.isReady)
        assertEquals(2, recordingOwner.attemptsCreated)
        assertFalse(recordingOwner.controller.current.automaticConnectSuppressed)
        assertEquals(createdAfterDisconnect + 1, SessionForegroundService.serviceInstancesCreatedForTests)
        assertEquals("explicit service Start never fell through to the legacy handler", 0, legacyAttempts.get())
        SessionForegroundService.requestDisconnect(context)
        eventually { recordingOwner.controller.current.connection.phase == ConnectionState.Phase.DISCONNECTED }
    }

    @Test
    fun legacyToolbarAndDirectConnectAreBlockedWhileServiceSelectionAndSessionAreActive() {
        grantNotificationPermissionForDisclosureTest()
        val recordingOwner = RecordingTransportOwner(manualSelections = true)
        install(recordingOwner)
        val legacyAttempts = AtomicInteger()
        legacyConnectProbeLease = MyConnectionHandler.installLegacyConnectAttemptProbeForTests {
            legacyAttempts.incrementAndGet()
            true
        }
        val activityScenario = launchVisibleActivity()
        requestConnect(activityScenario)
        val binding = bind(activityScenario)
        assertTrue(recordingOwner.selectionStarted.await(3, TimeUnit.SECONDS))
        eventually { SessionForegroundService.hasPendingStart(context) }
        assertNull("the pending selection deliberately has no desired host yet",
            recordingOwner.controller.current.desiredHost)
        assertEquals(0, recordingOwner.attemptsCreated)
        val serviceCountDuringSelection = SessionForegroundService.serviceInstancesCreatedForTests

        // Exercise both old toolbar routing and a direct call to the legacy connect root.
        invokeLegacyToolbarConnect(activityScenario)
        waitForTextContains("foreground session request is waiting for notification permission or host selection")
        clickText("Close")
        activityScenario.onActivity { MainActivity.conn.xMouseTryConnect() }
        waitForTextContains("foreground session request is waiting for notification permission or host selection")
        clickText("Close")
        assertEquals("pending metadata must block legacy credential/transport attempts", 0, legacyAttempts.get())
        assertEquals(1, recordingOwner.selectionCalls.get())
        assertEquals(0, recordingOwner.attemptsCreated)
        assertEquals(serviceCountDuringSelection, SessionForegroundService.serviceInstancesCreatedForTests)

        recordingOwner.completeSelection(0, SessionFixtures.host("pending-service.invalid"))
        eventually { recordingOwner.controller.current.connection.isReady }
        eventually { !SessionForegroundService.hasPendingStart(context) }
        invokeLegacyToolbarConnect(activityScenario)
        waitForTextContains("A foreground session is active")
        clickText("Open controls")
        activityScenario.onActivity { MainActivity.conn.xMouseTryConnect() }
        waitForTextContains("A foreground session is active")
        clickText("Open controls")
        assertEquals("active service state must block every legacy connect entry point", 0, legacyAttempts.get())
        assertEquals(1, recordingOwner.selectionCalls.get())
        assertEquals(1, recordingOwner.attemptsCreated)
        assertEquals(serviceCountDuringSelection, SessionForegroundService.serviceInstancesCreatedForTests)

        unbind(activityScenario, binding)
        SessionForegroundService.requestDisconnect(context)
        eventually { recordingOwner.controller.current.connection.phase == ConnectionState.Phase.DISCONNECTED }
    }

    @Test
    fun notificationDisconnectInvalidatesPendingMetadataBeforeItCanStartTransport() {
        grantNotificationPermissionForDisclosureTest()
        val recordingOwner = RecordingOwner(manualSelections = true)
        install(recordingOwner)
        val activityScenario = launchVisibleActivity()
        requestConnect(activityScenario)
        val binding = bind(activityScenario)
        assertTrue(recordingOwner.selectionStarted.await(3, TimeUnit.SECONDS))
        assertForegroundDisclosure(binding.binder)
        val notification = context.getSystemService(NotificationManager::class.java).activeNotifications
            .first { it.id == NOTIFICATION_ID }.notification
        val disconnect = notification.actions[1].actionIntent

        unbind(activityScenario, binding)
        activityScenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        disconnect.send()
        eventually {
            binding.binder.destroyedForTests && recordingOwner.controller.current.automaticConnectSuppressed
        }
        recordingOwner.completeSelection(0, SessionFixtures.host("stale-notification.invalid"))
        eventually { recordingOwner.selectionsCompleted.get() == 1 }
        instrumentation.waitForIdleSync()
        assertEquals("stale pending host metadata must never create a transport", 0,
            recordingOwner.attempts.created.get())
        assertNull(recordingOwner.controller.current.desiredHost)
        assertTrue(recordingOwner.controller.current.automaticConnectSuppressed)
        assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications
            .any { it.id == NOTIFICATION_ID })
    }

    @Test
    fun notificationDisconnectKeepsOwnerWhenProductionTransportCloseIsUnconfirmed() {
        grantNotificationPermissionForDisclosureTest()
        val recordingOwner = RecordingTransportOwner(blockCloses = true)
        install(recordingOwner)
        val activityScenario = launchVisibleActivity()
        requestConnect(activityScenario)
        val binding = bind(activityScenario)
        eventually { binding.binder.snapshot.connection.isReady }
        val notification = context.getSystemService(NotificationManager::class.java).activeNotifications
            .first { it.id == NOTIFICATION_ID }.notification
        val disconnect = notification.actions[1].actionIntent

        unbind(activityScenario, binding)
        activityScenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        disconnect.send()
        assertTrue("recording transport cleanup did not start", recordingOwner.backend.entered.await(3, TimeUnit.SECONDS))
        eventually { recordingOwner.controller.current.cleanupUnconfirmed && binding.binder.destroyedForTests }
        assertTrue(recordingOwner.controller.current.automaticConnectSuppressed)
        assertNull(recordingOwner.controller.current.desiredHost)
        assertSame("uncertain cleanup must retain the original application owner",
            recordingOwner, (context.applicationContext as XMouseApplication).sessionOwners.retainedOwnerOrNull())
        assertFalse("Disconnect must remove the foreground notification even when close is unconfirmed",
            context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == NOTIFICATION_ID })

        recordingOwner.backend.release.countDown()
        try { disconnect.send() } catch (_: PendingIntent.CanceledException) { }
        Thread.sleep(200)
        assertTrue(recordingOwner.controller.current.cleanupUnconfirmed)
        assertTrue(recordingOwner.controller.current.automaticConnectSuppressed)
        assertEquals("an uncertain close is never bypassed by a repeated action", 1, recordingOwner.attemptsCreated)
    }

    @Test
    fun staleDisconnectAfterOwnerAbsenceDoesNotCreateAnOwnerOrForegroundService() {
        val registry = (context.applicationContext as XMouseApplication).sessionOwners
        assertNull(registry.retainedOwnerOrNull())
        val activityScenario = launchVisibleActivity()
        activityScenario.onActivity { activity ->
            activity.startService(Intent(activity, SessionForegroundService::class.java)
                .setAction(SessionForegroundService.ACTION_NOTIFICATION_DISCONNECT))
        }
        eventually { registry.retainedOwnerOrNull() == null }
        assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications
            .any { it.id == NOTIFICATION_ID })
        activityScenario.close()
        scenario = null
    }

    @SdkSuppress(minSdkVersion = 33)
    @Test
    fun stalePermissionResultsAfterRecreationAndDisconnectCannotStartOrClearNewerRequest() {
        org.junit.Assume.assumeTrue("run with notification permission revoked by the host between test runs",
            context.checkSelfPermission(POST_NOTIFICATIONS_PERMISSION) == PackageManager.PERMISSION_DENIED)
        resetNotificationPermissionPrompt()
        val recordingOwner = RecordingTransportOwner()
        install(recordingOwner)
        val legacyAttempts = AtomicInteger()
        legacyConnectProbeLease = MyConnectionHandler.installLegacyConnectAttemptProbeForTests {
            legacyAttempts.incrementAndGet()
            true
        }

        val activityScenario = launchVisibleActivity()
        showSessionControlsFromMenu(activityScenario)
        clickText("Start session")
        waitForText("Show session notification actions?")
        val oldTicketRef = AtomicReference<String?>()
        activityScenario.onActivity { activity ->
            oldTicketRef.set(activityStringField(activity, "pendingForegroundStartTicket"))
            setActivityStringField(activity, "pendingNotificationPermissionTicket", oldTicketRef.get())
        }
        val oldTicket = requireNotNull(oldTicketRef.get())
        assertTrue(SessionForegroundService.hasReservedStart(context, oldTicket))
        val serviceInstancesBeforeRecreation = SessionForegroundService.serviceInstancesCreatedForTests

        // MainActivity deliberately cancels its activity-owned ticket on destroy. A restored
        // ActivityResult therefore arrives with no local request capability in the new Activity.
        activityScenario.recreate()
        assertFalse(SessionForegroundService.hasReservedStart(context, oldTicket))
        assertFalse(SessionForegroundService.hasPendingStart(context))
        val recreatedActivity = AtomicReference<MainActivity>()
        activityScenario.onActivity { recreatedActivity.set(it) }
        assertNull(activityStringField(recreatedActivity.get(), "pendingForegroundStartTicket"))
        assertNull(activityStringField(recreatedActivity.get(), "pendingNotificationPermissionTicket"))
        deliverPermissionResult(activityScenario, granted = true)
        deliverPermissionResult(activityScenario, granted = false)
        assertFalse(SessionForegroundService.hasPendingStart(context))
        assertEquals(0, recordingOwner.selectionCalls.get())
        assertEquals(0, recordingOwner.attemptsCreated)
        assertEquals(0, legacyAttempts.get())
        assertEquals(serviceInstancesBeforeRecreation, SessionForegroundService.serviceInstancesCreatedForTests)
        assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications
            .any { it.id == NOTIFICATION_ID })

        // A stale callback tagged with the old ticket must not erase a newer explicit Start request.
        showSessionControlsFromMenu(activityScenario)
        clickText("Start session")
        waitForText("Show session notification actions?")
        val newerTicketRef = AtomicReference<String?>()
        activityScenario.onActivity { activity ->
            newerTicketRef.set(activityStringField(activity, "pendingForegroundStartTicket"))
            setActivityStringField(activity, "pendingNotificationPermissionTicket", newerTicketRef.get())
        }
        val newerTicket = requireNotNull(newerTicketRef.get())
        assertNotEquals(oldTicket, newerTicket)
        assertTrue(SessionForegroundService.hasReservedStart(context, newerTicket))
        deliverPermissionResultForTicket(activityScenario, oldTicket, granted = true)
        deliverPermissionResultForTicket(activityScenario, oldTicket, granted = false)
        val currentTickets = AtomicReference<Pair<String?, String?>>()
        activityScenario.onActivity { activity ->
            currentTickets.set(Pair(
                activityStringField(activity, "pendingForegroundStartTicket"),
                activityStringField(activity, "pendingNotificationPermissionTicket"),
            ))
        }
        assertEquals(Pair(newerTicket, newerTicket), currentTickets.get())
        assertTrue(SessionForegroundService.hasReservedStart(context, newerTicket))
        assertEquals(0, recordingOwner.selectionCalls.get())
        assertEquals(0, recordingOwner.attemptsCreated)
        assertEquals(0, legacyAttempts.get())

        // The actual Disconnect service action invalidates the newer reservation before its result.
        val serviceInstancesBeforeDisconnect = SessionForegroundService.serviceInstancesCreatedForTests
        assertTrue(SessionForegroundService.requestDisconnect(context))
        eventually {
            !SessionForegroundService.hasPendingStart(context) &&
                !SessionForegroundService.hasReservedStart(context, newerTicket) &&
                SessionForegroundService.serviceInstancesCreatedForTests > serviceInstancesBeforeDisconnect
        }
        val serviceInstancesAfterDisconnect = SessionForegroundService.serviceInstancesCreatedForTests
        deliverPermissionResult(activityScenario, granted = true)
        deliverPermissionResult(activityScenario, granted = false)
        assertFalse(SessionForegroundService.hasPendingStart(context))
        val clearedTickets = AtomicReference<Pair<String?, String?>>()
        activityScenario.onActivity { activity ->
            clearedTickets.set(Pair(
                activityStringField(activity, "pendingForegroundStartTicket"),
                activityStringField(activity, "pendingNotificationPermissionTicket"),
            ))
        }
        assertEquals(Pair(null, null), clearedTickets.get())
        assertEquals(0, recordingOwner.selectionCalls.get())
        assertEquals(0, recordingOwner.attemptsCreated)
        assertEquals(0, legacyAttempts.get())
        assertEquals(serviceInstancesAfterDisconnect, SessionForegroundService.serviceInstancesCreatedForTests)
        assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications
            .any { it.id == NOTIFICATION_ID })

        // An explicit fresh Start remains usable after both stale result paths.
        clickText("Not now")
        showSessionControlsFromMenu(activityScenario)
        clickText("Start session")
        waitForText("Show session notification actions?")
        clickText("Continue without drawer actions")
        eventually { recordingOwner.controller.current.connection.isReady }
        assertEquals(1, recordingOwner.selectionCalls.get())
        assertEquals(1, recordingOwner.attemptsCreated)
        assertEquals(0, legacyAttempts.get())
    }

    @SdkSuppress(minSdkVersion = 33)
    @Test
    fun notificationPermissionGrantedAtPointOfUsePublishesActionsAfterSystemConsent() {
        org.junit.Assume.assumeTrue("run with notification permission revoked by the host between test runs",
            context.checkSelfPermission(POST_NOTIFICATIONS_PERMISSION) == PackageManager.PERMISSION_DENIED)
        resetNotificationPermissionPrompt()
        val recordingOwner = RecordingTransportOwner()
        install(recordingOwner)
        val activityScenario = launchVisibleActivity()
        showSessionControlsFromMenu(activityScenario)
        clickText("Start session")
        waitForText("Show session notification actions?")
        assertTrue(device.findObject(By.textContains("notification drawer")).text.contains("Task Manager"))
        clickText("Request permission")
        clickText("Allow")

        eventually { context.checkSelfPermission(POST_NOTIFICATIONS_PERMISSION) == PackageManager.PERMISSION_GRANTED }
        eventually { recordingOwner.controller.current.connection.isReady }
        assertTrue(context.getSystemService(NotificationManager::class.java).areNotificationsEnabled())
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications
            .any { it.id == NOTIFICATION_ID })
        val notification = context.getSystemService(NotificationManager::class.java).activeNotifications
            .first { it.id == NOTIFICATION_ID }.notification
        assertEquals(2, notification.actions.size)
        notification.actions.forEach { assertImmutable(it.actionIntent) }
        assertEquals(1, recordingOwner.attemptsCreated)
    }

    @SdkSuppress(minSdkVersion = 33)
    @Test
    fun notificationPermissionDenialExplainsTaskManagerAndPreservesInAppDisconnect() {
        org.junit.Assume.assumeTrue("run with notification permission revoked by the host between test runs",
            context.checkSelfPermission(POST_NOTIFICATIONS_PERMISSION) == PackageManager.PERMISSION_DENIED)
        resetNotificationPermissionPrompt()
        val recordingOwner = RecordingTransportOwner()
        install(recordingOwner)
        val legacyAttempts = AtomicInteger()
        legacyConnectProbeLease = MyConnectionHandler.installLegacyConnectAttemptProbeForTests {
            legacyAttempts.incrementAndGet()
            true
        }
        val activityScenario = launchVisibleActivity()
        showSessionControlsFromMenu(activityScenario)
        clickText("Start session")
        waitForText("Show session notification actions?")
        assertTrue("permission rationale must reserve the service request before prompting",
            SessionForegroundService.hasPendingStart(context))
        activityScenario.onActivity { MainActivity.conn.xMouseTryConnect() }
        assertEquals("legacy connect must remain blocked while permission is pending", 0, legacyAttempts.get())
        assertTrue(SessionForegroundService.hasPendingStart(context))
        clickText("Request permission")
        clickText("Don't allow")

        waitForText("Notification permission denied")
        assertTrue(device.findObject(By.textContains("Task Manager")).text.contains("notification drawer"))
        assertEquals(PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(POST_NOTIFICATIONS_PERMISSION))
        clickText("Continue without drawer actions")
        eventually { recordingOwner.controller.current.connection.isReady }
        assertFalse(context.getSystemService(NotificationManager::class.java).areNotificationsEnabled())

        device.openNotification()
        assertFalse("denial must not be described as a visible drawer notification",
            device.wait(Until.hasObject(By.text("XMouse remote session")), 1_000))
        device.pressBack()
        instrumentation.waitForIdleSync()
        showSessionControlsFromMenu(activityScenario)
        waitForText("Disconnect session")
        assertTrue(device.findObject(By.textContains("Task Manager")).text.contains("notification drawer"))
        clickText("Disconnect session")
        eventually {
            recordingOwner.controller.current.connection.phase == ConnectionState.Phase.DISCONNECTED &&
                recordingOwner.controller.current.automaticConnectSuppressed
        }
        assertEquals(1, recordingOwner.backend.events.count { it.name == "session.close" })
        assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications
            .any { it.id == NOTIFICATION_ID })
    }

    @SdkSuppress(minSdkVersion = 33)
    @Test
    fun revokedNotificationPermissionAllowsSessionAndInAppDisconnectWithoutPromptingOnResume() {
        org.junit.Assume.assumeTrue("revoke POST_NOTIFICATIONS outside instrumentation before this isolated run",
            context.checkSelfPermission(POST_NOTIFICATIONS_PERMISSION) == PackageManager.PERMISSION_DENIED)
        if (oldPermissionRequestedPreference == null) {
            oldPermissionRequestedPreference = defaultPreferences.getBoolean("session_notification_permission_requested", false)
        }
        // A real runtime-permission revoke kills the target process on this API level. The host applies
        // that revoke between isolated invocations; this run verifies the next foreground use remains legal.
        defaultPreferences.edit { putBoolean("session_notification_permission_requested", true) }
        val recordingOwner = RecordingTransportOwner()
        install(recordingOwner)
        val activityScenario = launchVisibleActivity()
        showSessionControlsFromMenu(activityScenario)
        clickText("Start session")
        clickText("Continue without drawer actions")
        eventually { recordingOwner.controller.current.connection.isReady }
        assertEquals(PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(POST_NOTIFICATIONS_PERMISSION))
        assertFalse(context.getSystemService(NotificationManager::class.java).areNotificationsEnabled())
        activityScenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        activityScenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        instrumentation.waitForIdleSync()
        assertFalse("resuming must never loop the runtime permission prompt",
            device.wait(Until.hasObject(By.text("Request permission")), 500))
        assertTrue("the session can start after notification permission was revoked",
            recordingOwner.controller.current.connection.isReady)

        showSessionControlsFromMenu(activityScenario)
        waitForText("Disconnect session")
        assertTrue(device.findObject(By.textContains("Task Manager")).text.contains("notification drawer"))
        clickText("Disconnect session")
        eventually { recordingOwner.controller.current.automaticConnectSuppressed &&
            recordingOwner.controller.current.connection.phase == ConnectionState.Phase.DISCONNECTED }
        assertEquals(1, recordingOwner.backend.events.count { it.name == "session.close" })
    }

    @SdkSuppress(minSdkVersion = 33)
    @Test
    fun permanentNotificationDenialOffersSettingsInsteadOfAnotherRuntimePrompt() {
        org.junit.Assume.assumeTrue("run with notification permission revoked by the host between test runs",
            context.checkSelfPermission(POST_NOTIFICATIONS_PERMISSION) == PackageManager.PERMISSION_DENIED)
        if (oldPermissionRequestedPreference == null) {
            oldPermissionRequestedPreference = defaultPreferences.getBoolean("session_notification_permission_requested", false)
        }
        shell("pm set-permission-flags ${context.packageName} $POST_NOTIFICATIONS_PERMISSION user-set user-fixed")
        defaultPreferences.edit { putBoolean("session_notification_permission_requested", true) }
        val recordingOwner = RecordingTransportOwner()
        install(recordingOwner)
        val activityScenario = launchVisibleActivity()
        showSessionControlsFromMenu(activityScenario)
        clickText("Start session")
        waitForText("Notification visibility is off")
        assertTrue(device.findObject(By.textContains("another permission prompt")).text.contains("notification settings"))
        clickText("Open notification settings")
        assertTrue("Settings action should display Android notification settings",
            device.wait(Until.hasObject(By.pkg("com.android.settings")), 5_000))
        device.pressBack()
        instrumentation.waitForIdleSync()
        assertFalse("permanent denial must not relaunch the runtime prompt on return",
            device.wait(Until.hasObject(By.text("Allow")), 500))
        showSessionControlsFromMenu(activityScenario)
        clickText("Continue request")
        waitForText("Notification visibility is off")
        assertTrue(device.findObject(By.textContains("another permission prompt")).text.contains("notification settings"))
        assertFalse("retry after permanent denial must use settings, not another runtime dialog",
            device.wait(Until.hasObject(By.text("Allow")), 500))
        assertEquals(0, recordingOwner.selectionCalls.get())
    }

    @SdkSuppress(maxSdkVersion = 32)
    @Test
    fun api26ConnectControlDoesNotRequestUnsupportedNotificationPermission() {
        val recordingOwner = RecordingTransportOwner()
        install(recordingOwner)
        val activityScenario = launchVisibleActivity()
        showSessionControlsFromMenu(activityScenario)
        clickText("Start session")
        eventually { recordingOwner.controller.current.connection.isReady }
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications
            .any { it.id == NOTIFICATION_ID })
        assertEquals(1, recordingOwner.selectionCalls.get())
        assertEquals(1, recordingOwner.attemptsCreated)
        assertFalse("API 26 must not display the unsupported notification permission request",
            device.hasObject(By.text("Request permission")))
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
