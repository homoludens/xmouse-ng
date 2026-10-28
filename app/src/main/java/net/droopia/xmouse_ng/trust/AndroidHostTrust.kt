package net.droopia.xmouse_ng.trust

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Looper
import net.droopia.xmouse_ng.connection.EndpointLocator
import net.droopia.xmouse_ng.secrets.AndroidSecretFileIo
import java.io.File

object AndroidHostTrust {
    /** Explicitly not backed up by current metadata-only backup allowlists, and never Keystore-bound. */
    @JvmStatic fun open(context: Context): HostKeyRepository =
        HostKeyRepository(File(context.applicationContext.noBackupFilesDir, "host-trust"), AndroidSecretFileIo)
}

/** Activity-local, main-only presentation. The pure owner never retains this object strongly. */
class HostTrustUi(private val activity: Activity, private val requests: HostTrustRequests? = null) : HostTrustRequests.Listener {
    private var request: HostTrustRequests.Request? = null
    private var dialog: AlertDialog? = null
    fun start() { mainOnly(); requests?.attach(this) }
    fun stop() {
        mainOnly(); requests?.detach()
        request?.cancel(); request = null
        dialog?.dismiss(); dialog = null
    }
    override fun show(request: HostTrustRequests.Request) {
        mainOnly()
        if (activity.isFinishing || activity.isDestroyed || !request.isPending) { request.cancel(); return }
        dialog?.dismiss()
        this.request = request
        val name = when (val locator = request.endpoint.locator) {
            is EndpointLocator.Manual -> locator.host.original
            is EndpointLocator.DiscoveredService -> locator.stableTargetHostname?.original
                ?: "${locator.instanceName} (${locator.serviceType}.${locator.domain})"
        }
        dialog = AlertDialog.Builder(activity).setTitle("Trust this SSH host?")
            .setMessage("Intended computer: $name\nPort: ${request.endpoint.port}\nHost key: ${request.key.algorithm}\n${request.key.fingerprint}\n\nVerify this fingerprint with the computer owner before accepting.")
            .setPositiveButton("Accept and save") { _, _ -> request.accept() }
            .setNegativeButton("Reject") { _, _ -> request.reject() }
            .setNeutralButton("Cancel") { _, _ -> request.cancel() }
            .setOnCancelListener { request.cancel() }
            .create().also { it.setCanceledOnTouchOutside(false); it.show() }
    }
    override fun dismiss(request: HostTrustRequests.Request) {
        mainOnly()
        if (this.request === request) { dialog?.dismiss(); dialog = null; this.request = null }
    }
    private fun mainOnly() { check(Looper.myLooper() === Looper.getMainLooper()) }
}
