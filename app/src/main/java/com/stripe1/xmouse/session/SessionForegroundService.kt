package com.stripe1.xmouse.session

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.stripe1.xmouse.MainActivity
import com.stripe1.xmouse.R
import com.stripe1.xmouse.XMouseApplication
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

enum class ForegroundConnectRequestResult {
    /** The OS accepted the request to start; this does not claim that SSH connected. */
    ACCEPTED,
    ACTIVITY_NOT_VISIBLE,
    SERVICE_START_REJECTED,
}

enum class SessionServiceStartFailure {
    FOREGROUND_PROMOTION_REJECTED,
    OWNER_INITIALIZATION_FAILED,
    HOST_SELECTION_FAILED,
}

/** Started-and-bound, application-owner-backed connected-device SSH session service. */
class SessionForegroundService : Service() {
    @Volatile private var foregroundPromoted = false
    @Volatile private var destroyed = false
    @Volatile private var startFailure: SessionServiceStartFailure? = null
    @Volatile private var latestValidStartId = 0
    private var pendingSelections = 0
    private val activeStartTickets = LinkedHashSet<String>()
    /** Only a failed/rejected latest selection may stop after older lookups retire. */
    private var latestRequestNeedsIdleStop = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val localBinder by lazy(LazyThreadSafetyMode.NONE) { SessionBinder(this) }

    private val registry: SessionOwnerRegistry
        get() = (application as XMouseApplication).sessionOwners

    override fun onBind(intent: Intent?): IBinder = localBinder

    override fun onCreate() {
        super.onCreate()
        createdServiceInstances.incrementAndGet()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_NOTIFICATION_DISCONNECT) {
            // A notification action is a user-requested stop, never a connection capability. Do not
            // initialize an owner here: after process death a stale action must not select metadata,
            // recreate SSH work, or bypass the retained controller's cleanup barrier.
            latestValidStartId = startId
            latestRequestNeedsIdleStop = false
            registry.cancelAllStarts()
            activeStartTickets.clear()
            registry.retainedOwnerOrNull()?.controller?.disconnect()
            stopForegroundAndSelf(startId)
            return START_NOT_STICKY
        }

        val ticket = intent?.getStringExtra(EXTRA_START_TICKET)
        val mode = registry.consumeStart(intent?.action, ticket)
        if (mode == null) {
            // A null OS restart or an unissued/invalid request never creates an owner or starts SSH.
            if (!foregroundPromoted && pendingSelections == 0) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        activeStartTickets.add(requireNotNull(ticket))

        latestValidStartId = startId
        latestRequestNeedsIdleStop = false
        startFailure = null
        if (!foregroundPromoted) {
            try {
                promoteToForeground()
            } catch (_: RuntimeException) {
                startFailure = SessionServiceStartFailure.FOREGROUND_PROMOTION_REJECTED
                latestRequestNeedsIdleStop = true
                finishStart(requireNotNull(ticket))
                if (pendingSelections == 0) stopSelfResult(startId)
                return START_NOT_STICKY
            }
        }

        val owner = try {
            registry.owner()
        } catch (_: Exception) {
            startFailure = SessionServiceStartFailure.OWNER_INITIALIZATION_FAILED
            latestRequestNeedsIdleStop = true
            finishStart(requireNotNull(ticket))
            stopForegroundAndSelf(startId)
            return START_NOT_STICKY
        }
        // Capture the auto-connect permit before selectedHost performs asynchronous repository work.
        val disconnectEpoch = owner.controller.captureDisconnectEpoch()
        val permit = if (mode == SessionStartMode.FOREGROUND_AUTO_CONNECT) {
            owner.controller.automaticConnectPermit()
        } else null
        pendingSelections++
        try {
            val preferences = applicationContext.getSharedPreferences(
                "${applicationContext.packageName}_preferences", Context.MODE_PRIVATE,
            )
            val shell = preferences.getString("setting_shell", "").orEmpty()
            val initialCommand = preferences.getString(
                "setting_xdotool_initial", "export DISPLAY=':0' && unset HISTFILE",
            )
            owner.selectedHost(shell, initialCommand) { host ->
                mainHandler.post {
                    if (!destroyed) applySelectedHost(owner, mode, permit, disconnectEpoch, host, startId,
                        requireNotNull(ticket))
                }
            }
        } catch (_: Exception) {
            mainHandler.post {
                if (!destroyed) applySelectedHost(owner, mode, permit, disconnectEpoch, null, startId,
                    requireNotNull(ticket))
            }
        }
        return START_NOT_STICKY
    }

