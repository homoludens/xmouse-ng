package net.droopia.xmouse_ng.session

import java.util.HashSet
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Application-owned interface retained independently of started/bound Service instances. */
interface SessionServiceOwner {
    val controller: SessionController
    val interactions: SessionInteractions
    fun selectedHost(shell: String, initialCommand: String?, completion: (SessionHost?) -> Unit)
}

enum class SessionStartMode(internal val action: String) {
    EXPLICIT_CONNECT(SessionForegroundService.ACTION_CONNECT),
    FOREGROUND_AUTO_CONNECT(SessionForegroundService.ACTION_AUTO_CONNECT),
    FOREGROUND_RECOVERY(SessionForegroundService.ACTION_RECOVER),
}

internal data class SessionStartRequest(val mode: SessionStartMode,
    val recovery: SessionController.RecoveryPermit? = null,
    val foreground: ForegroundRecoveryWindow? = null)

/** Revocable foreground capability. The service retains no Activity/View while dispatch is pending. */
class ForegroundRecoveryWindow : AutoCloseable {
    private val active = AtomicBoolean(true)
    val isActive: Boolean get() = active.get()
    override fun close() { active.set(false) }
}

/**
 * Holds exactly one session owner for the application process and issues one-use launch tickets.
 * Tickets are transient capabilities, not saved connection intent or authentication material.
 */
class SessionOwnerRegistry internal constructor(private val ownerFactory: () -> SessionServiceOwner) {
    private val lock = Any()
    @Volatile private var retainedOwner: SessionServiceOwner? = null
    private val pendingStarts = LinkedHashMap<String, SessionStartRequest>()
    private val dispatchedStarts = HashSet<String>()
    private val activeStarts = HashSet<String>()

    internal fun owner(): SessionServiceOwner = retainedOwner ?: synchronized(lock) {
        retainedOwner ?: ownerFactory().also { retainedOwner = it }
    }

    /** Does not initialize an owner; bind-only service destruction must not start session I/O. */
    internal fun retainedOwnerOrNull(): SessionServiceOwner? = retainedOwner

    internal fun issueStart(mode: SessionStartMode, recovery: SessionController.RecoveryPermit? = null,
        foreground: ForegroundRecoveryWindow? = null): String? = synchronized(lock) {
        if (pendingStarts.size + activeStarts.size >= MAX_PENDING_STARTS) return@synchronized null
        val ticket = UUID.randomUUID().toString()
        pendingStarts[ticket] = SessionStartRequest(mode, recovery, foreground)
        ticket
    }

    /** Reserves the single-use ticket for dispatch after any point-of-use permission flow completes. */
    internal fun dispatchStart(ticket: String): SessionStartMode? = synchronized(lock) {
        val mode = pendingStarts[ticket] ?: return@synchronized null
        if (!dispatchedStarts.add(ticket)) return@synchronized null
        mode.mode
    }

    /** True only for a live reservation that has not already been dispatched to the Service. */
    internal fun hasReservedStart(ticket: String?): Boolean = synchronized(lock) {
        ticket != null && pendingStarts.containsKey(ticket) && ticket !in dispatchedStarts
    }

    internal fun consumeStart(action: String?, ticket: String?): SessionStartRequest? = synchronized(lock) {
        if (ticket.isNullOrBlank()) return@synchronized null
        val mode = pendingStarts.remove(ticket) ?: return@synchronized null
        if (!dispatchedStarts.remove(ticket)) return@synchronized null
        if (mode.mode.action != action) return@synchronized null
        activeStarts.add(ticket)
        mode
    }

    internal fun cancelStart(ticket: String) = synchronized(lock) {
        pendingStarts.remove(ticket)
        dispatchedStarts.remove(ticket)
        activeStarts.remove(ticket)
    }

    internal fun completeStart(ticket: String) = synchronized(lock) { activeStarts.remove(ticket) }

    internal fun cancelAllStarts() = synchronized(lock) {
        pendingStarts.clear()
        dispatchedStarts.clear()
        activeStarts.clear()
    }

    /** Includes permission-flow reservations and service requests waiting for selected-host metadata. */
    internal fun hasPendingStart(): Boolean = synchronized(lock) {
        pendingStarts.isNotEmpty() || activeStarts.isNotEmpty()
    }

    /** Test-only cleanup for the idle owner created when an earlier MainActivity bound the service. */
    internal fun clearIdleOwnerForTests() = synchronized(lock) {
        check(!hasPendingStart()) { "Cannot clear a session owner while a start is pending" }
        retainedOwner?.let { previous ->
            val snapshot = previous.controller.current
            check(snapshot.desiredHost == null && snapshot.attemptHost == null &&
                snapshot.connection.phase == net.droopia.xmouse_ng.connection.ConnectionState.Phase.DISCONNECTED &&
                !snapshot.cleanupUnconfirmed) {
                "Cannot clear an active process session owner (phase=${snapshot.connection.phase}, " +
                    "desired=${snapshot.desiredHost != null}, attempt=${snapshot.attemptHost != null}, " +
                    "cleanupUnconfirmed=${snapshot.cleanupUnconfirmed})"
            }
            previous.interactions.invalidate()
            previous.controller.close()
            retainedOwner = null
        }
    }

    /** Test-only owner injection; runtime code never replaces or clears a retained owner. */
    internal fun installOwnerForTests(owner: SessionServiceOwner): AutoCloseable = synchronized(lock) {
        check(retainedOwner == null) { "A process session owner already exists" }
        retainedOwner = owner
        AutoCloseable {
            synchronized(lock) {
                check(retainedOwner === owner) { "Test owner changed unexpectedly" }
                retainedOwner = null
            }
        }
    }

    private companion object { const val MAX_PENDING_STARTS = 16 }
}
