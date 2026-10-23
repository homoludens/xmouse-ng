package com.stripe1.xmouse.session

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.SystemClock
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.ListView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.core.content.edit
import androidx.core.view.GravityCompat
import androidx.appcompat.widget.Toolbar
import androidx.drawerlayout.widget.DrawerLayout
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import com.stripe1.xmouse.MainActivity
import com.stripe1.xmouse.DatabaseHandler
import com.stripe1.xmouse.CustomKeyboardButtonAdapter
import com.stripe1.xmouse.ListEditActivity
import com.stripe1.xmouse.MyJSView
import com.stripe1.xmouse.MyMouseView
import com.stripe1.xmouse.R
import com.stripe1.xmouse.SpanVariableGridView
import com.stripe1.xmouse.XMouseApplication
import com.stripe1.xmouse.layout.LayoutButton
import com.stripe1.xmouse.layout.LayoutEditorActivity
import com.stripe1.xmouse.layout.LayoutProfile
import com.stripe1.xmouse.layout.LayoutProfileRepository
import com.stripe1.xmouse.layout.LegacyLayoutProfileMigrator
import com.stripe1.xmouse.connection.ConnectionFailure
import com.stripe1.xmouse.connection.ConnectionState
import com.stripe1.xmouse.connection.Endpoint
import com.stripe1.xmouse.connection.EndpointLocator
import com.stripe1.xmouse.secrets.AuthenticationMaterial
import com.stripe1.xmouse.secrets.CredentialKind
import com.stripe1.xmouse.secrets.CredentialReadResult
import com.stripe1.xmouse.secrets.CredentialReentryRequest
import com.stripe1.xmouse.secrets.SecretId
import com.stripe1.xmouse.secrets.AndroidSecretFileIo
import com.stripe1.xmouse.transport.RecordingSshBackend
import com.stripe1.xmouse.transport.SshBackend
import com.stripe1.xmouse.transport.SshConnectionRequest
import com.stripe1.xmouse.transport.SshLimits
import com.stripe1.xmouse.transport.SshRuntime
import com.stripe1.xmouse.discovery.SshServiceDiscovery
import com.stripe1.xmouse.trust.HostKeyRepository
import com.stripe1.xmouse.trust.ApprovedHostKey
import com.stripe1.xmouse.trust.HostTrustException
import com.stripe1.xmouse.trust.HostTrustRequests
import com.stripe1.xmouse.session.SessionForegroundService.Companion.NOTIFICATION_CHANNEL_ID
import com.stripe1.xmouse.session.SessionForegroundService.Companion.NOTIFICATION_ID
import com.stripe1.xmouse.permissions.LocalNetworkPermissionCoordinator
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
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
import java.util.function.BooleanSupplier
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil

