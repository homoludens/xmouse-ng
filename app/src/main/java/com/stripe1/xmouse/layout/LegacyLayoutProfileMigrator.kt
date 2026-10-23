package com.stripe1.xmouse.layout

import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.security.MessageDigest

enum class LegacyLayoutMigrationFailure(val description: String) {
    UNREADABLE_SOURCE("source cannot be read as UTF-8; check access and encoding, then retry"),
    MALFORMED_SOURCE("source contains an empty or invalid record; repair the retained source and retry"),
    AMBIGUOUS_SOURCE("record delimiters are ambiguous; repair the retained source before retrying"),
    PROFILE_SAVE_FAILED("profile could not be committed and verified; check storage and retry"),
    PROFILE_READ_FAILED("previously migrated profile cannot be read; recover it before retrying"),
    CHECKPOINT_READ_FAILED("migration checkpoint cannot be read; recover it before retrying"),
    CHECKPOINT_WRITE_FAILED("migration checkpoint could not be committed; check storage and retry"),
}

data class LegacyLayoutMigrationWarning(
    val sourceFileName: String,
    val fingerprint: String?,
    val reason: LegacyLayoutMigrationFailure,
    val recordNumber: Int? = null,
) {
    /** Deliberately excludes source lines and exception messages, which can contain commands. */
    val message: String
        get() = "Legacy layout '$sourceFileName'" +
            (recordNumber?.let { " record $it" } ?: "") +
            ": ${reason.description}. Retained legacy files are unchanged."
}

data class LegacyLayoutMigrationResult(
    val profiles: List<LayoutProfile>,
    val warnings: List<LegacyLayoutMigrationWarning>,
)

class LegacyLayoutMigrationException(val result: LegacyLayoutMigrationResult) :
    IOException(result.warnings.joinToString("\n") { it.message })

