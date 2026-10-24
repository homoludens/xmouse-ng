package com.stripe1.xmouse.session

import android.content.Context
import com.stripe1.xmouse.CommandTransport
import com.stripe1.xmouse.XMouseApplication
import java.util.concurrent.atomic.AtomicReference

/** Activity-safe bridge; it retains the application and an opaque token, never a screen or View. */
class SessionCommandTransport(context: Context) : CommandTransport {
    private val application = context.applicationContext as XMouseApplication
    /** Opaque screen capability only; Activity presenters/listeners are never retained here. */
    private val presentation = AtomicReference<SessionInteractions.PresentationToken?>()
    /** Activity-local mirror; the controller remains the authoritative single active capability. */
    private val controlSurface = AtomicReference<InputSurfaceToken?>()

    fun setPresentationToken(token: SessionInteractions.PresentationToken?) {
        presentation.set(token)
    }

    fun clearPresentationToken(token: SessionInteractions.PresentationToken?) {
        if (token != null) presentation.compareAndSet(token, null)
    }

    fun activateControlSurface(token: SessionInteractions.PresentationToken?): InputSurfaceToken? {
        val controller = application.sessionOwners.retainedOwnerOrNull()?.controller ?: return null
        val previous = controlSurface.get()
        if (previous != null && controller.isControlSurfaceActive(previous)) return previous
        val activated = controller.activateControlSurface(token) ?: return null
        controlSurface.set(activated)
        return activated
    }

    fun deactivateControlSurface(token: InputSurfaceToken?) {
        if (token == null || !controlSurface.compareAndSet(token, null)) return
        application.sessionOwners.retainedOwnerOrNull()?.controller?.deactivateControlSurface(token)
    }

    fun activeControlSurface(): InputSurfaceToken? = controlSurface.get()

    fun isInputTokenActive(token: Any?): Boolean {
        val controller = application.sessionOwners.retainedOwnerOrNull()?.controller ?: return false
        return when (token) {
            is InputSurfaceToken -> controller.isControlSurfaceActive(token)
            is InputGestureToken -> controller.isInputGestureActive(token)
            else -> false
        }
    }

    fun beginInputGesture(surface: InputSurfaceToken?): InputGestureToken? =
        application.sessionOwners.retainedOwnerOrNull()?.controller?.beginInputGesture(surface)

    fun finishInputGesture(token: InputGestureToken?) {
        application.sessionOwners.retainedOwnerOrNull()?.controller?.finishInputGesture(token)
    }

    fun cancelInputGesture(token: InputGestureToken?) {
        application.sessionOwners.retainedOwnerOrNull()?.controller?.cancelInputGesture(token)
    }

    override fun executeShellCommand(command: String): Boolean {
        // Read once at submission; an inactive/detached screen cannot borrow a newer screen's lease.
        val target = controlSurface.get() ?: return false
        return executeInputCommand(command, target)
    }

    fun executeInputCommand(command: String, token: Any?): Boolean =
        application.sessionOwners.retainedOwnerOrNull()?.controller?.executeInputCommand(command, token) ?: false

    /** A View/gesture adapter captures this opaque token instead of reading a later active surface. */
    fun forInputToken(token: Any): CommandTransport = object : CommandTransport {
        override fun executeShellCommand(command: String): Boolean = executeInputCommand(command, token)
    }

    fun executeExecCommand(command: String, presentation: SessionInteractions.PresentationToken?): Boolean =
        if (presentation == null) false else
            application.sessionOwners.retainedOwnerOrNull()?.controller?.executeExecCommand(command, presentation) ?: false

    fun canSendClipboard(presentation: SessionInteractions.PresentationToken?): Boolean =
        application.sessionOwners.retainedOwnerOrNull()?.controller?.canSendClipboard(presentation) ?: false

    fun sendClipboard(text: String, presentation: SessionInteractions.PresentationToken?): Boolean =
        if (presentation == null) false else
            application.sessionOwners.retainedOwnerOrNull()?.controller?.sendClipboard(text, presentation) ?: false
}
