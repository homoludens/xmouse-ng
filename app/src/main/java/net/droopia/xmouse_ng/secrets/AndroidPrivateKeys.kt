package net.droopia.xmouse_ng.secrets

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager

/** Validated key boundary; production imports share the application credential repository's gate. */
object AndroidPrivateKeys {
    const val LEGACY_PATH = "pref_addkeybutton"
    const val LABEL = "pref_private_key_label"
    private const val PREVIOUS_REFERENCE = "pref_private_key_previous_ref"
    private const val PREVIOUS_PATH = "pref_private_key_previous_path"

    @JvmStatic
    fun open(context: Context): PrivateKeyRepository = open(
        PreferenceManager.getDefaultSharedPreferences(context), AndroidSecretStore.open(context),
    )

    @JvmStatic
    fun open(preferences: SharedPreferences, secrets: SecretStore): PrivateKeyRepository =
        PrivateKeyRepository(AndroidPrivateKeyReferences(preferences), secrets)

    /** Post-3.5 bridge: protected only; migration must precede use. Never read legacy secret text. */
    @JvmStatic
    @Throws(SecretStoreException::class, PrivateKeyImportException::class)
    fun readConfiguredPassphrase(preferences: SharedPreferences, secrets: SecretStore): ByteArray {
        val reference = preferences.getString(LegacyCredentialReferences.GLOBAL_PASSPHRASE, null)
        if (reference != null) {
            val id = try { SecretId(reference) } catch (_: IllegalArgumentException) {
                keyImportFail(PrivateKeyImportException.Reason.INVALID_REFERENCE)
            }
            return secrets.read(id) ?: keyImportFail(PrivateKeyImportException.Reason.MISSING_KEY)
        }
        if (preferences.contains("pref_key_passphrase")) keyImportFail(PrivateKeyImportException.Reason.STORAGE_FAILURE)
        return byteArrayOf()
    }

    internal class AndroidPrivateKeyReferences(private val preferences: SharedPreferences) : PrivateKeyReferences {
        override fun snapshot() = PrivateKeyConfiguration(
            preferences.getString(LegacyCredentialReferences.GLOBAL_PRIVATE_KEY, null),
            preferences.getString(LEGACY_PATH, null),
        )

        override fun replace(expected: PrivateKeyConfiguration, replacement: SecretId) {
            val before = preferences.all
            val editor = preferences.edit()
            editor.putString(LegacyCredentialReferences.GLOBAL_PRIVATE_KEY, replacement.value)
            editor.putString(LABEL, "Imported SSH private key")
            editor.remove(LEGACY_PATH)
            editor.remove(CredentialMetadataBackup.PRIVATE_KEY_REENTRY)
            // Retain recovery context for the intrinsically uncertain SharedPreferences commit(false)
            // case. Old ciphertext is NEVER overwritten/deleted, even after successful replacement.
            editor.putString(PREVIOUS_REFERENCE, expected.reference)
            editor.putString(PREVIOUS_PATH, expected.legacyPath)
            val committed = editor.commit()
            if (!committed) {
                // Android updates its cache before disk. Restore only our fields, not unrelated prefs.
                val rollback = preferences.edit()
                listOf(LegacyCredentialReferences.GLOBAL_PRIVATE_KEY, LABEL, LEGACY_PATH, PREVIOUS_REFERENCE, PREVIOUS_PATH,
                    CredentialMetadataBackup.PRIVATE_KEY_REENTRY).forEach {
                    when (val value = before[it]) {
                        is String -> rollback.putString(it, value)
                        is Boolean -> rollback.putBoolean(it, value)
                        is Int -> rollback.putInt(it, value)
                        is Long -> rollback.putLong(it, value)
                        is Float -> rollback.putFloat(it, value)
                        is Set<*> -> rollback.putStringSet(it, value.filterIsInstance<String>().toSet())
                        else -> rollback.remove(it)
                    }
                }
                val restored = rollback.commit()
                if (!restored) keyImportFail(PrivateKeyImportException.Reason.STORAGE_FAILURE)
                keyImportFail(PrivateKeyImportException.Reason.STORAGE_FAILURE)
            }
        }
    }
}
