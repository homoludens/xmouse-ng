package net.droopia.xmouse_ng.trust

import net.droopia.xmouse_ng.connection.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.function.BooleanSupplier

class HostKeyRepositoryTest {
    private val yes = BooleanSupplier { true }
    private fun fixture(action: (File) -> Unit) {
        val directory = Files.createTempDirectory("xmouse-trust-test-").toFile()
        try { action(directory) } finally { directory.deleteRecursively() }
    }
    @Test fun fullKeyFingerprintAndNormalizedHostPortSurviveReopen() = fixture { directory ->
        val endpoint = Endpoint.manual("Desktop.LOCAL.", 2222)
        val key = syntheticHostKey()
        HostKeyRepository(directory, TrustFileIo()).approve(endpoint.identity, key, yes)
        val reopened = HostKeyRepository(directory, TrustFileIo())
        val stored = requireNotNull(reopened.find(Endpoint.manual("desktop.local", 2222).identity))
        assertArrayEquals(key.bytes(), stored.bytes()); assertEquals(key.fingerprint, stored.fingerprint)
        assertNull(reopened.find(Endpoint.manual("desktop.local", 22).identity))
        assertNull(reopened.find(Endpoint.manual("192.0.2.10", 2222).identity))
        val copy = stored.bytes(); copy.fill(0); assertTrue(stored.matches(key))
        assertTrue(File(directory, HostKeyRepository.FILE).readBytes().toList().windowed(key.bytes().size).any { it == key.bytes().toList() })
    }
    @Test fun logicalServiceAndAdvertisedHostnameUseExistingEndpointIdentityRules() = fixture { directory ->
        val repo = HostKeyRepository(directory, TrustFileIo()); val key = syntheticHostKey()
        val first = Endpoint(EndpointLocator.DiscoveredService("Desk SSH", "_SSH._TCP.", "LOCAL."), 2022)
        repo.approve(first.identity, key, yes)
        assertTrue(repo.find(Endpoint(EndpointLocator.DiscoveredService("desk ssh", "_ssh._tcp", "local"), 2022).identity)!!.matches(key))
        assertNull(repo.find(Endpoint(EndpointLocator.DiscoveredService("desk ssh", "_ssh._tcp", "local"), 22).identity))
        val advertised = Endpoint(EndpointLocator.DiscoveredService("Desk SSH", "_ssh._tcp", "local", EndpointHost("desktop.local")), 22)
        repo.approve(advertised.identity, key, yes)
        assertTrue(repo.find(Endpoint.manual("desktop.local", 22).identity)!!.matches(key))
    }
    @Test fun mismatchedApprovalNeverReplacesExistingFullKey() = fixture { directory ->
        val repo = HostKeyRepository(directory, TrustFileIo()); val identity = Endpoint.manual("desk.local", 22).identity
        val first = syntheticHostKey(); repo.approve(identity, first, yes)
        assertEquals(HostTrustException.Reason.MISMATCH, assertThrows(HostTrustException::class.java) { repo.approve(identity, syntheticHostKey(2), yes) }.reason)
        assertTrue(repo.find(identity)!!.matches(first))
    }
    @Test fun deliberateReplacementCompareAndSwapPreservesOtherEndpointRecords() = fixture { directory ->
        val repo = HostKeyRepository(directory, TrustFileIo())
        val firstIdentity = Endpoint.manual("desk.local", 22).identity
        val otherIdentity = Endpoint.manual("other.local", 2200).identity
        val old = syntheticHostKey(1); val replacement = syntheticHostKey(2); val other = syntheticHostKey(3)
        repo.approve(firstIdentity, old, yes); repo.approve(otherIdentity, other, yes)

        repo.replace(firstIdentity, old, replacement, yes)

        val reopened = HostKeyRepository(directory, TrustFileIo())
        assertTrue(reopened.find(firstIdentity)!!.matches(replacement))
        assertTrue(reopened.find(otherIdentity)!!.matches(other))
        assertEquals(HostTrustException.Reason.MISMATCH, assertThrows(HostTrustException::class.java) {
            reopened.replace(firstIdentity, old, syntheticHostKey(4), yes)
        }.reason)
        assertTrue(reopened.find(firstIdentity)!!.matches(replacement))
    }
    @Test fun failedReplacementBeforePublicationKeepsPreviouslyTrustedKey() = fixture { directory ->
        val identity = Endpoint.manual("desk.local", 22).identity
        val old = syntheticHostKey(1); val candidate = syntheticHostKey(2)
        HostKeyRepository(directory, TrustFileIo()).approve(identity, old, yes)
        val failing = HostKeyRepository(directory, object : TrustFileIo() {
            override fun sync(output: FileOutputStream) { throw IOException("NOT_REAL_storage_detail") }
        })
        assertEquals(HostTrustException.Reason.STORAGE, assertThrows(HostTrustException::class.java) {
            failing.replace(identity, old, candidate, yes)
        }.reason)
        assertTrue(HostKeyRepository(directory, TrustFileIo()).find(identity)!!.matches(old))
    }
    @Test fun corruptTruncatedOversizeFingerprintAndUnknownVersionNeverResetToUnknown() = fixture { directory ->
        val identity = Endpoint.manual("desk.local", 22).identity
        val repo = HostKeyRepository(directory, TrustFileIo()); repo.approve(identity, syntheticHostKey(), yes)
        val file = File(directory, HostKeyRepository.FILE); val original = file.readBytes()
        val wrongFingerprint = original.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        val version = original.copyOf().also { it[7] = 2 }
        for (damaged in listOf(byteArrayOf(), original.copyOf(20), original + byteArrayOf(0), ByteArray(1024 * 1024 + 1), wrongFingerprint, version)) {
            file.writeBytes(damaged)
            assertEquals(HostTrustException.Reason.INVALID_REPOSITORY, assertThrows(HostTrustException::class.java) { repo.find(identity) }.reason)
            assertThrows(HostTrustException::class.java) { repo.approve(identity, syntheticHostKey(), yes) }
            assertArrayEquals(damaged, file.readBytes())
        }
    }
    @Test fun unreadableSnapshotAndInterruptedArtifactsAreNotFreshRepositories() = fixture { directory ->
        val identity = Endpoint.manual("desk.local", 22).identity
        File(directory, HostKeyRepository.FILE).mkdir()
        assertEquals(HostTrustException.Reason.STORAGE, assertThrows(HostTrustException::class.java) { HostKeyRepository(directory, TrustFileIo()).find(identity) }.reason)
        File(directory, HostKeyRepository.FILE).delete()
        File(directory, "interrupted.pending").writeBytes(byteArrayOf(1))
        assertEquals(HostTrustException.Reason.INVALID_REPOSITORY, assertThrows(HostTrustException::class.java) { HostKeyRepository(directory, TrustFileIo()).find(identity) }.reason)
    }
    @Test fun syncRenameReadbackAndDirectoryFailuresCannotPublishApproval() = fixture { root ->
        for (stage in listOf("sync", "rename", "readback", "directory", "final-directory")) {
            val directory = File(root, stage)
            val identity = Endpoint.manual("desk.local", 22).identity
            val io = object : TrustFileIo() {
                override fun sync(output: FileOutputStream) { if (stage == "sync") throw IOException("NOT_REAL_provider_detail"); super.sync(output) }
                override fun replace(source: File, destination: File) {
                    if (stage == "rename") throw IOException()
                    super.replace(source, destination)
                    if (stage == "readback") destination.writeBytes(byteArrayOf(1))
                }
                override fun syncDirectory(directory: File) {
                    if (stage == "directory" && File(directory, HostKeyRepository.FILE).exists()) throw IOException()
                    if (stage == "final-directory" && File(directory, HostKeyRepository.FILE).exists() && !File(directory, ".unverified").exists()) throw IOException()
                    super.syncDirectory(directory)
                }
            }
            val repo = HostKeyRepository(directory, io)
            val error = assertThrows(HostTrustException::class.java) { repo.approve(identity, syntheticHostKey(), yes) }
            assertTrue(error.message in HostTrustException.Reason.entries.map { it.name }); assertNull(error.cause)
            if (stage == "sync") assertNull(HostKeyRepository(directory, TrustFileIo()).find(identity))
            else assertThrows(HostTrustException::class.java) { HostKeyRepository(directory, TrustFileIo()).find(identity) }
        }
    }
    @Test fun invalidPublicKeyFormatsAndInputDerivedLengthsAreBounded() {
        val good = syntheticHostKey().bytes()
        for (bad in listOf(byteArrayOf(), ByteArray(ApprovedHostKey.MAX_BYTES + 1), good.copyOf(10), good + byteArrayOf(0), byteArrayOf(0x7f, -1, -1, -1))) {
            assertEquals(HostTrustException.Reason.INVALID_KEY, assertThrows(HostTrustException::class.java) { ApprovedHostKey.parse(bad) }.reason)
        }
    }
    @Test fun cancelledBeforePublicationCannotStoreFirstTrust() = fixture { directory ->
        val repo = HostKeyRepository(directory, TrustFileIo()); val identity = Endpoint.manual("desk.local", 22).identity
        assertEquals(HostTrustException.Reason.CANCELLED, assertThrows(HostTrustException::class.java) { repo.approve(identity, syntheticHostKey(), BooleanSupplier { false }) }.reason)
        assertNull(repo.find(identity))
    }
    @Test fun cancellationAfterStagingFsyncPreventsAtomicPublication() = fixture { directory ->
        val current = java.util.concurrent.atomic.AtomicBoolean(true)
        val repo = HostKeyRepository(directory, object : TrustFileIo() {
            override fun sync(output: FileOutputStream) { super.sync(output); current.set(false) }
        })
        val identity = Endpoint.manual("desk.local", 22).identity
        assertEquals(HostTrustException.Reason.CANCELLED, assertThrows(HostTrustException::class.java) { repo.approve(identity, syntheticHostKey(), BooleanSupplier { current.get() }) }.reason)
        assertNull(HostKeyRepository(directory, TrustFileIo()).find(identity))
        assertFalse(File(directory, HostKeyRepository.FILE).exists())
    }
}
