package com.stripe1.xmouse.layout

import android.content.Context
import android.view.View
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stripe1.xmouse.R
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real native Activity/listeners with a uniquely owned synthetic profile, never a command sink. */
@RunWith(AndroidJUnit4::class)
class NativeActionEditorAndroidTest {
    @Test fun metadataSaveRecreationAndReopenKeepExactLegacyValuesAndCancelDoesNotWrite() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = LayoutProfileRepository(File(context.filesDir, LayoutEditorActivity.PROFILE_DIRECTORY))
        val original = LayoutProfile(id = "native-form-${UUID.randomUUID()}", name = "Synthetic form fixture",
            buttons = listOf(LayoutButton("first", "First", "key alt+F4", 5, "#FFFFFF"),
                LayoutButton("custom", "Fixture custom", "  xdotool mousemove 7 9\nprintf '雪'  ", 7, "#80a1B2c3")))
        repository.createProfile(original)
        try {
            ActivityScenario.launch<ActionEditorActivity>(ActionEditorActivity.intent(context, original.id, "custom")).use { scenario ->
                awaitForm(scenario, "Fixture custom")
                scenario.onActivity { activity ->
                    assertEquals(original.buttons[1].command, activity.findViewById<EditText>(R.id.action_command).text.toString())
                    assertTrue(activity.findViewById<View>(R.id.action_size_custom).isShown)
                    activity.findViewById<EditText>(R.id.action_name).setText("Fixture renamed")
                }
                scenario.recreate()
                awaitForm(scenario, "Fixture renamed")
                scenario.onActivity { it.findViewById<View>(R.id.action_editor_save).performClick() }
                eventually { repository.loadProfile(original.id)?.buttons?.get(1)?.title == "Fixture renamed" }
            }
            val expected = original.copy(buttons = original.buttons.map {
                if (it.id == "custom") it.copy(title = "Fixture renamed") else it
            })
            assertEquals(expected, LayoutProfileRepository(File(context.filesDir, LayoutEditorActivity.PROFILE_DIRECTORY)).loadProfile(original.id))
            ActivityScenario.launch<ActionEditorActivity>(ActionEditorActivity.intent(context, original.id, "custom")).use { scenario ->
                awaitForm(scenario, "Fixture renamed")
                scenario.onActivity {
                    it.findViewById<EditText>(R.id.action_name).setText("Cancelled change")
                    it.onBackPressedDispatcher.onBackPressed()
                }
            }
            assertEquals(expected, repository.loadProfile(original.id))
            assertNull(repository.loadDraft(original.id))
        } finally { repository.deleteProfile(original.id, confirmed = true) }
    }

    private fun awaitForm(scenario: ActivityScenario<ActionEditorActivity>, title: String) = eventually {
        var ready = false
        scenario.onActivity { ready = it.findViewById<EditText>(R.id.action_name).text.toString() == title }
        ready
    }

    private fun eventually(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(20)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertTrue("native form/repository did not settle", predicate())
    }
}
