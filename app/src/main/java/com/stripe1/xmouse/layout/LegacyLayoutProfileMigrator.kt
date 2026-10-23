package com.stripe1.xmouse.layout

import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Reads the retained legacy files and writes one repository profile per stable source identity. */
class LegacyLayoutProfileMigrator(
    private val legacyDirectory: File,
    private val repository: LayoutProfileRepository,
) {
    /**
     * Imports each legacy source that exists. Profile and button IDs derive from source identity
     * and record order, so rerunning a valid migration replaces the same repository records.
     * Legacy source files are only read; this migration does not alter or remove them.
     */
    fun migrate(): List<LayoutProfile> = SOURCES.mapNotNull { source ->
        val legacyFile = File(legacyDirectory, source.fileName)
        if (!legacyFile.exists()) return@mapNotNull null
        if (!legacyFile.isFile) {
            throw IOException("Legacy layout source '${source.fileName}' is not a file")
        }

        val buttons = readButtons(legacyFile, source)
        if (buttons.isEmpty()) {
            throw IOException("Legacy layout source '${source.fileName}' contains no buttons")
        }

        val profile = LayoutProfile(
            id = source.profileId,
            name = source.profileName,
            buttons = buttons,
        )
        repository.saveProfile(profile)
        profile
    }

    private fun readButtons(file: File, source: LegacyLayoutSource): List<LayoutButton> {
        val text = try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(file.readBytes())).toString()
        } catch (failure: Exception) {
            throw IOException("Could not read legacy layout source '${source.fileName}'", failure)
        }

        return BufferedReader(StringReader(text)).useLines { lines ->
            lines.mapIndexed { index, line ->
                val record = try {
                    LegacyLayoutReader.parseRecord(line, DEFAULT_LAYOUT_BUTTON_COLOR)
                } catch (failure: Exception) {
                    throw IOException(
                        "Could not parse legacy layout source '${source.fileName}' record ${index + 1}",
                        failure,
                    )
                }
                LayoutButton(
                    id = "${source.profileId}-button-${index + 1}",
                    title = record.title,
                    command = record.command,
                    width = record.span,
                    color = record.color,
                )
            }.toList()
        }
    }

    private data class LegacyLayoutSource(
        val fileName: String,
        val profileId: String,
        val profileName: String,
    )

    private companion object {
        val SOURCES = listOf(
            LegacyLayoutSource("keyLayoutFile.csv", "legacy-keyLayoutFile", "Active layout"),
            LegacyLayoutSource("keyFileOne.csv", "legacy-keyFileOne", "Preset 1"),
            LegacyLayoutSource("keyFileTwo.csv", "legacy-keyFileTwo", "Preset 2"),
            LegacyLayoutSource("keyFileThree.csv", "legacy-keyFileThree", "Preset 3"),
        )
    }
}
