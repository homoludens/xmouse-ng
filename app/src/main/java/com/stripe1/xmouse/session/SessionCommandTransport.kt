package com.stripe1.xmouse.session

import android.content.Context
import com.stripe1.xmouse.CommandTransport
import com.stripe1.xmouse.XMouseApplication

/** Activity-safe compatibility bridge; it retains only the application and never a screen or View. */
class SessionCommandTransport(context: Context) : CommandTransport {
    private val application = context.applicationContext as XMouseApplication

    override fun executeShellCommand(command: String): Boolean =
        application.sessionOwners.retainedOwnerOrNull()?.controller?.executeShellCommand(command) ?: false

    fun executeExecCommand(command: String): Boolean =
        application.sessionOwners.retainedOwnerOrNull()?.controller?.executeExecCommand(command) ?: false
}
