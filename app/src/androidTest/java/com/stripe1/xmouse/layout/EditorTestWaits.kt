package com.stripe1.xmouse.layout

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText

/** Compose idleness does not wait for the repository's serial I/O workers. */
internal fun ComposeTestRule.awaitEditorTag(tag: String) = waitUntil(5_000) {
    onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
}

internal fun ComposeTestRule.awaitEditorText(text: String) = waitUntil(5_000) {
    onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
}
