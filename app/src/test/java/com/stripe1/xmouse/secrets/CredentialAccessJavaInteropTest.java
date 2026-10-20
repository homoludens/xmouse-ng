package com.stripe1.xmouse.secrets;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class CredentialAccessJavaInteropTest {
    @Test
    public void javaPresentationCanCancelAnExplicitReentryRequest() {
        SecretStore store = new SecretStore() {
            @Override
            public byte[] read(SecretId id) throws SecretStoreException {
                throw new SecretStoreException(SecretStoreException.Reason.KEY_UNAVAILABLE);
            }

            @Override
            public void write(SecretId id, byte[] plaintext) {
                throw new AssertionError("Re-entry cannot write storage");
            }
        };
        AtomicReference<CredentialReadResult> result = new AtomicReference<>();
        CredentialReentryRequest request = new CredentialAccess(store).read(
                new SecretId("global_ssh_key"), reentry -> assertTrue(reentry.cancel()), result::set);
        assertSame(SecretStoreException.Reason.KEY_UNAVAILABLE, request.getReason());
        assertSame(CredentialReadResult.Cancelled.INSTANCE, result.get());
    }
}
