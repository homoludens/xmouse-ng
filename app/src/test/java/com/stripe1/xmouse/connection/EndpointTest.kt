package com.stripe1.xmouse.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class EndpointTest {
    @Test
    fun manualEndpointRetainsOriginalHostAndValidatedPort() {
        val endpoint = Endpoint.manual(" Desktop.Local. ", " 00022 ")
        assertEquals(" Desktop.Local. ", (endpoint.locator as EndpointLocator.Manual).host.original)
        assertEquals(22, endpoint.port)
        assertEquals("desktop.local", (endpoint.identity as EndpointIdentity.Host).host)
    }

    @Test
    fun minimumMaximumAndNonDefaultPortsAreAccepted() {
        for (port in listOf(1, 22, 2222, 65535)) {
            assertEquals(port, Endpoint.manual("desktop.local", port).port)
            assertEquals(port, Endpoint.parsePort(port.toString()))
        }
    }

    @Test
    fun invalidIntegerPortsAreRejectedIncludingOnCopy() {
        for (port in listOf(Int.MIN_VALUE, -1, 0, 65536, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { Endpoint.manual("desktop.local", port) }
            assertThrows(IllegalArgumentException::class.java) { Endpoint.manual("desktop.local", 22).copy(port = port) }
        }
    }

    @Test
    fun blankOutOfRangeAndNonDecimalPortInputIsRejected() {
        for (input in listOf("", " ", "0", "65536", "9999999999999999999", "-22", "+22", "22.0", "2 2", "0x16", "２２")) {
            assertThrows("Invalid port: $input", IllegalArgumentException::class.java) { Endpoint.manual("desktop.local", input) }
        }
    }

    @Test
    fun normalizedHostAndPortDetermineManualIdentityNotDisplaySpelling() {
        val first = Endpoint.manual("DESKTOP.Local.", "00022")
        val second = Endpoint.manual("desktop.local", 22)
        assertNotEquals(first.locator, second.locator)
        assertEquals(first.identity, second.identity)
        assertEquals(first.identity.hashCode(), second.identity.hashCode())
    }

    @Test
    fun portAndIntendedHostChangesRequireDifferentIdentities() {
        val original = Endpoint.manual("desktop.local", 22).identity
        assertNotEquals(original, Endpoint.manual("desktop.local", 2222).identity)
        assertNotEquals(original, Endpoint.manual("other.local", 22).identity)
        assertNotEquals(original, Endpoint.manual("192.0.2.1", 22).identity)
        assertNotEquals(Endpoint.manual("192.0.2.1", 22).identity, Endpoint.manual("192.0.2.2", 22).identity)
    }

    @Test
    fun ipv6EquivalentSpellingsHaveTheSameIdentity() {
        assertEquals(
            Endpoint.manual("[2001:DB8::1]", 22).identity,
            Endpoint.manual("2001:db8:0:0:0:0:0:1", 22).identity,
        )
    }

    @Test
    fun manuallyEnteredIpv6ScopesAreNotConflated() {
        assertEquals(Endpoint.manual("fe80::1%003", 22).identity, Endpoint.manual("fe80::1%3", 22).identity)
        assertNotEquals(Endpoint.manual("fe80::1%eth0", 22).identity, Endpoint.manual("fe80::1%eth1", 22).identity)
        assertNotEquals(Endpoint.manual("fe80::1%eth0", 22).identity, Endpoint.manual("fe80::1%ETH0", 22).identity)
        assertNotEquals(Endpoint.manual("fe80::1", 22).identity, Endpoint.manual("fe80::1%3", 22).identity)
    }

    @Test
    fun addressOnlyNsdDataRetainsServiceLocatorWithoutFabricatingHostname() {
        val locator = service("Office workstation", "_ssh._tcp.", "local.")
        val endpoint = Endpoint(locator, 2222)
        assertNull(locator.stableTargetHostname)
        val identity = endpoint.identity as EndpointIdentity.Service
        assertEquals("office workstation", identity.instanceName)
        assertEquals("_ssh._tcp", identity.serviceType)
        assertEquals("local", identity.domain)
        assertEquals(2222, identity.port)
        assertNotEquals(Endpoint.manual("192.0.2.1", 2222).identity, identity)
    }

    @Test
    fun serviceIdentityNormalizesAsciiCaseTypeAndDomainRootDot() {
        val first = Endpoint(service("LINUX Desktop", "_SSH._TCP.", "LOCAL."), 22)
        val second = Endpoint(service("linux desktop", "_ssh._tcp", "local"), 22)
        assertEquals("LINUX Desktop", (first.locator as EndpointLocator.DiscoveredService).instanceName)
        assertEquals(first.identity, second.identity)
        assertEquals(first.identity.hashCode(), second.identity.hashCode())
    }

    @Test
    fun serviceInstanceIsALiteralUnicodeLabelNotAConcatenatedDnsName() {
        val locator = service("Büro . Linux", "_ssh._tcp", "local")
        assertEquals("büro . linux", (Endpoint(locator, 22).identity as EndpointIdentity.Service).instanceName)
        assertNotEquals(
            Endpoint(service("Écran", "_ssh._tcp", "local"), 22).identity,
            Endpoint(service("écran", "_ssh._tcp", "local"), 22).identity,
        )
    }

    @Test
    fun serviceTupleComponentsAndPortRemainDistinct() {
        val original = Endpoint(service("Office", "_ssh._tcp", "local"), 22).identity
        assertNotEquals(original, Endpoint(service("Other", "_ssh._tcp", "local"), 22).identity)
        assertNotEquals(original, Endpoint(service("Office", "_ssh._tcp", "example"), 22).identity)
        assertNotEquals(original, Endpoint(service("Office", "_ssh._tcp", "local"), 2222).identity)
        assertNotEquals(
            Endpoint(service("Office.local", "_ssh._tcp", "example"), 22).identity,
            Endpoint(service("Office", "_ssh._tcp", "local.example"), 22).identity,
        )
    }

    @Test
    fun advertisedStableTargetUsesSameHostIdentityAsManualEntry() {
        val locator = service("Office workstation", "_ssh._tcp", "local", "DESKTOP.Local.")
        assertEquals("DESKTOP.Local.", locator.stableTargetHostname?.original)
        assertEquals(Endpoint.manual("desktop.local", 2222).identity, Endpoint(locator, 2222).identity)
    }

    @Test
    fun changingOrRemovingSavedTargetChangesIdentityRatherThanInheritingTrust() {
        val original = Endpoint(service("Office", "_ssh._tcp", "local", "desktop.local"), 22).identity
        assertNotEquals(original, Endpoint(service("Office", "_ssh._tcp", "local", "other.local"), 22).identity)
        assertNotEquals(original, Endpoint(service("Office", "_ssh._tcp", "local"), 22).identity)
    }

    @Test
    fun resolvedNumericAddressesCannotBePresentedAsStableServiceHostnames() {
        for (target in listOf("192.0.2.1", "2001:db8::1", "fe80::1%3", "")) {
            assertThrows(IllegalArgumentException::class.java) { service("Office", "_ssh._tcp", "local", target) }
        }
    }

    @Test
    fun invalidOrNonSshServiceMetadataIsRejected() {
        for (instance in listOf("", " ", "Office\nLinux", "a".repeat(64), "é".repeat(32))) {
            assertThrows(IllegalArgumentException::class.java) { service(instance, "_ssh._tcp", "local") }
        }
        for (type in listOf("", "_http._tcp", "_ssh._udp", "_ssh._tcp.local.", " _ssh._tcp")) {
            assertThrows(IllegalArgumentException::class.java) { service("Office", type, "local") }
        }
        for (domain in listOf("", ".", "local..", " local", "192.0.2.1", "::1")) {
            assertThrows(IllegalArgumentException::class.java) { service("Office", "_ssh._tcp", domain) }
        }
    }

    @Test
    fun manualIdentitySurvivesFreshDhcpIpv4Ipv6AndNetworkObservations() {
        val first = Observation(Endpoint.manual("DESKTOP.local.", 22), listOf("192.0.2.1"), 10)
        val next = Observation(Endpoint.manual("desktop.local", 22), listOf("192.0.2.99", "fe80::1%8"), 20)
        assertChangedObservationsKeepIdentity(first, next)
    }

    @Test
    fun serviceOnlyIdentitySurvivesAddressAndNetworkChanges() {
        val first = Observation(Endpoint(service("Office", "_ssh._tcp", "local"), 2222), listOf("192.0.2.1"), 10)
        val next = Observation(Endpoint(service("Office", "_ssh._tcp", "local"), 2222), listOf("2001:db8::2"), 20)
        assertChangedObservationsKeepIdentity(first, next)
    }

    @Test
    fun advertisedTargetIdentitySurvivesDhcpAndNetworkChanges() {
        val first = Observation(Endpoint(service("Office", "_ssh._tcp", "local", "desktop.local"), 22), listOf("192.0.2.1"), 10)
        val next = Observation(Endpoint(service("Office", "_ssh._tcp", "local", "desktop.local"), 22), listOf("192.0.2.2"), 20)
        assertChangedObservationsKeepIdentity(first, next)
    }

    @Test
    fun identityConstructorsAndCopiesCannotBypassCanonicalizationOrPortValidation() {
        val host = Endpoint.manual("desktop.local", 22).identity as EndpointIdentity.Host
        assertThrows(IllegalArgumentException::class.java) { host.copy(host = "DESKTOP.local.") }
        assertThrows(IllegalArgumentException::class.java) { host.copy(port = 0) }
        val discovered = Endpoint(service("Office", "_ssh._tcp", "local"), 22).identity as EndpointIdentity.Service
        assertThrows(IllegalArgumentException::class.java) { discovered.copy(instanceName = "OFFICE") }
        assertThrows(IllegalArgumentException::class.java) { discovered.copy(serviceType = "_http._tcp") }
        assertThrows(IllegalArgumentException::class.java) { discovered.copy(domain = "LOCAL.") }
        assertThrows(IllegalArgumentException::class.java) { discovered.copy(port = 65536) }
    }

    private fun service(instance: String, type: String, domain: String, target: String? = null) =
        EndpointLocator.DiscoveredService(instance, type, domain, target?.let(::EndpointHost))

    // Test-only observations make explicit that neither IPs nor Android network handles enter saved values.
    private data class Observation(val endpoint: Endpoint, val addresses: List<String>, val networkHandle: Long)

    private fun assertChangedObservationsKeepIdentity(first: Observation, next: Observation) {
        assertNotEquals(first.addresses, next.addresses)
        assertNotEquals(first.networkHandle, next.networkHandle)
        assertEquals(first.endpoint.identity, next.endpoint.identity)
    }
}
