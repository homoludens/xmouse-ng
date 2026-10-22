package com.stripe1.xmouse.transport

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.IOException
import java.net.DatagramPacket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** RFC 6762 legacy-unicast queries from an ephemeral port; never joins a multicast group.
 * Owns only one lookup's socket, including close on setup failure. Lifecycle/lock hardening
 * and API 26 hardware verification remain task 6.5, not a persistent listener here.
 */
internal class AndroidMdnsSocket private constructor(
    private val socket: MulticastSocket,
    private val networkInterface: NetworkInterface,
    private val prefixes: List<Pair<ByteArray, Int>>,
    private val destinations: List<InetAddress>,
) : MdnsSocket {
    override fun send(query: ByteArray) {
        var sent = false
        var failure: IOException? = null
        for (destination in destinations) {
            try {
                socket.send(DatagramPacket(query, query.size, destination, QueryOnlyMdnsResolver.PORT))
                sent = true
            } catch (unavailable: IOException) {
                failure = unavailable // An unavailable family must not discard the other family's reply.
            }
        }
        if (!sent) throw failure ?: UnknownHostException()
    }

    override fun receive(timeoutMillis: Int): MdnsPacket? {
        socket.soTimeout = timeoutMillis
        // The extra byte detects oversized/truncated UDP payloads instead of accepting a prefix.
        val buffer = ByteArray(MdnsAddressCodec.MAX_PACKET_BYTES + 1)
        val packet = DatagramPacket(buffer, buffer.size)
        try { socket.receive(packet) } catch (_: SocketTimeoutException) { return null }
        if (packet.length > MdnsAddressCodec.MAX_PACKET_BYTES) return null
        return MdnsPacket(buffer.copyOf(packet.length), packet.address, packet.port)
    }

    override fun isOnLink(address: InetAddress): Boolean = prefixes.any { (prefix, bits) ->
        val candidate = address.address
        candidate.size == prefix.size && bits > 0 && candidate.indices.all { index ->
            val mask = (0xff shl (8 - (bits - index * 8).coerceIn(0, 8))) and 0xff
            candidate[index].toInt() and mask == prefix[index].toInt() and mask
        }
    }

    override fun scope(address: InetAddress): InetAddress? =
        if (address is Inet6Address && address.isLinkLocalAddress) {
            Inet6Address.getByAddress(null, address.address, networkInterface.index)
        } else address

    override fun close() = socket.close()

    companion object {
        fun open(connectivity: ConnectivityManager, network: Network): AndroidMdnsSocket {
            val capabilities = connectivity.getNetworkCapabilities(network) ?: throw UnknownHostException()
            // Never choose an underlying Wi-Fi network to route around the user's active VPN.
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            ) throw UnknownHostException()
            val properties = connectivity.getLinkProperties(network) ?: throw UnknownHostException()
            val name = properties.interfaceName ?: throw UnknownHostException()
            val iface = NetworkInterface.getByName(name) ?: throw UnknownHostException()
            if (iface.index <= 0 || !iface.isUp || iface.isLoopback) throw UnknownHostException()
            val prefixes = properties.linkAddresses.filter { MdnsAddressCodec.usable(it.address) }
                .map { it.address.address to it.prefixLength }
            val destinations = buildList {
                if (prefixes.any { it.first.size == 4 }) add(InetAddress.getByAddress(byteArrayOf(224.toByte(), 0, 0, 251.toByte())))
                if (prefixes.any { it.first.size == 16 }) {
                    val group = ByteArray(16).apply { this[0] = 0xff.toByte(); this[1] = 2; this[15] = 0xfb.toByte() }
                    add(Inet6Address.getByAddress(null, group, iface.index))
                }
            }
            if (destinations.isEmpty()) throw UnknownHostException()
            val socket = MulticastSocket(null)
            var handedOff = false
            try {
                socket.bind(InetSocketAddress(0))
                if (socket.localPort == QueryOnlyMdnsResolver.PORT) throw UnknownHostException()
                network.bindSocket(socket)
                socket.networkInterface = iface
                socket.timeToLive = 255
                val result = AndroidMdnsSocket(socket, iface, prefixes, destinations)
                handedOff = true
                return result
            } finally {
                if (!handedOff) socket.close()
            }
        }
    }
}
