package com.stripe1.xmouse.clipboard

import com.stripe1.xmouse.transport.ClipboardTextBounds
import com.stripe1.xmouse.transport.ClipboardTextValidity

sealed interface ClipboardRead {
    data class Text(val value: String) : ClipboardRead
    data object Empty : ClipboardRead
    data object NonText : ClipboardRead
    data object Unavailable : ClipboardRead
}

fun interface PlainTextClipboardSource {
    fun read(): ClipboardRead
}

sealed interface ClipboardPreparation {
    data class Ready(val text: String) : ClipboardPreparation
    data object NotTapped : ClipboardPreparation
    data object NotForeground : ClipboardPreparation
    data object ConnectFirst : ClipboardPreparation
    data object Empty : ClipboardPreparation
    data object NonText : ClipboardPreparation
    data object TooLarge : ClipboardPreparation
    data object Malformed : ClipboardPreparation
    data object Unavailable : ClipboardPreparation
}

/** Reads no clipboard data until all deliberate-action and foreground/session gates pass. */
class ClipboardShareCoordinator {
    fun prepareForSend(
        tapped: Boolean,
        foregroundVisible: Boolean,
        sessionReady: Boolean,
        source: PlainTextClipboardSource,
    ): ClipboardPreparation {
        if (!tapped) return ClipboardPreparation.NotTapped
        if (!foregroundVisible) return ClipboardPreparation.NotForeground
        if (!sessionReady) return ClipboardPreparation.ConnectFirst

        return when (val read = source.read()) {
            ClipboardRead.Empty -> ClipboardPreparation.Empty
            ClipboardRead.NonText -> ClipboardPreparation.NonText
            ClipboardRead.Unavailable -> ClipboardPreparation.Unavailable
            is ClipboardRead.Text -> when {
                read.value.isEmpty() -> ClipboardPreparation.Empty
                else -> when (ClipboardTextBounds.validate(read.value)) {
                    ClipboardTextValidity.VALID -> ClipboardPreparation.Ready(read.value)
                    ClipboardTextValidity.TOO_LARGE -> ClipboardPreparation.TooLarge
                    ClipboardTextValidity.MALFORMED -> ClipboardPreparation.Malformed
                }
            }
        }
    }
}
