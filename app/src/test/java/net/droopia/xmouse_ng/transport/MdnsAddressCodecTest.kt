package net.droopia.xmouse_ng.transport

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.xbill.DNS.Name
import org.xbill.DNS.Type
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetAddress

/** One packet suite, not a cross-product of address families and every malformed field. */
@RunWith(Parameterized::class)
class MdnsAddressCodecTest(private val label: String, private val packets: List<ByteArray>, private val expected: Int, private val type: Int) {
    @Test fun boundedAddressPacket() {
        for (packet in packets) {
            val addresses = MdnsAddressCodec.answers(packet, MdnsPackets.name, type, MdnsPackets.ID)
            assertEquals(label, expected, addresses.size)
            if (expected > 0) assertArrayEquals(MdnsPackets.address(type).address, addresses.first().address)
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun cases(): List<Array<Any>> {
            fun case(label: String, packet: ByteArray, expected: Int = 0, type: Int = Type.A) =
                arrayOf<Any>(label, listOf(packet), expected, type)
            fun rejected(label: String, vararg packets: ByteArray) = arrayOf<Any>(label, packets.toList(), 0, Type.A)
            val valid = MdnsPackets.response()
            val start = 12 + MdnsPackets.name.toWire().size + 4
            fun changed(offset: Int, value: Int) = valid.copyOf().apply { this[offset] = value.toByte() }
            return listOf(
                case("A compressed owner and cache-flush IN", valid, 1),
                case("AAAA uncompressed and case insensitive", MdnsPackets.response(Type.AAAA, compressed = false, owner = "DESKTOP.LOCAL."), 1, Type.AAAA),
                case("eight compression hops", MdnsPackets.chain(8), 1),
                case("compression depth limit", MdnsPackets.chain(9)),
                rejected("loops forward and invalid-boundary pointers",
                    changed(start + 1, start), changed(start + 1, start + 2), changed(start + 1, 13)),
                case("unsupported extended label", changed(start, 0x40)),
                case("expanded name exceeds 255 bytes", MdnsPackets.response(ownerWire = ByteArrayOutputStream().apply {
                    repeat(4) { write(63); write(ByteArray(63) { 'x'.code.toByte() }) }; write(0)
                }.toByteArray())),
                case("packet byte limit", valid.copyOf(MdnsAddressCodec.MAX_PACKET_BYTES + 1)),
                rejected("bounded counts and question count", changed(7, 33), changed(5, 2), changed(9, 255)),
                rejected("truncated header or RDATA", valid.copyOf(8), valid.copyOf(valid.size - 1)),
                case("invalid A length", changed(start + 11, 5)),
                case("invalid AAAA length", MdnsPackets.response(Type.AAAA).dropLast(1).toByteArray().apply { this[start + 11] = 15 }, type = Type.AAAA),
                case("malformed tail cannot publish earlier answer", valid + byteArrayOf(0)),
                case("unrelated owner", MdnsPackets.response(owner = "other.local.", compressed = false)),
                rejected("question name type and class must match",
                    MdnsPackets.response(question = "another.local."),
                    MdnsPackets.response(questionType = Type.AAAA),
                    MdnsPackets.response(questionClass = 3)),
                case("transaction ID must match", changed(1, 0x35)),
                rejected("truncation errors and query packets", changed(2, 0x86), changed(3, 3), changed(2, 4)),
                rejected("non-IN goodbye and non-unicast answers",
                    MdnsPackets.response(recordClass = 3), MdnsPackets.response(ttl = 0),
                    MdnsPackets.response(addresses = listOf(InetAddress.getByAddress(byteArrayOf(224.toByte(), 0, 0, 1))))),
                case("candidate bound", MdnsPackets.response(addresses = List(10) { index ->
                    InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, (10 + index).toByte()))
                }), SshAddressResolver.MAX_CANDIDATES),
            )
        }
    }
}

/** Synthetic bare-hostname packets only: no PTR/SRV/TXT or _ssh._tcp advertisement. */
internal object MdnsPackets {
    const val ID = 0x1234
    val name: Name = Name.fromString("desktop.local.")
    fun address(type: Int): InetAddress = InetAddress.getByAddress(
        if (type == Type.A) byteArrayOf(192.toByte(), 0, 2, 10)
        else byteArrayOf(0xfe.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 10),
    )

    fun response(
        type: Int = Type.A, id: Int = ID, compressed: Boolean = true,
        owner: String = "desktop.local.", ownerWire: ByteArray? = null,
        question: String = "desktop.local.", questionType: Int = type, questionClass: Int = 1,
        recordClass: Int = 0x8001, ttl: Int = 10,
        addresses: List<InetAddress> = listOf(address(type)),
    ): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            header(out, id, addresses.size)
            out.write(Name.fromString(question).toWire()); out.writeShort(questionType); out.writeShort(questionClass)
            for (address in addresses) {
                out.write(ownerWire ?: if (compressed) byteArrayOf(0xc0.toByte(), 12) else Name.fromString(owner).toWire())
                out.writeShort(type); out.writeShort(recordClass); out.writeInt(ttl)
                out.writeShort(address.address.size); out.write(address.address)
            }
        }
    }.toByteArray()

    fun chain(hops: Int): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            header(out, ID, hops)
            out.write(name.toWire()); out.writeShort(Type.A); out.writeShort(1)
            var previous = 12
            repeat(hops) { index ->
                val start = bytes.size()
                out.writeShort(0xc000 or previous); previous = start
                out.writeShort(if (index == hops - 1) Type.A else 65000)
                out.writeShort(1); out.writeInt(10)
                val data = if (index == hops - 1) address(Type.A).address else byteArrayOf()
                out.writeShort(data.size); out.write(data)
            }
        }
    }.toByteArray()

    private fun header(out: DataOutputStream, id: Int, answers: Int) {
        out.writeShort(id); out.writeShort(0x8400); out.writeShort(1)
        out.writeShort(answers); out.writeShort(0); out.writeShort(0)
    }
}
