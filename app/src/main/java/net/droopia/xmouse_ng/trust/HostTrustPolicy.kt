package net.droopia.xmouse_ng.trust

import com.jcraft.jsch.*
import net.droopia.xmouse_ng.connection.Endpoint
import net.droopia.xmouse_ng.transport.JschSshBackend
import java.util.function.BooleanSupplier

/** JSch calls check AFTER verifying the KEX signature, BEFORE sending ANY authentication request. */
class HostTrustPolicy @JvmOverloads constructor(
    private val requests: HostTrustRequests,
    private val trusted: Runnable = Runnable { },
) : JschSshBackend.SessionPolicy {
    override fun configure(jsch: JSch, session: Session, endpoint: Endpoint) =
        configure(jsch, session, endpoint, BooleanSupplier { true })

    override fun configure(jsch: JSch, session: Session, endpoint: Endpoint, current: BooleanSupplier) {
        // An opaque per-session alias prevents JSch substituting the dial IP or decorating the port.
        val alias = "xmouse-logical-endpoint"
        session.hostKeyAlias = alias
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("host_certificate_to_key_fallback", "no")
        session.setHostKeyRepository(object : com.jcraft.jsch.HostKeyRepository {
            private var checked: HostKey? = null
            override fun check(host: String?, key: ByteArray): Int {
                if (host != alias) trustFailure(HostTrustException.Reason.MISMATCH)
                val parsed = ApprovedHostKey.parse(key)
                requests.verify(endpoint, parsed, current)
                if (!current.asBoolean) trustFailure(HostTrustException.Reason.CANCELLED)
                trusted.run()
                checked = HostKey(alias, parsed.bytes())
                return com.jcraft.jsch.HostKeyRepository.OK
            }
            override fun getHostKey(): Array<HostKey> = checked?.let { arrayOf(it) } ?: emptyArray()
            override fun getHostKey(host: String?, type: String?): Array<HostKey> = getHostKey().filter {
                (host == null || host == alias) && (type == null || it.type == type)
            }.toTypedArray()
            override fun getKnownHostsRepositoryID(): String = "xmouse-private-host-trust-v1"
            override fun add(hostkey: HostKey?, ui: UserInfo?) { trustFailure(HostTrustException.Reason.MISMATCH) }
            override fun remove(host: String?, type: String?) { trustFailure(HostTrustException.Reason.MISMATCH) }
            override fun remove(host: String?, type: String?, key: ByteArray?) { trustFailure(HostTrustException.Reason.MISMATCH) }
        })
    }
    override fun cancelPendingTrust() = requests.cancelPending()

    override fun replaceHostKey(mismatch: HostKeyMismatch, current: BooleanSupplier) =
        requests.replace(mismatch, current)
}
