package net.droopia.xmouse_ng.transport

import com.jcraft.jsch.JSchException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JschAuthenticationClassifierTest {
    @Test fun recognizesOnlyPinnedJschAuthenticationExhaustionSignal() {
        assertTrue(JschSshBackend.isAuthenticationRejected(
            JSchException("Auth fail for methods 'password,keyboard-interactive'")))
        assertTrue(JschSshBackend.isAuthenticationRejected(
            JSchException("Auth fail for methods 'publickey'")))
        // Session.connect emits this terminal result when an unavailable interactive challenge ends
        // the configured methods. The production session has no UserInfo/UI prompt to cancel.
        assertTrue(JschSshBackend.isAuthenticationRejected(
            JSchException("Auth cancel for methods 'keyboard-interactive'")))

        assertFalse(JschSshBackend.isAuthenticationRejected(
            JSchException("Session.connect: Auth fail for methods 'password'")))
        assertFalse(JschSshBackend.isAuthenticationRejected(
            JSchException("Auth fail for methods 'password\nNOT_REAL_secret'")))
        assertFalse(JschSshBackend.isAuthenticationRejected(
            JSchException("Auth fail for methods '" + "a".repeat(1100) + "'")))
        assertFalse(JschSshBackend.isAuthenticationRejected(JSchException("invalid protocol(kex)")))
    }
}
