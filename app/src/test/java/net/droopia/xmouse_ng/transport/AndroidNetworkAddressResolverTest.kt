package net.droopia.xmouse_ng.transport

import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import net.droopia.xmouse_ng.connection.Endpoint
import net.droopia.xmouse_ng.connection.EndpointLocator
import net.droopia.xmouse_ng.permissions.LocalNetworkPermissionCoordinator
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import org.xbill.DNS.Message
import org.xbill.DNS.Type
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketAddress
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.function.BooleanSupplier

class AndroidNetworkAddressResolverTest {
    private class Fixture {
        val connectivity = mock(ConnectivityManager::class.java)
        val network = mock(Network::class.java)
        var granted = true
        var current = true
        val permission = LocalNetworkPermissionCoordinator({ 37 }, { granted })
        val active = BooleanSupplier { current }
        init { `when`(connectivity.activeNetwork).thenReturn(network) }
        fun resolver(fallback: (Network, String, () -> Unit) -> Array<InetAddress>) =
            AndroidNetworkAddressResolver(connectivity, permission, fallback)
    }

    private class Packets : MdnsSocket {
        var now = 0L
        val queries = mutableListOf<Message>()
        val packets = ArrayDeque<MdnsPacket>()
        var onReceive: () -> Unit = {}
        var flood = false
        var closed = false
        var closeCount = 0
        var opened = 0
        var failSendAt = 0
        var sendCount = 0
        var receiveFailure: IOException? = null
        var closeFailure: IOException? = null
        val local = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 20))
        override fun send(query: ByteArray) {
            sendCount++
            if (sendCount == failSendAt) throw IOException("send failed")
            queries.add(Message(query))
        }
        override fun receive(timeoutMillis: Int): MdnsPacket? {
            assertTrue(timeoutMillis in 1..QueryOnlyMdnsResolver.POLL_MILLIS)
            now += TimeUnit.MILLISECONDS.toNanos(timeoutMillis.toLong())
            onReceive()
            receiveFailure?.let { throw it }
            return packets.removeFirstOrNull() ?: if (flood) packet(byteArrayOf(0)) else null
        }
        fun packet(bytes: ByteArray, port: Int = 5353, source: InetAddress = local) = MdnsPacket(bytes, source, port)
        override fun isOnLink(address: InetAddress) = address == local
        override fun scope(address: InetAddress): InetAddress = if (address is Inet6Address && address.isLinkLocalAddress) {
            Inet6Address.getByAddress(null, address.address, 7)
        } else address
        override fun close() {
            assertFalse(closed)
            closeCount++
            closed = true
            closeFailure?.let { throw it }
        }
        fun resolver() = QueryOnlyMdnsResolver({ opened++; this }, { now }, { MdnsPackets.ID })
    }

    @Test fun nativeAnswersWinAndKeepNetworkScopeAndAttemptLocalIdentity() {
        val f = Fixture()
        val scoped = Inet6Address.getByAddress(null, MdnsPackets.address(Type.AAAA).address, 7)
        `when`(f.network.getAllByName("desktop.local")).thenReturn(arrayOf(scoped, scoped))
        val resolver = f.resolver { _, _, _ -> fail("native success must not multicast"); emptyArray() }
        val resolution = resolver.resolve("desktop.local", f.active)
        assertEquals(listOf(scoped), resolution.candidates())
        assertEquals(7, (resolution.candidates().single() as Inet6Address).scopeId)
        Socket().use { socket -> resolution.bind(socket); verify(f.network).bindSocket(socket) }
        `when`(f.connectivity.activeNetwork).thenReturn(mock(Network::class.java))
        assertFalse(resolution.isCurrent)
        assertThrows(SshAddressResolver.NetworkChangedException::class.java) { resolution.checkCurrent() }
    }

    @Test fun savedServiceLocatorUsesFreshCurrentNetworkCandidatesAndRejectsStaleRoutes() {
        val f = Fixture()
        `when`(f.network.networkHandle).thenReturn(73L)
        val locator = EndpointLocator.DiscoveredService("Office SSH", "_ssh._tcp", "local")
        val endpoint = Endpoint(locator, 2222)
        val snapshots = ArrayDeque(listOf(
            listOf(InetAddress.getByName("192.0.2.20")),
            listOf(InetAddress.getByName("192.0.2.99"), InetAddress.getByName("2001:db8::9")),
        ))
        var lookups = 0
        val serviceLookup = SshServiceCandidateLookup { requested, networkIdentity, current, checkCurrent ->
            assertEquals(locator, requested)
            assertEquals("73", networkIdentity)
            assertTrue(current.asBoolean)
            checkCurrent()
            lookups++
            snapshots.removeFirst()
        }
        val resolver = AndroidNetworkAddressResolver(f.connectivity, f.permission, serviceLookup = serviceLookup)

        val first = resolver.resolve(endpoint, locator.instanceName, f.active)
        assertEquals(listOf("192.0.2.20"), first.candidates().map { it.hostAddress })
        val second = resolver.resolve(endpoint, locator.instanceName, f.active)
        assertEquals(listOf("192.0.2.99", "2001:db8:0:0:0:0:0:9"), second.candidates().map { it.hostAddress })
        assertEquals(2, lookups)
        assertEquals(2222, endpoint.port) // The advertisement's changing port never replaces saved trust identity.

        `when`(f.connectivity.activeNetwork).thenReturn(mock(Network::class.java))
        assertFalse(second.isCurrent)
        assertThrows(SshAddressResolver.NetworkChangedException::class.java) { second.checkCurrent() }
    }

    @Test fun serviceLookupPermissionAndCancellationAreCheckedBeforeCandidatesAreAccepted() {
        val f = Fixture()
        val locator = EndpointLocator.DiscoveredService("Office SSH", "_ssh._tcp", "local")
        val endpoint = Endpoint(locator, 22)
        var lookups = 0
        val resolver = AndroidNetworkAddressResolver(f.connectivity, f.permission,
            serviceLookup = SshServiceCandidateLookup { _, _, current, _ ->
                lookups++
                if (!current.asBoolean) throw CancellationException()
                listOf(InetAddress.getByName("192.0.2.20"))
            })

        f.granted = false
        assertThrows(SshAddressResolver.AccessDeniedException::class.java) {
            resolver.resolve(endpoint, locator.instanceName, f.active)
        }
        assertEquals(0, lookups)

        f.granted = true
        f.current = false
        assertThrows(CancellationException::class.java) {
            resolver.resolve(endpoint, locator.instanceName, f.active)
        }
        assertEquals(0, lookups)
    }

    @Test fun nativeLocalFailureUsesOnlyAAndAaaaWithoutAnSshAdvertisementAndPreservesTrustName() {
        val f = Fixture()
        val packets = Packets()
        `when`(f.network.getAllByName("Desktop.local.")).thenThrow(UnknownHostException())
        packets.packets.add(packets.packet(MdnsPackets.response(owner = "other.local.", compressed = false)))
        packets.packets.add(packets.packet(byteArrayOf(0)))
        packets.packets.add(packets.packet(MdnsPackets.response(), port = 53))
        packets.packets.add(packets.packet(MdnsPackets.response(), source = InetAddress.getByAddress(byteArrayOf(203.toByte(), 0, 113, 1))))
        packets.packets.add(packets.packet(MdnsPackets.response()))
        packets.packets.add(packets.packet(MdnsPackets.response(Type.AAAA, id = MdnsPackets.ID + 1)))
        packets.onReceive = {
            if (packets.now == TimeUnit.MILLISECONDS.toNanos(QueryOnlyMdnsResolver.TIMEOUT_MILLIS.toLong())) {
                packets.packets.add(packets.packet(MdnsPackets.response())) // Late answer cannot erase earlier results.
            }
        }
        val resolver = f.resolver { network, host, guard ->
            assertSame(f.network, network)
            packets.resolver().resolve(host, guard)
        }
        val endpoint = Endpoint.manual("Desktop.local.", 2222)
        val request = SshConnectionRequest(endpoint, "Desktop.local.", "", null)
        val backend = JschSshBackend({ _, _, _ -> }, resolver)
        val resolved = backend.resolve(request, f.active)
        assertSame(endpoint, resolved.endpoint)
        assertEquals("Desktop.local.", resolved.address)
        assertEquals(request.endpoint.identity, resolved.endpoint.identity)
        assertEquals(2, resolved.resolvedAddresses.size)
        assertEquals(7, (resolved.resolvedAddresses[1] as Inet6Address).scopeId)
        assertTrue(packets.closed)
        assertEquals(1, packets.opened)
        assertEquals(1, packets.closeCount)
        assertEquals(listOf(Type.A, Type.AAAA), packets.queries.map { it.question.type })
        assertTrue(packets.queries.all {
            it.question.name == MdnsPackets.name && it.question.dClass == 1 &&
                it.toWire().slice(2..3).all { flag -> flag == 0.toByte() }
        })
    }

    @Test fun fallbackNeverHandlesOrdinaryDnsIpOrNonResolutionFailures() {
        val f = Fixture()
        val resolver = f.resolver { _, _, _ -> fail("ineligible fallback"); emptyArray() }
        for (host in listOf("example.org", "desktop.local.evil", "local", "192.0.2.1", "fe80::1%7")) {
            `when`(f.network.getAllByName(host)).thenThrow(UnknownHostException())
            assertThrows(UnknownHostException::class.java) { resolver.resolve(host, f.active) }
        }
        `when`(f.network.getAllByName("desktop.local")).thenThrow(SecurityException())
        assertThrows(SecurityException::class.java) { resolver.resolve("desktop.local", f.active) }
        val packets = Packets()
        assertThrows(UnknownHostException::class.java) { packets.resolver().resolve("example.org") {} }
        assertEquals(0, packets.opened)
    }

    @Test fun silenceAndMalformedFloodShareOneFiniteDeadlineAndCloseTemporaryResources() {
        for (flood in listOf(false, true)) {
            val packets = Packets().also { it.flood = flood }
            assertThrows(SshAddressResolver.ResolutionTimeoutException::class.java) { packets.resolver().resolve("desktop.local") {} }
            assertEquals(TimeUnit.MILLISECONDS.toNanos(QueryOnlyMdnsResolver.TIMEOUT_MILLIS.toLong()), packets.now)
            assertEquals(2, packets.queries.size)
            assertTrue(packets.closed)
            assertEquals(1, packets.opened)
            assertEquals(1, packets.closeCount)
        }
        // The production SSH owner must not relabel this DNS expiry as a TCP/SSH timeout.
        val f = Fixture()
        val packets = Packets()
        `when`(f.network.getAllByName("desktop.local")).thenThrow(UnknownHostException())
        val resolver = f.resolver { _, host, guard -> packets.resolver().resolve(host, guard) }
        val backend = JschSshBackend({ _, _, _ -> fail("DNS failure must not start SSH") }, resolver)
        val worker = SshTransport.newWorker()
        val transport = SshTransport(backend, worker)
        try {
            val result = SshDeadlineTest.Result<Void>()
            transport.connect(SshConnectionRequest(Endpoint.manual("desktop.local", 22), "desktop.local", "", null),
                RecordingSshBackend.authentication(), result)
            assertEquals(SshTransport.Failure.RESOLUTION_TIMEOUT, result.await().failure)
            assertFalse(transport.isConnected)
            assertTrue(packets.closed)
        } finally {
            transport.close()
            assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    @Test fun fallbackClosesExactlyOnceOnSendReceiveAndCloseFailures() {
        for (sendFailure in listOf(1, 2)) {
            val packets = Packets().also { it.failSendAt = sendFailure }
            assertThrows(IOException::class.java) { packets.resolver().resolve("desktop.local") {} }
            assertTrue(packets.closed)
            assertEquals(1, packets.closeCount)
        }

        val receiveFailure = Packets().also { it.receiveFailure = IOException("receive failed") }
        assertThrows(IOException::class.java) { receiveFailure.resolver().resolve("desktop.local") {} }
        assertTrue(receiveFailure.closed)
        assertEquals(1, receiveFailure.closeCount)

        val closeFailure = Packets().also {
            it.failSendAt = 1
            it.closeFailure = IOException("close failed")
        }
        val sendError = assertThrows(IOException::class.java) {
            closeFailure.resolver().resolve("desktop.local") {}
        }
        assertEquals("send failed", sendError.message)
        assertEquals(1, sendError.suppressed.size)
        assertEquals("close failed", sendError.suppressed.single().message)
        assertTrue(closeFailure.closed)
        assertEquals(1, closeFailure.closeCount)
    }

    @Test fun deniedNetworkPoliciesDoNotAllocateFallbackSockets() {
        val policies = listOf(
            Triple(false, false, true), // VPN without NOT_VPN capability
            Triple(true, true, true), // VPN transport with an underlying Wi-Fi-like route
            Triple(true, false, false), // Cellular/non-local transport
        )
        for ((notVpn, vpnTransport, wifiTransport) in policies) {
            val connectivity = mock(ConnectivityManager::class.java)
            val network = mock(Network::class.java)
            val capabilities = mock(NetworkCapabilities::class.java)
            `when`(connectivity.getNetworkCapabilities(network)).thenReturn(capabilities)
            `when`(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)).thenReturn(notVpn)
            `when`(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)).thenReturn(vpnTransport)
            `when`(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)).thenReturn(wifiTransport)
            `when`(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)).thenReturn(false)
            var socketAllocations = 0

            assertThrows(UnknownHostException::class.java) {
                AndroidMdnsSocket.open(connectivity, network, socketFactory = {
                    socketAllocations++
                    MulticastSocket(null)
                })
            }
            assertEquals(0, socketAllocations)
        }
    }

    @Test fun socketSetupFailureClosesTheAllocatedSocketOnce() {
        val connectivity = mock(ConnectivityManager::class.java)
        val network = mock(Network::class.java)
        val capabilities = mock(NetworkCapabilities::class.java)
        val linkProperties = mock(LinkProperties::class.java)
        val linkAddress = mock(LinkAddress::class.java)
        val networkInterface = mock(NetworkInterface::class.java)
        `when`(connectivity.getNetworkCapabilities(network)).thenReturn(capabilities)
        `when`(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)).thenReturn(true)
        `when`(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)).thenReturn(false)
        `when`(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)).thenReturn(true)
        `when`(connectivity.getLinkProperties(network)).thenReturn(linkProperties)
        `when`(linkProperties.interfaceName).thenReturn("test0")
        `when`(linkProperties.linkAddresses).thenReturn(listOf(linkAddress))
        `when`(linkAddress.address).thenReturn(InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 1)))
        `when`(linkAddress.prefixLength).thenReturn(24)
        `when`(networkInterface.index).thenReturn(7)
        `when`(networkInterface.isUp).thenReturn(true)
        `when`(networkInterface.isLoopback).thenReturn(false)
        val socket = object : MulticastSocket(null) {
            var closeCount = 0
            override fun bind(bindpoint: SocketAddress?) { throw IOException("bind failed") }
            override fun close() {
                closeCount++
                super.close()
            }
        }

        assertThrows(IOException::class.java) {
            AndroidMdnsSocket.open(
                connectivity,
                network,
                socketFactory = { socket },
                interfaceLookup = { name ->
                    assertEquals("test0", name)
                    networkInterface
                },
            )
        }
        assertTrue(socket.isClosed)
        assertEquals(1, socket.closeCount)
    }

    @Test fun cancellationRevocationAndNetworkChangesCannotPublishLateFallbackAnswers() {
        for (reason in listOf("cancel", "permission", "network")) {
            val f = Fixture()
            val packets = Packets()
            `when`(f.network.getAllByName("desktop.local")).thenThrow(UnknownHostException())
            if (reason != "cancel") packets.packets.add(packets.packet(MdnsPackets.response()))
            packets.onReceive = {
                when (reason) {
                    "cancel" -> f.current = false
                    "permission" -> f.granted = false
                    else -> `when`(f.connectivity.activeNetwork).thenReturn(mock(Network::class.java))
                }
            }
            val resolver = f.resolver { _, host, guard -> packets.resolver().resolve(host, guard) }
            val expected = when (reason) {
                "cancel" -> CancellationException::class.java
                "permission" -> SshAddressResolver.AccessDeniedException::class.java
                else -> SshAddressResolver.NetworkChangedException::class.java
            }
            assertThrows(expected) { resolver.resolve("desktop.local", f.active) }
            assertTrue(packets.closed)
            assertEquals(1, packets.closeCount)
            // The same gate prevents a subsequent forbidden lookup from opening any socket.
            var opened = false
            val guarded = f.resolver { _, _, _ -> opened = true; emptyArray() }
            if (reason != "network") assertThrows(expected) { guarded.resolve("desktop.local", f.active) }
            assertFalse(opened)
        }
    }
}
