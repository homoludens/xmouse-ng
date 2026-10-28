package net.droopia.xmouse_ng.session

import net.droopia.xmouse_ng.connection.ConnectionFailure
import net.droopia.xmouse_ng.connection.ConnectionState
import java.lang.ref.WeakReference
import java.util.concurrent.Executor

/** One form's attempt-only owner. The dispatcher MUST enqueue onto the form's serial UI lane.
 * No command API, controller/service, persistence, or strong UI reference is exposed here.
 * A result (including success) is published only after the physical close barrier. */
class ConnectionProbe(
    private val factory: SessionAttemptFactory,
    private val dispatcher: Executor,
    private val retireCredentials: () -> Unit = {},
) {
    interface Listener {
        fun phase(phase: ConnectionState.Phase)
        fun finished(failure: ConnectionFailure?, cancelled: Boolean)
    }
    private var observer = WeakReference<Listener>(null)
    private var attempt: SessionAttempt? = null
    private var generation = 0L
    private var closing = false
    private var cancelledWhileClosing = false
    var blocked = false
        private set
    val busy get() = attempt != null

    fun attach(listener: Listener) { observer = WeakReference(listener) }
    fun detach() { observer.clear(); cancel() }

    fun start(candidate: SessionHost): Boolean {
        if (busy || blocked) return false
        // The normal session's shell/initial command preferences are deliberately not accepted.
        require(candidate.shell.isEmpty() && candidate.initialCommand == null)
        factory.invalidateReviews()
        val token = ++generation
        cancelledWhileClosing = false
        val owned = try { factory.create(candidate) } catch (_: Exception) {
            retireCredentials()
            observer.get()?.finished(ConnectionFailure.Transport(ConnectionFailure.TransportReason.PROTOCOL_ERROR), false)
            return false
        }
        attempt = owned
        observer.get()?.phase(ConnectionState.Phase.PREPARING_CREDENTIALS)
        try { owned.start(object : SessionAttempt.Listener {
            override fun phase(phase: ConnectionState.Phase) {
                dispatcher.execute { if (token == generation && attempt === owned && !closing) observer.get()?.phase(phase) }
            }
            override fun ready() { dispatcher.execute { finish(token, null, false) } }
            override fun failed(failure: ConnectionFailure) { dispatcher.execute { finish(token, failure, false) } }
        }) } catch (_: Exception) {
            finish(token, ConnectionFailure.Transport(ConnectionFailure.TransportReason.PROTOCOL_ERROR), false)
        }
        return true
    }

    fun cancel() {
        if (attempt == null) return
        if (closing) { cancelledWhileClosing = true; return }
        // Queued readiness/failure cannot win after a form change or lifecycle cancellation.
        finish(++generation, null, true)
    }

    private fun finish(token: Long, failure: ConnectionFailure?, cancelled: Boolean) {
        val owned = attempt ?: return
        if (token != generation || closing) return
        closing = true
        retireCredentials()
        factory.invalidateReviews() // Probe never offers changed-key replacement.
        observer.get()?.phase(ConnectionState.Phase.CLOSING)
        owned.cancelAndClose { closeFailure -> dispatcher.execute {
            if (attempt !== owned || token != generation) return@execute
            factory.invalidateReviews()
            attempt = null; closing = false
            blocked = closeFailure != null
            observer.get()?.finished(closeFailure ?: if (cancelledWhileClosing) null else failure,
                cancelled || cancelledWhileClosing)
        } }
    }
}
