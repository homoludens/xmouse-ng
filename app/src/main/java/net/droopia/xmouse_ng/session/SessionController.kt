package net.droopia.xmouse_ng.session

import net.droopia.xmouse_ng.connection.ConnectionFailure
import net.droopia.xmouse_ng.connection.ConnectionState
import java.lang.ref.WeakReference
import java.util.concurrent.Executor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BooleanSupplier

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
    /** Input commands retain their capability through the transport queue, not just this owner queue. */
    fun executeInputCommand(
        command: String,
        presentation: SessionInteractions.PresentationToken?,
        isCurrent: BooleanSupplier,
        onWriteStarting: Runnable,
    ): Boolean {
        if (!isCurrent.asBoolean) return false
        onWriteStarting.run()
        return executeShellCommand(command, presentation)
    }
    /** Release bypasses the revoked surface but remains scoped to this exact attempt and tracked hold. */
    fun executeInputRelease(
        command: String,
        shouldRelease: BooleanSupplier,
        onWriteStarting: Runnable,
    ): Boolean {
        if (!shouldRelease.asBoolean) return false
        onWriteStarting.run()
        return executeShellCommand(command, null)
    }
    fun executeExecCommand(command: String, presentation: SessionInteractions.PresentationToken): Boolean = false
    /** Clipboard work has its own typed result path; text is never a command-history value. */
    fun canSendClipboard(presentation: SessionInteractions.PresentationToken): Boolean = false
    fun executeClipboard(text: String, presentation: SessionInteractions.PresentationToken): Boolean = false
}

fun interface SessionAttemptFactory {
    fun create(host: SessionHost): SessionAttempt
    fun invalidateReviews() { }
    /** Read-only point-of-use prerequisite; never requests permission or starts network work. */
    fun accessFailure(): ConnectionFailure.Permission? = null
}