/** Real target service/notification checks with synthetic attempts or the production transport's recording backend. */
@RunWith(AndroidJUnit4::class)
class SessionForegroundServiceAndroidTest {
    private companion object { const val POST_NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS" }

    @get:Rule val compose = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val defaultPreferences get() = context.getSharedPreferences(
        "${context.packageName}_preferences", Context.MODE_PRIVATE,
    )

    @Before
    fun clearIdleOwnerRetainedByEarlierActivityTests() {
        context.stopService(Intent(context, SessionForegroundService::class.java))
        eventually {
            SessionForegroundService.serviceInstancesCreatedForTests ==
                SessionForegroundService.serviceInstancesDestroyedForTests
        }
        (context.applicationContext as XMouseApplication).sessionOwners.clearIdleOwnerForTests()
    }
    private var scenario: ActivityScenario<MainActivity>? = null
    private var ownerLease: AutoCloseable? = null
    private var owner: RecordingOwner? = null
    private var transportOwner: RecordingTransportOwner? = null
    private var oldAutoConnect: Boolean? = null
    private var oldKeyboardBatch: Boolean? = null
    private var oldKeyboardBatchPresent = false
    private var oldInputBackend: String? = null
    private var oldInitialCommand: String? = null
    private var oldGyroEnabled: Boolean? = null
    private val oldGyroSettings = mutableMapOf<String, String?>()
    private var oldSettingDelay: Int? = null
    private var savedScriptId = 0
    private var oldPermissionRequestedPreference: Boolean? = null
    private val device get() = UiDevice.getInstance(instrumentation)

    private class RecordingAttemptFactory : SessionAttemptFactory {
        val created = AtomicInteger()
        val closes = AtomicInteger()
        val failClose = AtomicBoolean()
        val createdHosts = CopyOnWriteArrayList<SessionHost>()
        private val nextFailures = ArrayDeque<ConnectionFailure>()

        fun failNextWith(vararg failures: ConnectionFailure) {
            synchronized(nextFailures) { nextFailures.addAll(failures.toList()) }
        }

        override fun create(host: SessionHost): SessionAttempt {
            created.incrementAndGet()
            createdHosts.add(host)
            return object : SessionAttempt {
                override fun start(listener: SessionAttempt.Listener) {
                    listener.phase(ConnectionState.Phase.RESOLVING)
                    val failure = synchronized(nextFailures) {
                        if (nextFailures.isEmpty()) null else nextFailures.removeFirst()
                    }
                    if (failure == null) listener.ready() else listener.failed(failure)
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
        private val selectedHost: SessionHost? = null,
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
                completion(selectedHost ?: SessionFixtures.host())
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
        private val credentialPromptReads: Int = 0,
        private val requireFirstTrust: Boolean = false,
        private val automaticallySelectedHost: SessionHost = SessionFixtures.host(),
        blockExecConnect: Boolean = false,
    ) : SessionServiceOwner, AutoCloseable {
        val directory = File(ApplicationProvider.getApplicationContext<Context>().cacheDir,
            "notification-session-${java.util.UUID.randomUUID()}")
        val backend = RecordingSshBackend().apply {
            if (blockCloses) { blockAllCloses = true; ignoreInterrupts = true }
            if (blockExecConnect) { blockAt = "exec.connect"; ignoreInterrupts = true }
        }
        val selectionCalls = AtomicInteger()
        val selectionsCompleted = AtomicInteger()
        val credentialReads = AtomicInteger()
        val cancelledCredentialRequests = AtomicInteger()
        val credentialRequests = CopyOnWriteArrayList<CredentialReentryRequest>()
        val backendRequests = CopyOnWriteArrayList<SshConnectionRequest>()
        val attemptsCreated get() = backend.events.count { it.name == "create" }
        val selectionStarted = CountDownLatch(1)
        init { check(directory.mkdirs() || directory.isDirectory) }
        private val pendingSelections = mutableListOf<(SessionHost?) -> Unit>()
        private val selectionWorker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "synthetic-notification-selection").apply { isDaemon = true }
        }
        private val credentialWorker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "synthetic-notification-credentials").apply { isDaemon = true }
        }
        override val interactions = SessionInteractions(Executor { android.os.Handler(Looper.getMainLooper()).post(it) })
        private val credentialProvider = SessionCredentialProvider { selection, prompts, current, callback ->
            credentialWorker.execute {
                if (current.asBoolean) {
                    val readNumber = credentialReads.incrementAndGet()
                    val host = SessionFixtures.host(selection.host.host ?: "synthetic.invalid", selection.host.id,
                        selection.host.passwordReference ?: "synthetic_password", selection.host.port?.toInt() ?: 2222)
                    if (readNumber <= credentialPromptReads) {
                        val request = CredentialReentryRequest(
                            SecretId("synthetic_reentry_${selection.host.id}_$readNumber"),
                        ) { result ->
                            when (result) {
                                is CredentialReadResult.Available -> {
                                    result.plaintext.fill(0)
                                    callback.complete(SessionFixtures.material(host), null)
                                }
                                else -> {
                                    if (result === CredentialReadResult.Cancelled) cancelledCredentialRequests.incrementAndGet()
                                    callback.complete(null, null)
                                }
                            }
                        }
                        credentialRequests.add(request)
                        prompts.request(CredentialKind.PASSWORD, request)
                    } else callback.complete(SessionFixtures.material(host), null)
                } else callback.complete(null, null)
            }
        }
        private val syntheticHostKey = ApprovedHostKey.parse(OpenSSHPublicKeyUtil.encodePublicKey(
            Ed25519PrivateKeyParameters(ByteArray(32) { (it + 1).toByte() }, 0).generatePublicKey(),
        ))
        private val attemptFactory = SshSessionAttemptFactory(
            credentialProvider,
            HostKeyRepository(directory, AndroidSecretFileIo),
            interactions,
            SshLimits(200, if (requireFirstTrust) 60_000 else 350, 200, 600,
                if (blockExecConnect) 10_000 else 250, 250, if (blockExecConnect) 10_000 else 450, 450,
                if (blockExecConnect) 2_000 else 150, 150, 2, 50, 150, 4096),
            SshRuntime.SHARED,
            credentialTimeoutMillis = 2_000,
            closeTimeoutMillis = if (blockExecConnect) 5_000 else 1_000,
            backendFactory = { trust, trusted ->
                object : SshBackend {
                    override fun create(request: SshConnectionRequest, authentication: AuthenticationMaterial): SshBackend.Session =
                        create(request, authentication, BooleanSupplier { true })

                    override fun create(
                        request: SshConnectionRequest,
                        authentication: AuthenticationMaterial,
                        current: BooleanSupplier,
                    ): SshBackend.Session {
                        if (requireFirstTrust) {
                            trust.verify(request.endpoint, syntheticHostKey, current)
                            trusted.run()
                        }
                        backendRequests.add(request)
                        return backend.create(request, authentication, current)
                    }

                    override fun cancelPendingTrust() = backend.cancelPendingTrust()
                    override fun replaceHostKey(mismatch: com.stripe1.xmouse.trust.HostKeyMismatch,
                        current: BooleanSupplier) = backend.replaceHostKey(mismatch, current)
                }
            },
        )
        override val controller = SessionController(attemptFactory)

        override fun selectedHost(shell: String, initialCommand: String?, completion: (SessionHost?) -> Unit) {
            selectionCalls.incrementAndGet()
            if (manualSelections) {
                synchronized(pendingSelections) { pendingSelections.add(completion) }
                selectionStarted.countDown()
            } else {
                selectionWorker.execute {
                    completion(automaticallySelectedHost.copy(shell = shell, initialCommand = initialCommand))
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

    private class RecordingDiscoveryFactory : ListEditActivity.DiscoveryFactory {
        class Handle(val listener: SshServiceDiscovery.Listener) : ListEditActivity.DiscoveryHandle {
            val starts = AtomicInteger()
            val closes = AtomicInteger()
            override fun start() { starts.incrementAndGet() }
            override fun close() { closes.incrementAndGet() }
        }

        val handles = CopyOnWriteArrayList<Handle>()

        override fun create(context: Context, listener: SshServiceDiscovery.Listener): ListEditActivity.DiscoveryHandle =
            Handle(listener).also(handles::add)
    }

    private fun install(owner: RecordingOwner) {
        this.owner = owner
        ownerLease = (context.applicationContext as XMouseApplication).sessionOwners.installOwnerForTests(owner)
    }

    private fun install(owner: RecordingTransportOwner) {
        this.transportOwner = owner
        ownerLease = (context.applicationContext as XMouseApplication).sessionOwners.installOwnerForTests(owner)
    }

    private fun launchVisibleActivity(
        autoConnect: Boolean = false,
        inputBackend: String? = null,
        initialCommand: String? = null,
    ): ActivityScenario<MainActivity> {
        val preferences = defaultPreferences
        oldAutoConnect = preferences.getBoolean("autologin_checkbox", false)
        oldInputBackend = preferences.getString("setting_dotool_initial", null)
        oldInitialCommand = preferences.getString("setting_xdotool_initial", null)
        preferences.edit {
            putBoolean("autologin_checkbox", autoConnect)
            inputBackend?.let { putString("setting_dotool_initial", it) }
            initialCommand?.let { putString("setting_xdotool_initial", it) }
        }
        return ActivityScenario.launch(MainActivity::class.java).also { scenario = it }
    }

    private fun dispatchTouch(view: View, downTime: Long, eventTime: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        try { assertTrue("legacy control rejected a touch event", view.onTouchEvent(event)) }
        finally { event.recycle() }
    }

    private fun dispatchCapturedButtonTouch(view: View, downTime: Long, eventTime: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        try { assertTrue("captured button rejected its pointer sequence", view.dispatchTouchEvent(event)) }
        finally { event.recycle() }
    }

    private fun useImmediateTouchDelayForTest() {
        if (oldSettingDelay == null) oldSettingDelay = MainActivity.setting_delay
        MainActivity.setting_delay = 0
    }

    private fun clickLegacyButton(activity: MainActivity, title: String) {
        val grid = activity.findViewById<SpanVariableGridView>(R.id.key_drag_DragAndDropGridView)
        val adapter = grid.adapter as CustomKeyboardButtonAdapter
        val position = (0 until adapter.count).firstOrNull { adapter.getItem(it)?.getmTitle() == title }
        assertNotNull("legacy custom button is missing: $title", position)
        val button = grid.getChildAt(requireNotNull(position))
        assertNotNull("legacy custom button view was not attached: $title", button)
        assertTrue("legacy custom button did not receive its click: $title", button.performClick())
    }

    private fun addSavedScript(activity: MainActivity, alias: String, command: String): Int {
        val database = MainActivity::class.java.getDeclaredField("db").apply { isAccessible = true }
            .get(activity) as DatabaseHandler
        val fields = ArrayList<ArrayList<String>>().apply {
            add(arrayListOf("Alias", alias))
            add(arrayListOf("Script", command))
        }
        database.addRow(DatabaseHandler.SCRIPT_TABLE_NAME, fields)
        val row = database.listAll(DatabaseHandler.SCRIPT_TABLE_NAME, arrayOf("Alias", "Script", "id"))
            .single { it[0] == alias }
        return row[2].toInt().also { savedScriptId = it; activity.initMenu() }
    }

    private fun invokeToolbarConnectAction(scenario: ActivityScenario<MainActivity>) {
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

    private fun activitySnapshot(activity: MainActivity): SessionSnapshot? =
        MainActivity::class.java.getDeclaredField("sessionSnapshot").apply { isAccessible = true }
            .get(activity) as SessionSnapshot?

    private fun activityPrompt(activity: MainActivity): SessionInteractions.Prompt? {
        val presenter = MainActivity::class.java.getDeclaredField("sessionPresenter").apply { isAccessible = true }
            .get(activity)
        return presenter.javaClass.getDeclaredField("currentPrompt").apply { isAccessible = true }
            .get(presenter) as SessionInteractions.Prompt?
    }

    private fun activityPrompt(scenario: ActivityScenario<MainActivity>): SessionInteractions.Prompt? {
        var prompt: SessionInteractions.Prompt? = null
        scenario.onActivity { prompt = activityPrompt(it) }
        return prompt
    }

    private fun activityPresentationToken(activity: MainActivity): SessionInteractions.PresentationToken? =
        (MainActivity::class.java.getDeclaredField("sessionPresenter").apply { isAccessible = true }
            .get(activity) as SessionActivityPresenter).presentationToken()

    private fun recentCommandText(activity: MainActivity): String =
        activity.findViewById<android.widget.TextView>(R.id.recentCmd).text.toString()

    private fun recentCommandTextOn(scenario: ActivityScenario<MainActivity>): String {
        var actual = ""
        scenario.onActivity { actual = recentCommandText(it) }
        return actual
    }

    private fun waitForRecentCommand(scenario: ActivityScenario<MainActivity>, expected: String) {
        eventually {
            var actual: String? = null
            scenario.onActivity { actual = recentCommandText(it) }
            actual == expected
        }
    }

    private fun activityOutputDialog(activity: MainActivity): AlertDialog? {
        val presenter = MainActivity::class.java.getDeclaredField("sessionPresenter").apply { isAccessible = true }
            .get(activity)
        return presenter.javaClass.getDeclaredField("outputDialog").apply { isAccessible = true }
            .get(presenter) as AlertDialog?
    }

    private fun discoveryDialog(activity: ListEditActivity): AlertDialog? =
        ListEditActivity::class.java.getDeclaredField("discoveryDialog").apply { isAccessible = true }
            .get(activity) as AlertDialog?

    @Suppress("UNCHECKED_CAST")
    private fun discoveryCandidates(activity: ListEditActivity): Map<Any, Any> =
        ListEditActivity::class.java.getDeclaredField("discoveredCandidates").apply { isAccessible = true }
            .get(activity) as Map<Any, Any>

    private fun discoveryError(activity: ListEditActivity): String? =
        ListEditActivity::class.java.getDeclaredField("discoveryError").apply { isAccessible = true }
            .get(activity) as String?

    private fun waitForActivitySessionBinding(scenario: ActivityScenario<MainActivity>) {
        eventually {
            var attached = false
            scenario.onActivity { activity ->
                attached = MainActivity::class.java.getDeclaredField("promptInteractions")
                    .apply { isAccessible = true }.get(activity) != null
            }
            attached
        }
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

    private fun grantLocalNetworkPermissionForConnectionTest() {
        if (Build.VERSION.SDK_INT < 37) return
        shell("pm grant ${context.packageName} ${LocalNetworkPermissionCoordinator.PERMISSION}")
        assertEquals(PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(LocalNetworkPermissionCoordinator.PERMISSION))
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
                .mapNotNull { node -> runCatching { node.text }.getOrNull() }
            listOf(
                "android.widget.Button",
                "androidx.appcompat.widget.AppCompatButton",
                "com.google.android.material.button.MaterialButton",
            ).forEach { className ->
                visibleText += device.findObjects(By.clazz(className))
                    .mapNotNull { node -> runCatching { node.text }.getOrNull() }
            }
            if (visibleText.any { normalizedUiText(it) == expected }) return
            Thread.sleep(50)
        } while (System.nanoTime() < deadline)
        fail("UI text did not appear: $text; accessibility text=$visibleText")
    }

    private fun waitForTextContains(text: String, timeoutMillis: Long = 5_000) {
        val expected = normalizedUiText(text)
        val awaited = runCatching { device.wait(Until.findObject(By.textContains(text)), timeoutMillis) }
            .getOrNull()?.let { node -> runCatching { node.text }.getOrNull() }
        val appeared = awaited?.let { normalizedUiText(it).contains(expected) } == true ||
            device.findObjects(By.clazz("android.widget.TextView")).any { node ->
                runCatching { normalizedUiText(node.text).contains(expected) }.getOrDefault(false)
            }
        assertTrue("UI text containing '$text' did not appear", appeared)
    }

    private fun clickText(text: String) {
        val expected = normalizedUiText(text)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val target = device.findObjects(By.clickable(true)).firstOrNull { node ->
                runCatching { normalizedUiText(node.text) == expected }.getOrDefault(false)
            }
            if (target != null) {
                try {
                    target.click()
                    instrumentation.waitForIdleSync()
                    return
                } catch (_: androidx.test.uiautomator.StaleObjectException) {
                    // A prompt can be replaced between the accessibility query and the click.
                }
            }
            Thread.sleep(50)
        }
        val nodes = device.findObjects(By.clickable(true)).mapNotNull { node ->
            runCatching {
                "${node.applicationPackage}/${node.className} text=${node.text} desc=${node.contentDescription} id=${node.resourceName}"
            }.getOrNull()
        }
        fail("Clickable UI text did not appear (case-insensitive): $text; " +
            "currentPackage=${device.currentPackageName}; clickableNodes=$nodes")
    }

    private fun clickDialogButton(text: String) {
        val expected = normalizedUiText(text)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val target = device.findObjects(By.clickable(true)).firstOrNull { node ->
                runCatching {
                    node.className.toString().endsWith("Button") && normalizedUiText(node.text) == expected
                }.getOrDefault(false)
            }
            if (target != null) {
                try {
                    target.click()
                    instrumentation.waitForIdleSync()
                    return
                } catch (_: androidx.test.uiautomator.StaleObjectException) {
                    // A prompt can be replaced between the accessibility query and the click.
                }
            }
            Thread.sleep(50)
        }
        fail("Dialog button did not appear (case-insensitive): $text")
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

    @Test
    fun resolutionFailureGuidesManualEntryAndRetryRetainsHostWithoutPasswordMislabel() {
        grantNotificationPermissionForDisclosureTest()
        grantLocalNetworkPermissionForConnectionTest()
        val configuredHost = SessionFixtures.host("desktop.local", 71, "synthetic_resolution_profile", 2271)
        val recordingOwner = RecordingOwner(selectedHost = configuredHost)
        recordingOwner.attempts.failNextWith(
            ConnectionFailure.Resolution(ConnectionFailure.ResolutionReason.NOT_FOUND),
            ConnectionFailure.Authentication(ConnectionFailure.AuthenticationReason.REJECTED),
        )
        install(recordingOwner)
        val activityScenario = launchVisibleActivity()

        requestConnect(activityScenario)
        eventually {
            val snapshot = recordingOwner.controller.current
            snapshot.attemptHost == null &&
                snapshot.connection.failure == ConnectionFailure.Resolution(ConnectionFailure.ResolutionReason.NOT_FOUND)
        }
        assertEquals(configuredHost, recordingOwner.controller.current.desiredHost)
        assertEquals(configuredHost.endpoint.identity, recordingOwner.controller.current.connection.endpoint)

        showSessionControlsFromMenu(activityScenario)
        waitForTextContains("Manage Hosts")
        waitForTextContains("IP address")
        waitForText("Retry")
        val resolutionDialogText = device.findObjects(By.clazz("android.widget.TextView"))
            .mapNotNull { runCatching { it.text }.getOrNull() }.joinToString(" ")
        assertFalse("a DNS failure must not be described as a password failure",
            resolutionDialogText.contains("password", ignoreCase = true))

        clickDialogButton("Retry")
        val resolutionMessageDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (device.findObjects(By.textContains("Manage Hosts")).isNotEmpty() &&
            System.nanoTime() < resolutionMessageDeadline) Thread.sleep(50)
        assertTrue("resolution guidance should dismiss before checking the authentication message",
            device.findObjects(By.textContains("Manage Hosts")).isEmpty())
        eventually {
            val snapshot = recordingOwner.controller.current
            recordingOwner.selectionCalls.get() == 2 && snapshot.attemptHost == null &&
                snapshot.connection.failure == ConnectionFailure.Authentication(ConnectionFailure.AuthenticationReason.REJECTED)
        }
        assertEquals("Retry must keep the selected profile and endpoint", configuredHost,
            recordingOwner.controller.current.desiredHost)
        assertEquals(listOf(configuredHost, configuredHost), recordingOwner.attempts.createdHosts.toList())

        showSessionControlsFromMenu(activityScenario)
        waitForTextContains("Authentication was not accepted")
        val authenticationDialogText = device.findObjects(By.clazz("android.widget.TextView"))
            .mapNotNull { runCatching { it.text }.getOrNull() }.joinToString(" ")
        assertFalse("authentication errors must not reuse DNS/manual-IP wording",
            authenticationDialogText.contains("Manage Hosts", ignoreCase = true) ||
                authenticationDialogText.contains("could not be found", ignoreCase = true))
    }

    @After
    fun cleanup() {
        owner?.release()
        context.stopService(Intent(context, SessionForegroundService::class.java))
        scenario?.close()
        scenario = null
        owner?.close()
        owner = null
        transportOwner?.close()
        transportOwner = null
        ownerLease?.close()
        ownerLease = null
        if (savedScriptId != 0) {
            DatabaseHandler(context).deleteRow(DatabaseHandler.SCRIPT_TABLE_NAME, savedScriptId)
            savedScriptId = 0
        }
        oldAutoConnect?.let { value -> defaultPreferences.edit { putBoolean("autologin_checkbox", value) } }
        if (oldKeyboardBatchPresent) {
            defaultPreferences.edit { putBoolean("setting_keyboard_batch", requireNotNull(oldKeyboardBatch)) }
        } else defaultPreferences.edit { remove("setting_keyboard_batch") }
        oldKeyboardBatchPresent = false
        oldKeyboardBatch = null
        oldInputBackend?.let { value -> defaultPreferences.edit { putString("setting_dotool_initial", value) } }
            ?: defaultPreferences.edit { remove("setting_dotool_initial") }
        oldInitialCommand?.let { value -> defaultPreferences.edit { putString("setting_xdotool_initial", value) } }
            ?: defaultPreferences.edit { remove("setting_xdotool_initial") }
        oldGyroEnabled?.let { value -> defaultPreferences.edit { putBoolean("control_gyro_enabled", value) } }
            ?: defaultPreferences.edit { remove("control_gyro_enabled") }
        if (oldGyroSettings.isNotEmpty()) {
            defaultPreferences.edit {
                oldGyroSettings.forEach { (key, value) ->
                    if (value == null) remove(key) else putString(key, value)
                }
            }
            oldGyroSettings.clear()
        }
        oldSettingDelay?.let { MainActivity.setting_delay = it }
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
        assertEquals("a null OS restart cannot revive a session after process death",
            Service.START_NOT_STICKY, nullResult)
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
        assertTrue("API 37 local-network permission must be declared for direct LAN access",
            LocalNetworkPermissionCoordinator.PERMISSION in requested)
        assertTrue(Manifest.permission.CHANGE_WIFI_MULTICAST_STATE in requested)
        assertTrue("android.permission.FOREGROUND_SERVICE" in requested)
        assertTrue("connected-device FGS permission missing",
            "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" in requested)
        val bootReceiver = context.packageManager.queryBroadcastReceivers(
            Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(context.packageName), PackageManager.MATCH_DISABLED_COMPONENTS,
        )
        assertTrue("a boot-triggered receiver must not bypass a force-stop", bootReceiver.isEmpty())
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
        activityScenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        context.stopService(Intent(context, SessionForegroundService::class.java))
        eventually {
            firstBinding.binder.destroyedForTests && !firstBinding.binder.isForeground &&
                recordingOwner.attempts.closes.get() == 1 &&
                recordingOwner.controller.current.connection.phase == ConnectionState.Phase.ERROR &&
                recordingOwner.controller.current.foregroundRecoveryAvailable
        }
        assertNotNull("involuntary service loss retains intent, but never a ready session without disclosure",
            recordingOwner.controller.current.desiredHost)
        assertFalse(recordingOwner.controller.current.connection.isReady)
        activityScenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        val recreatedBinding = bind(activityScenario)
        assertNotSame("service must be recreated after teardown", firstService, recreatedBinding.binder.serviceForTests)
        eventually { recreatedBinding.binder.snapshot.connection.isReady }
        assertEquals("foreground recovery uses the retained host, not a new preference selection", 6,
            recordingOwner.selectionCalls.get())
        instrumentation.waitForIdleSync()
        assertEquals("a new visible request may start a new attempt after confirmed cleanup", 2,
            recordingOwner.attempts.created.get())

        recordingOwner.controller.disconnect()
        eventually { recordingOwner.controller.current.connection.phase == ConnectionState.Phase.DISCONNECTED }
        assertTrue(recordingOwner.controller.current.automaticConnectSuppressed)
        unbind(activityScenario, recreatedBinding)
        activityScenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        context.stopService(Intent(context, SessionForegroundService::class.java))

        activityScenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        requestConnect(activityScenario, automatic = true)
        val autoBinding = bind(activityScenario)
        eventually { recordingOwner.selectionCalls.get() == 7 }
        eventually { recordingOwner.controller.current.automaticConnectSuppressed }
        eventually { !autoBinding.binder.isForeground }
        assertNull(recordingOwner.controller.current.desiredHost)
        assertEquals("saved auto-connect must not undo Disconnect", 2, recordingOwner.attempts.created.get())
        assertFalse(autoBinding.binder.isForeground)
        unbind(activityScenario, autoBinding)
    }

    @Test
    fun activityRecreationAndReturnWithAutoConnectEnabledReattachToTheExistingSession() {
        grantNotificationPermissionForDisclosureTest()
        val recordingOwner = RecordingTransportOwner()
        install(recordingOwner)
        val activityScenario = launchVisibleActivity(autoConnect = true)
        eventually { recordingOwner.controller.current.connection.isReady }
        eventually {
            var ready = false
            activityScenario.onActivity { activity -> ready = activitySnapshot(activity)?.connection?.isReady == true }
            ready
        }
        val firstActivity = AtomicReference<MainActivity>()
        activityScenario.onActivity { firstActivity.set(it) }
        val serviceInstances = SessionForegroundService.serviceInstancesCreatedForTests
        assertEquals(1, recordingOwner.selectionCalls.get())
        assertEquals(1, recordingOwner.attemptsCreated)
        assertEquals(0, recordingOwner.backend.events.count { it.name == "session.close" })

        activityScenario.recreate()
        eventually {
            var current = false
            activityScenario.onActivity { activity ->
                current = activity !== firstActivity.get() && activitySnapshot(activity)?.connection?.isReady == true &&
                    activity.findViewById<Toolbar>(R.id.toolbar).subtitle == "Connected"
            }
            current
        }
        assertEquals("recreation must not select another host or create another SSH attempt", 1,
            recordingOwner.selectionCalls.get())
        assertEquals(1, recordingOwner.attemptsCreated)
        assertEquals(0, recordingOwner.backend.events.count { it.name == "session.close" })
        assertEquals(serviceInstances, SessionForegroundService.serviceInstancesCreatedForTests)

        activityScenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        assertTrue("stopping the control Activity must leave its service session ready",
            recordingOwner.controller.current.connection.isReady)
        assertEquals(0, recordingOwner.backend.events.count { it.name == "session.close" })
        activityScenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        eventually {
            var ready = false
            activityScenario.onActivity { activity -> ready = activitySnapshot(activity)?.connection?.isReady == true }
            ready
        }
        assertEquals("auto-connect on return must be idempotent", 1, recordingOwner.selectionCalls.get())
        assertEquals(1, recordingOwner.attemptsCreated)
        assertEquals(0, recordingOwner.backend.events.count { it.name == "session.close" })
        assertEquals(serviceInstances, SessionForegroundService.serviceInstancesCreatedForTests)

        // Existing lifecycle coverage also exercises one representative interrupted-network return.
        // Disable saved auto-connect: retained connection intent, not that preference, permits recovery.
        defaultPreferences.edit { putBoolean("autologin_checkbox", false) }
        activityScenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        recordingOwner.backend.channels.first { it.name == "shell" && !it.closed }.closed = true
        eventually { recordingOwner.controller.current.attemptHost == null &&
            recordingOwner.controller.current.connection.phase == ConnectionState.Phase.ERROR }
        assertFalse(recordingOwner.controller.current.connection.isReady)
        assertNotNull(recordingOwner.controller.current.desiredHost)
        assertEquals(1, recordingOwner.attemptsCreated)
        activityScenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        eventually { recordingOwner.controller.current.connection.isReady && recordingOwner.attemptsCreated == 2 }
        assertEquals("recovery does not replace retained intent with saved host metadata", 1,
            recordingOwner.selectionCalls.get())
        assertEquals(2, recordingOwner.credentialReads.get())

        // A failed foreground recovery must leave honest state and visible, deliberate Retry.
        activityScenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        recordingOwner.backend.failAt = "session.connect"
        recordingOwner.backend.channels.first { it.name == "shell" && !it.closed }.closed = true
        eventually { recordingOwner.controller.current.attemptHost == null &&
            recordingOwner.controller.current.connection.phase == ConnectionState.Phase.ERROR }
        activityScenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        eventually { recordingOwner.attemptsCreated == 3 && recordingOwner.controller.current.attemptHost == null }
        assertFalse(recordingOwner.controller.current.connection.isReady)
        assertFalse(recordingOwner.controller.current.foregroundRecoveryAvailable)
        activityScenario.onActivity { activity ->
            assertEquals("Connection failed", activity.findViewById<Toolbar>(R.id.toolbar).subtitle)
        }
        showSessionControlsFromMenu(activityScenario)
        waitForText("Retry")
        clickText("Disconnect")
        eventually { recordingOwner.controller.current.connection.phase == ConnectionState.Phase.DISCONNECTED }
        assertEquals(3, recordingOwner.attemptsCreated)

        SessionForegroundService.requestDisconnect(context)
        eventually { recordingOwner.controller.current.connection.phase == ConnectionState.Phase.DISCONNECTED }
        assertEquals(3, recordingOwner.backend.events.count { it.name == "session.close" })
    }

    @Test
    fun leavingHostDiscoveryStopsLateCallbacksWithoutClosingForegroundSession() {
        grantNotificationPermissionForDisclosureTest()
        grantLocalNetworkPermissionForConnectionTest()
        val recordingOwner = RecordingTransportOwner()
        install(recordingOwner)
        val mainScenario = launchVisibleActivity(autoConnect = true)
        eventually { recordingOwner.controller.current.connection.isReady }
        val sessionBinding = bind(mainScenario)
        val sessionBefore = recordingOwner.controller.current
        assertTrue("the SSH session must be owned by the started foreground service", sessionBinding.binder.isForeground)
        assertTrue(sessionBinding.binder.snapshot.connection.isReady)
        assertTrue(sessionBefore.connection.isReady)
        assertTrue(SessionForegroundService.hasRetainedSession(context))

        val factory = RecordingDiscoveryFactory()
        ListEditActivity.setDiscoveryFactoryForTests(factory)
        ListEditActivity.setPermissionRequiredForTests(false)
        var discoveryHandle: RecordingDiscoveryFactory.Handle? = null
        try {
            ActivityScenario.launch<ListEditActivity>(Intent(context, ListEditActivity::class.java)).use { discoveryScenario ->
                discoveryScenario.onActivity { activity ->
                    activity.findViewById<View>(R.id.find_computers).performClick()
                }
                assertEquals(1, factory.handles.size)
                discoveryHandle = factory.handles.single()
                assertEquals(1, discoveryHandle!!.starts.get())
                val candidate = SshServiceDiscovery.Candidate(
                    SshServiceDiscovery.Identity("test-network", "office", "_ssh._tcp", "local"),
                    EndpointLocator.DiscoveredService("Office", "_ssh._tcp", "local"),
                    2222,
                    listOf(InetAddress.getByName("192.0.2.23")),
                )
                discoveryHandle!!.listener.onServiceFound(candidate)
                instrumentation.waitForIdleSync()
                discoveryScenario.onActivity { activity ->
                    val dialog = requireNotNull(discoveryDialog(activity))
                    assertTrue(dialog.isShowing)
                    assertEquals(1, dialog.findViewById<ListView>(R.id.find_computers_results).adapter.count)
                }

                // Move the real host-management Activity to CREATED so onStop dismisses its dialog.
                discoveryScenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                discoveryScenario.onActivity { activity ->
                    assertNull("stopping host management dismisses discovery", discoveryDialog(activity))
                    assertTrue(discoveryCandidates(activity).isEmpty())
                }
                assertEquals("onStop closes the active NSD handle once", 1, discoveryHandle!!.closes.get())

                // A callback already queued by NSD after dismissal must not repopulate or update UI state.
                discoveryHandle!!.listener.onServiceFound(candidate)
                discoveryHandle!!.listener.onServiceUpdated(candidate)
                discoveryHandle!!.listener.onServiceLost(candidate.identity)
                discoveryHandle!!.listener.onFailure(
                    SshServiceDiscovery.Failure(SshServiceDiscovery.Operation.START, 3, null, null),
                )
                instrumentation.waitForIdleSync()
                discoveryScenario.onActivity { activity ->
                    assertNull(discoveryDialog(activity))
                    assertTrue("late results are ignored after the lifecycle token is invalidated",
                        discoveryCandidates(activity).isEmpty())
                    assertNull("late failures are ignored after the lifecycle token is invalidated",
                        discoveryError(activity))
                }
                assertEquals("late callbacks must not close the handle again", 1, discoveryHandle!!.closes.get())
            }

            assertEquals("destroying the stopped Activity must not close discovery twice", 1,
                requireNotNull(discoveryHandle).closes.get())
            val sessionAfter = recordingOwner.controller.current
            assertEquals("discovery lifecycle must not replace the active session generation",
                sessionBefore.generation, sessionAfter.generation)
            assertSame("the existing connection state must remain attached", sessionBefore.connection,
                sessionAfter.connection)
            assertTrue("the service-backed SSH connection remains ready", sessionAfter.connection.isReady)
            assertEquals(sessionBefore.generation, sessionBinding.binder.snapshot.generation)
            assertTrue(sessionBinding.binder.snapshot.connection.isReady)
            assertTrue(SessionForegroundService.hasRetainedSession(context))
            assertEquals("leaving discovery must not create another SSH attempt", 1, recordingOwner.attemptsCreated)
            assertEquals("leaving discovery must not close the SSH transport", 0,
                recordingOwner.backend.events.count { it.name == "session.close" })
        } finally {
            ListEditActivity.setDiscoveryFactoryForTests(null)
            ListEditActivity.setPermissionRequiredForTests(null)
            unbind(mainScenario, sessionBinding)
        }
    }

    @Test
    fun autoConnectOffIdleBindTeardownThenEnableAndReturnConnectsExactlyOnce() {
        grantNotificationPermissionForDisclosureTest()
        val recordingOwner = RecordingTransportOwner()
        install(recordingOwner)
        assertFalse("fresh test owner should start unsuppressed: ${recordingOwner.controller.current}",
            recordingOwner.controller.current.automaticConnectSuppressed)
        val activityScenario = launchVisibleActivity(autoConnect = false)
        waitForActivitySessionBinding(activityScenario)

        val firstService = AtomicReference<SessionForegroundService.SessionBinder>()
        activityScenario.onActivity { activity ->
            firstService.set(MainActivity::class.java.getDeclaredField("sessionBinder")
                .apply { isAccessible = true }.get(activity) as SessionForegroundService.SessionBinder)
        }
        assertNotNull(firstService.get())
        assertEquals(0, recordingOwner.selectionCalls.get())
        assertFalse("idle Activity binding changed the fresh owner: ${recordingOwner.controller.current}",
            recordingOwner.controller.current.automaticConnectSuppressed)
        val intentVersion = recordingOwner.controller.current.intentVersion

        // A permission reservation belongs to the application registry, not the observer-only Service.
        var reservation: String? = null
        activityScenario.onActivity { reservation = SessionForegroundService.reserveAutomaticConnectIfNeeded(it) }
        assertNotNull(reservation)
        assertTrue(SessionForegroundService.hasReservedStart(context, reservation))

        activityScenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        eventually { firstService.get().destroyedForTests }
        val idle = recordingOwner.controller.current
        assertEquals(ConnectionState.Phase.DISCONNECTED, idle.connection.phase)
        assertFalse("observer-only teardown must not persist an auto-connect opt-out", idle.automaticConnectSuppressed)
        assertEquals("observer-only teardown must not advance controller intent", intentVersion, idle.intentVersion)
        assertTrue("observer-only teardown must preserve an outstanding permission reservation",
            SessionForegroundService.hasReservedStart(context, reservation))
        assertEquals(0, recordingOwner.attemptsCreated)
        SessionForegroundService.cancelReservedConnect(context, requireNotNull(reservation))

        defaultPreferences.edit { putBoolean("autologin_checkbox", true) }
        activityScenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        eventually { recordingOwner.controller.current.connection.isReady }
        eventually { recordingOwner.selectionCalls.get() == 1 && recordingOwner.attemptsCreated == 1 }
        eventually {
            var connected = false
            activityScenario.onActivity { connected = activitySnapshot(it)?.connection?.isReady == true }
            connected
        }
        Thread.sleep(250)
        assertEquals("return with auto-connect enabled must issue one selection", 1, recordingOwner.selectionCalls.get())
        assertEquals("return with auto-connect enabled must create one attempt", 1, recordingOwner.attemptsCreated)
        assertEquals(0, recordingOwner.backend.events.count { it.name == "session.close" })

        assertTrue(SessionForegroundService.requestDisconnect(context))
        eventually { recordingOwner.controller.current.connection.phase == ConnectionState.Phase.DISCONNECTED }
        assertTrue(recordingOwner.controller.current.automaticConnectSuppressed)
    }

    @Test
    fun activityControlsHandCommandsToTheServiceOwnedProductionTransport() {
        val recordingOwner = RecordingTransportOwner()
        install(recordingOwner)
        val activityScenario = launchVisibleActivity()
        requestConnect(activityScenario)
        eventually { recordingOwner.controller.current.connection.isReady }

        var shellAccepted = false
        var execAccepted = false
        activityScenario.onActivity { activity ->
            shellAccepted = activity.commandTransport.executeShellCommand("synthetic activity command")
            execAccepted = activity.commandTransport.executeExecCommand("synthetic activity script",
                activityPresentationToken(activity))
        }
        assertTrue("application command proxy rejected an available service session", shellAccepted)
        assertTrue("script command proxy rejected an available service session", execAccepted)
        eventually { recordingOwner.backend.writes.any { it.contains("synthetic activity command") } }
        eventually { recordingOwner.backend.channels.any { it.name == "exec" && it.command == "synthetic activity script" } }
        waitForTextContains("out one")
        clickText("Ok")
        SessionForegroundService.requestDisconnect(context)
        eventually { recordingOwner.controller.current.connection.phase == ConnectionState.Phase.DISCONNECTED }
    }

    @Test
    fun layoutEditorNeverDispatchesAndNormalControlsUseOnlyTheSavedProfile() {
        grantNotificationPermissionForDisclosureTest()
        grantLocalNetworkPermissionForConnectionTest()

        val repository = LayoutProfileRepository(
            File(context.filesDir, LayoutEditorActivity.PROFILE_DIRECTORY),
        )
        val previousSelectedProfileId = repository.loadSelectedProfile()?.id
        val migration = LegacyLayoutProfileMigrator(context.filesDir, repository).migrateWithReport()
        assertTrue("the test's saved layout must activate without migration warnings", migration.warnings.isEmpty())
        val activeProfileSeededForTest =
            runCatching { repository.loadProfile(LegacyLayoutProfileMigrator.ACTIVE_PROFILE_ID) }.isFailure
        if (activeProfileSeededForTest) {
            repository.createProfile(LayoutProfile(
                id = LegacyLayoutProfileMigrator.ACTIVE_PROFILE_ID,
                name = "Active layout",
                buttons = listOf(LayoutButton("active-button", "Active", "printf active", 2)),
            ))
        }
        val original = LayoutProfile(
            id = "editor-boundary-${java.util.UUID.randomUUID()}",
            name = "Editor boundary",
            buttons = listOf(
                LayoutButton("source", "Source", "printf source", 3),
                LayoutButton("target", "Target", "printf target", 4),
            ),
        )

        try {
            repository.createProfile(original)
            repository.selectProfile(original.id)
            val recordingOwner = RecordingTransportOwner()
            install(recordingOwner)
            val activityScenario = launchVisibleActivity(
                autoConnect = true,
                inputBackend = "xdotool",
                initialCommand = "",
            )
            eventually { recordingOwner.controller.current.connection.isReady }
            eventually {
                var ready = false
                activityScenario.onActivity { ready = activitySnapshot(it)?.connection?.isReady == true }
                ready
            }
            // The same production service transport records editor-time and normal-mode commands.
            recordingOwner.backend.writes.clear()
            recordingOwner.backend.commandOrder.clear()

            activityScenario.onActivity { activity ->
                val toolbar = activity.findViewById<Toolbar>(R.id.toolbar)
                if (toolbar.menu.findItem(R.id.action_edit_layout) == null) {
                    activity.menuInflater.inflate(R.menu.main, toolbar.menu)
                }
                activity.onOptionsItemSelected(requireNotNull(toolbar.menu.findItem(R.id.action_edit_layout)))
            }

            val sourceNode = compose.onNodeWithTag("layout-editor-button-source").performScrollTo()
            sourceNode.performClick()
            sourceNode.assertIsSelected()
            assertEquals("a preview tap only selects an editor button", original, repository.loadProfile(original.id))
            assertNull(repository.loadDraft(original.id))

            val sourceBounds = sourceNode.fetchSemanticsNode().boundsInRoot
            val targetBounds = compose.onNodeWithTag("layout-editor-button-target")
                .fetchSemanticsNode().boundsInRoot
            val destination = Offset(
                targetBounds.right - targetBounds.width / 4f,
                targetBounds.center.y,
            ) - sourceBounds.topLeft
            sourceNode.performTouchInput {
                down(center)
                advanceEventTime(1_000)
                moveTo(destination, delayMillis = 100)
            }
            compose.onNodeWithTag("layout-editor-placement-preview").assertExists()
            sourceNode.performTouchInput { up() }
            compose.waitForIdle()

            val moved = original.copy(buttons = original.buttons.reversed())
            assertEquals(moved, repository.loadDraft(original.id))
            assertEquals("dragging cannot update the saved profile", original, repository.loadProfile(original.id))

            compose.onNodeWithTag("layout-editor-increase-width").performScrollTo().performClick()
            compose.waitForIdle()
            val resized = moved.copy(buttons = listOf(moved.buttons[0], moved.buttons[1].copy(width = 4)))
            assertEquals(resized, repository.loadDraft(original.id))
            assertEquals("resizing cannot update the saved profile", original, repository.loadProfile(original.id))

            val expectedCommand = "printf 'saved-layout-only'"
            compose.onNodeWithTag("layout-editor-add-button").performScrollTo().performClick()
            compose.onNodeWithTag("layout-editor-button-title").performScrollTo().performTextInput("Saved action")
            compose.onNodeWithTag("layout-editor-button-command").performScrollTo().performTextInput(expectedCommand)
            compose.onNodeWithTag("layout-editor-button-save").performClick()
            compose.waitForIdle()

            val draft = requireNotNull(repository.loadDraft(original.id))
            val created = draft.buttons.last()
            assertEquals("Saved action", created.title)
            assertEquals(expectedCommand, created.command)
            assertEquals(resized.copy(buttons = resized.buttons + created), draft)
            assertEquals("create/edit stays draft-only until Save", original, repository.loadProfile(original.id))

            // Let any incorrectly submitted editor command reach the recording provider before Save.
            Thread.sleep(250)
            assertTrue("editor taps, drag, resize, and create must send zero shell commands",
                recordingOwner.backend.writes.isEmpty())
            assertTrue(recordingOwner.backend.commandOrder.isEmpty())

            compose.onNodeWithTag("layout-editor-save").performScrollTo().performClick()
            eventually { repository.loadProfile(original.id) == draft && repository.loadDraft(original.id) == null }
            assertTrue("saving and returning to normal mode must not itself execute a button",
                recordingOwner.backend.writes.isEmpty())

            eventually {
                var reloaded = false
                activityScenario.onActivity { activity ->
                    val adapter = activity.findViewById<SpanVariableGridView>(R.id.key_drag_DragAndDropGridView)
                        .adapter as? CustomKeyboardButtonAdapter
                    val button = adapter?.let { current ->
                        (0 until current.count).mapNotNull(current::getItem)
                            .singleOrNull { it.getmTitle() == "Saved action" }
                    }
                    reloaded = button?.getmCommand() == expectedCommand && button.getmSpans() == created.width &&
                        activity.activeControlInputToken() != null
                }
                reloaded
            }

            activityScenario.onActivity { activity ->
                assertTrue("normal custom controls must be available after editor return",
                    activity.findViewById<View>(R.id.fab).performClick())
            }
            eventually {
                var active = false
                activityScenario.onActivity { active = it.activeControlInputToken() != null }
                active
            }
            assertTrue(recordingOwner.backend.writes.isEmpty())

            activityScenario.onActivity { clickLegacyButton(it, "Saved action") }
            val expectedWrite = "$expectedCommand\r\n"
            eventually {
                recordingOwner.backend.writes.toList() == listOf(expectedWrite) &&
                    recordingOwner.backend.commandOrder.toList() == listOf("shell:$expectedCommand")
            }
            assertEquals("only the saved button command reaches the normal-mode sink",
                listOf("shell:$expectedCommand"), recordingOwner.backend.commandOrder.toList())
        } finally {
            try {
                scenario?.close()
            } finally {
                scenario = null
                val restoreProfileId = previousSelectedProfileId ?: LegacyLayoutProfileMigrator.ACTIVE_PROFILE_ID
                if (runCatching { repository.loadProfile(restoreProfileId) }.isSuccess) {
                    repository.selectProfile(restoreProfileId)
                }
                if (runCatching { repository.loadProfile(original.id) }.isSuccess) {
                    repository.deleteProfile(original.id, confirmed = true)
                }
                if (activeProfileSeededForTest &&
                    runCatching { repository.loadProfile(LegacyLayoutProfileMigrator.ACTIVE_PROFILE_ID) }.isSuccess) {
                    repository.deleteProfile(LegacyLayoutProfileMigrator.ACTIVE_PROFILE_ID, confirmed = true)
                }
            }
        }
    }

    @Test
    fun legacyControlsCustomButtonsAndSavedScriptsUseTheServiceCommandSink() {
        grantNotificationPermissionForDisclosureTest()
        val startup = "synthetic startup command must run once"
        val rawCustomCommand = "xdotool windowminimize $(xdotool getactivewindow)"
        val scriptAlias = "saved route ${java.util.UUID.randomUUID()}"
        val scriptCommand = "printf 'saved script command'"
        val keyboardText = context.getString(R.string.app_name)
        val recordingOwner = RecordingTransportOwner()
        install(recordingOwner)
        val activityScenario = launchVisibleActivity(
            autoConnect = true,
            inputBackend = "xdotool",
            initialCommand = startup,
        )
        eventually { recordingOwner.controller.current.connection.isReady }
        eventually { recordingOwner.backend.writes.any { it == "$startup\r\n" } }
        assertEquals("the initial host command is sent exactly once on the first connection", 1,
            recordingOwner.backend.writes.count { it == "$startup\r\n" })

        activityScenario.onActivity { activity ->
            val mouseLayout = activity.findViewById<android.widget.LinearLayout>(R.id.mouse)
            val mouse = (0 until mouseLayout.childCount)
                .map { mouseLayout.getChildAt(it) }.filterIsInstance<MyMouseView>().single()
            assertTrue("the touchpad must be laid out", mouse.width > 0)
            val downTime = SystemClock.uptimeMillis()
            val x = mouse.width / 2f
            dispatchTouch(mouse, downTime, downTime, MotionEvent.ACTION_DOWN, x, 40f)
            dispatchTouch(mouse, downTime, downTime + 20, MotionEvent.ACTION_MOVE, x + 30f, 55f)
            dispatchTouch(mouse, downTime, downTime + 40, MotionEvent.ACTION_UP, x + 30f, 55f)
            assertTrue("legacy mouse-button click was not handled",
                activity.findViewById<View>(R.id.firstMouseButton).performClick())

            assertTrue("joystick mode toggle was not handled",
                activity.findViewById<View>(R.id.jsToggle).performClick())
            val joystickLayout = activity.findViewById<android.widget.LinearLayout>(R.id.js)
            val joystick = (0 until joystickLayout.childCount)
                .map { joystickLayout.getChildAt(it) }.filterIsInstance<MyJSView>().single()
            val size = View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY)
            joystick.measure(size, size)
            joystick.layout(0, 0, 240, 240)
            val joystickDown = SystemClock.uptimeMillis()
            dispatchTouch(joystick, joystickDown, joystickDown, MotionEvent.ACTION_DOWN, 120f, 120f)
            dispatchTouch(joystick, joystickDown, joystickDown + 20, MotionEvent.ACTION_MOVE, 160f, 90f)
            dispatchTouch(joystick, joystickDown, joystickDown + 40, MotionEvent.ACTION_UP, 160f, 90f)

            val keyboardKeyDown = SystemClock.uptimeMillis()
            val dispatcher = KeyEvent.DispatcherState()
            val down = KeyEvent(keyboardKeyDown, keyboardKeyDown, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_VOLUME_UP, 0)
            dispatcher.startTracking(down, activity)
            assertTrue("legacy volume-down/up dispatch rejected key-down",
                activity.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, down))
            val up = KeyEvent(keyboardKeyDown, keyboardKeyDown + 1, KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_VOLUME_UP, 0)
            dispatcher.handleUpEvent(up)
            assertTrue("legacy volume shortcut was not tracked", up.isTracking)
            assertTrue("legacy volume shortcut dispatch rejected key-up",
                activity.onKeyUp(KeyEvent.KEYCODE_VOLUME_UP, up))

            assertTrue("keyboard mode toggle was not handled", activity.findViewById<View>(R.id.fab).performClick())
            activity.findViewById<EditText>(R.id.keyboard_input).setText(R.string.app_name)
            assertTrue("batch keyboard send button was not handled",
                activity.findViewById<View>(R.id.keyboard_send).performClick())
            clickLegacyButton(activity, "Backspace")
            clickLegacyButton(activity, "+Volume")
            clickLegacyButton(activity, "Minimize")
            val rawButton = (activity.findViewById<SpanVariableGridView>(R.id.key_drag_DragAndDropGridView).adapter
                as CustomKeyboardButtonAdapter).let { adapter ->
                (0 until adapter.count).mapNotNull { adapter.getItem(it) }.single { it.getmTitle() == "Minimize" }
            }
            assertEquals("the raw custom-button command must remain unchanged", rawCustomCommand,
                rawButton.getmCommand())

            addSavedScript(activity, scriptAlias, scriptCommand)
        }

        eventually {
            recordingOwner.backend.writes.any { it.startsWith("xdotool mousemove_relative -- ") } &&
                recordingOwner.backend.writes.count { it.startsWith("xdotool mousemove_relative -- ") } >= 2 &&
                recordingOwner.backend.writes.contains("xdotool click 1\r\n") &&
                recordingOwner.backend.writes.count { it == "xdotool key XF86AudioRaiseVolume\r\n" } >= 2 &&
                recordingOwner.backend.writes.contains("xdotool type '$keyboardText'\r\n") &&
                recordingOwner.backend.writes.contains("xdotool key BackSpace\r\n") &&
                recordingOwner.backend.writes.contains("$rawCustomCommand\r\n")
        }
        // Once the foreground inputs are observed at the provider boundary, suspend controls before
        // exercising the saved-script drawer. Otherwise the new fail-closed transport admission is
        // correctly allowed to drop input that has not yet reached the provider.
        activityScenario.onActivity {
            it.findViewById<DrawerLayout>(R.id.drawer_layout).openDrawer(GravityCompat.START)
        }
        waitForRecentCommand(activityScenario, rawCustomCommand)
        val shellCommandsBeforeScript = recordingOwner.backend.commandOrder.toList()
        assertTrue("the real touchpad/joystick and volume/keyboard/custom routes must reach the same shell sink",
            shellCommandsBeforeScript.any { it.startsWith("shell:xdotool mousemove_relative -- ") } &&
                shellCommandsBeforeScript.contains("shell:xdotool click 1") &&
                shellCommandsBeforeScript.contains("shell:xdotool type '$keyboardText'") &&
                shellCommandsBeforeScript.contains("shell:$rawCustomCommand"))

        var scriptSelectionHandled = false
        activityScenario.onActivity { activity ->
            val navigation = activity.findViewById<com.google.android.material.navigation.NavigationView>(R.id.nav_view)
            assertEquals(scriptAlias, navigation.menu.findItem(savedScriptId).title.toString())
            scriptSelectionHandled = navigation.menu.performIdentifierAction(savedScriptId, 0)
        }
        assertTrue("the open Saved Scripts drawer did not route its selected item", scriptSelectionHandled)
        eventually { recordingOwner.backend.channels.any { it.name == "exec" && it.command == scriptCommand } }
        assertEquals("Activity shell inputs and the saved exec script must share one ordered sink",
            shellCommandsBeforeScript + "exec:$scriptCommand", recordingOwner.backend.commandOrder.toList())
        waitForTextContains("out one")
        clickText("Copy")
        var copiedOutput: String? = null
        activityScenario.onActivity { activity ->
            copiedOutput = activity.getSystemService(ClipboardManager::class.java)
                .primaryClip?.getItemAt(0)?.text?.toString()
        }
        assertEquals("err oneerr twoout oneout two", copiedOutput)

        val detachedScreenTransport = AtomicReference<SessionCommandTransport>()
        activityScenario.onActivity { detachedScreenTransport.set(it.commandTransport) }
        activityScenario.recreate()
        eventually {
            var attached = false
            activityScenario.onActivity { activity ->
                attached = activitySnapshot(activity)?.connection?.isReady == true && activityOutputDialog(activity) == null
            }
            attached
        }
        assertEquals("reattaching the real control Activity must not rerun the saved startup command", 1,
            recordingOwner.backend.writes.count { it == "$startup\r\n" })
        assertTrue(recordingOwner.controller.current.connection.isReady)

        val postRecreationCommand = "synthetic exact shell command after Activity recreation"
        var shellAccepted = false
        activityScenario.onActivity { activity ->
            shellAccepted = activity.commandTransport.executeShellCommand(postRecreationCommand)
        }
        assertTrue("the reattached Activity's current presentation must admit shell input", shellAccepted)
        waitForRecentCommand(activityScenario, postRecreationCommand)

        val detachedScreenInput = "synthetic detached screen input after reattachment"
        assertFalse("a detached screen cannot borrow the reattached screen's input capability",
            detachedScreenTransport.get().executeShellCommand(detachedScreenInput))
        assertFalse("detached input is not written by the retained session",
            recordingOwner.backend.writes.any { it == "$detachedScreenInput\r\n" })
        assertEquals("detached input cannot borrow the new screen's recent-command target",
            postRecreationCommand, recentCommandTextOn(activityScenario))

        SessionForegroundService.requestDisconnect(context)
        eventually { recordingOwner.controller.current.connection.phase == ConnectionState.Phase.DISCONNECTED }
        var disconnectedCommandAccepted = true
        activityScenario.onActivity { activity ->
            disconnectedCommandAccepted = activity.commandTransport.executeShellCommand(
                "must not appear after disconnect")
        }
        assertFalse("disconnected input must be rejected", disconnectedCommandAccepted)
        assertEquals("rejected input must not replace the last accepted command",
            postRecreationCommand, recentCommandTextOn(activityScenario))
    }

    @Test
    fun cancelledMouseAndKeyboardButtonTouchUpsCannotBorrowResumedSurfaceButAccessibilityStillWorks() {
        grantNotificationPermissionForDisclosureTest()
        oldKeyboardBatchPresent = defaultPreferences.contains("setting_keyboard_batch")
        oldKeyboardBatch = if (oldKeyboardBatchPresent)
            defaultPreferences.getBoolean("setting_keyboard_batch", true) else null
        defaultPreferences.edit { putBoolean("setting_keyboard_batch", true) }

        val recordingOwner = RecordingTransportOwner()
        install(recordingOwner)
        val startup = "synthetic input lifecycle startup"
        val activityScenario = launchVisibleActivity(autoConnect = true, inputBackend = "xdotool",
            initialCommand = startup)
        eventually { recordingOwner.controller.current.connection.isReady }
        eventually { recordingOwner.backend.writes.contains("$startup\r\n") }
        recordingOwner.backend.writes.clear() // Exclude the profile's expected initial shell command.
        eventually {
            var active = false
            activityScenario.onActivity { active = it.activeControlInputToken() != null }
            active
        }

        val mouseButton = AtomicReference<View>()
        val keyboardButton = AtomicReference<View>()
        val oldMouseSurface = AtomicReference<InputSurfaceToken>()
        var mouseDownTime = 0L
        activityScenario.onActivity { activity ->
            mouseButton.set(activity.findViewById(R.id.firstMouseButton))
            assertTrue("mouse button was not laid out", mouseButton.get().width > 0)
            oldMouseSurface.set(requireNotNull(activity.activeControlInputToken()))
            mouseDownTime = SystemClock.uptimeMillis()
            dispatchCapturedButtonTouch(mouseButton.get(), mouseDownTime, mouseDownTime,
                MotionEvent.ACTION_DOWN, 20f, 20f)
            assertTrue(mouseButton.get().isPressed)
        }

        activityScenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        eventually {
            var inactive = false
            activityScenario.onActivity { inactive = it.activeControlInputToken() == null }
            inactive
        }
        activityScenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        val resumedMouseButton = AtomicReference<View>()
        eventually {
            var current: InputSurfaceToken? = null
            activityScenario.onActivity { activity ->
                current = activity.activeControlInputToken()
                resumedMouseButton.set(activity.findViewById(R.id.firstMouseButton))
            }
            current != null && current !== oldMouseSurface.get()
        }
        activityScenario.onActivity {
            dispatchCapturedButtonTouch(mouseButton.get(), mouseDownTime, SystemClock.uptimeMillis(),
                MotionEvent.ACTION_UP, 20f, 20f)
            assertTrue("stale mouse UP emitted input: ${recordingOwner.backend.writes}",
                recordingOwner.backend.writes.isEmpty())
            assertTrue("delayed click callback was not consumed", mouseButton.get().performClick())
        }
        assertTrue("stale mouse click callback emitted input: ${recordingOwner.backend.writes}",
            recordingOwner.backend.writes.isEmpty())

        activityScenario.onActivity {
            assertTrue("a fresh accessibility click must not be blocked by canceled touch ownership",
                resumedMouseButton.get().performAccessibilityAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, android.os.Bundle()))
        }
        eventually { recordingOwner.backend.writes.contains("xdotool click 1\r\n") }
        assertEquals(1, recordingOwner.backend.writes.count { it == "xdotool click 1\r\n" })

        activityScenario.onActivity {
            val button = resumedMouseButton.get()
            assertTrue("resumed mouse button was not laid out", button.width > 0 && button.height > 0)
            val freshDown = SystemClock.uptimeMillis()
            val x = button.width / 2f
            val y = button.height / 2f
            dispatchCapturedButtonTouch(button, freshDown, freshDown, MotionEvent.ACTION_DOWN, x, y)
            dispatchCapturedButtonTouch(button, freshDown, freshDown + 1, MotionEvent.ACTION_UP, x, y)
        }
        eventually { recordingOwner.backend.writes.count { it == "xdotool click 1\r\n" } == 2 }

        val oldKeyboardSurface = AtomicReference<InputSurfaceToken>()
        var keyboardDownTime = 0L
        activityScenario.onActivity { activity ->
            activity.findViewById<View>(R.id.fab).performClick()
            activity.findViewById<EditText>(R.id.keyboard_input).setText("input_lifecycle_fixture")
            keyboardButton.set(activity.findViewById(R.id.keyboard_send))
            assertTrue("keyboard send button was not laid out", keyboardButton.get().width > 0)
            oldKeyboardSurface.set(requireNotNull(activity.activeControlInputToken()))
            keyboardDownTime = SystemClock.uptimeMillis()
            dispatchCapturedButtonTouch(keyboardButton.get(), keyboardDownTime, keyboardDownTime,
                MotionEvent.ACTION_DOWN, 20f, 20f)
        }

        activityScenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        eventually {
            var inactive = false
            activityScenario.onActivity { inactive = it.activeControlInputToken() == null }
            inactive
        }
        activityScenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        val resumedKeyboardButton = AtomicReference<View>()
        eventually {
            var current: InputSurfaceToken? = null
            activityScenario.onActivity { activity ->
                current = activity.activeControlInputToken()
                resumedKeyboardButton.set(activity.findViewById(R.id.keyboard_send))
            }
            current != null && current !== oldKeyboardSurface.get()
        }
        activityScenario.onActivity {
            dispatchCapturedButtonTouch(keyboardButton.get(), keyboardDownTime, SystemClock.uptimeMillis(),
                MotionEvent.ACTION_UP, 20f, 20f)
            assertTrue("delayed click callback was not consumed", keyboardButton.get().performClick())
        }
        assertFalse("stale keyboard-send UP typed text on the resumed surface",
            recordingOwner.backend.writes.any { it.startsWith("xdotool type ") })

        activityScenario.onActivity {
            assertTrue("a legitimate accessibility click must remain usable despite canceled touch ownership",
                resumedKeyboardButton.get().performAccessibilityAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, android.os.Bundle()))
        }
        eventually { recordingOwner.backend.writes.contains("xdotool type 'input_lifecycle_fixture'\r\n") }
        assertEquals(2, recordingOwner.backend.writes.count { it == "xdotool click 1\r\n" })
        assertEquals(1, recordingOwner.backend.writes.count { it == "xdotool type 'input_lifecycle_fixture'\r\n" })
        activityScenario.onActivity {
            val button = resumedKeyboardButton.get()
            assertTrue("resumed keyboard-send button was not laid out", button.width > 0 && button.height > 0)
            it.findViewById<EditText>(R.id.keyboard_input).setText("input_lifecycle_fixture")
            val freshDown = SystemClock.uptimeMillis()
            val x = button.width / 2f
            val y = button.height / 2f
            dispatchCapturedButtonTouch(button, freshDown, freshDown, MotionEvent.ACTION_DOWN, x, y)
            dispatchCapturedButtonTouch(button, freshDown, freshDown + 1, MotionEvent.ACTION_UP, x, y)
        }
        eventually { recordingOwner.backend.writes.count { it == "xdotool type 'input_lifecycle_fixture'\r\n" } == 2 }
        assertEquals(2, recordingOwner.backend.writes.count { it == "xdotool click 1\r\n" })
        assertEquals(2, recordingOwner.backend.writes.count { it == "xdotool type 'input_lifecycle_fixture'\r\n" })
        assertTrue(recordingOwner.controller.current.connection.isReady)
    }

