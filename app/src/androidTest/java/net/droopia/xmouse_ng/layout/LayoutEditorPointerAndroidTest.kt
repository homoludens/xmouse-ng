package net.droopia.xmouse_ng.layout

import android.content.Context
import android.content.Intent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** One focused device test: real Compose pointer drag, preview, release, and visible width controls. */
@RunWith(AndroidJUnit4::class)
class LayoutEditorPointerAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun pointerMoveAndResizeKeepSavedProfileUntouchedAndRejectInvalidWidthInTheOpenDraft() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = LayoutProfileRepository(File(context.filesDir, LayoutEditorActivity.PROFILE_DIRECTORY))
        val saved = LayoutProfile(
            id = "pointer-${UUID.randomUUID()}",
            name = "Pointer test",
            buttons = listOf(
                LayoutButton(id = "source", title = "Source", command = "  echo '雪,raw'\nnext  ", width = 3),
                LayoutButton(id = "target", title = "Target", command = "target", width = 4),
                LayoutButton(id = "maximum", title = "Maximum", command = "max", width = 10),
            ),
        )
        repository.createProfile(saved)
        try {
            ActivityScenario.launch<LayoutEditorActivity>(
                Intent(context, LayoutEditorActivity::class.java)
                    .putExtra(LayoutEditorActivity.EXTRA_PROFILE_ID, saved.id),
            ).use {
                compose.awaitEditorTag("layout-editor-button-source")
                val source = compose.onNodeWithTag("layout-editor-button-source")
                source.performScrollTo()
                val sourceBounds = source.fetchSemanticsNode().boundsInRoot
                val targetBounds = compose.onNodeWithTag("layout-editor-button-target").fetchSemanticsNode().boundsInRoot
                val destination = Offset(targetBounds.right - targetBounds.width / 4f, targetBounds.center.y) - sourceBounds.topLeft
                source.performTouchInput {
                    down(center)
                    advanceEventTime(1000)
                    moveTo(destination, delayMillis = 100)
                }
                compose.onNodeWithTag("layout-editor-placement-preview").assertExists()
                assertEquals(null, repository.loadDraft(saved.id))
                source.performTouchInput { up() }

                val moved = saved.copy(buttons = listOf(saved.buttons[1], saved.buttons[0], saved.buttons[2]))
                compose.waitUntil(5_000) { repository.loadDraft(saved.id) == moved }
                assertEquals(moved, repository.loadDraft(saved.id))
                compose.onNodeWithText("Title: Source").assertExists()
                compose.onNodeWithTag("layout-editor-increase-width").performScrollTo().performTouchInput { click() }
                val resized = moved.copy(buttons = moved.buttons.map { if (it.id == "source") it.copy(width = 4) else it })
                compose.waitUntil(5_000) { repository.loadDraft(saved.id) == resized }
                assertEquals(resized, repository.loadDraft(saved.id))

                compose.onNodeWithTag("layout-editor-button-maximum").performScrollTo().performTouchInput { click() }
                compose.awaitEditorText("Title: Maximum")
                compose.onNodeWithTag("layout-editor-increase-width").performScrollTo().performTouchInput { click() }
                compose.awaitEditorTag("layout-editor-width-error")
                compose.onNodeWithTag("layout-editor-width-error").performScrollTo().assertIsDisplayed()
                assertEquals(resized, repository.loadDraft(saved.id))
                assertEquals(saved, repository.loadProfile(saved.id))
            }
        } finally {
            repository.deleteProfile(saved.id, confirmed = true)
        }
    }
}
