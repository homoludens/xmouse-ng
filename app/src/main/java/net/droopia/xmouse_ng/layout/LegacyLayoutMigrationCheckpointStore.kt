package net.droopia.xmouse_ng.layout

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import org.json.JSONObject

/** No source text is stored here: only exact-byte fingerprints, parser versions and typed reasons. */
internal data class LegacyLayoutMigrationCheckpoint(
    val fingerprint: String?,
    val parserVersion: Int,
    val failureReason: LegacyLayoutMigrationFailure? = null,
    val recordNumber: Int? = null,
    val previousSuccessFingerprint: String? = null,
    val previousSuccessParserVersion: Int? = null,
) {
    init {
        require(fingerprint == null || Regex("[a-f0-9]{64}").matches(fingerprint))
        require(parserVersion > 0)
        require(failureReason != null || fingerprint != null)
        require(recordNumber == null || recordNumber > 0)
        require(failureReason != null || recordNumber == null)
        require((previousSuccessFingerprint == null) == (previousSuccessParserVersion == null))
        require(previousSuccessFingerprint == null || Regex("[a-f0-9]{64}").matches(previousSuccessFingerprint))
        require(previousSuccessParserVersion == null || previousSuccessParserVersion > 0)
        require(failureReason != null || previousSuccessFingerprint == null)
    }

    // Source read/parse errors do not modify the saved profile or invalidate its provenance.
    val successfulFingerprint: String?
        get() = if (failureReason == null) fingerprint else previousSuccessFingerprint

    val successfulParserVersion: Int?
        get() = if (failureReason == null) parserVersion else previousSuccessParserVersion

    fun succeededFor(fingerprint: String, parserVersion: Int): Boolean =
        successfulFingerprint == fingerprint && successfulParserVersion == parserVersion
}

/** Separate atomic records, never profile/draft files or entries in the user profile registry. */
internal class LegacyLayoutMigrationCheckpointStore(private val fileOps: LayoutProfileFileOps) {
    constructor(directory: File) : this(AndroidAtomicLayoutProfileFileOps(directory))

    fun read(sourceId: String): LegacyLayoutMigrationCheckpoint? {
        val bytes = fileOps.readAtomically(fileName(sourceId)) ?: return null
        val text = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        val root = JSONObject(text)
        require(root.opt("version") == 1) { "Unsupported layout migration checkpoint version" }
        val status = root.opt("status")
        require(status == "success" || status == "error") { "Invalid layout migration status" }
        val parserVersion = root.opt("parserVersion") as? Int
            ?: throw IOException("Invalid layout migration parser version")
        val fingerprint = if (root.isNull("fingerprint")) null else root.getString("fingerprint")
        val reason = if (status == "error") {
            LegacyLayoutMigrationFailure.valueOf(root.getString("reason"))
        } else {
            require(root.isNull("reason")) { "Success checkpoint cannot contain an error" }
            null
        }
        val recordNumber = if (root.isNull("recordNumber")) null else {
            root.opt("recordNumber") as? Int ?: throw IOException("Invalid layout migration record number")
        }
        val previousSuccessFingerprint = if (root.isNull("previousSuccessFingerprint")) null else {
            root.getString("previousSuccessFingerprint")
        }
        val previousSuccessParserVersion = if (root.isNull("previousSuccessParserVersion")) null else {
            root.opt("previousSuccessParserVersion") as? Int
                ?: throw IOException("Invalid previous layout migration parser version")
        }
        return LegacyLayoutMigrationCheckpoint(
            fingerprint, parserVersion, reason, recordNumber,
            previousSuccessFingerprint, previousSuccessParserVersion,
        )
    }

    fun write(sourceId: String, checkpoint: LegacyLayoutMigrationCheckpoint) {
        val bytes = JSONObject()
            .put("version", 1)
            .put("parserVersion", checkpoint.parserVersion)
            .put("fingerprint", checkpoint.fingerprint ?: JSONObject.NULL)
            .put("status", if (checkpoint.failureReason == null) "success" else "error")
            .put("reason", checkpoint.failureReason?.name ?: JSONObject.NULL)
            .put("recordNumber", checkpoint.recordNumber ?: JSONObject.NULL)
            .put("previousSuccessFingerprint", checkpoint.previousSuccessFingerprint ?: JSONObject.NULL)
            .put("previousSuccessParserVersion", checkpoint.previousSuccessParserVersion ?: JSONObject.NULL)
            .toString().toByteArray(StandardCharsets.UTF_8)
        val fileName = fileName(sourceId)
        fileOps.writeAtomically(fileName, bytes)
        if (fileOps.readAtomically(fileName)?.contentEquals(bytes) != true) {
            throw IOException("Layout migration checkpoint was not committed")
        }
    }

    private fun fileName(sourceId: String): String {
        require(isStableLayoutId(sourceId)) { "Invalid layout migration source ID" }
        return "$sourceId.migration.json"
    }
}
