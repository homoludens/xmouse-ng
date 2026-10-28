package net.droopia.xmouse_ng.session

import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** FIFO, non-inline, single-owner event queue. Tests explicitly choose event interleavings. */
interface SessionEventQueue : AutoCloseable {
    fun post(action: Runnable): Boolean
}

class SerialSessionEventQueue : SessionEventQueue {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "xmouse-session-owner") }
    override fun post(action: Runnable): Boolean = try { worker.execute(action); true }
        catch (_: RejectedExecutionException) { false }
    override fun close() { worker.shutdown() }
}
