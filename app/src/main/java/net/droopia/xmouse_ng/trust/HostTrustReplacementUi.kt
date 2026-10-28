package net.droopia.xmouse_ng.trust

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.widget.Toast
import net.droopia.xmouse_ng.connection.EndpointLocator
import java.util.function.Consumer

/** Activity-local, two-step mismatch review. Neither screen authenticates the blocked connection. */
class HostTrustReplacementUi(
    private val activity: Activity,
    private val onConfirmed: Consumer<HostKeyMismatch>,
) {
    private var candidate: HostKeyMismatch? = null
    private var reviewDialog: AlertDialog? = null
    private var confirmationDialog: AlertDialog? = null

    fun show(mismatch: HostKeyMismatch) {
        mainOnly()
        if (!mismatch.isCurrent() || activity.isFinishing || activity.isDestroyed) return
        dismissCurrent(cancel = true)
        candidate = mismatch
        reviewDialog = AlertDialog.Builder(activity)
            .setTitle("SSH host key changed")
            .setMessage(details(mismatch))
            .setPositiveButton("Review replacement") { _, _ -> confirmReview(mismatch) }
            .setNegativeButton("Cancel") { _, _ -> cancel(mismatch) }
            .setOnCancelListener { cancel(mismatch) }
            .create().also { it.setCanceledOnTouchOutside(false); it.show() }
    }

    /** Dismisses only this candidate after its durable replacement result; no retry is started. */
    fun finish(mismatch: HostKeyMismatch) {
        mainOnly()
        if (candidate === mismatch) dismissCurrent(cancel = false)
    }

    fun stop() {
        mainOnly()
        dismissCurrent(cancel = true)
    }

    private fun confirmReview(mismatch: HostKeyMismatch) {
        mainOnly()
        if (!isCurrent(mismatch)) return stale()
        reviewDialog?.setOnCancelListener(null)
        reviewDialog?.dismiss(); reviewDialog = null
        confirmationDialog = AlertDialog.Builder(activity)
            .setTitle("Replace the saved host key?")
            .setMessage(
                details(mismatch) +
                    "\n\nOnly replace it after verifying the new fingerprint independently with the computer owner. " +
                    "This changes trust only; connect again afterward to authenticate.",
            )
            .setPositiveButton("Replace trusted key") { _, _ ->
                if (isCurrent(mismatch) && mismatch.confirm()) {
                    confirmationDialog = null
                    reviewDialog = null
                    onConfirmed.accept(mismatch)
                } else stale()
            }
            .setNegativeButton("Cancel") { _, _ -> cancel(mismatch) }
            .setOnCancelListener { cancel(mismatch) }
            .create().also { it.setCanceledOnTouchOutside(false); it.show() }
    }

    private fun details(mismatch: HostKeyMismatch): String {
        val endpoint = when (val locator = mismatch.endpoint.locator) {
            is EndpointLocator.Manual -> locator.host.original
            is EndpointLocator.DiscoveredService -> locator.stableTargetHostname?.original
                ?: "${locator.instanceName} (${locator.serviceType}.${locator.domain})"
        }
        return "Intended computer: $endpoint\nPort: ${mismatch.endpoint.port}" +
            "\n\nPreviously trusted key: ${mismatch.trustedKey.algorithm}\n${mismatch.trustedKey.fingerprint}" +
            "\n\nNewly presented key: ${mismatch.presentedKey.algorithm}\n${mismatch.presentedKey.fingerprint}" +
            "\n\nWARNING: A changed key may indicate impersonation. Verify the new fingerprint out of band; " +
            "a reconnect or address change is not proof of identity."
    }

    private fun isCurrent(mismatch: HostKeyMismatch): Boolean =
        candidate === mismatch && mismatch.isCurrent() && !activity.isFinishing && !activity.isDestroyed

    private fun cancel(mismatch: HostKeyMismatch) {
        if (candidate === mismatch) dismissCurrent(cancel = true)
        else mismatch.cancel()
    }

    private fun stale() {
        dismissCurrent(cancel = true)
        Toast.makeText(activity, "This host-key review expired or the connection changed. Retry to review again.", Toast.LENGTH_LONG).show()
    }

    private fun dismissCurrent(cancel: Boolean) {
        if (cancel) candidate?.cancel()
        candidate = null
        reviewDialog?.setOnCancelListener(null)
        reviewDialog?.dismiss(); reviewDialog = null
        confirmationDialog?.setOnCancelListener(null)
        confirmationDialog?.dismiss(); confirmationDialog = null
    }

    private fun mainOnly() { check(Looper.myLooper() === Looper.getMainLooper()) }
}
