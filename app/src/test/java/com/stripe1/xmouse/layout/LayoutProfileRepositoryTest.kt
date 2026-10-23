package com.stripe1.xmouse.layout

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutProfileRepositoryTest {
    @Test
    fun atomicRoundTripAndInterruptedSaveKeepSavedProfileAndRetryableDraft() {
        val files = FakeLayoutProfileFileOps()
        val firstRepository = LayoutProfileRepository(files)
        val saved = profile(name = "Büro, original", command = "printf '雪, \"raw\"'\nsecond line")
        firstRepository.saveProfile(saved)

        val recreated = LayoutProfileRepository(files)
        assertEquals(saved, recreated.loadProfile(saved.id))

        val attempted = profile(name = "改訂 draft", command = "echo  keep spaces, commas\nline two")
        files.interruptNextProfileWrite = true
        val writeFailure = assertThrows(LayoutProfileWriteException::class.java) {
            recreated.saveProfile(attempted)
        }
        assertEquals(LayoutProfileRecord.PROFILE, writeFailure.record)
        assertTrue(files.truncatedStageWasWritten)

        val afterInterruption = LayoutProfileRepository(files)
        assertEquals(saved, afterInterruption.loadProfile(saved.id))
        assertEquals(attempted, afterInterruption.loadDraft(saved.id))

        afterInterruption.saveProfile(afterInterruption.loadDraft(saved.id)!!)
        val afterRetry = LayoutProfileRepository(files)
        assertEquals(attempted, afterRetry.loadProfile(saved.id))
        assertNull(afterRetry.loadDraft(saved.id))

        files.failNextRead = true
        val readFailure = assertThrows(LayoutProfileReadException::class.java) {
            afterRetry.loadProfile(saved.id)
        }
        assertEquals(LayoutProfileRecord.PROFILE, readFailure.record)
        assertFalse(files.interruptNextProfileWrite)
    }

    private fun profile(name: String, command: String) = LayoutProfile(
        id = "stable-profile",
        name = name,
        buttons = listOf(
            LayoutButton(
                id = "stable-button",
                title = "按鈕, control",
                command = command,
                width = 4,
                color = "#80ABCDEF",
            ),
        ),
    )

    private class FakeLayoutProfileFileOps : LayoutProfileFileOps {
        private val committed = mutableMapOf<String, ByteArray>()
        private val staging = mutableMapOf<String, ByteArray>()
        var interruptNextProfileWrite = false
        var truncatedStageWasWritten = false
            private set
        var failNextRead = false

        override fun readAtomically(fileName: String): ByteArray? {
            if (failNextRead) {
                failNextRead = false
                throw IOException("injected read failure")
            }
            return committed[fileName]?.copyOf()
        }

        override fun writeAtomically(fileName: String, bytes: ByteArray) {
            if (interruptNextProfileWrite && fileName.endsWith(".profile.json")) {
                interruptNextProfileWrite = false
                staging[fileName] = bytes.copyOfRange(0, bytes.size / 2)
                truncatedStageWasWritten = true
                throw IOException("injected interruption after truncated staging write")
            }
            committed[fileName] = bytes.copyOf()
            staging.remove(fileName)
        }

        override fun deleteAtomically(fileName: String) {
            committed.remove(fileName)
            staging.remove(fileName)
        }
    }
}