/** Immutable, atomically observable intent + observation. No commands, secrets, or UI objects. */
data class SessionSnapshot(
    val desiredHost: SessionHost? = null,
    val attemptHost: SessionHost? = null,
    val generation: Long = 0,
    val intentVersion: Long = 0,
    val connection: ConnectionState = ConnectionState.Disconnected(),
    val automaticConnectSuppressed: Boolean = false,
    val foregroundRecoveryAvailable: Boolean = false,
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
    class RecoveryPermit internal constructor(internal val generation: Long, internal val version: Long,
        internal val disconnectEpoch: Long)
    @Volatile var current = SessionSnapshot()
        private set
    private var generation = 0L
    /** Invalidates service metadata callbacks immediately, before the serialized disconnect event runs. */
    private val disconnectEpoch = AtomicLong()
    /** Bounds admitted input waiting on the serialized owner queue; the transport has its own cap. */
    private val pendingCommandAdmissions = AtomicLong()
    /** Immutable admission token: callers can validate a ready session without reading queue-owned state. */
    private data class CommandOwner(
        val generation: Long,
        val host: SessionHost,
        val disconnectEpoch: Long,
        val attempt: SessionAttempt,
    )
    private val commandOwner = AtomicReference<CommandOwner?>()
    private data class ActiveInputSurface(
        val token: InputSurfaceToken,
        val owner: CommandOwner,
        val presentation: SessionInteractions.PresentationToken?,
        val transportAllowed: AtomicBoolean = AtomicBoolean(true),
        val releaseScopeRequested: AtomicBoolean = AtomicBoolean(),
        val slotReleased: AtomicBoolean = AtomicBoolean(),
    )
    private class InputGesture(
        val token: InputGestureToken,
        val surface: ActiveInputSurface,
        val accepting: AtomicBoolean = AtomicBoolean(true),
        val active: AtomicBoolean = AtomicBoolean(true),
        /** Normal UP stops new admission but deliberately leaves already-admitted transport work valid. */
        val transportAllowed: AtomicBoolean = AtomicBoolean(true),
    )
    private data class HeldButtonKey(
        val surface: InputSurfaceToken,
        val generation: Long,
        val backendButton: String,
    )
    private data class HeldButton(
        val key: HeldButtonKey,
        val gesture: InputGestureToken?,
        val owner: CommandOwner,
        val releaseCommand: String,
        val writes: TrackedButtonWriteState,
    )
    private class PendingInputRelease(val surface: ActiveInputSurface) {
        var wholeSurface = false
        val gestures = LinkedHashMap<InputGestureToken, InputGesture>()
    }
    /** A single active control presentation; its owner includes the exact attempt/host/epoch. */
    private val activeInputSurface = AtomicReference<ActiveInputSurface?>()
    private val inputGestures = ConcurrentHashMap<InputGestureToken, InputGesture>()
    private val inputGestureCount = AtomicInteger()
    private val inputSurfaceCount = AtomicInteger()
    /** Coalesces exact surface/gesture release scopes into bounded controller-queue work. */
    private val inputCleanupScheduled = AtomicBoolean()
    private val inputCleanupLock = Any()
    private val pendingInputReleases = LinkedHashMap<InputSurfaceToken, PendingInputRelease>()
    /** Mutated only on the serialized controller queue. */
    private val heldButtons = LinkedHashMap<HeldButtonKey, HeldButton>()
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

    fun foregroundRecoveryPermit(): RecoveryPermit? {
        val snapshot = current
        if (snapshot.closed || snapshot.automaticConnectSuppressed || !snapshot.foregroundRecoveryAvailable ||
            snapshot.desiredHost == null || snapshot.cleanupUnconfirmed) return null
        return RecoveryPermit(snapshot.generation, snapshot.intentVersion, disconnectEpoch.get())
    }

    /** Only a visible, one-use service start may call this; never called by a loss/health callback. */
    internal fun recoverInForeground(permit: RecoveryPermit, isCurrentRequest: () -> Boolean,
        onDecision: (Boolean) -> Unit = {}) {
        val posted = events.post {
            val host = current.desiredHost
            if (!isCurrentRequest() || host == null || current.closed || current.automaticConnectSuppressed ||
                !current.foregroundRecoveryAvailable || current.cleanupUnconfirmed ||
                current.generation != permit.generation || current.intentVersion != permit.version ||
                disconnectEpoch.get() != permit.disconnectEpoch) {
                onDecision(false)
                return@post
            }
            val denied = factory.accessFailure()
            if (denied != null) {
                // Keep the opportunity for a later foreground return after the user grants access.
                publish(current.copy(connection = ConnectionState.Error(host.endpoint.identity, denied)))
                onDecision(false)
                return@post
            }
            factory.invalidateReviews()
            publish(current.copy(foregroundRecoveryAvailable = false))
            request(host) // Includes the exact old-attempt close barrier and a fresh factory/resolve.
            onDecision(true)
        }
        if (!posted) onDecision(false)
    }

    /** Service loss is not user Disconnect. No attempt is restarted from this callback. */
    fun interrupted() { events.post {
        val old = owned ?: return@post
        if (!matches(old)) return@post
        resumeAfterClose = false
        closeOwned(old, ConnectionState.Error(old.host.endpoint.identity,
            ConnectionFailure.Transport(ConnectionFailure.TransportReason.CONNECTION_LOST)),
            recoveryAvailable = current.connection.isReady)
    } }

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
        publish(current.copy(intentVersion = current.intentVersion + 1, automaticConnectSuppressed = false,
            foregroundRecoveryAvailable = false))
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

    /** Synchronous, read-only preflight used before the Activity is allowed to read ClipboardManager. */
    fun canSendClipboard(presentation: SessionInteractions.PresentationToken?): Boolean {
        if (presentation == null) return false
        val owner = commandOwner.get() ?: return false
        return isCurrentReadyOwner(owner) && owner.attempt.canSendClipboard(presentation)
    }

    /** Clipboard text is captured only by this admitted generation and is never replayed. */
    fun sendClipboard(text: String, presentation: SessionInteractions.PresentationToken?): Boolean {
        if (text.isEmpty() || presentation == null) return false
        val owner = commandOwner.get() ?: return false
        val currentTarget = {
            isCurrentReadyOwner(owner) && owner.attempt.canSendClipboard(presentation)
        }
        if (!currentTarget()) return false
        return postReadyCommand(
            command = "clipboard-send",
            expectedOwner = owner,
            admissionIsCurrent = currentTarget,
            dispatchIsCurrent = currentTarget,
        ) { it.executeClipboard(text, presentation) }
    }

    /**
     * Opens an input-only capability for the exact ready attempt currently attached to the controls.
     * Replacing a surface invalidates its gestures immediately and releases its tracked buttons on
     * this controller's ordered background lane.
     */
    fun activateControlSurface(presentation: SessionInteractions.PresentationToken?): InputSurfaceToken? {
        val owner = commandOwner.get() ?: return null
        if (!isCurrentReadyOwner(owner)) return null
        if (!reserveInputSurfaceSlot()) return null
        val token = InputSurfaceToken()
        val next = ActiveInputSurface(token, owner, presentation)
        val previous = activeInputSurface.getAndSet(next)
        if (previous != null) retireInputSurface(previous, release = true)
        if (activeInputSurface.get() !== next || !isCurrentReadyOwner(owner)) {
            if (activeInputSurface.compareAndSet(next, null)) retireInputSurface(next, release = true)
            else retireInputSurface(next, release = true)
            return null
        }
        return token
    }

    fun isControlSurfaceActive(token: InputSurfaceToken?): Boolean {
        if (token == null) return false
        val surface = activeInputSurface.get() ?: return false
        return surface.token === token && surface.transportAllowed.get() && isCurrentReadyOwner(surface.owner)
    }

    /** Revokes first, then queues best-effort releases without waiting on the caller. */
    fun deactivateControlSurface(token: InputSurfaceToken?) {
        if (token == null) return
        while (true) {
            val surface = activeInputSurface.get() ?: return
            if (surface.token !== token) return
            if (activeInputSurface.compareAndSet(surface, null)) {
                retireInputSurface(surface, release = true)
                return
            }
        }
    }

    fun beginInputGesture(surfaceToken: InputSurfaceToken?): InputGestureToken? {
        if (surfaceToken == null) return null
        val surface = activeInputSurface.get() ?: return null
        if (surface.token !== surfaceToken || !surface.transportAllowed.get() || !isCurrentReadyOwner(surface.owner)) return null
        if (!reserveInputGestureSlot()) return null
        val gesture = InputGestureToken()
        val lease = InputGesture(gesture, surface)
        inputGestures[gesture] = lease
        // A concurrent focus/surface transition must not leave a fresh old-surface lease open.
        if (activeInputSurface.get() !== surface) {
            lease.accepting.set(false)
            lease.active.set(false)
            lease.transportAllowed.set(false)
            removeInputGesture(lease)
            return null
        }
        return gesture
    }

    fun isInputGestureActive(token: InputGestureToken?): Boolean {
        if (token == null) return false
        val gesture = inputGestures[token] ?: return false
        return gesture.active.get() && gesture.accepting.get() && gesture.transportAllowed.get() &&
            gesture.surface.transportAllowed.get() && activeInputSurface.get() === gesture.surface
    }

    /** A normal UP closes admission after already-admitted commands, preserving FIFO ordering. */
    fun finishInputGesture(token: InputGestureToken?) {
        val gesture = token?.let(inputGestures::get) ?: return
        if (!gesture.accepting.compareAndSet(true, false)) return
        // The synchronous admission close rejects new gesture input. One coalesced queue event retires
        // any number of finished leases after input commands already admitted to that FIFO lane.
        scheduleInputCleanup()
    }

    /** ACTION_CANCEL invalidates queued gesture work synchronously and requests best-effort release. */
    fun cancelInputGesture(token: InputGestureToken?) {
        val gesture = token?.let(inputGestures::get) ?: return
        if (!gesture.active.compareAndSet(true, false)) return
        gesture.accepting.set(false)
        gesture.transportAllowed.set(false)
        requestGestureInputRelease(gesture)
        scheduleInputCleanup()
    }

    /** Control shell input is scoped to either the live surface or one DOWN..UP gesture lease. */
    fun executeInputCommand(command: String, token: Any?): Boolean {
        val surface: ActiveInputSurface
        val gesture: InputGesture?
        when (token) {
            is InputSurfaceToken -> {
                surface = activeInputSurface.get()?.takeIf {
                    it.token === token && it.transportAllowed.get()
                } ?: return false
                gesture = null
            }
            is InputGestureToken -> {
                gesture = inputGestures[token]?.takeIf {
                    it.active.get() && it.accepting.get() && it.transportAllowed.get() &&
                        it.surface.transportAllowed.get() && activeInputSurface.get() === it.surface
                } ?: return false
                surface = gesture.surface
            }
            else -> return false
        }
        val parsedHold = TrackedInputCommand.parse(command)
        val admissionIsCurrent = {
            activeInputSurface.get() === surface &&
                (gesture == null || (inputGestures[gesture.token] === gesture &&
                    gesture.active.get() && gesture.accepting.get()))
        }
        val dispatchIsCurrent = {
            activeInputSurface.get() === surface &&
                (gesture == null || (inputGestures[gesture.token] === gesture && gesture.active.get()))
        }
        val transportIsCurrent = BooleanSupplier { isTransportInputCurrent(surface, gesture) }
        return postReadyCommand(command, surface.owner,
            admissionIsCurrent = admissionIsCurrent,
            dispatchIsCurrent = dispatchIsCurrent) { attempt ->
            val key = parsedHold?.let {
                HeldButtonKey(surface.token, surface.owner.generation, it.backendButton)
            }
            val previous = key?.let(heldButtons::get)
            val tracked = if (parsedHold?.down == true && key != null) {
                HeldButton(key, gesture?.token, surface.owner, parsedHold.releaseCommand,
                    previous?.writes ?: TrackedButtonWriteState())
            } else previous
            val writeStarting = Runnable {
                when {
                    parsedHold?.down == true -> tracked?.writes?.markDownWriteStarting()
                    parsedHold != null -> tracked?.writes?.markReleaseWriteStarting()
                }
                if (parsedHold != null && !parsedHold.down && key != null && tracked != null) {
                    events.post { heldButtons.remove(key, tracked) }
                }
            }
            val accepted = attempt.executeInputCommand(command, surface.presentation, transportIsCurrent, writeStarting)
            if (accepted && parsedHold?.down == true && key != null && tracked != null) {
                heldButtons[key] = tracked
            }
            accepted
        }
    }

    private fun isTransportInputCurrent(surface: ActiveInputSurface, gesture: InputGesture?): Boolean =
        surface.transportAllowed.get() && activeInputSurface.get() === surface &&
            isCurrentReadyOwner(surface.owner) &&
            // A normal UP closes further admission and retires the local gesture record, but it
            // must not invalidate commands that already crossed this controller's FIFO queue.
            // Cancellation and surface retirement revoke transportAllowed synchronously.
            (gesture == null || gesture.transportAllowed.get())

    private fun isCurrentReadyOwner(owner: CommandOwner): Boolean {
        val snapshot = current
        return commandOwner.get() === owner && disconnectEpoch.get() == owner.disconnectEpoch &&
            !snapshot.closed && snapshot.connection.isReady && snapshot.generation == owner.generation &&
            snapshot.attemptHost === owner.host && snapshot.desiredHost?.sameRequest(owner.host) == true
    }

    private fun retireInputSurface(surface: ActiveInputSurface, release: Boolean) {
        surface.transportAllowed.set(false)
        inputGestures.values.filter { it.surface === surface }.forEach { gesture ->
            gesture.accepting.set(false)
            gesture.active.set(false)
            gesture.transportAllowed.set(false)
        }
        if (release) {
            if (surface.releaseScopeRequested.compareAndSet(false, true)) requestSurfaceInputRelease(surface)
            scheduleInputCleanup()
        } else releaseInputSurfaceSlot(surface)
    }

    private fun requestSurfaceInputRelease(surface: ActiveInputSurface) {
        synchronized(inputCleanupLock) {
            val pending = pendingInputReleases.getOrPut(surface.token) { PendingInputRelease(surface) }
            pending.wholeSurface = true
            pending.gestures.clear()
        }
    }

    private fun requestGestureInputRelease(gesture: InputGesture) {
        synchronized(inputCleanupLock) {
            val pending = pendingInputReleases.getOrPut(gesture.surface.token) {
                PendingInputRelease(gesture.surface)
            }
            if (!pending.wholeSurface) pending.gestures[gesture.token] = gesture
        }
    }

    /** At most one cleanup event is pending; repeated finishes/releases are folded into its state. */
    private fun scheduleInputCleanup() {
        if (!inputCleanupScheduled.compareAndSet(false, true)) return
        // Only leases finished before this event was enqueued may be retired by it. A gesture that
        // finishes while the event is pending gets one coalesced follow-up behind its own FIFO work.
        val finishedGestures = inputGestures.values.filter { !it.active.get() || !it.accepting.get() }
        val posted = events.post {
            try {
                finishedGestures.forEach { gesture ->
                    if (inputGestures[gesture.token] === gesture &&
                        (!gesture.active.get() || !gesture.accepting.get())) {
                        gesture.active.set(false)
                        removeInputGesture(gesture)
                    }
                }
                val releases = synchronized(inputCleanupLock) {
                    pendingInputReleases.values.map { pending ->
                        Triple(pending.surface, pending.wholeSurface, pending.gestures.values.toList())
                    }.also { pendingInputReleases.clear() }
                }
                releases.forEach { (surface, wholeSurface, gestures) ->
                    if (wholeSurface) {
                        releaseHeldInputForSurface(surface)
                        inputGestures.values.filter { it.surface === surface }.forEach(::removeInputGesture)
                        releaseInputSurfaceSlot(surface)
                    } else {
                        gestures.forEach { gesture ->
                            releaseHeldInputForGesture(surface, gesture)
                            removeInputGesture(gesture)
                        }
                    }
                }
            } finally {
                inputCleanupScheduled.set(false)
                if (hasPendingInputReleases() || hasFinishedInputGestures()) scheduleInputCleanup()
            }
        }
        if (!posted) {
            inputCleanupScheduled.set(false)
            retireFinishedInputGestures()
            val abandoned = synchronized(inputCleanupLock) {
                pendingInputReleases.values.map { it.surface }.also { pendingInputReleases.clear() }
            }
            abandoned.forEach(::releaseInputSurfaceSlot)
        }
    }

    private fun retireFinishedInputGestures() {
        inputGestures.values.filter { !it.active.get() || !it.accepting.get() }.forEach(::removeInputGesture)
    }

    private fun hasFinishedInputGestures(): Boolean =
        inputGestures.values.any { !it.active.get() || !it.accepting.get() }

    private fun hasPendingInputReleases(): Boolean = synchronized(inputCleanupLock) {
        pendingInputReleases.isNotEmpty()
    }

    private fun removeInputGesture(gesture: InputGesture) {
        if (inputGestures.remove(gesture.token, gesture)) inputGestureCount.decrementAndGet()
    }

    private fun reserveInputSurfaceSlot(): Boolean {
        while (true) {
            val current = inputSurfaceCount.get()
            if (current >= MAX_INPUT_SURFACES) return false
            if (inputSurfaceCount.compareAndSet(current, current + 1)) return true
        }
    }

    private fun releaseInputSurfaceSlot(surface: ActiveInputSurface) {
        if (surface.slotReleased.compareAndSet(false, true)) inputSurfaceCount.decrementAndGet()
    }

    private fun reserveInputGestureSlot(): Boolean {
        while (true) {
            val current = inputGestureCount.get()
            if (current >= MAX_ACTIVE_INPUT_GESTURES) return false
            if (inputGestureCount.compareAndSet(current, current + 1)) return true
        }
    }

    private fun releaseHeldInputForSurface(surface: ActiveInputSurface) {
        releaseHeldButtons(heldButtons.values.filter { it.key.surface === surface.token }, surface.owner)
    }

    private fun releaseHeldInputForGesture(surface: ActiveInputSurface, gesture: InputGesture) {
        releaseHeldButtons(heldButtons.values.filter {
            it.key.surface === surface.token && it.gesture === gesture.token
        }, surface.owner)
    }

    /** Exact scope only; conditional transport release skips a DOWN that never reached provider write. */
    private fun releaseHeldButtons(matches: List<HeldButton>, owner: CommandOwner) {
        matches.forEach { heldButtons.remove(it.key, it) }
        val active = owned
        if (active == null || active.closing || !current.connection.isReady ||
            active.generation != owner.generation || active.host !== owner.host) return
        matches.forEach { held ->
            active.attempt.executeInputRelease(
                held.releaseCommand,
                BooleanSupplier { held.writes.needsRelease() },
                Runnable { held.writes.markReleaseWriteStarting() },
            )
        }
    }

    private fun postReadyCommand(
        command: String,
        expectedOwner: CommandOwner? = null,
        admissionIsCurrent: () -> Boolean = { true },
        dispatchIsCurrent: () -> Boolean = admissionIsCurrent,
        execute: (SessionAttempt) -> Boolean,
    ): Boolean {
        if (command.isEmpty()) return false
        val owner = expectedOwner ?: commandOwner.get() ?: return false
        val admitted = current
        if (commandOwner.get() !== owner || !admissionIsCurrent() || disconnectEpoch.get() != owner.disconnectEpoch || admitted.closed || !admitted.connection.isReady ||
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
                if (commandOwner.get() !== owner || !dispatchIsCurrent() || disconnectEpoch.get() != owner.disconnectEpoch || snapshot.closed ||
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
            automaticConnectSuppressed = true, foregroundRecoveryAvailable = false, closed = terminal))
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
        commandOwner.set(CommandOwner(entry.generation, entry.host, disconnectEpoch.get(), entry.attempt))
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
                // A failed recovery never rearms itself; only a newly interrupted ready session does.
                closeOwned(entry, ConnectionState.Error(host.endpoint.identity, failure),
                    recoveryAvailable = current.connection.isReady &&
                        (failure is ConnectionFailure.Transport || failure is ConnectionFailure.Permission))
            } }
        }
        try { entry.attempt.start(listener) } catch (_: Exception) {
            closeOwned(entry, ConnectionState.Error(host.endpoint.identity,
                ConnectionFailure.Transport(ConnectionFailure.TransportReason.PROTOCOL_ERROR)))
        }
    }
    private fun matches(entry: Owned) = owned === entry && entry.generation == current.generation &&
        !entry.closing && !current.closed

    private fun closeOwned(entry: Owned, observation: ConnectionState,
        recoveryAvailable: Boolean = current.foregroundRecoveryAvailable) {
        if (entry.closing) return
        val inputSurface = activeInputSurface.get()?.takeIf { it.owner.generation == entry.generation && it.owner.host === entry.host }
        if (inputSurface != null && activeInputSurface.compareAndSet(inputSurface, null)) {
            retireInputSurface(inputSurface, release = false)
        }
        // On host change, explicit disconnect, or detected loss, clear local holds before the attempt
        // retires. Releases are best effort and remain tied to this exact old attempt/backend code.
        releaseHeldInputForOwner(entry)
        commandOwner.get()?.takeIf { it.generation == entry.generation && it.host === entry.host }?.let {
            commandOwner.compareAndSet(it, null)
        }
        entry.closing = true // Invalidate every queued auth/trust/ready/loss event BEFORE cancellation.
        publish(current.copy(connection = observation, foregroundRecoveryAvailable = recoveryAvailable))
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

    private fun releaseHeldInputForOwner(entry: Owned) {
        val matches = heldButtons.values.filter { it.owner.generation == entry.generation && it.owner.host === entry.host }
        releaseHeldButtons(matches,
            CommandOwner(entry.generation, entry.host, disconnectEpoch.get(), entry.attempt))
        inputGestures.values.filter { it.surface.owner.generation == entry.generation && it.surface.owner.host === entry.host }
            .forEach { gesture ->
                gesture.accepting.set(false)
                gesture.active.set(false)
                gesture.transportAllowed.set(false)
                removeInputGesture(gesture)
            }
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

    private companion object {
        const val MAX_PENDING_COMMAND_ADMISSIONS = 128
        const val MAX_ACTIVE_INPUT_GESTURES = 64
        const val MAX_INPUT_SURFACES = 64
    }
}
