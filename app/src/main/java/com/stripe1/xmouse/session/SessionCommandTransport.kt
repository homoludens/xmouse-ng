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

    fun setPresentationToken(token: SessionInteractions.PresentationToken?) {
        presentation.set(token)
    }

    fun clearPresentationToken(token: SessionInteractions.PresentationToken?) {
        if (token != null) presentation.compareAndSet(token, null)
    }

    override fun executeShellCommand(command: String): Boolean {
        // Read once at submission so queued work can never acquire a replacement screen's token.
        val target = presentation.get()
        return application.sessionOwners.retainedOwnerOrNull()?.controller
            ?.executeShellCommand(command, target) ?: false
    }

    fun executeExecCommand(command: String, presentation: SessionInteractions.PresentationToken?): Boolean =
        if (presentation == null) false else
            application.sessionOwners.retainedOwnerOrNull()?.controller?.executeExecCommand(command, presentation) ?: false
}
