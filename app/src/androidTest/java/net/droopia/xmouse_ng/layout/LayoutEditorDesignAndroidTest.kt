package net.droopia.xmouse_ng.layout

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import net.droopia.xmouse_ng.R
import java.io.File
import java.util.Locale
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Focused coverage for the redesigned layout-editor shell and contextual operations. */
@RunWith(AndroidJUnit4::class)
class LayoutEditorDesignAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun modeTabsHelpAndPersistentFootersExposeOnlyActionsForTheCurrentMode() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = repository(context)
        val saved = profile("shell", listOf(LayoutButton("one", "One", "opaque", 3, "#A1B2C3")))
        repository.createProfile(saved)
        try {
            ActivityScenario.launch<LayoutEditorActivity>(intent(context, saved.id)).use {
                compose.awaitEditorTag("layout-editor-visual-footer")
                compose.onNodeWithTag("layout-editor-back").assertExists()
                compose.onNodeWithContentDescription("Back").assertExists()
                compose.onNodeWithTag("layout-editor-help").performClick()
                compose.onNodeWithText("Visual operations update a recoverable draft", substring = true).assertExists()
                compose.onNodeWithTag("layout-editor-help-close").performClick()

                compose.onNodeWithTag("layout-editor-visual-mode").assertIsSelected().assertIsEnabled()
                compose.onNodeWithTag("layout-editor-xml-mode").assertIsNotSelected().assertIsEnabled()
                val visualFooter = compose.onNodeWithTag("layout-editor-visual-footer").assertIsDisplayed()
                val visualFooterBounds = visualFooter.fetchSemanticsNode().boundsInRoot
                val visualSaveBounds = compose.onNodeWithTag("layout-editor-save").fetchSemanticsNode().boundsInRoot
                val visualDiscardBounds = compose.onNodeWithTag("layout-editor-discard").fetchSemanticsNode().boundsInRoot
                assertTrue("Save must be in the persistent footer", visualSaveBounds.bottom <= visualFooterBounds.bottom)
                assertTrue("Discard must be in the persistent footer", visualDiscardBounds.top >= visualFooterBounds.top &&
                    visualDiscardBounds.bottom <= visualFooterBounds.bottom)

                compose.onNodeWithTag("layout-editor-xml-mode").performScrollTo().performClick()
                compose.awaitEditorTag("layout-editor-xml-footer")
                compose.onNodeWithTag("layout-editor-xml-mode").assertIsSelected().assertIsEnabled()
                compose.onNodeWithTag("layout-editor-visual-mode").assertIsNotSelected().assertIsEnabled()
                compose.onNodeWithTag("layout-editor-xml-cancel").assertIsDisplayed()
                compose.onNodeWithTag("layout-editor-xml-apply").assertIsDisplayed()
                assertTrue(
                    "XML must not render Save or Discard",
                    compose.onAllNodesWithTag("layout-editor-save").fetchSemanticsNodes().isEmpty() &&
                        compose.onAllNodesWithTag("layout-editor-discard").fetchSemanticsNodes().isEmpty(),
                )

                val originalSource = LayoutProfileXml.serialize(saved)
                val editedSource = originalSource.replace("name=\"${saved.name}\"", "name=\"XML draft\"")
                assertTrue("The fixture must produce an XML-only edit", editedSource != originalSource)
                compose.onNodeWithTag("layout-editor-xml-source").performScrollTo().performTextReplacement(editedSource)
                compose.awaitEditorTextEquals("layout-editor-xml-source", editedSource)
                compose.onNodeWithText("XML edited").assertExists()
                compose.onNodeWithTag("layout-editor-xml-cancel").performClick()
                compose.onNodeWithText(context.getString(R.string.layout_editor_xml_discard_title)).assertExists()
                compose.onNodeWithText(context.getString(R.string.layout_editor_cancel)).performClick()
                compose.awaitEditorTextEquals("layout-editor-xml-source", editedSource)

                clickXmlMoreItem(context.getString(R.string.layout_editor_xml_reset))
                compose.onNodeWithText(context.getString(R.string.layout_editor_xml_discard_title)).assertExists()
                compose.onNodeWithText(context.getString(R.string.layout_editor_cancel)).performClick()
                compose.awaitEditorTextEquals("layout-editor-xml-source", editedSource)
                clickXmlMoreItem(context.getString(R.string.layout_editor_xml_reset))
                compose.onNodeWithText(context.getString(R.string.layout_editor_xml_reset)).performClick()
                compose.awaitEditorTextEquals("layout-editor-xml-source", originalSource)
                compose.onNodeWithText("Saved").assertExists()

                clickXmlMoreItem(context.getString(R.string.layout_editor_xml_help))
                compose.onNodeWithText("Version 1 uses a", substring = true).assertExists()
                compose.onNodeWithTag("layout-editor-help-close").performClick()
                compose.onNodeWithTag("layout-editor-visual-mode").performScrollTo().performClick()
                compose.awaitEditorTag("layout-editor-visual-footer")
                compose.onNodeWithTag("layout-editor-visual-mode").assertIsSelected()
            }
        } finally {
            if (repository.listProfiles().size == 1) {
                repository.createProfile(LayoutProfile(id = "design-test-anchor", name = "Test anchor"))
            }
            repository.deleteProfile(saved.id, confirmed = true)
        }
    }

    @Test
    fun deleteUsesTheConfirmedStableButtonIdAndRemainsUndoable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = repository(context)
        val saved = profile(
            "delete",
            listOf(
                LayoutButton("target", "Target", "target raw command", 2, "#123456"),
                LayoutButton("other", "Other", "other", 4, "#ABCDEF"),
            ),
        )
        repository.createProfile(saved)
        try {
            ActivityScenario.launch<LayoutEditorActivity>(intent(context, saved.id)).use {
                compose.awaitEditorTag("layout-editor-button-target")
                compose.onNodeWithTag("layout-editor-button-target").performScrollTo().performClick()
                compose.onNodeWithTag("layout-editor-selected-button-controls").assertIsDisplayed()
                compose.onNodeWithTag("layout-editor-selection-more").performScrollTo().performClick()
                compose.onNodeWithTag("layout-editor-move-earlier").assertIsNotEnabled()
                compose.onNodeWithText("Move earlier (already first)").assertExists()
                compose.onNodeWithTag("layout-editor-delete-button").performClick()
                compose.onNodeWithText("Delete “Target” from this draft?", substring = true).assertExists()
                compose.onNodeWithTag("layout-editor-delete-cancel").performClick()
                assertNull(repository.loadDraft(saved.id))

                compose.onNodeWithTag("layout-editor-selection-more").performScrollTo().performClick()
                compose.onNodeWithTag("layout-editor-delete-button").performClick()
                compose.onNodeWithTag("layout-editor-delete-confirm").performClick()
                val deletedDraft = saved.copy(buttons = listOf(saved.buttons.last()))
                compose.waitUntil(5_000) { repository.loadDraft(saved.id) == deletedDraft }
                assertEquals("Deletion changes the draft only", saved, repository.loadProfile(saved.id))
                compose.onNodeWithTag("layout-editor-selected-button-controls").assertDoesNotExist()

                compose.onNodeWithTag("layout-editor-undo").performScrollTo().performClick()
                compose.waitUntil(5_000) { repository.loadDraft(saved.id) == null }
                assertEquals(saved, repository.loadProfile(saved.id))
            }
        } finally {
            repository.deleteProfile(saved.id, confirmed = true)
        }
    }

    @Test
    fun visualDiscardConfirmsThenRestoresTheSavedLayoutWithoutLeavingTheEditor() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = repository(context)
        val saved = profile("discard", listOf(LayoutButton("one", "One", "opaque", 3, "#102938")))
        repository.createProfile(saved)
        try {
            ActivityScenario.launch<LayoutEditorActivity>(intent(context, saved.id)).use {
                compose.awaitEditorTag("layout-editor-button-one")
                compose.onNodeWithTag("layout-editor-button-one").performScrollTo().performClick()
                compose.onNodeWithTag("layout-editor-increase-width").performScrollTo().performClick()
                val changed = saved.copy(buttons = listOf(saved.buttons.single().copy(width = 4)))
                compose.waitUntil(5_000) { repository.loadDraft(saved.id) == changed }
                compose.onNodeWithText("Unsaved changes").assertExists()
                compose.onNodeWithTag("layout-editor-save").assertIsEnabled()
                compose.onNodeWithTag("layout-editor-discard").assertIsEnabled()

                compose.onNodeWithTag("layout-editor-discard").performClick()
                compose.onNodeWithText(context.getString(R.string.layout_editor_discard_title)).assertExists()
                compose.onNodeWithTag("layout-editor-discard-cancel").performClick()
                assertEquals(changed, repository.loadDraft(saved.id))

                compose.onNodeWithTag("layout-editor-discard").performClick()
                compose.onNodeWithTag("layout-editor-discard-confirm").performClick()
                compose.waitUntil(5_000) { repository.loadDraft(saved.id) == null }
                assertEquals(saved, repository.loadProfile(saved.id))
                compose.onNodeWithTag("layout-editor-visual-footer").assertIsDisplayed()
                compose.onNodeWithText("Saved").assertExists()
                compose.onNodeWithTag("layout-editor-save").assertIsNotEnabled()
                compose.onNodeWithTag("layout-editor-discard").assertIsNotEnabled()
            }
        } finally {
            repository.deleteProfile(saved.id, confirmed = true)
        }
    }

    @Test
    fun actualButtonCountsAndStoredValuesSurviveSynthetic57And80ButtonLayouts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = repository(context)
        val profiles = listOf(profile("preserve57", syntheticButtons(57)), profile("preserve80", syntheticButtons(80)))
        profiles.forEach(repository::createProfile)
        try {
            profiles.forEach { saved ->
                ActivityScenario.launch<LayoutEditorActivity>(intent(context, saved.id)).use {
                    compose.awaitEditorTag("layout-editor-button-count")
                    compose.onNodeWithText(context.getString(R.string.layout_editor_saved_buttons, saved.buttons.size))
                        .assertExists()
                    compose.onNodeWithTag("layout-editor-button-${saved.buttons.last().id}")
                        .performScrollTo().assertIsDisplayed()
                    assertEquals("Opening is read-only and retains all ordered data", saved, repository.loadProfile(saved.id))
                    assertNull(repository.loadDraft(saved.id))
                }
            }
        } finally {
            profiles.forEach { saved ->
                if (repository.listProfiles().size == 1) {
                    repository.createProfile(LayoutProfile(id = "design-test-anchor", name = "Test anchor"))
                }
                repository.deleteProfile(saved.id, confirmed = true)
            }
        }
    }

    private fun clickXmlMoreItem(text: String) {
        compose.onNodeWithTag("layout-editor-xml-more").performScrollTo().performClick()
        try {
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (failure: Throwable) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val directory = requireNotNull(context.getExternalFilesDir(null))
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            device.takeScreenshot(File(directory, "xml-menu-timeout.png"))
            device.dumpWindowHierarchy(File(directory, "xml-menu-timeout.xml"))
            throw failure
        }
        compose.onNodeWithText(text).performClick()
    }

    private fun repository(context: Context) =
        LayoutProfileRepository(File(context.filesDir, LayoutEditorActivity.PROFILE_DIRECTORY))

    private fun intent(context: Context, id: String) =
        Intent(context, LayoutEditorActivity::class.java).putExtra(LayoutEditorActivity.EXTRA_PROFILE_ID, id)

    private fun profile(suffix: String, buttons: List<LayoutButton>) = LayoutProfile(
        id = "design-${suffix}-${UUID.randomUUID()}",
        name = "Design test $suffix",
        buttons = buttons,
    )

    private fun syntheticButtons(count: Int): List<LayoutButton> = (0 until count).map { index ->
        LayoutButton(
            id = "synthetic-$index",
            title = "Button $index",
            command = "  opaque command $index\r\n  ",
            width = index % 10 + 1,
            color = String.format(Locale.ROOT, "#%06X", index * 7919 % 0xFFFFFF),
        )
    }
}
