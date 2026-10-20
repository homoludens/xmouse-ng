package com.stripe1.xmouse.secrets

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.CancellationSignal
import android.text.InputType
import android.widget.EditText
import android.widget.Toast

/** Activity-local presentation only; cancelling/leaving cannot reset or overwrite stored credentials. */
class CredentialUi(private val activity: Activity) : CredentialPrompts {
    private val repository = AndroidCredentials.get(activity)
    private val main = Handler(Looper.getMainLooper())
    private class PendingReentry(val request: CredentialReentryRequest) {
        lateinit var deadline: Runnable
        var dialog: AlertDialog? = null
        var keyRead: PrivateKeyImportAttempt? = null
        var keyOpen: CancellationSignal? = null
        var pickerLaunched = false
    }
    private val requests = mutableMapOf<CredentialReentryRequest, PendingReentry>()
    private val dialogs = mutableSetOf<AlertDialog>()
    private var active = true
    private var keyRequest: PendingReentry? = null
    private var pickerCode = -1
    // Never reuse a code in this Activity owner: old/duplicate results remain distinguishable.
    private val issuedPickerCodes = mutableSetOf<Int>()
    private val deadlines = mutableSetOf<Runnable>()
    @Volatile var generation: Int = 0
        private set

    fun start() { active = true }
    fun stop() {
        active = false
        generation++
        requests.values.toList().forEach { finish(it, cancel = true) }
        dialogs.toList().forEach { it.dismiss() }; dialogs.clear()
        deadlines.forEach { main.removeCallbacks(it) }; deadlines.clear()
    }
    fun isCurrent(token: Int): Boolean = active && generation == token && !activity.isFinishing && !activity.isDestroyed
    fun message() { Toast.makeText(activity, CredentialRepository.RECOVERY_MESSAGE, Toast.LENGTH_LONG).show() }

    /** Main-thread, idempotent cleanup of this request only, including late deadline/read callbacks. */
    private fun finish(pending: PendingReentry, cancel: Boolean): Boolean {
        if (requests[pending.request] !== pending) return false
        requests.remove(pending.request)
        main.removeCallbacks(pending.deadline); deadlines.remove(pending.deadline)
        if (keyRequest === pending) { keyRequest = null; pickerCode = -1 }
        pending.keyRead?.cancel(); pending.keyRead = null
        pending.keyOpen?.cancel(); pending.keyOpen = null
        pending.dialog?.let { dialogs.remove(it); pending.dialog = null; it.dismiss() }
        if (cancel) pending.request.cancel()
        return true
    }

