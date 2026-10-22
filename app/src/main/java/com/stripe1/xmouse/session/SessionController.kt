package com.stripe1.xmouse.session

import com.stripe1.xmouse.connection.ConnectionFailure
import com.stripe1.xmouse.connection.ConnectionState
import java.lang.ref.WeakReference
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** One attempt owns credentials, prompts and transport, including every late completion's cleanup. */
interface SessionAttempt {
    interface Listener {
        fun phase(phase: ConnectionState.Phase)
        fun ready()
        fun failed(failure: ConnectionFailure)
    }
    fun start(listener: Listener)
    /** Non-blocking invalidation. Completion is exactly once per call, bounded, and conservative. */
    fun cancelAndClose(completion: (ConnectionFailure?) -> Unit)
    /** Compatibility handoff; the service-owned attempt retains transport ordering/cancellation. */
    fun executeShellCommand(command: String): Boolean = false
    /** Optional opaque target captured by the input proxy at submission; never a UI reference. */
    fun executeShellCommand(command: String, presentation: SessionInteractions.PresentationToken?): Boolean =
        executeShellCommand(command)
    fun executeExecCommand(command: String, presentation: SessionInteractions.PresentationToken): Boolean = false
}

fun interface SessionAttemptFactory {
    fun create(host: SessionHost): SessionAttempt
    fun invalidateReviews() { }
}

/** Immutable, atomically observable intent + observation. No commands, secrets, or UI objects. */
data class SessionSnapshot(
    val desiredHost: SessionHost? = null,
    val attemptHost: SessionHost? = null,
    val generation: Long = 0,
    val intentVersion: Long = 0,
    val connection: ConnectionState = ConnectionState.Disconnected(),
    val automaticConnectSuppressed: Boolean = false,
    val cleanupUnconfirmed: Boolean = false,
    val closed: Boolean = false,
)

