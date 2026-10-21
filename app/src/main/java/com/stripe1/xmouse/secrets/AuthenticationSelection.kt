package com.stripe1.xmouse.secrets

/** Non-secret, immutable credential-reference snapshot. Never look up the selected host again mid-attempt. */
data class AuthenticationSelection(
    val host: CredentialHost,
    val useKey: Boolean,
    val passphraseReference: String?,
    val legacyPassphrasePresent: Boolean,
    val passphraseReentry: Boolean,
    val privateKeyReference: String?,
    val privateKeyReentry: Boolean,
    val legacyPrivateKeyPath: String?,
)
