package com.stripe1.xmouse.layout

import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextRange
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.stripe1.xmouse.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Actions edit route, native editable source, validation, cancellation, Apply, and Save. */
@RunWith(AndroidJUnit4::class)
class XmlLayoutEditorAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun actionEditCanOpenXmlValidateCancelApplyAndSaveWithoutDispatch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = LayoutProfileRepository(File(context.filesDir, LayoutEditorActivity.PROFILE_DIRECTORY))
        val saved = LayoutProfile(
            id = "xml-android-${java.util.UUID.randomUUID()}",
            name = "XML Android test",
            buttons = listOf(
                LayoutButton("first", "First", "xdotool key alt+F4", 2, "#aBcDeF"),
                LayoutButton("second", "Second", "  printf 'opaque\r\n\t'  ", 5, "#80112233"),
            ),
        )
        repository.createProfile(saved)
        try {
            ActivityScenario.launch<ActionEditorActivity>(ActionEditorActivity.intent(context, saved.id, "first")).use { scenario ->
                eventually {
                    var ready = false
                    scenario.onActivity { ready = it.findViewById<View>(R.id.action_editor_open_advanced) != null }
                    ready
                }
                scenario.onActivity { it.findViewById<View>(R.id.action_editor_open_advanced).performClick() }
                compose.awaitEditorTag("layout-editor-xml-mode")
                compose.onNodeWithTag("layout-editor-xml-mode").performScrollTo().performClick()
                compose.awaitEditorTag("layout-editor-xml-source")

                val initialSource = LayoutProfileXml.serialize(saved)
                val sourceNode = compose.onNodeWithTag("layout-editor-xml-source").performScrollTo()
                val insertion = initialSource.indexOf("  <button")
                val typedComment = "<!-- rapid native text input -->\n"
                sourceNode.performTextInputSelection(TextRange(insertion, insertion))
                typedComment.forEach { sourceNode.performTextInput(it.toString()) }
                var expectedSource = initialSource.replaceRange(insertion, insertion, typedComment)
                compose.awaitEditorTextEquals("layout-editor-xml-source", expectedSource)

                val cursorInsertion = "<!-- cursor insertion -->\n"
                val insertionOffset = insertion + typedComment.length
                sourceNode.performTextInputSelection(TextRange(insertionOffset, insertionOffset))
                cursorInsertion.forEach { sourceNode.performTextInput(it.toString()) }
                expectedSource = expectedSource.replaceRange(insertionOffset, insertionOffset, cursorInsertion)
                compose.awaitEditorTextEquals("layout-editor-xml-source", expectedSource)

                val rawInvalid = "<layout version=\"1\" id=\"${saved.id}\""
                sourceNode.performTextReplacement(rawInvalid)
                compose.awaitEditorTextEquals("layout-editor-xml-source", rawInvalid)
                compose.onNodeWithTag("layout-editor-xml-validate").performScrollTo().performClick()
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithTag("layout-editor-xml-message", useUnmergedTree = true)
                        .fetchSemanticsNodes().isNotEmpty()
                }
                val validationMessage = compose.onNodeWithTag("layout-editor-xml-message", useUnmergedTree = true)
                    .fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }
                assertTrue("validation should show line and column: $validationMessage",
                    validationMessage.contains("Line 1, column"))
                compose.onNodeWithTag("layout-editor-xml-format").performScrollTo().performClick()
                compose.awaitEditorTextEquals("layout-editor-xml-source", rawInvalid)
                assertTrue(
                    "XML source mode must not render a Save action",
                    compose.onAllNodesWithTag("layout-editor-save").fetchSemanticsNodes().isEmpty(),
                )
                assertEquals(saved, repository.loadProfile(saved.id))
                assertNull(repository.loadDraft(saved.id))

                // Android Back-to-Visual asks before throwing away an invalid source buffer.
                compose.onNodeWithTag("layout-editor-xml-cancel").performClick()
                compose.onNodeWithText(context.getString(R.string.layout_editor_xml_discard)).performClick()
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithTag("layout-editor-xml-source").fetchSemanticsNodes().isEmpty()
                }
                assertEquals(saved, repository.loadProfile(saved.id))
                assertNull(repository.loadDraft(saved.id))

                compose.onNodeWithTag("layout-editor-xml-mode").performScrollTo().performClick()
                compose.awaitEditorTag("layout-editor-xml-source")
                val changed = saved.copy(buttons = listOf(saved.buttons[1], saved.buttons[0].copy(width = 7)))
                val valid = LayoutProfileXml.serialize(changed)
                val compact = valid.replace(Regex(">\\s*<"), "><")
                compose.onNodeWithTag("layout-editor-xml-source").performScrollTo().performTextReplacement(compact)
                compose.awaitEditorTextEquals("layout-editor-xml-source", compact)
                compose.onNodeWithTag("layout-editor-xml-validate").performScrollTo().performClick()
                compose.onNodeWithTag("layout-editor-xml-format").performScrollTo().performClick()
                compose.awaitEditorTextEquals("layout-editor-xml-source", valid)
                assertEquals(saved, repository.loadProfile(saved.id))
                assertNull(repository.loadDraft(saved.id))
                compose.onNodeWithTag("layout-editor-xml-apply").performClick()
                compose.waitUntil(5_000) { repository.loadDraft(saved.id) == changed }
                assertEquals(saved, repository.loadProfile(saved.id))

                compose.onNodeWithTag("layout-editor-save").performClick()
                compose.waitUntil(5_000) { repository.loadProfile(saved.id) == changed }
                assertNull(repository.loadDraft(saved.id))
            }
        } finally {
            if (repository.listProfiles().size == 1) {
                repository.createProfile(LayoutProfile(id = "xml-android-test-anchor", name = "Test anchor"))
            }
            repository.deleteProfile(saved.id, confirmed = true)
        }
    }

    @Test fun invalidXmlBufferSurvivesActivityRecreationInMemoryWithoutWritingProfileFiles() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = LayoutProfileRepository(File(context.filesDir, LayoutEditorActivity.PROFILE_DIRECTORY))
        val saved = LayoutProfile(
            id = "xml-recreate-${java.util.UUID.randomUUID()}",
            name = "XML recreation test",
            buttons = listOf(LayoutButton("button", "Button", "raw command", 4, "#aBcDeF")),
        )
        repository.createProfile(saved)
        try {
            ActivityScenario.launch<LayoutEditorActivity>(
                Intent(context, LayoutEditorActivity::class.java)
                    .putExtra(LayoutEditorActivity.EXTRA_PROFILE_ID, saved.id),
            ).use { scenario ->
                compose.awaitEditorTag("layout-editor-xml-mode")
                compose.onNodeWithTag("layout-editor-xml-mode").performScrollTo().performClick()
                compose.awaitEditorTag("layout-editor-xml-source")
                val raw = "<layout version=\"1\" id=\"${saved.id}\" name=\"unfinished\""
                compose.onNodeWithTag("layout-editor-xml-source").performScrollTo().performTextReplacement(raw)
                compose.awaitEditorTextEquals("layout-editor-xml-source", raw)
                assertNull(repository.loadDraft(saved.id))
                assertEquals(saved, repository.loadProfile(saved.id))

                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                compose.awaitEditorTag("layout-editor-back-save")
                compose.onNodeWithTag("layout-editor-back-save").assertIsNotEnabled()
                compose.onNodeWithTag("layout-editor-back-cancel").performClick()
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithTag("layout-editor-back-save").fetchSemanticsNodes().isEmpty()
                }
                compose.awaitEditorTextEquals("layout-editor-xml-source", raw)

                scenario.recreate()
                compose.awaitEditorTag("layout-editor-xml-source")
                compose.awaitEditorTextEquals("layout-editor-xml-source", raw)
                assertNull(repository.loadDraft(saved.id))
                assertEquals(saved, repository.loadProfile(saved.id))
            }
        } finally {
            if (repository.listProfiles().size == 1) {
                repository.createProfile(LayoutProfile(id = "xml-android-test-anchor", name = "Test anchor"))
            }
            repository.deleteProfile(saved.id, confirmed = true)
        }
    }

    @Test fun androidPlatformSaxRoundTripsOpaqueTextAndRejectsUnsafeOrForeignXml() {
        val profile = LayoutProfile(
            id = "android-parser-profile",
            name = "  Android\tprofile\r\n😀  ",
            buttons = listOf(
                LayoutButton(
                    id = "custom10",
                    title = "  Title\t<&>\"😀  ",
                    command = "  xdotool key alt+F4\r\n\tprintf '%s' \"<&>😀]]>\"\u0085  ",
                    width = 8,
                    color = "#80aBcDeF",
                ),
            ),
        )
        val source = LayoutProfileXml.serialize(profile)
        assertEquals(profile, LayoutProfileXml.parse(source, profile.id))

        val foreignIdentity = runCatching { LayoutProfileXml.parse(source, "some-other-profile") }.exceptionOrNull()
        assertTrue(foreignIdentity is LayoutXmlException)
        val dtd = "<!DOCTYPE layout SYSTEM \"urn:xmouse:test-fixture:external-dtd\"><layout/>"
        val internalEntity = "<!DOCTYPE layout [<!ENTITY x \"synthetic\">]>" + source
        val undeclaredEntity = source.replace("<command>", "<command>&fixture;", ignoreCase = false)
        val xml11 = source.replaceFirst("<?xml version=\"1.0\"", "<?xml version=\"1.1\"")
        val bomXml11 = "\uFEFF$xml11"
        val invalidTextReference = source.replace("<command>", "<command>&#x1;")
        val invalidAttributeReference = source.replace(
            "id=\"android-parser-profile\"", "id=\"android-parser&#x1;profile\"",
        )
        val duplicateAttribute = source.replace("width=\"8\"", "width=\"8\" width=\"9\"")
        val xinclude = "<layout xmlns:xi=\"http://www.w3.org/2001/XInclude\" version=\"1\" id=\"android-parser-profile\" name=\"x\" columns=\"10\"><xi:include href=\"urn:xmouse:test-fixture:include\"/></layout>"
        listOf(
            dtd, internalEntity, undeclaredEntity, xml11, bomXml11, invalidTextReference,
            invalidAttributeReference, duplicateAttribute, xinclude, "<layout version=\"1\"",
        ).forEach { unsafe ->
            val failure = runCatching { LayoutProfileXml.parse(unsafe, profile.id) }.exceptionOrNull()
            assertNotNull("The Android SAX parser must return a useful rejection, not process $unsafe", failure)
            assertTrue("Expected a layout XML validation failure, got $failure", failure is LayoutXmlException)
        }
    }

    @Test fun captureOptInXmlEditorScreensInBothThemesAtNormalAndNarrowLargeFont() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("captureXmlLayoutEditor") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val device = UiDevice.getInstance(instrumentation)
        val repository = LayoutProfileRepository(File(context.filesDir, LayoutEditorActivity.PROFILE_DIRECTORY))
        val profile = LayoutProfile(
            id = "xml-capture-${java.util.UUID.randomUUID()}",
            name = "XML source capture",
            buttons = listOf(
                LayoutButton("capture-one", "Open", "synthetic inert command", 3, "#80aBcDeF"),
                LayoutButton("capture-two", "Close", "another inert command", 4, "#AAbbCC"),
            ),
        )
        repository.createProfile(profile)
        val screenshotDirectory = File(context.getExternalFilesDir(null), "xml-layout-editor-20261027")
            .apply { mkdirs() }

        fun shell(command: String): String = device.executeShellCommand(command).trim()
        val originalSizeOverride = Regex("Override size: (\\d+x\\d+)")
            .find(shell("wm size"))?.groupValues?.get(1)
        val originalDensityOverride = Regex("Override density: (\\d+)")
            .find(shell("wm density"))?.groupValues?.get(1)
        val originalFontScale = shell("settings get system font_scale").ifBlank { "1.0" }
        val originalNightMode = Regex("Night mode: (yes|no|auto)")
            .find(shell("cmd uimode night"))?.groupValues?.get(1) ?: "auto"

        fun restoreDisplay() {
            shell(if (originalSizeOverride == null) "wm size reset" else "wm size $originalSizeOverride")
            shell(if (originalDensityOverride == null) "wm density reset" else "wm density $originalDensityOverride")
            shell("settings put system font_scale $originalFontScale")
        }

        fun capture(name: String) {
            instrumentation.waitForIdleSync()
            device.waitForIdle()
            Thread.sleep(400)
            val file = File(screenshotDirectory, "$name.png")
            assertTrue("capture $name", device.takeScreenshot(file))
            assertTrue("screenshot $name is nonempty", file.isFile && file.length() > 0)
            assertTrue("public evidence export requires Android 10+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.png")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/XMouseNGXmlLayoutEditor20261027-final-review5")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = requireNotNull(context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values,
            )) { "MediaStore insert failed for $name" }
            try {
                context.contentResolver.openOutputStream(uri, "w")!!.use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                }
                assertEquals("screenshot $name is published to the owned emulator", 1,
                    context.contentResolver.update(uri,
                        ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
            } catch (failure: Throwable) {
                context.contentResolver.delete(uri, null, null)
                throw failure
            }
        }

        fun enterXml() {
            compose.awaitEditorTag("layout-editor-xml-mode")
            compose.onNodeWithTag("layout-editor-xml-mode").performScrollTo().performClick()
            compose.awaitEditorTag("layout-editor-xml-source")
            compose.onNodeWithTag("layout-editor-xml-source").performScrollTo().assertIsDisplayed()
        }

        try {
            restoreDisplay()
            for (dark in listOf(false, true)) {
                val theme = if (dark) "dark" else "light"
                shell("cmd uimode night ${if (dark) "yes" else "no"}")
                ActivityScenario.launch<LayoutEditorActivity>(
                    Intent(context, LayoutEditorActivity::class.java)
                        .putExtra(LayoutEditorActivity.EXTRA_PROFILE_ID, profile.id),
                ).use { scenario ->
                    enterXml()
                    scenario.onActivity { activity ->
                        assertEquals(dark, (activity.resources.configuration.uiMode and
                            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
                    }
                    capture("xml-editor-normal-$theme")
                }

                shell("wm size 840x1920")
                shell("wm density 420")
                shell("settings put system font_scale 1.5")
                ActivityScenario.launch<LayoutEditorActivity>(
                    Intent(context, LayoutEditorActivity::class.java)
                        .putExtra(LayoutEditorActivity.EXTRA_PROFILE_ID, profile.id),
                ).use { scenario ->
                    enterXml()
                    scenario.onActivity { activity ->
                    assertEquals(320, activity.resources.configuration.screenWidthDp)
                    assertEquals(1.5f, activity.resources.configuration.fontScale)
                    }
                    capture("xml-editor-320dp-font150-$theme")

                    val invalidSource = "<layout version=\"1\" id=\"${profile.id}\">\n  <button id=\"unfinished\""
                    compose.onNodeWithTag("layout-editor-xml-source")
                        .performScrollTo().performTextReplacement(invalidSource)
                    compose.awaitEditorTextEquals("layout-editor-xml-source", invalidSource)
                    compose.onNodeWithTag("layout-editor-xml-validate").performScrollTo().performClick()
                    compose.waitUntil(5_000) {
                        compose.onAllNodesWithTag("layout-editor-xml-message", useUnmergedTree = true)
                            .fetchSemanticsNodes().isNotEmpty()
                    }
                    compose.onNodeWithTag("layout-editor-xml-message", useUnmergedTree = true)
                        .performScrollTo().assertIsDisplayed()
                    compose.onNodeWithTag("layout-editor-xml-source").assertIsDisplayed()
                    compose.onNodeWithTag("layout-editor-xml-footer").assertIsDisplayed()
                    eventually {
                        var visible = false
                        scenario.onActivity { activity ->
                            visible = ViewCompat.getRootWindowInsets(activity.window.decorView)
                                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                        }
                        visible
                    }
                    val footerBounds = compose.onNodeWithTag("layout-editor-xml-footer")
                        .fetchSemanticsNode().boundsInRoot
                    scenario.onActivity { activity ->
                        val composeRoot = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                        val composeLocation = IntArray(2)
                        composeRoot.getLocationOnScreen(composeLocation)
                        val insets = requireNotNull(ViewCompat.getRootWindowInsets(activity.window.decorView))
                        val keyboardTop = device.displayHeight - insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                        assertTrue("XML footer remains above the actually visible software keyboard",
                            composeLocation[1] + footerBounds.bottom <= keyboardTop + 1)
                    }
                    capture("xml-editor-320dp-font150-validation-keyboard-footer-$theme")
                }
                restoreDisplay()
            }
        } finally {
            restoreDisplay()
            shell("cmd uimode night $originalNightMode")
            if (repository.listProfiles().size == 1) {
                repository.createProfile(LayoutProfile(id = "xml-capture-anchor", name = "Test anchor"))
            }
            repository.deleteProfile(profile.id, confirmed = true)
        }
    }

    private fun eventually(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(20)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        org.junit.Assert.assertTrue("Action editor did not become ready", predicate())
    }
}
