package com.stripe1.xmouse.layout

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Focused semantics coverage for accessible, non-drag editor operations. */
@RunWith(AndroidJUnit4::class)
class LayoutEditorAccessibilityAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun labeledMoveAndResizeControlsSaveTheSelectedButton() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = LayoutProfileRepository(File(context.filesDir, LayoutEditorActivity.PROFILE_DIRECTORY))
        val saved = LayoutProfile(
            id = "accessibility-${UUID.randomUUID()}",
            name = "Accessibility test",
            buttons = listOf(
                LayoutButton("first", "First", "first", 3, "#123456"),
                LayoutButton("target", "Target", "  echo '雪,raw'\nnext  ", 2, "#a1B2c3"),
                LayoutButton("third", "Third", "third", 1, "#654321"),
                LayoutButton("fourth", "Fourth", "fourth", 2, "#ABCDEF"),
            ),
        )
        repository.createProfile(saved)
        try {
            ActivityScenario.launch<LayoutEditorActivity>(
                Intent(context, LayoutEditorActivity::class.java)
                    .putExtra(LayoutEditorActivity.EXTRA_PROFILE_ID, saved.id),
            ).use {
                compose.awaitEditorTag("layout-editor-button-target")
                val selected = compose.onNodeWithContentDescription(
                    "Target, button 2 of 4, width 2 columns",
                ).performScrollTo()
                selected.performClick()
                compose.awaitEditorTag("layout-editor-move-earlier")
                selected.assertIsSelected()

                val moveEarlier = compose.onNodeWithContentDescription(
                    "Target, button 2 of 4. Move earlier",
                ).performScrollTo()
                val targetBounds = moveEarlier.fetchSemanticsNode().boundsInRoot
                val minimumTargetPx = 48f * context.resources.displayMetrics.density
                assertTrue("Move action must be at least 48dp wide", targetBounds.width >= minimumTargetPx)
                assertTrue("Move action must be at least 48dp high", targetBounds.height >= minimumTargetPx)
                moveEarlier.performClick()

                compose.waitUntil(5_000) { repository.loadDraft(saved.id)?.buttons?.firstOrNull()?.id == "target" }

                compose.onNodeWithContentDescription("Target, button 1 of 4. Move later")
                    .performScrollTo().performClick()
                // Returning exactly to the saved order intentionally has no pending draft.
                compose.waitUntil(5_000) { repository.loadDraft(saved.id) == null }
                assertEquals(saved, repository.loadProfile(saved.id))
                compose.onNodeWithContentDescription("Target, button 2 of 4. Move later")
                    .performScrollTo().performClick()
                compose.waitUntil(5_000) { repository.loadDraft(saved.id)?.buttons?.getOrNull(2)?.id == "target" }
                compose.onNodeWithContentDescription("Target, width 2 columns. Increase width")
                    .performScrollTo().performClick()

                val resized = saved.copy(
                    buttons = listOf(
                        saved.buttons[0],
                        saved.buttons[2],
                        saved.buttons[1].copy(width = 3),
                        saved.buttons[3],
                    ),
                )
                compose.waitUntil(5_000) { repository.loadDraft(saved.id) == resized }
                assertEquals(resized, repository.loadDraft(saved.id))
                compose.onNodeWithTag("layout-editor-save").performScrollTo().performClick()
                compose.waitUntil(5_000) { repository.loadProfile(saved.id) == resized }
                assertEquals(resized, repository.loadProfile(saved.id))
            }
        } finally {
            repository.deleteProfile(saved.id, confirmed = true)
        }
    }
}
