package com.stripe1.xmouse.layout

import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Small storage boundary so repository failures can be tested without Android or real files. */
internal interface LayoutProfileFileOps {
    fun readAtomically(fileName: String): ByteArray?
    fun writeAtomically(fileName: String, bytes: ByteArray)
    fun deleteAtomically(fileName: String)
}

enum class LayoutProfileRecord {
    PROFILE,
    DRAFT,
}

class LayoutProfileReadException(
    val profileId: String,
    val record: LayoutProfileRecord,
    cause: Throwable,
) : IOException("Could not read the $record for layout profile '$profileId'.", cause)

class LayoutProfileWriteException(
    val profileId: String,
    val record: LayoutProfileRecord,
    cause: Throwable,
) : IOException("Could not write the $record for layout profile '$profileId'.", cause)

class LayoutProfileNotFoundException(profileId: String) :
    IOException("No saved layout profile exists with ID '$profileId'.")

/**
 * Persists one profile per stable ID and keeps recovery drafts in distinct files. A save first
 * commits the draft, then atomically replaces the saved profile. Thus an interrupted replacement
 * leaves both the last valid profile and the retryable draft available.
 */
class LayoutProfileRepository internal constructor(
    private val fileOps: LayoutProfileFileOps,
) {
    constructor(directory: File) : this(AndroidAtomicLayoutProfileFileOps(directory))

    /** Save only returns after the saved profile's atomic write has completed successfully. */
    fun saveProfile(profile: LayoutProfile) = synchronized(processLock) {
        val encoded = encode(profile, LayoutProfileRecord.PROFILE)
        writeRecord(profile.id, LayoutProfileRecord.DRAFT, encoded)
        writeRecord(profile.id, LayoutProfileRecord.PROFILE, encoded)

        // A crash before cleanup leaves an equal draft, which loadDraft recognizes as committed.
        // Cleanup is best-effort because the saved profile is already durable at this point.
        try {
            fileOps.deleteAtomically(fileName(profile.id, LayoutProfileRecord.DRAFT))
        } catch (_: Exception) {
            // A stale equal draft is not pending recovery state and will be ignored on load.
        }
    }

    /** Persist an in-progress editor snapshot independently of the last saved profile. */
    fun saveDraft(draft: LayoutProfile) = synchronized(processLock) {
        writeRecord(
            draft.id,
            LayoutProfileRecord.DRAFT,
            encode(draft, LayoutProfileRecord.DRAFT),
        )
    }

    fun loadProfile(profileId: String): LayoutProfile = synchronized(processLock) {
        validateProfileId(profileId)
        readRecord(profileId, LayoutProfileRecord.PROFILE)
            ?: throw LayoutProfileNotFoundException(profileId)
    }

    /** Returns only an unsaved draft; a leftover equal to the committed profile is not pending. */
    fun loadDraft(profileId: String): LayoutProfile? = synchronized(processLock) {
        validateProfileId(profileId)
        val draft = readRecord(profileId, LayoutProfileRecord.DRAFT) ?: return@synchronized null
        val saved = readRecord(profileId, LayoutProfileRecord.PROFILE)
        draft.takeUnless { it == saved }
    }

    fun discardDraft(profileId: String) = synchronized(processLock) {
        validateProfileId(profileId)
        try {
            fileOps.deleteAtomically(fileName(profileId, LayoutProfileRecord.DRAFT))
        } catch (failure: Exception) {
            throw LayoutProfileWriteException(profileId, LayoutProfileRecord.DRAFT, failure)
        }
    }

    private fun encode(profile: LayoutProfile, record: LayoutProfileRecord): ByteArray = try {
        val text = LayoutProfileJson.encode(profile)
        val buffer = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(text))
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        bytes
    } catch (failure: Exception) {
        throw LayoutProfileWriteException(profile.id, record, failure)
    }

    private fun writeRecord(profileId: String, record: LayoutProfileRecord, bytes: ByteArray) {
        try {
            fileOps.writeAtomically(fileName(profileId, record), bytes)
        } catch (failure: Exception) {
            throw LayoutProfileWriteException(profileId, record, failure)
        }
    }

    private fun readRecord(profileId: String, record: LayoutProfileRecord): LayoutProfile? {
        val bytes = try {
            fileOps.readAtomically(fileName(profileId, record))
        } catch (failure: Exception) {
            throw LayoutProfileReadException(profileId, record, failure)
        } ?: return null

        val profile = try {
            val text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
            LayoutProfileJson.decode(text)
        } catch (failure: Exception) {
            throw LayoutProfileReadException(profileId, record, failure)
        }
        if (profile.id != profileId) {
            throw LayoutProfileReadException(
                profileId,
                record,
                IllegalArgumentException("Stored layout profile ID does not match its storage ID"),
            )
        }
        return profile
    }

    private fun validateProfileId(profileId: String) {
        require(isStableLayoutId(profileId)) { "Profile ID must be a stable identifier" }
    }

    private fun fileName(profileId: String, record: LayoutProfileRecord): String {
        validateProfileId(profileId)
        val suffix = when (record) {
            LayoutProfileRecord.PROFILE -> PROFILE_SUFFIX
            LayoutProfileRecord.DRAFT -> DRAFT_SUFFIX
        }
        return "$profileId$suffix"
    }

    companion object {
        private const val PROFILE_SUFFIX = ".profile.json"
        private const val DRAFT_SUFFIX = ".draft.json"

        // Serialize repository instances in this app process; AtomicFile handles crash recovery.
        private val processLock = Any()
    }
}

/** Android AtomicFile keeps a backup of the previous base until the new bytes are synced. */
internal class AndroidAtomicLayoutProfileFileOps(private val directory: File) : LayoutProfileFileOps {
    override fun readAtomically(fileName: String): ByteArray? {
        ensureDirectory()
        val baseFile = child(fileName)
        return try {
            AtomicFile(baseFile).openRead().use { it.readBytes() }
        } catch (missing: FileNotFoundException) {
            if (baseFile.exists()) throw missing
            null
        }
    }

    override fun writeAtomically(fileName: String, bytes: ByteArray) {
        ensureDirectory()
        val atomicFile = AtomicFile(child(fileName))
        var output: FileOutputStream? = null
        try {
            output = atomicFile.startWrite()
            output.write(bytes)
            atomicFile.finishWrite(output)
            output = null
        } catch (failure: Throwable) {
            output?.let { opened ->
                try {
                    atomicFile.failWrite(opened)
                } catch (rollbackFailure: Throwable) {
                    failure.addSuppressed(rollbackFailure)
                }
            }
            throw failure
        }
    }

    override fun deleteAtomically(fileName: String) {
        ensureDirectory()
        AtomicFile(child(fileName)).delete()
    }

    private fun ensureDirectory() {
        if (directory.isDirectory) return
        if (directory.exists() || !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Could not create layout profile storage directory")
        }
    }

    private fun child(fileName: String): File {
        if (!fileNamePattern.matches(fileName)) throw IOException("Invalid layout storage name")
        val baseFile = File(directory, fileName)
        if (baseFile.canonicalFile.parentFile != directory.canonicalFile) {
            throw IOException("Layout storage path escaped its directory")
        }
        return baseFile
    }

    companion object {
        private val fileNamePattern =
            Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.(?:profile|draft|migration)\\.json")
    }
}
