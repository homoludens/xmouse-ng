package net.droopia.xmouse_ng.permissions

import net.droopia.xmouse_ng.connection.ConnectionFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalNetworkPermissionCoordinatorTest {
    @Test
    fun permissionPolicyCoversOlderOsGrantDenialAndRevocation() {
        var apiLevel = 36
        var granted = false
        val coordinator = LocalNetworkPermissionCoordinator({ apiLevel }, { granted })

        assertEquals(LocalNetworkPermissionCoordinator.AccessState.NOT_REQUIRED, coordinator.state())
        assertNull("earlier Android versions must not request this unsupported permission", coordinator.accessFailure())

        apiLevel = LocalNetworkPermissionCoordinator.REQUIRED_API
        assertEquals(LocalNetworkPermissionCoordinator.AccessState.REQUEST_REQUIRED, coordinator.state())
        assertEquals(
            ConnectionFailure.Permission(ConnectionFailure.PermissionReason.DENIED),
            coordinator.accessFailure(),
        )

        granted = true
        assertEquals(LocalNetworkPermissionCoordinator.AccessState.GRANTED, coordinator.state())
        assertNull(coordinator.accessFailure())

        granted = false // The same read-only gate observes revocation; the live attempt classifies it as REVOKED.
        assertEquals(LocalNetworkPermissionCoordinator.AccessState.REQUEST_REQUIRED, coordinator.state())
        assertEquals(
            ConnectionFailure.Permission(ConnectionFailure.PermissionReason.DENIED),
            coordinator.accessFailure(),
        )
    }
}
