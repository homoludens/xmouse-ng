package net.droopia.xmouse_ng.trust

import net.droopia.xmouse_ng.connection.EndpointIdentity
import net.droopia.xmouse_ng.secrets.SecretFileIo
import java.io.*
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.util.function.BooleanSupplier

/** One bounded, versioned snapshot, independent of credential encryption/Keystore. Worker I/O only. */
class HostKeyRepository internal constructor(private val directory: File, private val io: SecretFileIo) {
    private var poisoned = false
    fun find(identity: EndpointIdentity): ApprovedHostKey? = exclusive { read()[identity] }

    /** First approval only. */
    internal fun approve(identity: EndpointIdentity, key: ApprovedHostKey, current: BooleanSupplier) = exclusive {
        val records = read()
        records[identity]?.let {
            if (!it.matches(key)) trustFailure(HostTrustException.Reason.MISMATCH)
            return@exclusive
        }
        if (!current.asBoolean) trustFailure(HostTrustException.Reason.CANCELLED)
        if (records.size >= MAX_RECORDS) trustFailure(HostTrustException.Reason.INVALID_REPOSITORY)
        records[identity] = key
        publish(records, identity, key, current)
    }

    /** Deliberate replacement uses compare-and-swap against the complete previously trusted key. */
    internal fun replace(
        identity: EndpointIdentity,
        expected: ApprovedHostKey,
        replacement: ApprovedHostKey,
        current: BooleanSupplier,
    ) = exclusive {
        val records = read()
        val existing = records[identity] ?: trustFailure(HostTrustException.Reason.MISMATCH)
        if (!existing.matches(expected)) trustFailure(HostTrustException.Reason.MISMATCH)
        if (!current.asBoolean) trustFailure(HostTrustException.Reason.CANCELLED)
        records[identity] = replacement
        publish(records, identity, replacement, current)
    }

    private fun publish(
        records: LinkedHashMap<EndpointIdentity, ApprovedHostKey>,
        identity: EndpointIdentity,
        key: ApprovedHostKey,
        current: BooleanSupplier,
    ) {
        val bytes = encode(records)
        val pending = File.createTempFile("trust-", ".pending", directory)
        val unverified = File(directory, ".unverified")
        var publishing = false
        var replaceAttempted = false
        try {
            io.privateFile(pending)
            FileOutputStream(pending).use { it.write(bytes); io.sync(it) }
            if (!current.asBoolean) trustFailure(HostTrustException.Reason.CANCELLED)
            // An uncertain rename/read-back must never become a successful approval on the next read.
            publishing = true
            FileOutputStream(unverified).use { io.privateFile(unverified); it.write(1); io.sync(it) }
            io.syncDirectory(directory)
            if (!current.asBoolean) {
                if (!unverified.delete()) throw IOException()
                io.syncDirectory(directory)
                trustFailure(HostTrustException.Reason.CANCELLED)
            }
            // This is the final cancellation preflight. Once atomic replacement is attempted, a
            // racing cancellation may suppress success but cannot safely roll back this exact CAS pair.
            replaceAttempted = true
            io.replace(pending, File(directory, FILE))
            io.syncDirectory(directory)
            val verified = read(verifying = true)[identity]
            if (verified == null || !verified.matches(key)) trustFailure(HostTrustException.Reason.STORAGE)
            if (!unverified.delete()) throw IOException()
            io.syncDirectory(directory)
        } catch (failure: Exception) {
            if (publishing) {
                if (replaceAttempted) {
                    poisoned = true
                    // Once replacement is attempted, publication can be uncertain; keep it fail-closed.
                    try {
                        FileOutputStream(unverified).use { io.privateFile(unverified); it.write(1); io.sync(it) }
                        io.syncDirectory(directory)
                    } catch (_: Exception) { /* Disk failure cannot promise recovery; this owner remains closed. */ }
                } else {
                    // The old snapshot is known intact because atomic replacement was not attempted.
                    try {
                        if (unverified.exists() && !unverified.delete()) throw IOException()
                        io.syncDirectory(directory)
                    } catch (_: Exception) {
                        poisoned = true
                        try {
                            FileOutputStream(unverified).use { io.privateFile(unverified); it.write(1); io.sync(it) }
                            io.syncDirectory(directory)
                        } catch (_: Exception) { /* Disk failure cannot promise recovery; this owner remains closed. */ }
                    }
                }
            }
            throw failure
        } finally { if (pending.exists()) pending.delete() }
        if (!current.asBoolean) trustFailure(HostTrustException.Reason.CANCELLED)
    }