/** Service-ready owner. ALL mutations occur on events; observers and callers never perform I/O. */
class SessionController @JvmOverloads constructor(
    private val factory: SessionAttemptFactory,
    private val events: SessionEventQueue = SerialSessionEventQueue(),
) : AutoCloseable {
    fun interface Observer { fun changed(snapshot: SessionSnapshot) }
    class AutoConnectPermit internal constructor(internal val version: Long)
    @Volatile var current = SessionSnapshot()
        private set
    private var generation = 0L
    /** Invalidates service metadata callbacks immediately, before the serialized disconnect event runs. */
    private val disconnectEpoch = AtomicLong()
    /** Bounds admitted input waiting on the serialized owner queue; the transport has its own cap. */
    private val pendingCommandAdmissions = AtomicLong()
    /** Immutable admission token: callers can validate a ready session without reading queue-owned state. */
    private data class CommandOwner(val generation: Long, val host: SessionHost, val disconnectEpoch: Long)
    private val commandOwner = AtomicReference<CommandOwner?>()
    private var owned: Owned? = null
    private var resumeAfterClose = false
    private val observers = mutableListOf<Observation>()

    private class Owned(val generation: Long, val host: SessionHost, val attempt: SessionAttempt) {
        var closing = false
        var closeCompleted = false
    }

    /** Capture BEFORE asynchronous preference/credential preparation, not when its result returns. */
    fun automaticConnectPermit() = AutoConnectPermit(current.intentVersion)
    fun connectAutomatically(host: SessionHost, permit: AutoConnectPermit) {
        events.post { connectAutomaticallyOnQueue(host, permit) }
    }
    internal fun connectAutomaticallyIfCurrent(
        host: SessionHost,
        permit: AutoConnectPermit,
        expectedDisconnectEpoch: Long,
        isCurrentRequest: () -> Boolean,
        onDecision: (Boolean) -> Unit,
    ) {
        val posted = events.post {
            val accepted = disconnectEpoch.get() == expectedDisconnectEpoch && isCurrentRequest() &&
                connectAutomaticallyOnQueue(host, permit)
            onDecision(accepted)
        }
        if (!posted) onDecision(false)
    }

    private fun connectAutomaticallyOnQueue(host: SessionHost, permit: AutoConnectPermit): Boolean {
        val old = owned
        if (current.closed || current.automaticConnectSuppressed || permit.version != current.intentVersion ||
            (current.desiredHost != null && (old == null || (old.closing && !resumeAfterClose) ||
                !current.desiredHost!!.sameRequest(host)))) return false
        if (current.desiredHost?.sameRequest(host) == true && old != null && (!old.closing || resumeAfterClose)) return true
        factory.invalidateReviews()
        request(host)
        return true
    }

    fun connectIfNeeded(host: SessionHost) { events.post { connectExplicitly(host) } }

    /** Service Connects are accepted only if no explicit disconnect intervened during metadata lookup. */
    internal fun captureDisconnectEpoch(): Long = disconnectEpoch.get()
    internal fun connectIfNotDisconnectedSince(
        host: SessionHost,
        expectedEpoch: Long,
        isCurrentRequest: () -> Boolean = { true },
        onDecision: (Boolean) -> Unit = {},
    ) {
        val posted = events.post {
            val accepted = disconnectEpoch.get() == expectedEpoch && isCurrentRequest() && connectExplicitly(host)
            onDecision(accepted)
        }
        if (!posted) onDecision(false)
    }

    private fun connectExplicitly(host: SessionHost): Boolean {
        if (current.closed) return false
        val old = owned
        if (current.desiredHost?.sameRequest(host) == true && old != null && (!old.closing || resumeAfterClose) &&
            !current.automaticConnectSuppressed) return true
        factory.invalidateReviews()
        publish(current.copy(intentVersion = current.intentVersion + 1, automaticConnectSuppressed = false))
        request(host)
        return true
    }

    private fun request(host: SessionHost) {
        val old = owned
        if (current.desiredHost?.sameRequest(host) == true && old != null && (!old.closing || resumeAfterClose)) return
        publish(current.copy(desiredHost = host))
        if (current.cleanupUnconfirmed) return // A timeout is NOT permission to overlap the old provider.
        if (old == null) begin(host) else {
            resumeAfterClose = true
            closeOwned(old, ConnectionState.Closing(old.host.endpoint.identity))
        }
    }

    fun disconnect() {
        disconnectEpoch.incrementAndGet()
        factory.invalidateReviews()
        events.post { stop(false) }
    }
    override fun close() {
        disconnectEpoch.incrementAndGet()
        factory.invalidateReviews()
        events.post { stop(true) }
    }

    /** Queue acceptance only; a command is never transferred to a replacement/recovered attempt. */
    fun executeShellCommand(command: String): Boolean = executeShellCommand(command, null)

    /** Screen target is captured before owner-queue admission and is never rebound during dispatch. */
    fun executeShellCommand(command: String, presentation: SessionInteractions.PresentationToken?): Boolean =
        postReadyCommand(command) { it.executeShellCommand(command, presentation) }

    /** Script results are bound to the screen that admitted the exec, not whichever screen is attached later. */
    fun executeExecCommand(command: String, presentation: SessionInteractions.PresentationToken): Boolean =
        postReadyCommand(command) { it.executeExecCommand(command, presentation) }

    private fun postReadyCommand(command: String, execute: (SessionAttempt) -> Boolean): Boolean {
        if (command.isEmpty()) return false
        val owner = commandOwner.get() ?: return false
        val admitted = current
        if (disconnectEpoch.get() != owner.disconnectEpoch || admitted.closed || !admitted.connection.isReady ||
            admitted.generation != owner.generation || admitted.attemptHost !== owner.host ||
            admitted.desiredHost?.sameRequest(owner.host) != true) return false
        while (true) {
            val pending = pendingCommandAdmissions.get()
            if (pending >= MAX_PENDING_COMMAND_ADMISSIONS) return false
            if (pendingCommandAdmissions.compareAndSet(pending, pending + 1)) break
        }
        val posted = events.post {
            try {
                val snapshot = current
                val active = owned
                if (commandOwner.get() !== owner || disconnectEpoch.get() != owner.disconnectEpoch || snapshot.closed ||
                    !snapshot.connection.isReady || snapshot.generation != owner.generation ||
                    snapshot.attemptHost !== owner.host || snapshot.desiredHost?.sameRequest(owner.host) != true ||
                    active == null || active.generation != owner.generation || active.host !== owner.host || active.closing) {
                    return@post
                }
                execute(active.attempt)
            } finally {
                pendingCommandAdmissions.decrementAndGet()
            }
        }
        if (!posted) pendingCommandAdmissions.decrementAndGet()
        return posted
    }
    private fun stop(terminal: Boolean) {
        if (current.closed) return
        if (!terminal && current.desiredHost == null && current.automaticConnectSuppressed) return
        resumeAfterClose = false
        publish(current.copy(desiredHost = null, intentVersion = current.intentVersion + 1,
            automaticConnectSuppressed = true, closed = terminal))
        val old = owned
        if (old == null) {
            publish(current.copy(connection = ConnectionState.Disconnected()))
            if (terminal) events.close()
        } else if (current.cleanupUnconfirmed) {
            if (terminal) events.close() // Already invalidated; quarantined resources keep closing.
        } else closeOwned(old, ConnectionState.Closing(old.host.endpoint.identity))
    }

    private fun begin(host: SessionHost) {
        val token = ++generation
        publish(current.copy(attemptHost = host, generation = token,
            connection = ConnectionState.PreparingCredentials(host.endpoint.identity)))
        val entry = try { Owned(token, host, factory.create(host)) } catch (_: Exception) {
            publish(current.copy(attemptHost = null, connection = ConnectionState.Error(host.endpoint.identity,
                ConnectionFailure.Transport(ConnectionFailure.TransportReason.CAPACITY))))
            return
        }
        owned = entry
        commandOwner.set(CommandOwner(entry.generation, entry.host, disconnectEpoch.get()))
        val listener = object : SessionAttempt.Listener {
            override fun phase(phase: ConnectionState.Phase) { events.post {
                if (!matches(entry)) return@post
                val endpoint = host.endpoint.identity
                val observed = when (phase) {
                    ConnectionState.Phase.PREPARING_CREDENTIALS -> ConnectionState.PreparingCredentials(endpoint)
                    ConnectionState.Phase.RESOLVING -> ConnectionState.Resolving(endpoint)
                    ConnectionState.Phase.CONNECTING -> ConnectionState.Connecting(endpoint)
                    ConnectionState.Phase.AWAITING_TRUST -> ConnectionState.AwaitingTrust(endpoint)
                    ConnectionState.Phase.AUTHENTICATING -> ConnectionState.Authenticating(endpoint)
                    else -> return@post
                }
                if (!current.connection.isReady) publish(current.copy(connection = observed))
            } }
            override fun ready() { events.post {
                if (matches(entry) && !current.connection.isReady) {
                    publish(current.copy(connection = ConnectionState.Connected(host.endpoint.identity)))
                }
            } }
            override fun failed(failure: ConnectionFailure) { events.post {
                if (!matches(entry)) return@post
                resumeAfterClose = false // Retain desired host, but never run a recovery loop here.
                closeOwned(entry, ConnectionState.Error(host.endpoint.identity, failure))
            } }
        }
        try { entry.attempt.start(listener) } catch (_: Exception) {
            closeOwned(entry, ConnectionState.Error(host.endpoint.identity,
                ConnectionFailure.Transport(ConnectionFailure.TransportReason.PROTOCOL_ERROR)))
        }
    }
    private fun matches(entry: Owned) = owned === entry && entry.generation == current.generation &&
        !entry.closing && !current.closed

    private fun closeOwned(entry: Owned, observation: ConnectionState) {
        if (entry.closing) return
        commandOwner.get()?.takeIf { it.generation == entry.generation && it.host === entry.host }?.let {
            commandOwner.compareAndSet(it, null)
        }
        entry.closing = true // Invalidate every queued auth/trust/ready/loss event BEFORE cancellation.
        publish(current.copy(connection = observation))
        entry.attempt.cancelAndClose { failure -> events.post {
            if (owned !== entry || entry.closeCompleted) return@post
            entry.closeCompleted = true
            if (failure != null) {
                resumeAfterClose = false
                publish(current.copy(cleanupUnconfirmed = true, connection = ConnectionState.Error(
                    entry.host.endpoint.identity, ConnectionFailure.Transport(ConnectionFailure.TransportReason.CLEANUP_UNCONFIRMED))))
            } else {
                owned = null
                val next = current.desiredHost
                if (resumeAfterClose && next != null && !current.closed) {
                    resumeAfterClose = false
                    begin(next)
                } else publish(current.copy(attemptHost = null, connection = if (observation is ConnectionState.Error && current.desiredHost != null)
                    observation else ConnectionState.Disconnected(entry.host.endpoint.identity)))
            }
            if (current.closed) events.close()
        } }
    }

    /** Closing an observation only detaches presentation. Pending dispatch is invalidated too. */
    fun observe(dispatcher: Executor, observer: Observer): AutoCloseable {
        val observation = Observation(dispatcher, observer)
        events.post {
            observers.removeAll { !it.active.get() || it.listener.get() == null }
            observers.add(observation); observation.offer(current)
        }
        return observation
    }
    private fun publish(snapshot: SessionSnapshot) {
        if (current == snapshot) return
        current = snapshot
        observers.removeAll { !it.active.get() || it.listener.get() == null }
        observers.forEach { it.offer(snapshot) }
    }
    private class Observation(val dispatcher: Executor, observer: Observer) : AutoCloseable {
        val active = AtomicBoolean(true)
        val listener = WeakReference(observer)
        private val latest = AtomicReference<SessionSnapshot?>()
        private val posted = AtomicBoolean()
        fun offer(snapshot: SessionSnapshot) {
            if (!active.get()) return
            latest.set(snapshot)
            dispatch()
        }
        private fun dispatch() {
            if (!posted.compareAndSet(false, true)) return
            try { dispatcher.execute {
                val snapshot = latest.getAndSet(null)
                try { if (active.get() && snapshot != null) listener.get()?.changed(snapshot) }
                finally {
                    posted.set(false)
                    if (active.get() && latest.get() != null) dispatch()
                }
            } } catch (_: RuntimeException) { close(); posted.set(false) }
        }
        override fun close() { active.set(false); listener.clear(); latest.set(null) }
    }

    private companion object { const val MAX_PENDING_COMMAND_ADMISSIONS = 128 }
}