    @Test
    fun switchingToSettingsDuringTouchpadDragReleasesOnTheSameSessionAndRejectsStaleInput() {
        grantNotificationPermissionForDisclosureTest()
        val recordingOwner = RecordingTransportOwner()
        install(recordingOwner)
        val activityScenario = launchVisibleActivity(autoConnect = true, inputBackend = "xdotool")
        eventually { recordingOwner.controller.current.connection.isReady }
        val originalGeneration = recordingOwner.controller.current.generation
        val oldActivity = AtomicReference<MainActivity>()
        val mouseReference = AtomicReference<MyMouseView>()
        val staleButton = AtomicReference<View>()
        val downTime = SystemClock.uptimeMillis()

        activityScenario.onActivity { activity ->
            oldActivity.set(activity)
            useImmediateTouchDelayForTest()
            val mouseLayout = activity.findViewById<android.widget.LinearLayout>(R.id.mouse)
            val mouse = (0 until mouseLayout.childCount).map { mouseLayout.getChildAt(it) }
                .filterIsInstance<MyMouseView>().single()
            mouseReference.set(mouse)
            staleButton.set(activity.findViewById(R.id.firstMouseButton))
            val x = mouse.width / 2f
            dispatchTouch(mouse, downTime, downTime, MotionEvent.ACTION_DOWN, x, 40f)
            SystemClock.sleep(20)
            dispatchTouch(mouse, downTime, downTime + 10, MotionEvent.ACTION_MOVE, x + 12f, 50f)
        }
        eventually { recordingOwner.backend.writes.contains("xdotool mousedown 1\r\n") }
        assertFalse("the drag is still held before leaving", recordingOwner.backend.writes.contains("xdotool mouseup 1\r\n"))

        activityScenario.onActivity { it.startActivity(Intent(Settings.ACTION_SETTINGS)) }
        assertTrue("system Settings did not become foreground", device.wait(
            Until.hasObject(By.pkg("com.android.settings")), 10_000))
        eventually { recordingOwner.backend.writes.contains("xdotool mouseup 1\r\n") }
        assertTrue("the same SSH attempt remains ready while Settings is foreground",
            recordingOwner.controller.current.connection.isReady)
        assertEquals(originalGeneration, recordingOwner.controller.current.generation)
        assertEquals("input suspension must not close or replace the session", 0,
            recordingOwner.backend.events.count { it.name == "session.close" })
        assertTrue("ordinary app switching retains the ongoing session notification",
            context.getSystemService(NotificationManager::class.java).activeNotifications
                .any { it.id == NOTIFICATION_ID })

        activityScenario.onActivity { activity ->
            assertNull("a paused Activity has no active input capability", activity.activeControlInputToken())
            val mouse = mouseReference.get()
            dispatchTouch(mouse, downTime, downTime + 20, MotionEvent.ACTION_MOVE,
                mouse.width / 2f + 18f, 60f)
            dispatchTouch(mouse, downTime, downTime + 30, MotionEvent.ACTION_UP,
                mouse.width / 2f + 18f, 60f)
            assertFalse(activity.commandTransport.executeShellCommand("stale background shell input"))
            val keyDown = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP, 0)
            val keyUp = KeyEvent(downTime, downTime + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP, 0)
            activity.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, keyDown)
            activity.onKeyUp(KeyEvent.KEYCODE_VOLUME_UP, keyUp)
            staleButton.get().performClick()
            activity.findViewById<EditText>(R.id.keyboard_input).setText("stale background keyboard")
            MainActivity::class.java.getDeclaredMethod("useKeyboardSendText")
                .apply { isAccessible = true }.invoke(activity)
        }
        val writesAfterBackgroundEvents = recordingOwner.backend.writes.toList()
        device.pressBack()
        eventually {
            var active = false
            activityScenario.onActivity { activity ->
                active = activity.hasWindowFocus() && activity.activeControlInputToken() != null
            }
            active
        }
        assertTrue(recordingOwner.controller.current.connection.isReady)
        assertEquals(originalGeneration, recordingOwner.controller.current.generation)

        activityScenario.onActivity { activity ->
            val mouse = mouseReference.get()
            dispatchTouch(mouse, downTime, downTime + 40, MotionEvent.ACTION_MOVE,
                mouse.width / 2f + 20f, 60f)
            dispatchTouch(mouse, downTime, downTime + 50, MotionEvent.ACTION_UP,
                mouse.width / 2f + 20f, 60f)
            val freshDown = SystemClock.uptimeMillis()
            val x = mouse.width / 2f
            useImmediateTouchDelayForTest()
            dispatchTouch(mouse, freshDown, freshDown, MotionEvent.ACTION_DOWN, x, 40f)
            SystemClock.sleep(20)
            dispatchTouch(mouse, freshDown, freshDown + 10, MotionEvent.ACTION_MOVE, x + 12f, 50f)
            dispatchTouch(mouse, freshDown, freshDown + 20, MotionEvent.ACTION_UP, x + 12f, 50f)
        }
        val freshDragDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (recordingOwner.backend.writes.count { it == "xdotool mouseup 1\r\n" } < 2 &&
            System.nanoTime() < freshDragDeadline) Thread.sleep(10)
        assertTrue("the returned touchpad drag did not reach provider write: ${recordingOwner.backend.writes}",
            recordingOwner.backend.writes.count { it == "xdotool mouseup 1\r\n" } >= 2)
        assertEquals("late old events do not write after returning", writesAfterBackgroundEvents.size + 3,
            recordingOwner.backend.writes.size)
        assertEquals("the foreground session remains the original attempt", originalGeneration,
            recordingOwner.controller.current.generation)
        assertTrue(recordingOwner.controller.current.connection.isReady)
    }

    @Test
    fun taskRemovalAndExitClearSessionIntentTransportAndNotification() {
        grantNotificationPermissionForDisclosureTest()
        val recordingOwner = RecordingTransportOwner()
        install(recordingOwner)
        val activityScenario = launchVisibleActivity(autoConnect = true)
        eventually { recordingOwner.controller.current.connection.isReady }
        val binding = bind(activityScenario)
        eventually { binding.binder.snapshot.connection.isReady }
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications
            .any { it.id == NOTIFICATION_ID })

        instrumentation.runOnMainSync {
            binding.binder.serviceForTests.onTaskRemoved(null)
            binding.binder.serviceForTests.onTaskRemoved(null)
        }
        eventually {
            recordingOwner.controller.current.connection.phase == ConnectionState.Phase.DISCONNECTED &&
                recordingOwner.controller.current.automaticConnectSuppressed && !binding.binder.isForeground
        }
        assertNull(recordingOwner.controller.current.desiredHost)
        assertEquals(1, recordingOwner.backend.events.count { it.name == "session.close" })
        assertTrue(recordingOwner.backend.channels.all { it.closed })
        assertFalse("task dismissal removes the ongoing notification",
            context.getSystemService(NotificationManager::class.java).activeNotifications
                .any { it.id == NOTIFICATION_ID })

        // Repeated task cleanup remains a no-op for the already closed transport.
        assertEquals(1, recordingOwner.backend.events.count { it.name == "session.close" })
        unbind(activityScenario, binding)
        activityScenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        eventually { binding.binder.destroyedForTests }
        activityScenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        assertEquals("task dismissal suppresses saved auto-connect on return", 1, recordingOwner.attemptsCreated)

        // A later deliberate Connect is still allowed; Exit then uses the same explicit stop path.
        requestConnect(activityScenario)
        eventually { recordingOwner.controller.current.connection.isReady }
        assertEquals(2, recordingOwner.attemptsCreated)
        val exitBinding = bind(activityScenario)
        eventually { exitBinding.binder.snapshot.connection.isReady }
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications
            .any { it.id == NOTIFICATION_ID })
        unbind(activityScenario, exitBinding)
        val exitFinished = AtomicBoolean()
        activityScenario.onActivity { activity ->
            val toolbar = activity.findViewById<Toolbar>(R.id.toolbar)
            if (toolbar.menu.findItem(R.id.action_exit) == null) activity.menuInflater.inflate(R.menu.main, toolbar.menu)
            assertTrue("Exit action must be handled",
                activity.onOptionsItemSelected(toolbar.menu.findItem(R.id.action_exit)))
            exitFinished.set(activity.isFinishing)
        }
        assertTrue("Exit must close the control Activity", exitFinished.get())
        eventually {
            exitBinding.binder.destroyedForTests &&
                recordingOwner.controller.current.connection.phase == ConnectionState.Phase.DISCONNECTED &&
                recordingOwner.controller.current.automaticConnectSuppressed &&
                context.getSystemService(NotificationManager::class.java).activeNotifications
                    .none { it.id == NOTIFICATION_ID }
        }
        assertNull(recordingOwner.controller.current.desiredHost)
        assertEquals("Exit closes the second transport too", 2,
            recordingOwner.backend.events.count { it.name == "session.close" })
        assertTrue(recordingOwner.backend.channels.all { it.closed })
    }

    @Test
    fun ydotoolCustomMouseDownReleasesWithCapturedCodeWhenControlActivityPauses() {
        grantNotificationPermissionForDisclosureTest()
        val recordingOwner = RecordingTransportOwner()
        install(recordingOwner)
        val activityScenario = launchVisibleActivity(autoConnect = true, inputBackend = "ydotool")
        eventually { recordingOwner.controller.current.connection.isReady }
        val originalGeneration = recordingOwner.controller.current.generation
        activityScenario.onActivity { activity ->
            val adapter = CustomKeyboardButtonAdapter(activity, emptyList())
            adapter.execCommand("mousedown 3", activity.doTool, activity.commandTransport)
        }
        eventually { recordingOwner.backend.writes.contains("ydotool click 0x41\r\n") }

        activityScenario.onActivity { it.startActivity(Intent(Settings.ACTION_SETTINGS)) }
        assertTrue(device.wait(Until.hasObject(By.pkg("com.android.settings")), 10_000))
        eventually { recordingOwner.backend.writes.contains("ydotool click 0x81\r\n") }
        assertTrue(recordingOwner.controller.current.connection.isReady)
        assertEquals(originalGeneration, recordingOwner.controller.current.generation)
        assertFalse("the xdotool mapping must not be used for this ydotool hold",
            recordingOwner.backend.writes.contains("xdotool mouseup 3\r\n"))

        device.pressBack()
        eventually {
            var active = false
            activityScenario.onActivity { active = it.activeControlInputToken() != null }
            active
        }
        activityScenario.onActivity { activity ->
            val adapter = CustomKeyboardButtonAdapter(activity, emptyList())
            adapter.execCommand("mousedown 3", activity.doTool, activity.commandTransport)
            adapter.execCommand("mouseup 3", activity.doTool, activity.commandTransport)
        }
        eventually { recordingOwner.backend.writes.count { it == "ydotool click 0x81\r\n" } >= 2 }
        assertEquals(originalGeneration, recordingOwner.controller.current.generation)
        assertTrue(recordingOwner.controller.current.connection.isReady)
    }

    @Test
    fun touchCancelReleasesDragWithoutClickAndResetsJoystickForFreshDown() {
        grantNotificationPermissionForDisclosureTest()
        val recordingOwner = RecordingTransportOwner()
        install(recordingOwner)
        val activityScenario = launchVisibleActivity(autoConnect = true, inputBackend = "xdotool")
        eventually { recordingOwner.controller.current.connection.isReady }
        val mouseReference = AtomicReference<MyMouseView>()
        val joystickReference = AtomicReference<MyJSView>()
        val now = SystemClock.uptimeMillis()
        activityScenario.onActivity { activity ->
            useImmediateTouchDelayForTest()
            val mouse = (0 until activity.findViewById<android.widget.LinearLayout>(R.id.mouse).childCount)
                .map { activity.findViewById<android.widget.LinearLayout>(R.id.mouse).getChildAt(it) }
                .filterIsInstance<MyMouseView>().single()
            mouseReference.set(mouse)
            val x = mouse.width / 2f
            dispatchTouch(mouse, now, now, MotionEvent.ACTION_DOWN, x, 40f)
            SystemClock.sleep(20)
            dispatchTouch(mouse, now, now + 10, MotionEvent.ACTION_MOVE, x + 12f, 50f)
        }
        eventually { recordingOwner.backend.writes.contains("xdotool mousedown 1\r\n") }
        activityScenario.onActivity { activity ->
            val mouse = mouseReference.get()
            dispatchTouch(mouse, now, now + 20, MotionEvent.ACTION_CANCEL,
                mouse.width / 2f + 12f, 50f)
        }
        eventually { recordingOwner.backend.writes.contains("xdotool mouseup 1\r\n") }
        assertFalse("ACTION_CANCEL never synthesizes the touchpad click",
            recordingOwner.backend.writes.contains("xdotool click 1\r\n"))
        val afterCancel = recordingOwner.backend.writes.size
        activityScenario.onActivity { activity ->
            val mouse = mouseReference.get()
            dispatchTouch(mouse, now, now + 30, MotionEvent.ACTION_MOVE, mouse.width / 2f + 20f, 60f)
            dispatchTouch(mouse, now, now + 40, MotionEvent.ACTION_UP, mouse.width / 2f + 20f, 60f)
            assertEquals("post-cancel MOVE/UP cannot resume the old drag", afterCancel,
                recordingOwner.backend.writes.size)
            assertTrue(activity.findViewById<View>(R.id.jsToggle).performClick())
            val joystick = (0 until activity.findViewById<android.widget.LinearLayout>(R.id.js).childCount)
                .map { activity.findViewById<android.widget.LinearLayout>(R.id.js).getChildAt(it) }
                .filterIsInstance<MyJSView>().single()
            joystickReference.set(joystick)
            val size = View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY)
            joystick.measure(size, size)
            joystick.layout(0, 0, 240, 240)
            dispatchTouch(joystick, now, now, MotionEvent.ACTION_DOWN, 120f, 120f)
            dispatchTouch(joystick, now, now + 10, MotionEvent.ACTION_MOVE, 170f, 90f)
        }
        eventually { recordingOwner.backend.writes.size >= afterCancel + 2 }
        activityScenario.onActivity { activity ->
            dispatchTouch(joystickReference.get(), now, now + 20, MotionEvent.ACTION_CANCEL, 170f, 90f)
        }
        eventually { recordingOwner.backend.writes.size >= afterCancel + 2 }
        val afterJoystickCancel = recordingOwner.backend.writes.size
        activityScenario.onActivity { activity ->
            val joystick = joystickReference.get()
            dispatchTouch(joystick, now, now + 30, MotionEvent.ACTION_MOVE, 200f, 50f)
            dispatchTouch(joystick, now, now + 40, MotionEvent.ACTION_UP, 200f, 50f)
            assertEquals("joystick cancellation stops stale movement", afterJoystickCancel,
                recordingOwner.backend.writes.size)
            val cx = MyJSView::class.java.getDeclaredField("cx").apply { isAccessible = true }.getInt(joystick)
            val cy = MyJSView::class.java.getDeclaredField("cy").apply { isAccessible = true }.getInt(joystick)
            assertEquals(cx, MyJSView::class.java.getDeclaredField("hx").apply { isAccessible = true }.getInt(joystick))
            assertEquals(cy, MyJSView::class.java.getDeclaredField("hy").apply { isAccessible = true }.getInt(joystick))
            dispatchTouch(joystick, now + 50, now + 50, MotionEvent.ACTION_DOWN, 120f, 120f)
            dispatchTouch(joystick, now + 50, now + 60, MotionEvent.ACTION_MOVE, 170f, 90f)
        }
        eventually { recordingOwner.backend.writes.size > afterJoystickCancel }
        assertTrue(recordingOwner.controller.current.connection.isReady)
    }

    @Test
    fun injectedGyroscopeRegistrationAndLateCallbacksStayOnMainAndStopWithTheSurface() {
        val mainThread = Looper.getMainLooper().thread
        val callbackThread = AtomicReference<Thread>()
        val registerThread = AtomicReference<Thread>()
        val unregisterThread = AtomicReference<Thread>()
        val callbacks = AtomicInteger()
        lateinit var registeredListener: GyroscopeSamplingGate.Listener
        val gate = GyroscopeSamplingGate(object : GyroscopeSamplingGate.SensorFacade {
            override fun hasGyroscope() = true
            override fun register(listener: GyroscopeSamplingGate.Listener): Boolean {
                registerThread.set(Thread.currentThread())
                registeredListener = listener
                return true
            }
            override fun unregister(listener: GyroscopeSamplingGate.Listener) {
                assertSame(registeredListener, listener)
                unregisterThread.set(Thread.currentThread())
            }
        }, GyroscopeSamplingGate.MotionCallback { _, _, _ ->
            callbackThread.set(Thread.currentThread())
            callbacks.incrementAndGet()
        })
        val surface = InputSurfaceToken()

        instrumentation.runOnMainSync {
            gate.update(resumed = true, controlSurface = surface, userEnabled = true)
            registeredListener.onSample(1f, 2f)
        }
        assertTrue(gate.isSampling)
        assertSame(mainThread, registerThread.get())
        assertSame(mainThread, callbackThread.get())
        assertEquals(1, callbacks.get())

        instrumentation.runOnMainSync {
            gate.update(resumed = false, controlSurface = surface, userEnabled = true)
            registeredListener.onSample(3f, 4f)
        }
        assertFalse(gate.isSampling)
        assertSame(mainThread, unregisterThread.get())
        assertEquals("a queued callback from the unregistered sensor lease is inert", 1, callbacks.get())
    }

    @Test
    fun mainActivityLifecycleStopsAndRestartsItsGyroscopeInputLease() {
        grantNotificationPermissionForDisclosureTest()
        oldGyroEnabled = if (defaultPreferences.contains("control_gyro_enabled"))
            defaultPreferences.getBoolean("control_gyro_enabled", false) else null
        listOf("gyro_z_sensitivity_list", "gyro_y_sensitivity_list", "gyro_z_threshold_list",
            "gyro_y_threshold_list").forEach { key ->
            oldGyroSettings[key] = if (defaultPreferences.contains(key)) defaultPreferences.getString(key, null) else null
        }
        defaultPreferences.edit { putBoolean("control_gyro_enabled", false) }
        defaultPreferences.edit {
            putString("gyro_z_sensitivity_list", "1.0f")
            putString("gyro_y_sensitivity_list", "1.0f")
            putString("gyro_z_threshold_list", "0.5f")
            putString("gyro_y_threshold_list", "0.5f")
        }

        val recordingOwner = RecordingTransportOwner()
        install(recordingOwner)
        val activityScenario = launchVisibleActivity(autoConnect = true, inputBackend = "xdotool")
        eventually { recordingOwner.controller.current.connection.isReady }
        eventually {
            var active = false
            activityScenario.onActivity { active = it.activeControlInputToken() != null }
            active
        }

        val registered = CopyOnWriteArrayList<GyroscopeSamplingGate.Listener>()
        val unregistered = CopyOnWriteArrayList<GyroscopeSamplingGate.Listener>()
        val gateReference = AtomicReference<GyroscopeSamplingGate>()
        activityScenario.onActivity { activity ->
            val motion = MainActivity::class.java.getDeclaredMethod(
                "onGyroscopeMotion", InputSurfaceToken::class.java, java.lang.Float.TYPE, java.lang.Float.TYPE,
            ).apply { isAccessible = true }
            val gate = GyroscopeSamplingGate(object : GyroscopeSamplingGate.SensorFacade {
                override fun hasGyroscope() = true
                override fun register(listener: GyroscopeSamplingGate.Listener): Boolean {
                    registered.add(listener)
                    return true
                }
                override fun unregister(listener: GyroscopeSamplingGate.Listener) { unregistered.add(listener) }
            }, GyroscopeSamplingGate.MotionCallback { token, y, z -> motion.invoke(activity, token, y, z) })
            MainActivity::class.java.getDeclaredField("gyroSamplingGate").apply { isAccessible = true }
                .set(activity, gate)
            gateReference.set(gate)

            val toggle = activity.findViewById<android.widget.ToggleButton>(R.id.gyroToggle)
            assertFalse("test begins with gyro disabled to avoid real platform registration", toggle.isChecked)
            toggle.performClick()
            assertTrue(toggle.isChecked)
        }
        eventually { gateReference.get().isSampling && registered.size == 1 }
        val firstListener = registered.single()
        activityScenario.onActivity { firstListener.onSample(0f, 20f) }
        eventually { recordingOwner.backend.writes.any { it.startsWith("xdotool mousemove_relative -- ") } }
        val writesBeforeLeaving = recordingOwner.backend.writes.size
        val originalGeneration = recordingOwner.controller.current.generation

        activityScenario.onActivity { it.startActivity(Intent(Settings.ACTION_SETTINGS)) }
        assertTrue("system Settings did not become foreground", device.wait(
            Until.hasObject(By.pkg("com.android.settings")), 10_000))
        eventually { !gateReference.get().isSampling && unregistered.contains(firstListener) }
        val writesWhilePaused = recordingOwner.backend.writes.size
        assertEquals("the Activity lifecycle unregisters gyro before later sensor callbacks", writesBeforeLeaving,
            writesWhilePaused)
        activityScenario.onActivity { firstListener.onSample(0f, 25f) }
        assertEquals("a callback queued for the retired Activity surface cannot send motion", writesWhilePaused,
            recordingOwner.backend.writes.size)
        assertEquals(originalGeneration, recordingOwner.controller.current.generation)
        assertTrue(recordingOwner.controller.current.connection.isReady)

        device.pressBack()
        eventually {
            var active = false
            activityScenario.onActivity { active = it.activeControlInputToken() != null }
            active && gateReference.get().isSampling && registered.size == 2
        }
        val resumedListener = registered.last()
        activityScenario.onActivity {
            firstListener.onSample(0f, 30f)
            resumedListener.onSample(0f, 20f)
        }
        eventually { recordingOwner.backend.writes.size == writesWhilePaused + 1 }
        assertEquals("only the resumed surface's new gyro lease produces remote movement",
            writesWhilePaused + 1, recordingOwner.backend.writes.size)
        assertEquals(originalGeneration, recordingOwner.controller.current.generation)
    }

    @Test
    fun repeatedSameHostForegroundRequestsPreserveCredentialAndFirstTrustPrompts() {
        grantNotificationPermissionForDisclosureTest()
        val selectedHost = SessionFixtures.host("repeat-consent.synthetic.invalid", 61,
            "synthetic_repeat_consent", 2261)
        val recordingOwner = RecordingTransportOwner(
            credentialPromptReads = 1,
            requireFirstTrust = true,
            automaticallySelectedHost = selectedHost,
        )
        install(recordingOwner)
        val activityScenario = launchVisibleActivity()

        requestConnect(activityScenario)
        waitForText("Re-enter password")
        eventually { recordingOwner.credentialRequests.size == 1 }
        val credential = recordingOwner.credentialRequests.single()
        val firstPrompt = activityPrompt(activityScenario) as SessionInteractions.Prompt.Credential
        assertSame(credential, firstPrompt.request)
        assertTrue(credential.isPending)

        requestConnect(activityScenario)
        eventually {
            recordingOwner.selectionCalls.get() == 2 && recordingOwner.selectionsCompleted.get() == 2 &&
                !SessionForegroundService.hasPendingStart(context)
        }
        assertTrue("repeated same-host Connect must not cancel credential consent", credential.isPending)
        assertSame("credential prompt stays attached to the same request", firstPrompt.request,
            (activityPrompt(activityScenario) as SessionInteractions.Prompt.Credential).request)
        assertEquals("same-host request must not reread credentials", 1, recordingOwner.credentialReads.get())

        val syntheticSecret = byteArrayOf(71, 72, 73, 74)
        try { assertTrue("the still-current credential prompt must be approvable", credential.useForThisAttempt(syntheticSecret)) }
        finally { syntheticSecret.fill(0) }
        eventually(timeoutSeconds = 10) {
            activityPrompt(activityScenario) is SessionInteractions.Prompt.Trust ||
                recordingOwner.controller.current.connection is ConnectionState.Error
        }
        assertTrue("expected the production first-trust request; snapshot=${recordingOwner.controller.current}, " +
            "credentialReads=${recordingOwner.credentialReads.get()}, backendEvents=${recordingOwner.backend.events.map { it.name }}",
            activityPrompt(activityScenario) is SessionInteractions.Prompt.Trust)
        val trustPrompt = activityPrompt(activityScenario) as SessionInteractions.Prompt.Trust
        val trust = trustPrompt.request
        assertTrue("first-trust consent must be genuinely pending; snapshot=${recordingOwner.controller.current}, " +
            "backendEvents=${recordingOwner.backend.events.map { it.name }}", trust.isPending)
        waitForText("Trust this SSH host?")
        assertEquals(0, recordingOwner.attemptsCreated)

        requestConnect(activityScenario)
        eventually {
            recordingOwner.selectionCalls.get() == 3 && recordingOwner.selectionsCompleted.get() == 3 &&
                !SessionForegroundService.hasPendingStart(context)
        }
        assertTrue("repeated same-host Connect must not cancel first-trust consent", trust.isPending)
        assertSame("the original first-trust request remains current", trust,
            (activityPrompt(activityScenario) as SessionInteractions.Prompt.Trust).request)
        assertEquals("same-host retry must not restart credential reads", 1, recordingOwner.credentialReads.get())
        assertEquals("no authentication/backend attempt may precede host-key approval", 0,
            recordingOwner.backendRequests.size)

        clickText("Accept and save")
        eventually { recordingOwner.controller.current.connection.isReady }
        assertEquals(1, recordingOwner.credentialReads.get())
        assertEquals("one accepted consent path creates exactly one backend attempt", 1,
            recordingOwner.attemptsCreated)
        assertEquals(1, recordingOwner.backendRequests.size)
        assertEquals(selectedHost.endpoint.identity, recordingOwner.backendRequests.single().endpoint.identity)
        SessionForegroundService.requestDisconnect(context)
        eventually { recordingOwner.controller.current.connection.phase == ConnectionState.Phase.DISCONNECTED }
    }

    @Test
    fun newerForegroundHostSelectionCancelsOldCredentialAndRejectsLateApproval() {
        grantNotificationPermissionForDisclosureTest()
        val recordingOwner = RecordingTransportOwner(manualSelections = true, credentialPromptReads = 1)
        install(recordingOwner)
        val activityScenario = launchVisibleActivity()
        val oldHost = SessionFixtures.host("old-consent.synthetic.invalid", 62, "synthetic_old_consent", 2262)
        val newestHost = SessionFixtures.host("new-consent.synthetic.invalid", 63, "synthetic_new_consent", 2263)

        requestConnect(activityScenario)
        eventually { recordingOwner.selectionCalls.get() == 1 }
        recordingOwner.completeSelection(0, oldHost)
        waitForText("Re-enter password")
        eventually { recordingOwner.credentialRequests.size == 1 }
        val staleCredential = recordingOwner.credentialRequests.single()
        assertTrue(staleCredential.isPending)

        requestConnect(activityScenario)
        eventually { recordingOwner.selectionCalls.get() == 2 }
        recordingOwner.completeSelection(0, newestHost)
        eventually {
            !staleCredential.isPending && recordingOwner.controller.current.connection.isReady &&
                recordingOwner.controller.current.desiredHost?.sameRequest(newestHost) == true
        }
        val staleBytes = byteArrayOf(81, 82, 83)
        try {
            assertFalse("late credential entry cannot revive the superseded host",
                staleCredential.useForThisAttempt(staleBytes))
        } finally { staleBytes.fill(0) }
        assertEquals(1, recordingOwner.cancelledCredentialRequests.get())
        assertEquals(2, recordingOwner.credentialReads.get())
        assertEquals(1, recordingOwner.backendRequests.size)
        assertEquals("only the newest host may authenticate", newestHost.endpoint.identity,
            recordingOwner.backendRequests.single().endpoint.identity)
        assertEquals(1, recordingOwner.attemptsCreated)
        SessionForegroundService.requestDisconnect(context)
        eventually { recordingOwner.controller.current.connection.phase == ConnectionState.Phase.DISCONNECTED }
    }

    @Test
    fun activityOutputDialogIsDismissedOnStopAndNotReplayedAfterRecreation() {
        grantNotificationPermissionForDisclosureTest()
        val recordingOwner = RecordingTransportOwner()
        install(recordingOwner)
        val activityScenario = launchVisibleActivity()
        requestConnect(activityScenario)
        eventually { recordingOwner.controller.current.connection.isReady }
        var accepted = false
        activityScenario.onActivity { activity ->
            accepted = activity.commandTransport.executeExecCommand("synthetic output lifecycle",
                activityPresentationToken(activity))
        }
        assertTrue(accepted)
        waitForTextContains("out one")

        val oldDialog = AtomicReference<AlertDialog>()
        activityScenario.onActivity { activity ->
            oldDialog.set(requireNotNull(activityOutputDialog(activity)))
            assertTrue(oldDialog.get().isShowing)
        }
        activityScenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        assertFalse("stopping the Activity must dismiss its output window", oldDialog.get().isShowing)
        activityScenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        activityScenario.recreate()
        eventually {
            var rebound = false
            activityScenario.onActivity { activity ->
                rebound = activitySnapshot(activity)?.connection?.isReady == true && activityOutputDialog(activity) == null
            }
            rebound
        }
        assertFalse("old command output must not be replayed on the recreated screen", oldDialog.get().isShowing)
        assertTrue(recordingOwner.controller.current.connection.isReady)
        SessionForegroundService.requestDisconnect(context)
        eventually { recordingOwner.controller.current.connection.phase == ConnectionState.Phase.DISCONNECTED }
    }

    @Test
    fun lateExecOutputFromTheOldPresentationIsNotShownAfterActivityRecreation() {
        grantNotificationPermissionForDisclosureTest()
        val recordingOwner = RecordingTransportOwner(blockExecConnect = true)
        install(recordingOwner)
        val activityScenario = launchVisibleActivity()
        requestConnect(activityScenario)
        eventually { recordingOwner.controller.current.connection.isReady }

        var accepted = false
        activityScenario.onActivity { activity ->
            accepted = activity.commandTransport.executeExecCommand("old-screen blocked output",
                activityPresentationToken(activity))
        }
        assertTrue(accepted)
        assertTrue("the script did not reach the deliberately blocked provider operation",
            recordingOwner.backend.entered.await(5, TimeUnit.SECONDS))

        activityScenario.recreate()
        eventually {
            var currentScreenReady = false
            activityScenario.onActivity { activity ->
                currentScreenReady = activitySnapshot(activity)?.connection?.isReady == true &&
                    activityOutputDialog(activity) == null
            }
            currentScreenReady
        }
        recordingOwner.backend.release.countDown()
        eventually { recordingOwner.backend.events.any { it.name == "exec.close" } }
        instrumentation.waitForIdleSync()
        activityScenario.onActivity { assertNull("late output must stay with the destroyed screen", activityOutputDialog(it)) }
        assertTrue("rendering the previous screen result must not affect the service-owned transport",
            recordingOwner.controller.current.connection.isReady)
        SessionForegroundService.requestDisconnect(context)
        eventually { recordingOwner.controller.current.connection.phase == ConnectionState.Phase.DISCONNECTED }
    }

    @Test
    fun lateExecOutputFromAReplacedAttemptIsNotShownOnTheNewHost() {
        grantNotificationPermissionForDisclosureTest()
        val recordingOwner = RecordingTransportOwner(manualSelections = true, blockExecConnect = true)
        install(recordingOwner)
        val activityScenario = launchVisibleActivity()
        requestConnect(activityScenario)
        assertTrue(recordingOwner.selectionStarted.await(3, TimeUnit.SECONDS))
        val oldHost = SessionFixtures.host("old-script-output.synthetic.invalid", 71, "old_script_output", 2271)
        recordingOwner.completeSelection(0, oldHost)
        eventually { recordingOwner.controller.current.connection.isReady }

        var accepted = false
        activityScenario.onActivity { activity ->
            accepted = activity.commandTransport.executeExecCommand("old-host blocked output",
                activityPresentationToken(activity))
        }
        assertTrue(accepted)
        assertTrue("the old-host script did not reach its blocked provider operation",
            recordingOwner.backend.entered.await(5, TimeUnit.SECONDS))

        requestConnect(activityScenario)
        eventually { recordingOwner.selectionCalls.get() == 2 }
        val newHost = SessionFixtures.host("new-script-output.synthetic.invalid", 72, "new_script_output", 2272)
        recordingOwner.completeSelection(0, newHost)
        eventually {
            recordingOwner.controller.current.desiredHost?.sameRequest(newHost) == true &&
                recordingOwner.controller.current.connection.phase == ConnectionState.Phase.CLOSING
        }
        recordingOwner.backend.release.countDown()
        eventually {
            recordingOwner.controller.current.connection.isReady &&
                recordingOwner.controller.current.attemptHost?.sameRequest(newHost) == true
        }
        eventually { recordingOwner.backend.events.any { it.name == "exec.close" } }
        instrumentation.waitForIdleSync()
        activityScenario.onActivity { assertNull("old-attempt output must not appear for the new host", activityOutputDialog(it)) }
        assertEquals(newHost.endpoint.identity, recordingOwner.backendRequests.last().endpoint.identity)
        SessionForegroundService.requestDisconnect(context)
        eventually { recordingOwner.controller.current.connection.phase == ConnectionState.Phase.DISCONNECTED }
    }

    @Test
    fun activityPromptPresenterCancelsStaleCredentialRequestAcrossHostChangeAndRecreation() {
        val recordingOwner = RecordingTransportOwner()
        install(recordingOwner)
        val activityScenario = launchVisibleActivity()
        waitForActivitySessionBinding(activityScenario)

        val staleResult = AtomicReference<CredentialReadResult?>()
        val staleRequest = CredentialReentryRequest(SecretId("synthetic_old_host_password")) { staleResult.set(it) }
        val stalePrompt = SessionInteractions.Prompt.Credential(CredentialKind.PASSWORD, staleRequest)
        recordingOwner.interactions.show(Any(), stalePrompt, BooleanSupplier { true })
        waitForText("Re-enter password")

        // A new explicit host request invalidates the earlier activity-local prompt before auth can resume.
        recordingOwner.interactions.invalidate()
        assertSame(CredentialReadResult.Cancelled, staleResult.get())
        val afterHostChange = CredentialReentryRequest(SecretId("synthetic_new_host_password")) { }
        recordingOwner.interactions.show(Any(),
            SessionInteractions.Prompt.Credential(CredentialKind.PASSWORD, afterHostChange), BooleanSupplier { true })
        waitForText("Re-enter password")

        activityScenario.recreate()
        assertFalse("a credential prompt must be cancelled when its Activity is destroyed", afterHostChange.isPending)
        assertSame(CredentialReadResult.Cancelled, staleResult.get())
        assertFalse("the invalidated request must not accept stale entered bytes",
            staleRequest.useForThisAttempt("NOT_REAL_stale_password".toByteArray()))

        waitForActivitySessionBinding(activityScenario)
        val latestResult = AtomicReference<CredentialReadResult?>()
        val latest = CredentialReentryRequest(SecretId("synthetic_returned_host_password")) { latestResult.set(it) }
        recordingOwner.interactions.show(Any(),
            SessionInteractions.Prompt.Credential(CredentialKind.PASSWORD, latest), BooleanSupplier { true })
        waitForText("Re-enter password")
        clickText("Cancel")
        eventually { latestResult.get() === CredentialReadResult.Cancelled }
        assertSame(CredentialReadResult.Cancelled, latestResult.get())
        recordingOwner.interactions.invalidate()
        assertEquals(0, recordingOwner.attemptsCreated)
        assertTrue(recordingOwner.backend.events.isEmpty())
    }

    @Test
    fun activityPromptPresenterShowsBoundedChangedHostKeyReviewAndCancelKeepsOldTrust() {
        val recordingOwner = RecordingTransportOwner()
        install(recordingOwner)
        val activityScenario = launchVisibleActivity()
        waitForActivitySessionBinding(activityScenario)
        val trustDirectory = File(context.cacheDir, "activity-trust-${java.util.UUID.randomUUID()}")
        val repository = HostKeyRepository(trustDirectory, AndroidSecretFileIo)
        val requests = HostTrustRequests(repository, Executor { it.run() })
        val endpoint = Endpoint.manual("review.synthetic.invalid", 2222)
        val trustedKey = ApprovedHostKey.parse(OpenSSHPublicKeyUtil.encodePublicKey(
            Ed25519PrivateKeyParameters(ByteArray(32) { (it + 1).toByte() }, 0).generatePublicKey()))
        val changedKey = ApprovedHostKey.parse(OpenSSHPublicKeyUtil.encodePublicKey(
            Ed25519PrivateKeyParameters(ByteArray(32) { (it + 41).toByte() }, 0).generatePublicKey()))
        repository.approve(endpoint.identity, trustedKey, BooleanSupplier { true })
        val observedMismatch = try {
            requests.verify(endpoint, changedKey, BooleanSupplier { true })
            fail("a changed host key must produce a mismatch")
            error("unreachable")
        } catch (mismatch: HostTrustException) {
            assertEquals(HostTrustException.Reason.MISMATCH, mismatch.reason)
            requireNotNull(mismatch.mismatch)
        }
        val observation = requireNotNull(requests.captureMismatch(observedMismatch, BooleanSupplier { true }))
        val epoch = recordingOwner.interactions.reviewEpoch()
        val candidate = requireNotNull(requests.reviewMismatch(observation, BooleanSupplier { true }))
        val prompt = SessionInteractions.Prompt.Mismatch(
            candidate,
            { mismatch, complete ->
                try { requests.replace(mismatch, BooleanSupplier { mismatch.isCurrent() }); complete(true) }
                catch (_: Exception) { complete(false) }
            },
            { candidate.cancel() },
            { finished, success -> recordingOwner.interactions.finishMismatch(finished, success) },
        )
        assertTrue(recordingOwner.interactions.showReview(Any(), prompt, epoch, BooleanSupplier { true }))
        waitForText("SSH host key changed")
        waitForTextContains(trustedKey.fingerprint)
        waitForTextContains(changedKey.fingerprint)
        clickText("Review replacement")
        waitForText("Replace the saved host key?")
        clickText("Cancel")
        eventually { !candidate.isCurrent }
        assertFalse("cancelled mismatch review cannot replace trust", candidate.confirm())
        assertTrue(requireNotNull(repository.find(endpoint.identity)).matches(trustedKey))
        recordingOwner.interactions.invalidate()
        trustDirectory.deleteRecursively()
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
        activityScenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        // This is deliberate Disconnect, not the involuntary service-loss case exercised above.
        assertTrue(SessionForegroundService.requestDisconnect(context))
        eventually { firstBinding.binder.destroyedForTests }
        eventually {
            recordingOwner.controller.current.cleanupUnconfirmed &&
                recordingOwner.controller.current.automaticConnectSuppressed &&
                recordingOwner.controller.current.desiredHost == null
        }

        activityScenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
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
        activityScenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        eventually { autoRecreated.binder.destroyedForTests }

        activityScenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
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

        unbind(activityScenario, binding)
        activityScenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        context.stopService(Intent(context, SessionForegroundService::class.java))
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
        activityScenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        // Arm recovery before explicit Disconnect, proving the notification also revokes that intent.
        recordingOwner.backend.channels.first { it.name == "shell" && !it.closed }.closed = true
        eventually { recordingOwner.controller.current.attemptHost == null &&
            recordingOwner.controller.current.foregroundRecoveryAvailable }
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
        val activityScenario = launchVisibleActivity(autoConnect = true)
        eventually { recordingOwner.controller.current.connection.isReady }
        val originalActivity = AtomicReference<MainActivity>()
        activityScenario.onActivity { originalActivity.set(it) }

        val binding = bind(activityScenario)
        eventually { binding.binder.snapshot.connection.isReady }
        assertEquals(1, recordingOwner.selectionCalls.get())
        assertEquals(1, recordingOwner.attemptsCreated)
        val disconnect = context.getSystemService(NotificationManager::class.java).activeNotifications
            .first { it.id == NOTIFICATION_ID }.notification.actions[1].actionIntent
        val createdBeforeDisconnect = SessionForegroundService.serviceInstancesCreatedForTests

        unbind(activityScenario, binding)
        activityScenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
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
        waitForActivitySessionBinding(activityScenario)
        eventually { SessionForegroundService.serviceInstancesCreatedForTests == createdAfterDisconnect + 1 }
        assertSameActivity(activityScenario, originalActivity.get())
        // Binding the resumed control screen may recreate an idle service solely to observe state.
        // It must not recreate the foreground session or select another host after explicit Disconnect.
        val returnedBinding = bind(activityScenario)
        assertFalse(returnedBinding.binder.isForeground)
        assertEquals(ConnectionState.Phase.DISCONNECTED, returnedBinding.binder.snapshot.connection.phase)
        unbind(activityScenario, returnedBinding)
        assertEquals(1, recordingOwner.selectionCalls.get())
        assertEquals(1, recordingOwner.attemptsCreated)
        val createdAfterReturn = SessionForegroundService.serviceInstancesCreatedForTests
        assertEquals("ordinary resume may bind an idle service but must not start a session",
            createdAfterDisconnect + 1,
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
        assertEquals(1, recordingOwner.selectionCalls.get())
        assertEquals(1, recordingOwner.attemptsCreated)
        assertEquals(createdAfterReturn, SessionForegroundService.serviceInstancesCreatedForTests)
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
        assertEquals(createdAfterReturn, SessionForegroundService.serviceInstancesCreatedForTests)
        SessionForegroundService.requestDisconnect(context)
        eventually { recordingOwner.controller.current.connection.phase == ConnectionState.Phase.DISCONNECTED }
    }

    @Test
    fun toolbarConnectAndDisconnectOperateOnTheServiceOwnedSession() {
        grantNotificationPermissionForDisclosureTest()
        val recordingOwner = RecordingTransportOwner(manualSelections = true)
        install(recordingOwner)
        val activityScenario = launchVisibleActivity()
        invokeToolbarConnectAction(activityScenario)
        assertTrue(recordingOwner.selectionStarted.await(3, TimeUnit.SECONDS))
        eventually { SessionForegroundService.hasPendingStart(context) }
        assertNull("the pending selection deliberately has no desired host yet",
            recordingOwner.controller.current.desiredHost)
        assertEquals(0, recordingOwner.attemptsCreated)
        assertEquals(1, recordingOwner.selectionCalls.get())
        assertEquals(0, recordingOwner.attemptsCreated)

        recordingOwner.completeSelection(0, SessionFixtures.host("pending-service.invalid"))
        eventually { recordingOwner.controller.current.connection.isReady }
        eventually { !SessionForegroundService.hasPendingStart(context) }

        invokeToolbarConnectAction(activityScenario)
        eventually {
            recordingOwner.controller.current.connection.phase == ConnectionState.Phase.DISCONNECTED &&
                recordingOwner.controller.current.automaticConnectSuppressed
        }
        assertEquals(1, recordingOwner.selectionCalls.get())
        assertEquals(1, recordingOwner.attemptsCreated)
        assertEquals(1, recordingOwner.backend.events.count { it.name == "session.close" })
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
        context.startService(Intent(context, SessionForegroundService::class.java)
            .setAction(SessionForegroundService.ACTION_NOTIFICATION_DISCONNECT))
        eventually { registry.retainedOwnerOrNull() == null }
        assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications
            .any { it.id == NOTIFICATION_ID })
    }

    @SdkSuppress(minSdkVersion = 33)
    @Test
    fun stalePermissionResultsAfterRecreationAndDisconnectCannotStartOrClearNewerRequest() {
        org.junit.Assume.assumeTrue("run with notification permission revoked by the host between test runs",
            context.checkSelfPermission(POST_NOTIFICATIONS_PERMISSION) == PackageManager.PERMISSION_DENIED)
        resetNotificationPermissionPrompt()
        val recordingOwner = RecordingTransportOwner()
        install(recordingOwner)

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
        assertEquals("recreating the bound Activity may replace its idle observer service, but stale results " +
            "must not start foreground work", serviceInstancesBeforeRecreation + 1,
            SessionForegroundService.serviceInstancesCreatedForTests)
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

        // The actual Disconnect service action invalidates the newer reservation before its result.
        val serviceInstancesBeforeDisconnect = SessionForegroundService.serviceInstancesCreatedForTests
        assertTrue(SessionForegroundService.requestDisconnect(context))
        eventually {
            !SessionForegroundService.hasPendingStart(context) &&
                !SessionForegroundService.hasReservedStart(context, newerTicket)
        }
        assertEquals("the Activity already binds an idle observer service, so Disconnect reuses it",
            serviceInstancesBeforeDisconnect, SessionForegroundService.serviceInstancesCreatedForTests)
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
        val activityScenario = launchVisibleActivity()
        showSessionControlsFromMenu(activityScenario)
        clickText("Start session")
        waitForText("Show session notification actions?")
        assertTrue("permission rationale must reserve the service request before prompting",
            SessionForegroundService.hasPendingStart(context))
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
        activityScenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        eventually { firstBinding.binder.destroyedForTests }

        activityScenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
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
