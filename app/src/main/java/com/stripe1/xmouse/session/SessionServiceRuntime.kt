package com.stripe1.xmouse.session

import java.util.UUID

/** Application-owned interface retained independently of started/bound Service instances. */
interface SessionServiceOwner {
    val controller: SessionController
    val interactions: SessionInteractions
    fun selectedHost(shell: String, initialCommand: String?, completion: (SessionHost?) -> Unit)
}

enum class SessionStartMode(internal val action: String) {
    EXPLICIT_CONNECT(SessionForegroundService.ACTION_CONNECT),
    FOREGROUND_AUTO_CONNECT(SessionForegroundService.ACTION_AUTO_CONNECT),
}

/**
 * Holds exactly one session owner for the application process and issues one-use launch tickets.
 * Tickets are transient capabilities, not saved connection intent or authentication material.
 */
class SessionOwnerRegistry internal constructor(private val ownerFactory: () -> SessionServiceOwner) {
    private val lock = Any()
    @Volatile private var retainedOwner: SessionServiceOwner? = null
    private val pendingStarts = LinkedHashMap<String, SessionStartMode>()

    internal fun owner(): SessionServiceOwner = retainedOwner ?: synchronized(lock) {
        retainedOwner ?: ownerFactory().also { retainedOwner = it }
    }

    /** Does not initialize an owner; bind-only service destruction must not start session I/O. */
    internal fun retainedOwnerOrNull(): SessionServiceOwner? = retainedOwner

    internal fun issueStart(mode: SessionStartMode): String? = synchronized(lock) {
        if (pendingStarts.size >= MAX_PENDING_STARTS) return@synchronized null
        val ticket = UUID.randomUUID().toString()
        pendingStarts[ticket] = mode
        ticket
    }

    internal fun consumeStart(action: String?, ticket: String?): SessionStartMode? = synchronized(lock) {
        if (ticket.isNullOrBlank()) return@synchronized null
        val mode = pendingStarts.remove(ticket) ?: return@synchronized null
        mode.takeIf { it.action == action }
    }

    internal fun cancelStart(ticket: String) = synchronized(lock) { pendingStarts.remove(ticket) }

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
