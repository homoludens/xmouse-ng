package com.stripe1.xmouse.session

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.stripe1.xmouse.MyInterface
import com.stripe1.xmouse.R
import com.stripe1.xmouse.secrets.CredentialUi
import com.stripe1.xmouse.trust.HostKeyMismatch
import com.stripe1.xmouse.trust.HostTrustReplacementUi
import com.stripe1.xmouse.trust.HostTrustUi
import java.util.function.Consumer

/** Activity-local session presentation. Session owners retain this listener only weakly. */
class SessionActivityPresenter(
    private val activity: Activity,
    private val credentialUi: CredentialUi,
    private val screen: MyInterface,
) : SessionInteractions.Listener {
    private val trustUi = HostTrustUi(activity)
    private var replacementUi: HostTrustReplacementUi? = null
    private var interactions: SessionInteractions? = null
    private var activePresentation: SessionInteractions.PresentationToken? = null
    private var currentPrompt: SessionInteractions.Prompt? = null
    private var outputDialog: AlertDialog? = null
    private var attached = false

    fun start() {
        mainOnly()
        credentialUi.start()
    }

    fun attach(owner: SessionInteractions) {
        mainOnly()
        if (attached && interactions === owner) return
        detach(force = true)
        interactions = owner
        attached = true
        activePresentation = owner.attach(this)
        // attach() retires any Activity-local request from the previous owner, so restore prompt
        // presentation after the new service owner has been installed.
        credentialUi.start()
    }

    /** Leaving for the system document picker preserves the one-shot CredentialUi request/result route. */
    fun stop(preservePicker: Boolean = false) {
        mainOnly()
        dismissOutputDialog()
        if (preservePicker && credentialUi.hasPicker()) return
        detach(force = false)
        credentialUi.stop()
    }

    /** Destruction cannot retain a dead Activity for a picker or a service prompt. */
    fun destroy() {
        mainOnly()
        dismissOutputDialog()
        detach(force = true)
        credentialUi.stop()
    }

    private fun detach(force: Boolean) {
        if (attached) interactions?.detach()
        attached = false
        interactions = null
        activePresentation = null
        currentPrompt = null
        trustUi.stop()
        replacementUi?.stop()
        replacementUi = null
        if (force) credentialUi.stop()
    }

    override fun show(prompt: SessionInteractions.Prompt) {
        mainOnly()
        val lifecycle = (activity as? LifecycleOwner)?.lifecycle
        if (activity.isFinishing || activity.isDestroyed || lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) != true) {
            prompt.cancel()
            return
        }
        currentPrompt?.takeIf { it !== prompt }?.let(::dismiss)
        currentPrompt = prompt
        when (prompt) {
            is SessionInteractions.Prompt.Credential -> credentialUi.request(prompt.kind, prompt.request)
            is SessionInteractions.Prompt.Trust -> trustUi.show(prompt.request)
            is SessionInteractions.Prompt.Mismatch -> {
                replacementUi?.stop()
                replacementUi = HostTrustReplacementUi(activity, Consumer { mismatch ->
                    if (mismatch === prompt.mismatch) prompt.replace()
                }).also { it.show(prompt.mismatch) }
            }
        }
    }

    override fun dismiss(prompt: SessionInteractions.Prompt) {
        mainOnly()
        if (currentPrompt === prompt) currentPrompt = null
        when (prompt) {
            is SessionInteractions.Prompt.Credential -> credentialUi.dismiss(prompt.request)
            is SessionInteractions.Prompt.Trust -> trustUi.dismiss(prompt.request)
            is SessionInteractions.Prompt.Mismatch -> replacementUi?.finish(prompt.mismatch)
        }
    }

    override fun recentCommand(command: String) {
        mainOnly()
        if (isLive()) screen.showRecentCommand(command)
    }

    override fun execCompleted(command: String, output: String) {
        mainOnly()
        if (!isLive()) return
        dismissOutputDialog()
        val dialog = AlertDialog.Builder(activity).setTitle(command).setMessage(output)
            .setNeutralButton("Ok", null)
            .setPositiveButton("Copy") { _, _ ->
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("xmouse script output", output))
            }.create()
        outputDialog = dialog
        dialog.setOnDismissListener { if (outputDialog === dialog) outputDialog = null }
        dialog.show()
    }

    override fun execFailed(command: String) {
        mainOnly()
        if (isLive()) Toast.makeText(activity, "Command execution failed.", Toast.LENGTH_LONG).show()
    }

    override fun commandResultsInvalidated() {
        mainOnly()
        dismissOutputDialog()
    }

    /** Passed across the command boundary as an opaque screen token, never as an Activity/listener. */
    fun presentationToken(): SessionInteractions.PresentationToken? = activePresentation

    override fun replacementFinished(mismatch: HostKeyMismatch, success: Boolean) {
        mainOnly()
        replacementUi?.finish(mismatch)
        if (isLive()) Toast.makeText(activity,
            if (success) "Trusted host key replaced. Connect again to authenticate with the new key."
            else "Host-key replacement could not be verified. Connection remains blocked; review trust before retrying.",
            Toast.LENGTH_LONG).show()
    }

    private fun isLive(): Boolean = !activity.isFinishing && !activity.isDestroyed
    private fun dismissOutputDialog() {
        outputDialog?.let { dialog ->
            outputDialog = null
            dialog.setOnDismissListener(null)
            dialog.dismiss()
        }
    }
    private fun mainOnly() { check(Looper.myLooper() === Looper.getMainLooper()) }
}
