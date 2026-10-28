package net.droopia.xmouse_ng.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStateTest {
    private val endpoint = Endpoint.manual("desktop.local", 2222).identity

    @Test
    fun snapshotsRepresentEachConnectionPhaseWithTheIntendedEndpoint() {
        val states = listOf(
            ConnectionState.PreparingCredentials(endpoint),
            ConnectionState.Resolving(endpoint), ConnectionState.Connecting(endpoint),
            ConnectionState.AwaitingTrust(endpoint), ConnectionState.Authenticating(endpoint),
            ConnectionState.Connected(endpoint), ConnectionState.Closing(endpoint), ConnectionState.Disconnected(endpoint),
            ConnectionState.Error(endpoint, ConnectionFailure.Resolution(ConnectionFailure.ResolutionReason.NOT_FOUND)),
        )
        assertEquals(ConnectionState.Phase.entries, states.map { it.phase })
        states.forEach { assertEquals(endpoint, it.endpoint) }
    }

    @Test
    fun onlyUsableCommandChannelConnectedSnapshotIsReady() {
        val states = listOf(
            ConnectionState.PreparingCredentials(endpoint), ConnectionState.Closing(endpoint),
            ConnectionState.Resolving(endpoint), ConnectionState.Connecting(endpoint),
            ConnectionState.AwaitingTrust(endpoint), ConnectionState.Authenticating(endpoint),
            ConnectionState.Disconnected(endpoint),
            ConnectionState.Error(endpoint, ConnectionFailure.Transport(ConnectionFailure.TransportReason.CHANNEL_UNAVAILABLE)),
        )
        states.forEach { assertFalse(it.isReady) }
        assertTrue(ConnectionState.Connected(endpoint).isReady)
    }

    @Test
    fun idleAndDeliberatelyDisconnectedSnapshotsDoNotFabricateFailure() {
        assertNull(ConnectionState.Disconnected().endpoint)
        assertNull(ConnectionState.Disconnected().failure)
        assertEquals(endpoint, ConnectionState.Disconnected(endpoint).endpoint)
        assertNull(ConnectionState.Disconnected(endpoint).failure)
        assertNull(ConnectionState.AwaitingTrust(endpoint).failure)
    }

    @Test
    fun permissionResolutionTransportTrustAndAuthenticationHaveDistinctTypesAndCategories() {
        val failures = listOf(
            ConnectionFailure.Permission(ConnectionFailure.PermissionReason.DENIED),
            ConnectionFailure.Resolution(ConnectionFailure.ResolutionReason.NOT_FOUND),
            ConnectionFailure.Transport(ConnectionFailure.TransportReason.CONNECTION_REFUSED),
            ConnectionFailure.Trust(ConnectionFailure.TrustReason.REJECTED),
            ConnectionFailure.Authentication(ConnectionFailure.AuthenticationReason.REJECTED),
        )
        assertEquals(ConnectionFailure.Category.entries, failures.map { it.category })
        assertEquals(5, failures.map { it.javaClass }.distinct().size)
        for (failure in failures) {
            val error: ConnectionState = ConnectionState.Error(endpoint, failure)
            assertEquals(endpoint, error.endpoint)
            assertSame(failure, error.failure)
            assertFalse(error.isReady)
        }
    }

    @Test
    fun denialAndRevocationArePermissionFailuresNotBadPasswords() {
        for (reason in ConnectionFailure.PermissionReason.entries) {
            val failure = ConnectionFailure.Permission(reason)
            assertEquals(ConnectionFailure.Category.PERMISSION, failure.category)
            assertNotEquals(ConnectionFailure.Category.AUTHENTICATION, failure.category)
        }
    }

    @Test
    fun refusalUnreachableAndTimeoutReasonsRemainDistinguishableFromLookupFailures() {
        val transport = listOf(
            ConnectionFailure.TransportReason.CONNECTION_REFUSED,
            ConnectionFailure.TransportReason.UNREACHABLE,
            ConnectionFailure.TransportReason.TIMED_OUT,
        ).map { ConnectionFailure.Transport(it) }
        assertEquals(3, transport.distinct().size)
        transport.forEach { assertEquals(ConnectionFailure.Category.TRANSPORT, it.category) }
        assertNotEquals(
            ConnectionFailure.Transport(ConnectionFailure.TransportReason.TIMED_OUT),
            ConnectionFailure.Resolution(ConnectionFailure.ResolutionReason.TIMED_OUT),
        )
    }

    @Test
    fun trustMismatchRejectionAndApprovalTimeoutCannotBecomeAuthenticationFailure() {
        for (reason in ConnectionFailure.TrustReason.entries) {
            val failure = ConnectionFailure.Trust(reason)
            assertEquals(ConnectionFailure.Category.TRUST, failure.category)
            assertNotEquals(ConnectionFailure.Category.AUTHENTICATION, failure.category)
        }
        assertNotEquals(
            ConnectionFailure.Trust(ConnectionFailure.TrustReason.REJECTED),
            ConnectionFailure.Trust(ConnectionFailure.TrustReason.KEY_MISMATCH),
        )
    }

    @Test
    fun invalidPassphraseAndUnavailableKeyAreTypedWithoutExceptionOrSecretPayloads() {
        val passphrase = ConnectionFailure.Authentication(ConnectionFailure.AuthenticationReason.INVALID_PASSPHRASE)
        val key = ConnectionFailure.Authentication(ConnectionFailure.AuthenticationReason.KEY_UNAVAILABLE)
        assertNotEquals(passphrase, key)
        assertEquals(ConnectionFailure.Category.AUTHENTICATION, passphrase.category)
        assertEquals(ConnectionFailure.Category.AUTHENTICATION, key.category)
        assertEquals("Authentication(reason=INVALID_PASSPHRASE)", passphrase.toString())
    }

    @Test
    fun valueEqualitySupportsObservableStateDeduplication() {
        val equivalent = Endpoint.manual("DESKTOP.Local.", "02222").identity
        assertEquals(ConnectionState.Resolving(endpoint), ConnectionState.Resolving(equivalent))
        assertEquals(ConnectionState.Connected(endpoint), ConnectionState.Connected(equivalent))
        assertNotEquals(ConnectionState.AwaitingTrust(endpoint), ConnectionState.Authenticating(endpoint))
        assertNotEquals(ConnectionState.Disconnected(endpoint), ConnectionState.Connected(endpoint))
    }
}
