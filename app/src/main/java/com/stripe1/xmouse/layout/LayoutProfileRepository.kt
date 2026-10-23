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
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Small storage boundary so repository failures can be tested without Android or real files. */
internal interface LayoutProfileFileOps {
    fun listFileNames(): List<String>
    fun readAtomically(fileName: String): ByteArray?
    fun writeAtomically(fileName: String, bytes: ByteArray)
    fun deleteAtomically(fileName: String)
}

enum class LayoutProfileRecord {
    PROFILE,
    DRAFT,
    CATALOG,
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

class LayoutProfileAlreadyExistsException(profileId: String) :
    IOException("A layout profile ID is already stored or reserved: '$profileId'.")

class LayoutProfileLastProfileException :
    IOException("The last remaining layout profile cannot be deleted.")

class LayoutProfileListingException(cause: Throwable) :
    IOException("Could not list saved layout profiles.", cause)

/**
 * Persists one profile per stable ID and keeps recovery drafts in distinct files. A save first
 * commits the draft, then atomically replaces the saved profile. Thus an interrupted replacement
 * leaves both the last valid profile and the retryable draft available.
 */
class LayoutProfileRepository internal constructor(
    private val fileOps: LayoutProfileFileOps,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    constructor(directory: File) : this(AndroidAtomicLayoutProfileFileOps(directory))

    /** Save only returns after the saved profile's atomic write has completed successfully. */
    fun saveProfile(profile: LayoutProfile) = synchronized(processLock) {
        val catalog = ensureCatalog()
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

        if (profile.id !in catalog.profileIds) {
            writeCatalog(catalog.withProfile(profile.id))
        }
    }

    /**
     * Create a new profile without replacing an existing profile or orphaned draft with its ID.
     * The profile record commits first; a later listing reconciles it if the catalog write is
     * interrupted. Names are display data only and may be duplicated freely.
     */
    fun createProfile(profile: LayoutProfile): LayoutProfile = synchronized(processLock) {
        val catalog = ensureCatalog()
        createProfile(profile, catalog)
    }

    /** Rename only the display name. Stable profile/button IDs and saved content are preserved. */
    fun renameProfile(profileId: String, newName: String): LayoutProfile = synchronized(processLock) {
        val catalog = ensureCatalog()
        val saved = requireCatalogProfile(catalog, profileId)
        val renamed = saved.copy(name = newName)
        writeRecord(profileId, LayoutProfileRecord.PROFILE, encode(renamed, LayoutProfileRecord.PROFILE))
        renamed
    }

    /** Duplicate a saved profile under fresh stable IDs, preserving button order and values. */
    fun duplicateProfile(profileId: String, newName: String? = null): LayoutProfile =
        synchronized(processLock) {
            val catalog = ensureCatalog()
            val source = requireCatalogProfile(catalog, profileId)
            val fileNames = listFileNames()
            val duplicateId = nextId(emptySet()) { candidate ->
                candidate in catalog.profileIds ||
                    candidate in catalog.deletingProfileIds ||
                    candidate == catalog.selectedProfileId ||
                    hasAtomicFile(fileNames, "$candidate$PROFILE_SUFFIX") ||
                    hasAtomicFile(fileNames, "$candidate$DRAFT_SUFFIX")
            }
            val usedButtonIds = source.buttons.mapTo(mutableSetOf(), LayoutButton::id)
            val duplicate = LayoutProfile(
                id = duplicateId,
                name = newName ?: source.name,
                columnCount = source.columnCount,
                buttons = source.buttons.map { button ->
                    val buttonId = nextId(usedButtonIds) { false }
                    usedButtonIds += buttonId
                    button.copy(id = buttonId)
                },
            )
            createProfile(duplicate, catalog)
        }

    /** Atomically select an existing profile by ID; names are never used to resolve selection. */
    fun selectProfile(profileId: String) = synchronized(processLock) {
        val catalog = ensureCatalog()
        requireCatalogProfile(catalog, profileId)
        writeCatalog(catalog.copy(selectedProfileId = profileId))
    }

    /** Returns null when no profile is selected; a dangling selected ID is an explicit error. */
    fun loadSelectedProfile(): LayoutProfile? = synchronized(processLock) {
        val catalog = ensureCatalog()
        val profileId = catalog.selectedProfileId ?: return@synchronized null
        if (profileId !in catalog.profileIds) throw LayoutProfileNotFoundException(profileId)
        requireCatalogProfile(catalog, profileId)
    }

    /**
     * Delete only after explicit confirmation. Cancellation returns false before any storage
     * access or mutation. The catalog records a pending delete before removing the ID-owned files,
     * so another repository instance can finish an interrupted deletion without touching peers.
     */
    fun deleteProfile(profileId: String, confirmed: Boolean): Boolean = synchronized(processLock) {
        if (!confirmed) return@synchronized false
        validateProfileId(profileId)
        val catalog = ensureCatalog()
        requireCatalogProfile(catalog, profileId)
        if (catalog.profileIds.size <= 1) throw LayoutProfileLastProfileException()
        val remainingProfileIds = catalog.profileIds.filterNot { it == profileId }
        val selectedProfileId = catalog.selectedProfileId
            ?.takeIf { it in remainingProfileIds }
            ?: remainingProfileIds.firstOrNull()
        val deleting = catalog.copy(
            profileIds = remainingProfileIds,
            selectedProfileId = selectedProfileId,
            deletingProfileIds = (catalog.deletingProfileIds + profileId).distinct(),
        )
        writeCatalog(deleting)
        finishPendingDeletes(deleting)
        true
    }

    /** Returns every saved profile; corrupt or unreadable records fail the listing explicitly. */
    fun listProfiles(): List<LayoutProfile> = synchronized(processLock) {
        val catalog = ensureCatalog()
        catalog.profileIds.map { profileId -> requireCatalogProfile(catalog, profileId) }
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

    private fun createProfile(profile: LayoutProfile, catalog: ProfileCatalog): LayoutProfile {
        validateProfileId(profile.id)
        val fileNames = listFileNames()
        if (profile.id in catalog.profileIds || profile.id in catalog.deletingProfileIds ||
            profile.id == catalog.selectedProfileId ||
            hasAtomicFile(fileNames, "${profile.id}$PROFILE_SUFFIX") ||
            hasAtomicFile(fileNames, "${profile.id}$DRAFT_SUFFIX")
        ) {
            throw LayoutProfileAlreadyExistsException(profile.id)
        }
        writeRecord(profile.id, LayoutProfileRecord.PROFILE, encode(profile, LayoutProfileRecord.PROFILE))
        writeCatalog(catalog.withProfile(profile.id))
        return profile
    }

    /**
     * The atomic catalog is the ID index and records delete intents. Reconcile profile files not
     * yet indexed after a create/migration interruption; only `.profile.json` files are profiles,
     * so the separate `.migration.json` records never enter the user catalog.
     */
    private fun ensureCatalog(): ProfileCatalog {
        var catalog = readCatalog()
        var fileNames = listFileNames()
        var diskProfileIds = profileIdsFrom(fileNames)

        if (catalog == null) {
            // Validate every discovered profile before committing an initial catalog.
            diskProfileIds.forEach { readRecord(it, LayoutProfileRecord.PROFILE) }
            catalog = ProfileCatalog(profileIds = diskProfileIds)
            writeCatalog(catalog)
            return catalog
        }

        if (catalog.deletingProfileIds.isNotEmpty()) {
            catalog = finishPendingDeletes(catalog)
            fileNames = listFileNames()
            diskProfileIds = profileIdsFrom(fileNames)
        }

        catalog.profileIds.forEach { profileId ->
            if (!hasAtomicFile(fileNames, "$profileId$PROFILE_SUFFIX")) {
                throw LayoutProfileReadException(
                    profileId,
                    LayoutProfileRecord.PROFILE,
                    LayoutProfileNotFoundException(profileId),
                )
            }
        }

        val unindexed = diskProfileIds.filterNot {
            it in catalog.profileIds || it in catalog.deletingProfileIds
        }
        if (unindexed.isNotEmpty()) {
            unindexed.forEach { readRecord(it, LayoutProfileRecord.PROFILE) }
            catalog = catalog.copy(profileIds = catalog.profileIds + unindexed)
            writeCatalog(catalog)
        }
        return catalog
    }

    private fun finishPendingDeletes(catalog: ProfileCatalog): ProfileCatalog {
        catalog.deletingProfileIds.forEach { profileId ->
            deleteRecord(profileId, LayoutProfileRecord.PROFILE)
            deleteRecord(profileId, LayoutProfileRecord.DRAFT)
        }
        val completed = catalog.copy(deletingProfileIds = emptyList())
        writeCatalog(completed)
        return completed
    }

    private fun deleteRecord(profileId: String, record: LayoutProfileRecord) {
        try {
            fileOps.deleteAtomically(fileName(profileId, record))
        } catch (failure: Exception) {
            throw LayoutProfileWriteException(profileId, record, failure)
        }
    }

    private fun requireCatalogProfile(catalog: ProfileCatalog, profileId: String): LayoutProfile {
        validateProfileId(profileId)
        if (profileId !in catalog.profileIds) throw LayoutProfileNotFoundException(profileId)
        return readRecord(profileId, LayoutProfileRecord.PROFILE)
            ?: throw LayoutProfileNotFoundException(profileId)
    }

    private fun readCatalog(): ProfileCatalog? {
        val bytes = try {
            fileOps.readAtomically(CATALOG_FILE)
        } catch (failure: Exception) {
            throw LayoutProfileReadException(CATALOG_ID, LayoutProfileRecord.CATALOG, failure)
        } ?: return null
        return try {
            decodeCatalog(bytes)
        } catch (failure: Exception) {
            throw LayoutProfileReadException(CATALOG_ID, LayoutProfileRecord.CATALOG, failure)
        }
    }

    private fun writeCatalog(catalog: ProfileCatalog) {
        val bytes = try {
            encodeCatalog(catalog)
        } catch (failure: Exception) {
            throw LayoutProfileWriteException(CATALOG_ID, LayoutProfileRecord.CATALOG, failure)
        }
        try {
            fileOps.writeAtomically(CATALOG_FILE, bytes)
        } catch (failure: Exception) {
            throw LayoutProfileWriteException(CATALOG_ID, LayoutProfileRecord.CATALOG, failure)
        }
    }

    private fun listFileNames(): Set<String> = try {
        fileOps.listFileNames().toSet()
    } catch (failure: Exception) {
        throw LayoutProfileListingException(failure)
    }

    private fun profileIdsFrom(fileNames: Set<String>): List<String> = fileNames
        .asSequence()
        .filter { it.endsWith(PROFILE_SUFFIX) || it.endsWith("$PROFILE_SUFFIX$ATOMIC_BACKUP_SUFFIX") }
        .map { name ->
            val profileFileName = name.removeSuffix(ATOMIC_BACKUP_SUFFIX)
            val profileId = profileFileName.removeSuffix(PROFILE_SUFFIX)
            if (!isStableLayoutId(profileId)) {
                throw LayoutProfileListingException(IllegalArgumentException("Invalid profile filename"))
            }
            profileId
        }
        .distinct()
        .sorted()
        .toList()

    private fun hasAtomicFile(fileNames: Set<String>, fileName: String): Boolean =
        fileName in fileNames || "$fileName$ATOMIC_BACKUP_SUFFIX" in fileNames

    private fun nextId(excludedIds: Set<String>, isReserved: (String) -> Boolean): String {
        repeat(ID_GENERATION_ATTEMPTS) {
            val candidate = idGenerator()
            validateProfileId(candidate)
            if (candidate !in excludedIds && !isReserved(candidate)) return candidate
        }
        throw IOException("Could not generate a unique layout identifier")
    }

    private fun ProfileCatalog.withProfile(profileId: String): ProfileCatalog =
        if (profileId in profileIds) this else copy(profileIds = profileIds + profileId)

    private data class ProfileCatalog(
        val profileIds: List<String>,
        val selectedProfileId: String? = null,
        val deletingProfileIds: List<String> = emptyList(),
    ) {
        init {
            require(profileIds.all(::isStableLayoutId) && profileIds.distinct().size == profileIds.size)
            require(selectedProfileId == null || isStableLayoutId(selectedProfileId))
            require(deletingProfileIds.all(::isStableLayoutId) && deletingProfileIds.distinct().size == deletingProfileIds.size)
            require(deletingProfileIds.none { it in profileIds })
        }
    }

    private fun encodeCatalog(catalog: ProfileCatalog): ByteArray {
        val ids = JSONArray().also { array -> catalog.profileIds.forEach { array.put(it) } }
        val deleting = JSONArray().also { array -> catalog.deletingProfileIds.forEach { array.put(it) } }
        val text = JSONObject()
            .put("version", CATALOG_VERSION)
            .put("profileIds", ids)
            .put("selectedProfileId", catalog.selectedProfileId ?: JSONObject.NULL)
            .put("deletingProfileIds", deleting)
            .toString()
        return StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(text))
            .let { buffer ->
                ByteArray(buffer.remaining()).also { bytes -> buffer.get(bytes) }
            }
    }

    private fun decodeCatalog(bytes: ByteArray): ProfileCatalog {
        val text = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
        val root = JSONObject(text)
        val version = (root.opt("version") as? Number)?.toString()?.toIntOrNull()
        require(version == CATALOG_VERSION) { "Unsupported layout profile catalog version" }
        val profileIds = root.requiredStringArray("profileIds")
        val deletingProfileIds = root.requiredStringArray("deletingProfileIds")
        val selectedProfileId = when (val selected = root.opt("selectedProfileId")) {
            JSONObject.NULL -> null
            is String -> selected
            else -> throw IllegalArgumentException("Invalid selected profile ID")
        }
        return ProfileCatalog(profileIds, selectedProfileId, deletingProfileIds)
    }

    private fun JSONObject.requiredStringArray(key: String): List<String> {
        val array = opt(key) as? JSONArray ?: throw IllegalArgumentException("Missing or invalid '$key'")
        return (0 until array.length()).map { index ->
            array.opt(index) as? String ?: throw IllegalArgumentException("Invalid '$key' entry")
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
            LayoutProfileRecord.CATALOG -> throw IllegalArgumentException("Catalog is not a profile record")
        }
        return "$profileId$suffix"
    }

    companion object {
        private const val PROFILE_SUFFIX = ".profile.json"
        private const val DRAFT_SUFFIX = ".draft.json"
        private const val ATOMIC_BACKUP_SUFFIX = ".bak"
        private const val CATALOG_FILE = "profiles.catalog.json"
        private const val CATALOG_ID = "catalog"
        private const val CATALOG_VERSION = 1
        private const val ID_GENERATION_ATTEMPTS = 128

        // Serialize repository instances in this app process; AtomicFile handles crash recovery.
        private val processLock = Any()
    }
}

/** Android AtomicFile keeps a backup of the previous base until the new bytes are synced. */
internal class AndroidAtomicLayoutProfileFileOps(private val directory: File) : LayoutProfileFileOps {
    override fun listFileNames(): List<String> {
        ensureDirectory()
        return directory.list()?.toList()
            ?: throw IOException("Could not list layout profile storage directory")
    }

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
            Regex("(?:[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.(?:profile|draft|migration)|profiles\\.catalog)\\.json")
    }
}