/** Reads the retained legacy files and writes one repository profile per stable source identity. */
class LegacyLayoutProfileMigrator internal constructor(
    private val legacyDirectory: File,
    private val repository: LayoutProfileRepository,
    private val checkpoints: LegacyLayoutMigrationCheckpointStore,
) {
    constructor(legacyDirectory: File, repository: LayoutProfileRepository) : this(
        legacyDirectory,
        repository,
        LegacyLayoutMigrationCheckpointStore(File(legacyDirectory, "layout-migration")),
    )

    /**
     * Compatible list API. Warnings throw with the complete partial result rather than being lost.
     * Call [migrateWithReport] to handle independent source failures without an exception.
     */
    fun migrate(): List<LayoutProfile> {
        val result = migrateWithReport()
        if (result.warnings.isNotEmpty()) throw LegacyLayoutMigrationException(result)
        return result.profiles
    }

    /**
     * Read/validate a whole source, atomically save and verify its deterministic profile, THEN
     * checkpoint success. A crash in that window only causes replacement of the same profile ID.
     * Matching successes load the saved profile without rewriting subsequent user edits. Errors
     * remain retryable, and neither successful nor failed migration writes to retained sources.
     */
    fun migrateWithReport(): LegacyLayoutMigrationResult = synchronized(processLock) {
        val profiles = mutableListOf<LayoutProfile>()
        val warnings = mutableListOf<LegacyLayoutMigrationWarning>()
        for (source in SOURCES) {
            var fingerprint: String? = null
            var checkpoint: LegacyLayoutMigrationCheckpoint? = null
            try {
                val bytes = try {
                    Files.readAllBytes(File(legacyDirectory, source.fileName).toPath())
                } catch (_: NoSuchFileException) {
                    continue
                } catch (_: Exception) {
                    throw SourceFailure(LegacyLayoutMigrationFailure.UNREADABLE_SOURCE)
                }
                fingerprint = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                checkpoint = try {
                    checkpoints.read(source.profileId)
                } catch (_: Exception) {
                    warnings += source.warning(fingerprint, LegacyLayoutMigrationFailure.CHECKPOINT_READ_FAILED)
                    continue
                }
                if (checkpoint?.succeededFor(fingerprint, PARSER_VERSION) == true) {
                    try {
                        profiles += repository.loadProfile(source.profileId)
                        if (checkpoint.failureReason != null) {
                            recordCheckpoint(source, fingerprint, null, null, warnings)
                        }
                    } catch (_: Exception) {
                        warnings += source.warning(fingerprint, LegacyLayoutMigrationFailure.PROFILE_READ_FAILED)
                    }
                    continue
                }

                val profile = LayoutProfile(
                    id = source.profileId,
                    name = source.profileName,
                    buttons = readButtons(bytes, source),
                )
                try {
                    repository.saveProfile(profile)
                    if (repository.loadProfile(profile.id) != profile) {
                        throw IOException("Migrated layout profile was not committed")
                    }
                } catch (_: Exception) {
                    throw SourceFailure(LegacyLayoutMigrationFailure.PROFILE_SAVE_FAILED)
                }
                profiles += profile
                recordCheckpoint(source, fingerprint, null, null, warnings)
            } catch (failure: SourceFailure) {
                warnings += source.warning(fingerprint, failure.reason, failure.recordNumber)
                // An I/O failure happens before the normal checkpoint read. Do not erase an
                // earlier success (or an unreadable checkpoint) merely because the source failed.
                if (fingerprint == null) {
                    checkpoint = try {
                        checkpoints.read(source.profileId)
                    } catch (_: Exception) {
                        warnings += source.warning(fingerprint, LegacyLayoutMigrationFailure.CHECKPOINT_READ_FAILED)
                        continue
                    }
                }
                // A failed profile save may already have changed the saved bytes, so only carry
                // provenance through failures that happen before any repository write.
                val previousSuccess = checkpoint.takeUnless {
                    failure.reason == LegacyLayoutMigrationFailure.PROFILE_SAVE_FAILED
                }
                recordCheckpoint(source, fingerprint, failure.reason, failure.recordNumber, warnings, previousSuccess)
            }
        }
        LegacyLayoutMigrationResult(profiles.toList(), warnings.toList())
    }

    private fun recordCheckpoint(
        source: LegacyLayoutSource,
        fingerprint: String?,
        reason: LegacyLayoutMigrationFailure?,
        recordNumber: Int?,
        warnings: MutableList<LegacyLayoutMigrationWarning>,
        previousSuccess: LegacyLayoutMigrationCheckpoint? = null,
    ) {
        try {
            checkpoints.write(
                source.profileId,
                LegacyLayoutMigrationCheckpoint(
                    fingerprint, PARSER_VERSION, reason, recordNumber,
                    previousSuccess?.successfulFingerprint, previousSuccess?.successfulParserVersion,
                ),
            )
        } catch (_: Exception) {
            warnings += source.warning(fingerprint, LegacyLayoutMigrationFailure.CHECKPOINT_WRITE_FAILED)
        }
    }

    private fun readButtons(bytes: ByteArray, source: LegacyLayoutSource): List<LayoutButton> {
        val text = try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            throw SourceFailure(LegacyLayoutMigrationFailure.UNREADABLE_SOURCE)
        }

        return BufferedReader(StringReader(text)).useLines { lines ->
            lines.mapIndexed { index, line ->
                // The compatibility reader ignores surplus columns. Migration must not silently
                // truncate an ambiguous command/title containing the old unescaped delimiter.
                val separator = if (line.contains(SEPARATOR)) SEPARATOR else ","
                val fields = line.split(separator)
                val maximumFields = if (separator == SEPARATOR) 4 else 3
                if (fields.size > maximumFields) {
                    throw SourceFailure(LegacyLayoutMigrationFailure.AMBIGUOUS_SOURCE, index + 1)
                }
                try {
                    val record = LegacyLayoutReader.parseRecord(line, DEFAULT_LAYOUT_BUTTON_COLOR)
                    require(record.command.isNotBlank())
                    LayoutButton(
                        id = "${source.profileId}-button-${index + 1}",
                        title = record.title,
                        command = record.command,
                        width = record.span,
                        color = record.color,
                    )
                } catch (_: Exception) {
                    throw SourceFailure(LegacyLayoutMigrationFailure.MALFORMED_SOURCE, index + 1)
                }
            }.toList().also {
                if (it.isEmpty()) throw SourceFailure(LegacyLayoutMigrationFailure.MALFORMED_SOURCE)
            }
        }
    }

    private class SourceFailure(
        val reason: LegacyLayoutMigrationFailure,
        val recordNumber: Int? = null,
    ) : Exception()

    private data class LegacyLayoutSource(
        val fileName: String,
        val profileId: String,
        val profileName: String,
    ) {
        fun warning(fingerprint: String?, reason: LegacyLayoutMigrationFailure, recordNumber: Int? = null) =
            LegacyLayoutMigrationWarning(fileName, fingerprint, reason, recordNumber)
    }

    companion object {
        // Bump whenever the accepted formats or validation rules change.
        private const val PARSER_VERSION = 1
        private const val SEPARATOR = "<xmousesep>"
        private val processLock = Any()

        private val SOURCES = listOf(
            LegacyLayoutSource("keyLayoutFile.csv", ACTIVE_PROFILE_ID, "Active layout"),
            LegacyLayoutSource("keyFileOne.csv", PRESET_ONE_PROFILE_ID, "Preset 1"),
            LegacyLayoutSource("keyFileTwo.csv", PRESET_TWO_PROFILE_ID, "Preset 2"),
            LegacyLayoutSource("keyFileThree.csv", PRESET_THREE_PROFILE_ID, "Preset 3"),
        )

        const val ACTIVE_PROFILE_ID = "legacy-keyLayoutFile"
        const val PRESET_ONE_PROFILE_ID = "legacy-keyFileOne"
        const val PRESET_TWO_PROFILE_ID = "legacy-keyFileTwo"
        const val PRESET_THREE_PROFILE_ID = "legacy-keyFileThree"
    }
}
