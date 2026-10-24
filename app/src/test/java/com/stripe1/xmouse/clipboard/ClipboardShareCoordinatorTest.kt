package com.stripe1.xmouse.clipboard

import com.stripe1.xmouse.transport.ClipboardTextBounds
import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardShareCoordinatorTest {
    private class FakeClipboard(var value: ClipboardRead) : PlainTextClipboardSource {
        var reads = 0
        override fun read(): ClipboardRead {
            reads++
            return value
        }
    }

    private val coordinator = ClipboardShareCoordinator()

    @Test fun noTapBackgroundOrDisconnectedSessionNeverReadsClipboard() {
        val clipboard = FakeClipboard(ClipboardRead.Text("private clipboard text"))

        assertEquals(ClipboardPreparation.NotTapped,
            coordinator.prepareForSend(false, true, true, clipboard))
        assertEquals(ClipboardPreparation.NotForeground,
            coordinator.prepareForSend(true, false, true, clipboard))
        assertEquals(ClipboardPreparation.ConnectFirst,
            coordinator.prepareForSend(true, true, false, clipboard))
        assertEquals(0, clipboard.reads)
    }

    @Test fun explicitReadyTapPreservesLiteralUnicodeWhitespaceAndNewlines() {
        val literal = "snowman ☃ and emoji 😀\n\t  quoted 'text' \"text\" `ticks` \$(not-a-command)  \n"
        val clipboard = FakeClipboard(ClipboardRead.Text(literal))

        val prepared = coordinator.prepareForSend(true, true, true, clipboard)

        assertEquals(ClipboardPreparation.Ready(literal), prepared)
        assertEquals(1, clipboard.reads)
    }

    @Test fun emptyNonTextAndUnavailableClipboardAreDistinct() {
        val clipboard = FakeClipboard(ClipboardRead.Empty)
        assertEquals(ClipboardPreparation.Empty, coordinator.prepareForSend(true, true, true, clipboard))
        clipboard.value = ClipboardRead.NonText
        assertEquals(ClipboardPreparation.NonText, coordinator.prepareForSend(true, true, true, clipboard))
        clipboard.value = ClipboardRead.Unavailable
        assertEquals(ClipboardPreparation.Unavailable, coordinator.prepareForSend(true, true, true, clipboard))
        assertEquals(3, clipboard.reads)
    }

    @Test fun exactUtf8BoundIsAcceptedAndOversizedOrMalformedTextIsRejectedWithoutTruncation() {
        val clipboard = FakeClipboard(ClipboardRead.Text("😀".repeat(ClipboardTextBounds.MAX_BYTES / 4)))
        val exact = coordinator.prepareForSend(true, true, true, clipboard)
        org.junit.Assert.assertTrue(exact is ClipboardPreparation.Ready)

        clipboard.value = ClipboardRead.Text("x".repeat(ClipboardTextBounds.MAX_BYTES + 1))
        assertEquals(ClipboardPreparation.TooLarge, coordinator.prepareForSend(true, true, true, clipboard))
        clipboard.value = ClipboardRead.Text("\uD800")
        assertEquals(ClipboardPreparation.Malformed, coordinator.prepareForSend(true, true, true, clipboard))
        assertEquals(3, clipboard.reads)
    }
}
