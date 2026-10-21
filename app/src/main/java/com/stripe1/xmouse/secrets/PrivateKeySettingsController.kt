package com.stripe1.xmouse.secrets

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.preference.Preference
import android.widget.Toast
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Legacy PreferenceActivity uses its platform result API; no Activity/Fragment conversion needed. */
class PrivateKeySettingsController(private val activity: Activity, private val preference: Preference) {
    private val credentials = AndroidCredentials.get(activity)
    private val preferences = credentials.preferences
    private val secrets = credentials.secrets
    private val repository = AndroidPrivateKeys.open(preferences, secrets)
    private var attempt: PrivateKeyImportAttempt? = null
    private var signal: CancellationSignal? = null
    private var requestCode = -1
    private var destroyed = false

    init {
        updateSummary()
        preference.setOnPreferenceClickListener { launchPicker(); true }
        if (!preferences.contains(LegacyCredentialReferences.GLOBAL_PRIVATE_KEY) &&
            !preferences.getString(AndroidPrivateKeys.LEGACY_PATH, "").isNullOrEmpty()) {
            val recovery = repository.beginImport()
            attempt = recovery
            execute(recovery) { phrase -> repository.importLegacy(recovery, phrase) }
        }
    }

    private fun launchPicker() {
        cancelPending()
        attempt = repository.beginImport()
        requestCode = nextRequest.getAndUpdate { if (it == 0x7fff) 0x4000 else it + 1 }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            activity.startActivityForResult(intent, requestCode)
        } catch (_: ActivityNotFoundException) {
            cancelPending()
            showMessage("No system document picker is available. The previous key was kept.")
        }
    }

    fun onActivityResult(code: Int, result: Int, data: Intent?): Boolean {
        if (destroyed || code != requestCode || attempt == null) return false
        requestCode = -1 // Duplicate/late results cannot reuse this request.
        val importing = attempt!!
        val uri = data?.data
        if (result != Activity.RESULT_OK || uri == null || uri.scheme != "content") {
            cancelPending()
            updateSummary()
            if (result == Activity.RESULT_OK) showMessage("No readable document was selected. The previous key was kept.")
            return true
        }
        val cancellation = CancellationSignal()
        signal = cancellation
        execute(importing) { phrase ->
            try {
                val descriptor = activity.contentResolver.openAssetFileDescriptor(uri, "r", cancellation)
                    ?: keyImportFail(PrivateKeyImportException.Reason.READ_FAILURE)
                val input = try {
                    descriptor.createInputStream()
                } catch (_: IOException) {
                    try { descriptor.close() } catch (_: IOException) { /* Safe diagnostic below. */ }
                    keyImportFail(PrivateKeyImportException.Reason.READ_FAILURE)
                }
                // AutoCloseInputStream owns the descriptor; importer closes it BEFORE publication.
                repository.importKey(importing, input, phrase)
            } catch (_: IOException) {
                keyImportFail(PrivateKeyImportException.Reason.READ_FAILURE)
            } catch (_: SecurityException) {
                keyImportFail(PrivateKeyImportException.Reason.READ_FAILURE)
            } catch (_: OperationCanceledException) {
                keyImportFail(PrivateKeyImportException.Reason.CANCELLED)
            }
        }
        return true
    }

    private fun execute(importing: PrivateKeyImportAttempt, action: (ByteArray) -> Unit) {
        credentials.execute {
            val message = try {
                if (!credentials.prepare()) throw CredentialRepositoryException()
                val phrase = AndroidPrivateKeys.readConfiguredPassphrase(preferences, secrets)
                try { action(phrase) } finally { phrase.fill(0) }
                "Private key imported securely. The source document is no longer needed."
            } catch (failure: PrivateKeyImportException) {
                when (failure.reason) {
                    PrivateKeyImportException.Reason.CANCELLED -> null
                    PrivateKeyImportException.Reason.PASSPHRASE_REQUIRED_OR_INVALID ->
                        "Key unlock failed. Check Optional Key Passphrase, then select the key again. The previous key was kept."
                    PrivateKeyImportException.Reason.INVALID_KEY ->
                        "Unsupported or invalid private key. The previous key was kept."
                    PrivateKeyImportException.Reason.UNSUPPORTED_FORMAT ->
                        "Unsupported private-key format, cipher or KDF. The previous key was kept."
                    PrivateKeyImportException.Reason.RESOURCE_LIMIT ->
                        "Private-key parameters exceed the supported work or size limits. The previous key was kept."
                    PrivateKeyImportException.Reason.TOO_LARGE ->
                        "The key document exceeds the 1 MiB limit. The previous key was kept."
                    PrivateKeyImportException.Reason.READ_FAILURE, PrivateKeyImportException.Reason.MISSING_KEY ->
                        "Saved key or selected document is unavailable. Tap Private Key to re-import using the document picker."
                    else -> "Key import could not be committed. The previous key and recovery context were kept. Try again."
                }
            } catch (_: SecretStoreException) {
                "Protected key storage is unavailable. Existing encrypted keys were kept; no device key was reset. Restore access before saving."
            } catch (_: CredentialRepositoryException) {
                CredentialRepository.RECOVERY_MESSAGE
            }
            activity.runOnUiThread {
                if (!destroyed && attempt === importing) {
                    attempt = null
                    signal = null
                    updateSummary()
                    if (message != null) showMessage(message)
                }
            }
        }
    }

    private fun updateSummary() {
        // Never summarize a URI, raw path, passphrase, provider response, or private-key content.
        preference.summary = when {
            preferences.contains(LegacyCredentialReferences.GLOBAL_PRIVATE_KEY) -> "Imported SSH private key"
            !preferences.getString(AndroidPrivateKeys.LEGACY_PATH, "").isNullOrEmpty() ->
                "Saved key needs import. Tap to select it with the document picker."
            preferences.getBoolean(CredentialMetadataBackup.PRIVATE_KEY_REENTRY, false) ->
                "Private key was not restored. Tap to select it again with the document picker."
            else -> "Select an SSH private-key document"
        }
    }

    private fun showMessage(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()

    private fun cancelPending() {
        attempt?.cancel()
        signal?.cancel()
        attempt = null
        signal = null
        requestCode = -1
    }

    fun destroy() {
        destroyed = true
        cancelPending()
        // Recreation deliberately requires reselection; no URI/key/passphrase is put in saved state.
    }

    companion object { private val nextRequest = AtomicInteger(0x4000) }
}
