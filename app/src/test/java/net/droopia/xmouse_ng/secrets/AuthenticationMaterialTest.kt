package net.droopia.xmouse_ng.secrets

import net.droopia.xmouse_ng.HostItem
import org.junit.Assert.*
import org.junit.Test

class AuthenticationMaterialTest {
    @Test fun attemptCleanupClearsEveryCallerOwnedBufferAndIsRepeatable() {
        val host = CredentialHost(7, "label", "host", "user", "22", "opaque_reference", false)
        val material = AuthenticationMaterial(host, true, byteArrayOf(1), byteArrayOf(2), byteArrayOf(3))
        material.close(); material.close()
        assertArrayEquals(byteArrayOf(0), material.password)
        assertArrayEquals(byteArrayOf(0), material.passphrase)
        assertArrayEquals(byteArrayOf(0), material.privateKey)
        assertSame(host, material.host)
    }

    @Test fun listModelCarriesOnlyAnOpaqueCredentialReferenceNotPlaintext() {
        val host = CredentialHost(7, "label", "host", "user", "22", "opaque_reference", false)
        val item = HostItem().apply { credentialHost = host }
        assertSame(host, item.credentialHost)
        assertFalse(HostItem::class.java.declaredFields.any { it.name.equals("password", ignoreCase = true) })
    }
}
