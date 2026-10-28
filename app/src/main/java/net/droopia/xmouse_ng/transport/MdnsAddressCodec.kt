package net.droopia.xmouse_ng.transport

import net.droopia.xmouse_ng.connection.EndpointHost
import org.xbill.DNS.AAAARecord
import org.xbill.DNS.ARecord
import org.xbill.DNS.DClass
import org.xbill.DNS.DNSInput
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.Record
import org.xbill.DNS.Section
import org.xbill.DNS.Type
import java.io.IOException
import java.net.InetAddress
import java.nio.ByteBuffer

/** Restricted address-only codec boundary; see docs/verification/mdns-fallback.md for its review. */
internal object MdnsAddressCodec {
    const val MAX_PACKET_BYTES = 2048
    const val MAX_RECORDS = 32
    const val MAX_POINTERS = 8
    val TYPES = listOf(Type.A, Type.AAAA)

    fun localName(host: String): Name? = try {
        val parsed = EndpointHost(host)
        if (parsed.kind == EndpointHost.Kind.DNS && parsed.normalized.endsWith(".local")) {
            Name.fromString(parsed.normalized + ".")
        } else null
    } catch (_: IllegalArgumentException) {
        null
    }

    fun query(name: Name, type: Int, id: Int): ByteArray = Message(id).apply {
        addRecord(Record.newRecord(name, type, DClass.IN), Section.QUESTION)
    }.toWire()

    /** Legacy-unicast responses must echo the ID and single question (RFC 6762 section 6.7).
     * No CNAME following, generic RDATA parsing, referrals, or partial/truncated answers.
     */
    fun answers(packet: ByteArray, name: Name, type: Int, id: Int): List<InetAddress> = try {
        decode(packet, name, type, id)
    } catch (_: IOException) {
        emptyList()
    } catch (_: IllegalArgumentException) {
        emptyList()
    }

    private fun decode(packet: ByteArray, name: Name, type: Int, id: Int): List<InetAddress> {
        require(packet.size in 12..MAX_PACKET_BYTES && type in TYPES)
        val buffer = ByteBuffer.wrap(packet)
        val input = DNSInput(buffer)
        require(input.readU16() == id)
        // QR + AA only. Reject TC, non-QUERY opcodes, errors, and reserved/unicast DNS flags.
        require(input.readU16() == 0x8400)
        require(input.readU16() == 1)
        val answers = input.readU16()
        val authority = input.readU16()
        val additional = input.readU16()
        require(answers + authority + additional <= MAX_RECORDS)
        val names = NameLimits(packet)
        require(names.read(input) == name && input.readU16() == type && input.readU16() == DClass.IN)
        val found = LinkedHashSet<InetAddress>()
        repeat(answers + authority + additional) { index ->
            val owner = names.read(input)
            val recordType = input.readU16()
            val recordClass = input.readU16()
            val ttl = input.readU32()
            val length = input.readU16()
            require(length <= input.remaining())
            if (recordType == Type.A) require(length == 4)
            if (recordType == Type.AAAA) require(length == 16)
            if (index < answers && owner == name && recordType == type &&
                recordClass and 0x7fff == DClass.IN && ttl > 0
            ) {
                // Only these two fixed-size record parsers can be invoked by untrusted input.
                val record = Record.newRecord(owner, type, DClass.IN, ttl, input.readByteArray(length))
                val address = when (record) {
                    is ARecord -> record.address
                    is AAAARecord -> record.address
                    else -> null
                }
                if (address != null && address.address.size == length && usable(address) &&
                    found.size < SshAddressResolver.MAX_CANDIDATES
                ) found.add(address)
            } else {
                // Opaque unsupported RDATA: never enter its DNS type parser or follow its names.
                buffer.position(buffer.position() + length)
            }
        }
        require(input.remaining() == 0) // A malformed tail invalidates the whole datagram.
        return found.toList()
    }

    fun usable(address: InetAddress): Boolean = !address.isAnyLocalAddress &&
        !address.isLoopbackAddress && !address.isMulticastAddress &&
        !(address.address.size == 4 && (address.address[0].toInt() and 0xff == 0 ||
            address.address.all { it == 0xff.toByte() }))

    /** Iterative structural guard, NOT a name decoder. dnsjava does the actual name decoding.
     * Reject unvalidated pointer targets (including opaque RDATA), loops, and excessive work
     * before the library can traverse them. All offsets are relative to the complete packet.
     */
    private class NameLimits(private val packet: ByteArray) {
        private val boundaries = BooleanArray(packet.size)

        fun read(input: DNSInput): Name {
            var position = input.current()
            var pointers = 0
            var expanded = 1 // Root label.
            val visited = BooleanArray(packet.size)
            while (true) {
                require(position in packet.indices && !visited[position])
                visited[position] = true
                boundaries[position] = true
                val label = packet[position].toInt() and 0xff
                if (label == 0) break
                if (label and 0xc0 == 0xc0) {
                    require(position + 1 < packet.size && ++pointers <= MAX_POINTERS)
                    val target = ((label and 0x3f) shl 8) or (packet[position + 1].toInt() and 0xff)
                    require(target < position && boundaries[target])
                    position = target
                } else {
                    require(label in 1..63 && position + 1 + label < packet.size)
                    expanded += 1 + label
                    require(expanded <= 255)
                    position += 1 + label
                }
            }
            return Name(input)
        }
    }
}
