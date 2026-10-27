package com.stripe1.xmouse.layout

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onNodeWithTag

/** Compose idleness does not wait for the repository's serial I/O workers. */
internal fun ComposeTestRule.awaitEditorTag(tag: String) = waitUntil(5_000) {
    onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
}

internal fun ComposeTestRule.awaitEditorText(text: String) = waitUntil(5_000) {
    onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
}

/** Waits for the serialized background editor worker to publish its latest source buffer. */
internal fun ComposeTestRule.awaitEditorTextEquals(tag: String, text: String) {
    var actual: String? = null
    val result = runCatching {
        waitUntil(5_000) {
            actual = runCatching {
                val config = onNodeWithTag(tag).fetchSemanticsNode().config
                if (config.contains(SemanticsProperties.EditableText)) config[SemanticsProperties.EditableText].text else null
            }.getOrNull()
            actual == text
        }
    }
    if (result.isFailure) {
        throw AssertionError("Expected editor source <$text>, but it remained <$actual>.", result.exceptionOrNull())
    }
}
