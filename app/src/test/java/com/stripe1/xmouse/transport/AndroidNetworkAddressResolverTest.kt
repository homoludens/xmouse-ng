package com.stripe1.xmouse.transport

import android.net.ConnectivityManager
import android.net.Network
import com.stripe1.xmouse.connection.Endpoint
import com.stripe1.xmouse.permissions.LocalNetworkPermissionCoordinator
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import org.xbill.DNS.Message
import org.xbill.DNS.Type
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Socket
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
        var opened = 0
        val local = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 20))
        override fun send(query: ByteArray) { queries.add(Message(query)) }
        override fun receive(timeoutMillis: Int): MdnsPacket? {
            assertTrue(timeoutMillis in 1..QueryOnlyMdnsResolver.POLL_MILLIS)
            now += TimeUnit.MILLISECONDS.toNanos(timeoutMillis.toLong())
            onReceive()
            return packets.removeFirstOrNull() ?: if (flood) packet(byteArrayOf(0)) else null
        }
        fun packet(bytes: ByteArray, port: Int = 5353, source: InetAddress = local) = MdnsPacket(bytes, source, port)
        override fun isOnLink(address: InetAddress) = address == local
        override fun scope(address: InetAddress): InetAddress = if (address is Inet6Address && address.isLinkLocalAddress) {
            Inet6Address.getByAddress(null, address.address, 7)
        } else address
        override fun close() { assertFalse(closed); closed = true }
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

    @Test fun cancellationRevocationAndNetworkChangesCannotPublishLateFallbackAnswers() {
        for (reason in listOf("cancel", "permission", "network")) {
            val f = Fixture()
            val packets = Packets()
            `when`(f.network.getAllByName("desktop.local")).thenThrow(UnknownHostException())
            packets.packets.add(packets.packet(MdnsPackets.response()))
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
            // The same gate prevents a subsequent forbidden lookup from opening any socket.
            var opened = false
            val guarded = f.resolver { _, _, _ -> opened = true; emptyArray() }
            if (reason != "network") assertThrows(expected) { guarded.resolve("desktop.local", f.active) }
            assertFalse(opened)
        }
    }
}