    private fun read(verifying: Boolean = false): LinkedHashMap<EndpointIdentity, ApprovedHostKey> {
        if (poisoned || (!verifying && Files.exists(File(directory, ".unverified").toPath()))) trustFailure(HostTrustException.Reason.STORAGE)
        val file = File(directory, FILE)
        val bytes = try {
            Files.newInputStream(file.toPath()).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var remaining = MAX_FILE + 1
                while (remaining > 0) {
                    val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (count < 0) break
                    output.write(buffer, 0, count); remaining -= count
                }
                output.toByteArray().also { if (it.size > MAX_FILE) trustFailure(HostTrustException.Reason.INVALID_REPOSITORY) }
            }
        } catch (_: NoSuchFileException) {
            // A missing snapshot with interrupted/unrecognized artifacts is NOT a fresh repository.
            val names = directory.list() ?: trustFailure(HostTrustException.Reason.STORAGE)
            if (names.any { it != ".lock" }) trustFailure(HostTrustException.Reason.INVALID_REPOSITORY)
            return linkedMapOf()
        }
        try {
            val result = linkedMapOf<EndpointIdentity, ApprovedHostKey>()
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == MAGIC && input.readInt() == 1)
                val count = input.readInt(); require(count in 0..MAX_RECORDS)
                repeat(count) {
                    val kind = input.readUnsignedByte()
                    val identity = when (kind) {
                        1 -> EndpointIdentity.Host(input.readUTF().also { require(it.length <= 1024) }, input.readInt())
                        2 -> {
                            val instance = input.readUTF(); val type = input.readUTF(); val domain = input.readUTF()
                            require(instance.length <= 63 && type.length <= 32 && domain.length <= 253)
                            EndpointIdentity.Service(instance, type, domain, input.readInt())
                        }
                        else -> throw IOException()
                    }
                    val length = input.readInt(); require(length in 1..minOf(ApprovedHostKey.MAX_BYTES, input.available()))
                    val key = ApprovedHostKey.parse(ByteArray(length).also { input.readFully(it) })
                    require(input.readUTF() == key.fingerprint && result.put(identity, key) == null)
                }
                require(input.available() == 0)
            }
            return result
        } catch (_: Exception) { trustFailure(HostTrustException.Reason.INVALID_REPOSITORY) }
    }

    private fun encode(records: Map<EndpointIdentity, ApprovedHostKey>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC); output.writeInt(1); output.writeInt(records.size)
            records.forEach { (identity, key) ->
                when (identity) {
                    is EndpointIdentity.Host -> { output.writeByte(1); output.writeUTF(identity.host); output.writeInt(identity.port) }
                    is EndpointIdentity.Service -> {
                        output.writeByte(2); output.writeUTF(identity.instanceName); output.writeUTF(identity.serviceType)
                        output.writeUTF(identity.domain); output.writeInt(identity.port)
                    }
                }
                val full = key.bytes(); output.writeInt(full.size); output.write(full); output.writeUTF(key.fingerprint)
            }
        }
        return bytes.toByteArray().also { if (it.size > MAX_FILE) trustFailure(HostTrustException.Reason.INVALID_REPOSITORY) }
    }

    private fun <T> exclusive(action: () -> T): T = synchronized(processLock) {
        try {
            val created = !directory.isDirectory
            if (created && !directory.mkdirs()) throw IOException()
            io.privateDirectory(directory)
            if (created) io.syncDirectory(directory.parentFile ?: throw IOException())
            val lock = File(directory, ".lock")
            RandomAccessFile(lock, "rw").use { handle ->
                io.privateFile(lock)
                handle.channel.lock().use { action() }
            }
        } catch (failure: HostTrustException) { throw failure }
        catch (_: Exception) { trustFailure(HostTrustException.Reason.STORAGE) }
    }

    companion object {
        internal const val FILE = "approved-v1.bin"
        private const val MAGIC = 0x584d4854 // XMHT
        private const val MAX_RECORDS = 512
        private const val MAX_FILE = 1024 * 1024
        private val processLock = Any()
    }
}