    private fun promoteToForeground() {
        ensureNotificationChannel()
        val openControls = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_CONTROLS,
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_SESSION_CONTROLS)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnect = PendingIntent.getService(
            this,
            REQUEST_DISCONNECT,
            Intent(this, SessionForegroundService::class.java).setAction(ACTION_NOTIFICATION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_action_hardware_desktop_windows)
            .setContentTitle(getString(R.string.session_notification_title))
            .setContentText(getString(R.string.session_notification_text))
            .setContentIntent(openControls)
            .addAction(android.R.drawable.ic_menu_view, getString(R.string.session_notification_open), openControls)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.session_notification_disconnect), disconnect)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
        foregroundPromoted = true
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.session_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.session_notification_channel_description) },
        )
    }

    private fun applySelectedHost(
        owner: SessionServiceOwner,
        mode: SessionStartMode,
        permit: SessionController.AutoConnectPermit?,
        disconnectEpoch: Long,
        host: SessionHost?,
        startId: Int,
        ticket: String,
    ) {
        pendingSelections = (pendingSelections - 1).coerceAtLeast(0)
        // An older request may complete after a newer Connect. It must not connect or publish an error
        // for the newer request; its retirement still participates in idle-stop bookkeeping below.
        if (destroyed) return
        if (startId != latestValidStartId) {
            finishStart(ticket)
            // Retiring an old lookup may finish a deferred idle stop only for a latest failure/rejection.
            if (pendingSelections == 0 && latestRequestNeedsIdleStop) stopIfIdle(owner, latestValidStartId)
            return
        }
        if (owner.controller.captureDisconnectEpoch() != disconnectEpoch) {
            finishStart(ticket)
            latestRequestNeedsIdleStop = true
            stopIfIdle(owner, startId)
            return
        }
        if (host == null) {
            finishStart(ticket)
            startFailure = SessionServiceStartFailure.HOST_SELECTION_FAILED
            latestRequestNeedsIdleStop = true
            stopIfIdle(owner, startId)
            return
        }

        val isCurrentRequest = { !destroyed && latestValidStartId == startId }
        val onDecision: (Boolean) -> Unit = { accepted ->
            mainHandler.post {
                finishStart(ticket)
                if (destroyed || latestValidStartId != startId) return@post
                latestRequestNeedsIdleStop = !accepted
                if (!accepted) stopIfIdle(owner, startId)
            }
        }
        when (mode) {
            SessionStartMode.EXPLICIT_CONNECT -> owner.controller.connectIfNotDisconnectedSince(
                host, disconnectEpoch, isCurrentRequest, onDecision,
            )
            SessionStartMode.FOREGROUND_AUTO_CONNECT -> owner.controller.connectAutomaticallyIfCurrent(
                host, requireNotNull(permit), disconnectEpoch, isCurrentRequest, onDecision,
            )
        }
    }

    private fun stopIfIdle(owner: SessionServiceOwner, startId: Int) {
        val snapshot = owner.controller.current
        if (!latestRequestNeedsIdleStop || startId != latestValidStartId || pendingSelections != 0 ||
            snapshot.desiredHost != null ||
            snapshot.connection.isReady) return
        stopForegroundAndSelf(startId)
    }

    private fun stopForegroundAndSelf(startId: Int) {
        if (foregroundPromoted) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundPromoted = false
        }
        stopSelfResult(startId)
    }

    private fun finishStart(ticket: String) {
        activeStartTickets.remove(ticket)
        registry.completeStart(ticket)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        localBinder.closeSubscriptions()
        return false
    }

    override fun onDestroy() {
        destroyed = true
        activeStartTickets.toList().forEach(::finishStart)
        // Stop this service's session work, but retain the application owner/controller and its
        // CLEANUP_UNCONFIRMED barrier for any subsequent service instance.
        registry.retainedOwnerOrNull()?.controller?.disconnect()
        if (foregroundPromoted) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundPromoted = false
        }
        localBinder.closeSubscriptions()
        // Deliberately retain the application owner: service recreation must not bypass its close barrier.
        super.onDestroy()
    }

    class SessionBinder internal constructor(private val service: SessionForegroundService) : Binder() {
        private val subscriptions = LinkedHashSet<AutoCloseable>()

        val snapshot: SessionSnapshot get() = service.registry.owner().controller.current
        val interactions: SessionInteractions get() = service.registry.owner().interactions
        val isForeground: Boolean get() = service.foregroundPromoted
        val failure: SessionServiceStartFailure? get() = service.startFailure

        fun observe(dispatcher: Executor, observer: SessionController.Observer): AutoCloseable {
            val subscription = service.registry.owner().controller.observe(dispatcher, observer)
            synchronized(subscriptions) { subscriptions.add(subscription) }
            return AutoCloseable {
                synchronized(subscriptions) { subscriptions.remove(subscription) }
                subscription.close()
            }
        }

        internal val serviceForTests: SessionForegroundService get() = service
        internal val destroyedForTests: Boolean get() = service.destroyed

        internal fun closeSubscriptions() {
            val old = synchronized(subscriptions) { subscriptions.toList().also { subscriptions.clear() } }
            old.forEach { it.close() }
        }
    }

    companion object {
        const val ACTION_CONNECT = "com.stripe1.xmouse.session.CONNECT"
        const val ACTION_AUTO_CONNECT = "com.stripe1.xmouse.session.AUTO_CONNECT"
        const val ACTION_NOTIFICATION_DISCONNECT = "com.stripe1.xmouse.session.NOTIFICATION_DISCONNECT"
        internal const val EXTRA_START_TICKET = "com.stripe1.xmouse.session.extra.START_TICKET"
        const val NOTIFICATION_CHANNEL_ID = "xmouse_connected_session"
        const val NOTIFICATION_ID = 5202
        private const val REQUEST_OPEN_CONTROLS = 5203
        private const val REQUEST_DISCONNECT = 5204
        private val createdServiceInstances = AtomicInteger()

        internal val serviceInstancesCreatedForTests: Int get() = createdServiceInstances.get()

        /** True while a service-start ticket/metadata request or retained active session bars legacy SSH. */
        @JvmStatic
        fun blocksLegacyConnect(context: Context): Boolean {
            val registry = (context.applicationContext as? XMouseApplication)?.sessionOwners ?: return false
            if (registry.hasPendingStart()) return true
            val snapshot = registry.retainedOwnerOrNull()?.controller?.current ?: return false
            return snapshot.desiredHost != null || snapshot.connection.isReady || snapshot.cleanupUnconfirmed
        }

        /** Explicit Disconnect is a persistent opt-out from saved legacy auto-connect, not from Start. */
        @JvmStatic
        fun suppressesLegacyAutoConnect(context: Context): Boolean {
            if (blocksLegacyConnect(context)) return true
            val registry = (context.applicationContext as? XMouseApplication)?.sessionOwners ?: return false
            return registry.retainedOwnerOrNull()?.controller?.current?.automaticConnectSuppressed == true
        }

        /** A pending permission reservation or accepted service metadata lookup is visible to session UI. */
        @JvmStatic
        fun hasPendingStart(context: Context): Boolean =
            (context.applicationContext as? XMouseApplication)?.sessionOwners?.hasPendingStart() == true

        /** Per-ticket permission callback guard; broad pending state must not authorize stale results. */
        @JvmStatic
        fun hasReservedStart(context: Context, ticket: String?): Boolean =
            (context.applicationContext as? XMouseApplication)?.sessionOwners?.hasReservedStart(ticket) == true

        /** A non-creating read used only to avoid the legacy Activity auto-connect shadowing this owner. */
        @JvmStatic
        fun hasRetainedSession(context: Context): Boolean {
            val application = context.applicationContext as? XMouseApplication ?: return false
            val snapshot = application.sessionOwners.retainedOwnerOrNull()?.controller?.current ?: return false
            return snapshot.desiredHost != null || snapshot.connection.isReady || snapshot.cleanupUnconfirmed
        }

        /** Visible in-app Stop and notification Stop share the same non-creating service action. */
        @JvmStatic
        fun requestDisconnect(context: Context): Boolean = try {
            context.startService(Intent(context, SessionForegroundService::class.java)
                .setAction(ACTION_NOTIFICATION_DISCONNECT))
            true
        } catch (_: RuntimeException) {
            false
        }

        @JvmStatic
        fun areSessionNotificationsVisible(context: Context): Boolean {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
            val channel = context.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(NOTIFICATION_CHANNEL_ID)
            if (channel?.importance == NotificationManager.IMPORTANCE_NONE) return false
            return true
        }

        /**
         * The sole production start entry point. Call only from a visible Activity; no host, credential,
         * or other connection data is placed in the Intent. ACCEPTED is not an SSH-success result.
         */
        @JvmStatic
        @JvmOverloads
        fun requestConnect(activity: Activity, automatic: Boolean = false): ForegroundConnectRequestResult {
            val ticket = reserveConnectRequest(activity, automatic)
                ?: return if (!isVisible(activity)) ForegroundConnectRequestResult.ACTIVITY_NOT_VISIBLE
                else ForegroundConnectRequestResult.SERVICE_START_REJECTED
            return startReservedConnect(activity, ticket)
        }

        /** Reserves coexistence ownership while a user-initiated permission flow is on screen. */
        @JvmStatic
        @JvmOverloads
        fun reserveConnectRequest(activity: Activity, automatic: Boolean = false): String? {
            if (!isVisible(activity)) return null
            val application = activity.application as? XMouseApplication ?: return null
            val mode = if (automatic) SessionStartMode.FOREGROUND_AUTO_CONNECT else SessionStartMode.EXPLICIT_CONNECT
            return application.sessionOwners.issueStart(mode)
        }

        /** Dispatches a previously reserved one-use start after permission/visibility checks complete. */
        @JvmStatic
        fun startReservedConnect(activity: Activity, ticket: String): ForegroundConnectRequestResult {
            if (!isVisible(activity)) {
                (activity.application as? XMouseApplication)?.sessionOwners?.cancelStart(ticket)
                return ForegroundConnectRequestResult.ACTIVITY_NOT_VISIBLE
            }
            val application = activity.application as? XMouseApplication
                ?: return ForegroundConnectRequestResult.SERVICE_START_REJECTED
            val mode = application.sessionOwners.dispatchStart(ticket)
                ?: return ForegroundConnectRequestResult.SERVICE_START_REJECTED
            val intent = Intent(activity, SessionForegroundService::class.java)
                .setAction(mode.action)
                .putExtra(EXTRA_START_TICKET, ticket)
            return try {
                ContextCompat.startForegroundService(activity, intent)
                ForegroundConnectRequestResult.ACCEPTED
            } catch (_: RuntimeException) {
                application.sessionOwners.cancelStart(ticket)
                ForegroundConnectRequestResult.SERVICE_START_REJECTED
            }
        }

        @JvmStatic
        fun cancelReservedConnect(context: Context, ticket: String) {
            (context.applicationContext as? XMouseApplication)?.sessionOwners?.cancelStart(ticket)
        }

        private fun isVisible(activity: Activity): Boolean {
            if (activity.isFinishing || activity.isDestroyed) return false
            val lifecycle = (activity as? LifecycleOwner)?.lifecycle ?: return false
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return false
            val decor = activity.window?.decorView ?: return false
            return decor.visibility == View.VISIBLE && decor.windowVisibility == View.VISIBLE
        }
    }
}
