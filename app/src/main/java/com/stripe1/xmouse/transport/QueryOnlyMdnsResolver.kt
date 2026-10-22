package com.stripe1.xmouse.transport

import java.io.Closeable
import java.net.InetAddress
import java.net.UnknownHostException
import java.security.SecureRandom
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

/** One temporary, explicitly requested .local A/AAAA lookup; no service discovery or cache. */
internal class QueryOnlyMdnsResolver(
    private val open: () -> MdnsSocket,
    private val clock: () -> Long = System::nanoTime,
    private val transactionId: () -> Int = { random.nextInt(65535) + 1 },
) {
    fun resolve(host: String, checkCurrent: () -> Unit): Array<InetAddress> {
        val name = MdnsAddressCodec.localName(host) ?: throw UnknownHostException()
        val deadline = clock() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MILLIS.toLong())
        fun checkAttempt() {
            if (Thread.currentThread().isInterrupted) throw CancellationException()
            checkCurrent()
        }
        fun checkBudget() {
            checkAttempt()
            if (deadline - clock() <= 0) throw SshAddressResolver.ResolutionTimeoutException()
        }
        checkBudget()
        val firstId = transactionId().also { require(it in 1..65535) }
        val queries = MdnsAddressCodec.TYPES.zip(listOf(firstId, firstId % 65535 + 1))
        val found = LinkedHashSet<InetAddress>()
        open().use { socket ->
            for ((type, id) in queries) {
                checkBudget()
                socket.send(MdnsAddressCodec.query(name, type, id))
            }
            collect@ while (found.size < SshAddressResolver.MAX_CANDIDATES) {
                // Even an uninterrupted flood of irrelevant packets shares this same deadline.
                checkAttempt()
                val remaining = deadline - clock()
                if (remaining <= 0) break
                val wait = TimeUnit.NANOSECONDS.toMillis(remaining).coerceIn(1, POLL_MILLIS.toLong()).toInt()
                val packet = socket.receive(wait) ?: continue
                checkAttempt() // Cancellation/route changes take precedence over a received answer.
                if (deadline - clock() <= 0) break // Late packets cannot replace/erase timely answers.
                if (packet.port != PORT || !MdnsAddressCodec.usable(packet.source) ||
                    !socket.isOnLink(packet.source)
                ) continue
                for ((type, id) in queries) {
                    for (address in MdnsAddressCodec.answers(packet.bytes, name, type, id)) {
                        checkAttempt()
                        if (deadline - clock() <= 0) break@collect
                        val scoped = socket.scope(address) ?: continue
                        if (found.size < SshAddressResolver.MAX_CANDIDATES) found.add(scoped)
                    }
                }
            }
            // Do not publish results after cancellation, permission revocation, or route change.
            checkAttempt()
        }
        if (found.isEmpty()) throw SshAddressResolver.ResolutionTimeoutException()
        return found.toTypedArray()
    }

    companion object {
        const val PORT = 5353
        const val TIMEOUT_MILLIS = 1500
        const val POLL_MILLIS = 50
        private val random = SecureRandom()
    }
}

/** Injected datagram seam: no real LAN needed for codec/deadline/resolver tests. */
internal interface MdnsSocket : Closeable {
    fun send(query: ByteArray)
    fun receive(timeoutMillis: Int): MdnsPacket?
    fun isOnLink(address: InetAddress): Boolean
    fun scope(address: InetAddress): InetAddress?
}

internal data class MdnsPacket(val bytes: ByteArray, val source: InetAddress, val port: Int)