    override fun request(kind: CredentialKind, request: CredentialReentryRequest) {
        val token = generation
        main.post {
            if (!isCurrent(token)) { request.cancel(); return@post }
            if (!request.isPending || requests.containsKey(request)) return@post
            val pending = PendingReentry(request)
            pending.deadline = Runnable { finish(pending, cancel = true) }
            requests[request] = pending
            deadlines.add(pending.deadline); main.postDelayed(pending.deadline, 60_000)
            if (kind == CredentialKind.PRIVATE_KEY) {
                val dialog = AlertDialog.Builder(activity).setTitle("Private key unavailable")
                    .setMessage("Choose a key for this connection only. Encrypted records will not be replaced or reset.")
                    .setPositiveButton("Choose for this attempt") { _, _ ->
                        if (requests[request] !== pending) return@setPositiveButton
                        keyRequest?.takeIf { it !== pending }?.let { finish(it, cancel = true) }
                        val code = pickerCodes.allocate(issuedPickerCodes)
                        if (code == null) {
                            finish(pending, cancel = true)
                            Toast.makeText(activity, "Key picker request limit reached. Close and reopen this screen to choose again. Existing credentials were kept.", Toast.LENGTH_LONG).show()
                            return@setPositiveButton
                        }
                        keyRequest = pending
                        pickerCode = code
                        pending.pickerLaunched = true
                        try {
                            activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, pickerCode)
                        } catch (_: Exception) { finish(pending, cancel = true) }
                    }.setNegativeButton("Cancel") { _, _ -> finish(pending, cancel = true) }
                    .setOnCancelListener { finish(pending, cancel = true) }.create()
                pending.dialog = dialog
                dialogs.add(dialog); dialog.show()
                dialog.setOnDismissListener {
                    dialogs.remove(dialog); pending.dialog = null
                    // A fast Activity result may already have started reading before this dismissal
                    // callback runs. Dismissal after explicit Choose must not cancel that same read.
                    if (!pending.pickerLaunched) finish(pending, cancel = true)
                }
            } else {
                val input = passwordInput()
                val dialog = AlertDialog.Builder(activity)
                    .setTitle(if (kind == CredentialKind.PASSWORD) "Re-enter password" else "Re-enter key passphrase")
                    .setMessage("Device key unavailable. Use for this connection only; nothing will be saved or reset.")
                    .setView(input)
                    .setPositiveButton("Use once") { _, _ ->
                        val bytes = input.text.toString().toByteArray(Charsets.UTF_8)
                        try { if (finish(pending, cancel = false)) request.useForThisAttempt(bytes) }
                        catch (_: IllegalArgumentException) { request.cancel(); message() }
                        finally { bytes.fill(0); input.text.clear() }
                    }.setNegativeButton("Cancel") { _, _ -> finish(pending, cancel = true) }
                    .setOnCancelListener { finish(pending, cancel = true) }.create()
                pending.dialog = dialog
                dialogs.add(dialog); dialog.show()
                dialog.setOnDismissListener {
                    input.text.clear(); dialogs.remove(dialog); pending.dialog = null
                    finish(pending, cancel = true)
                }
            }
        }
    }

    /** Leaving for the picker is not cancellation; stop() is called by the Activity except for this case. */
    fun hasPicker(): Boolean = keyRequest != null
    fun onActivityResult(code: Int, result: Int, data: Intent?): Boolean {
        if (code != pickerCode || keyRequest == null) return false
        val pending = keyRequest!!
        val request = pending.request
        keyRequest = null; pickerCode = -1
        val uri = data?.data
        if (!request.isPending || result != Activity.RESULT_OK || uri?.scheme != "content") { finish(pending, cancel = true); return true }
        val reading = PrivateKeyImportAttempt(PrivateKeyConfiguration(null, null))
        val cancellation = CancellationSignal()
        pending.keyRead = reading
        pending.keyOpen = cancellation
        repository.execute {
            try {
                val descriptor = activity.applicationContext.contentResolver.openAssetFileDescriptor(uri, "r", cancellation)
                    ?: throw CredentialRepositoryException()
                val stream = try { descriptor.createInputStream() } catch (_: Exception) {
                    try { descriptor.close() } catch (_: Exception) { }
                    throw CredentialRepositoryException()
                }
                // Same bounded/closed/cancellable reader as imports; no persistence or reference change.
                // The existing SSH consumer validates/unlocks these bytes before authenticating.
                val bytes = PrivateKeyRepository.readForReentry(stream, reading)
                main.post {
                    try { if (finish(pending, cancel = false)) request.useForThisAttempt(bytes) }
                    finally { bytes.fill(0) }
                }
            } catch (_: Exception) {
                main.post { if (finish(pending, cancel = true) && active) message() }
            }
        }
        return true
    }

    /** Non-persistent replacement for framework EditTextPreference; no saved-state secret string. */
    fun editPassphrase() {
        val token = generation
        repository.execute {
            try {
                if (!repository.prepare()) throw CredentialRepositoryException()
                val bytes = repository.readPassphrase()
                main.post {
                    try {
                        if (!isCurrent(token)) return@post
                        val input = passwordInput().apply { setText(bytes.toString(Charsets.UTF_8)) }
                        val dialog = AlertDialog.Builder(activity).setTitle("Optional Key Passphrase").setView(input)
                            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
                        dialogs.add(dialog); dialog.show()
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            val plaintext = input.text.toString().toByteArray(Charsets.UTF_8)
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                            repository.execute {
                                val success = try { repository.savePassphrase(plaintext); true } catch (_: Exception) { false }
                                finally { plaintext.fill(0) }
                                main.post {
                                    if (isCurrent(token)) {
                                        if (success) dialog.dismiss() else { message(); dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true }
                                    }
                                }
                            }
                        }
                        dialog.setOnDismissListener { input.text.clear(); dialogs.remove(dialog) }
                    } finally { bytes.fill(0) }
                }
            } catch (_: Exception) { main.post { if (isCurrent(token)) message() } }
        }
    }

    private fun passwordInput() = EditText(activity).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        isSingleLine = true; isSaveEnabled = false
        if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }

    companion object { private val pickerCodes = ReentryPickerCodes() }
}
