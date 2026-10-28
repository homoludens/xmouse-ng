package net.droopia.xmouse_ng

import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.util.AtomicFile
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import net.droopia.xmouse_ng.layout.LayoutButton
import net.droopia.xmouse_ng.layout.LayoutEditorActivity
import net.droopia.xmouse_ng.layout.LayoutProfile
import net.droopia.xmouse_ng.layout.LayoutProfileRepository
import net.droopia.xmouse_ng.layout.SavedActionScript
import net.droopia.xmouse_ng.layout.awaitEditorTag
import com.google.android.material.button.MaterialButton
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in native screenshots, populated only with synthetic inert commands and profile data. */
@RunWith(AndroidJUnit4::class)
class EditorScriptsRefreshCaptureAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun captureEditorAndScriptsRefreshScreensInBothThemes() {
        assumeTrue(InstrumentationRegistry.getArguments()
            .getString("captureEditorScriptsDesignRefresh") == "true")

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val device = UiDevice.getInstance(instrumentation)
        val evidenceDirectory = File(
            context.getExternalFilesDir(null), "editor-scripts-design-refresh-20261027",
        ).apply { mkdirs() }
        val evidenceRelativePath =
            "${Environment.DIRECTORY_PICTURES}/XMouseNGEditorScriptsDesignRefresh20261027"
        val profileDirectory = File(context.filesDir, LayoutEditorActivity.PROFILE_DIRECTORY)
        val catalogFile = File(profileDirectory, "profiles.catalog.json")
        val originalCatalog = readAtomicSnapshot(catalogFile)
        val repository = LayoutProfileRepository(profileDirectory)
        val profile = LayoutProfile(
            id = "capture-refresh-${UUID.randomUUID()}",
            name = "Synthetic 57-button layout",
            buttons = (0 until 57).map { index ->
                LayoutButton(
                    id = "capture-$index",
                    title = "Button $index",
                    command = "  inert synthetic command $index\r\n  ",
                    width = index % 10 + 1,
                    color = String.format(Locale.ROOT, "#%06X", index * 7919 % 0xFFFFFF),
                )
            },
        )
        val originalSize = device.executeShellCommand("wm size")
        val originalDensity = device.executeShellCommand("wm density")
        val originalFontScale = device.executeShellCommand("settings get system font_scale").ifBlank { "1.0" }
        val originalNightMode = Regex("Night mode: (yes|no|auto)")
            .find(device.executeShellCommand("cmd uimode night"))?.groupValues?.get(1) ?: "auto"

        fun shell(command: String) { device.executeShellCommand(command) }
        fun restoreDisplay() {
            val sizeOverride = Regex("Override size: (\\d+x\\d+)").find(originalSize)?.groupValues?.get(1)
            val densityOverride = Regex("Override density: (\\d+)").find(originalDensity)?.groupValues?.get(1)
            shell(if (sizeOverride == null) "wm size reset" else "wm size $sizeOverride")
            shell(if (densityOverride == null) "wm density reset" else "wm density $densityOverride")
            shell("settings put system font_scale $originalFontScale")
        }
        fun capture(name: String) {
            instrumentation.waitForIdleSync()
            device.waitForIdle()
            Thread.sleep(350)
            val file = File(evidenceDirectory, "$name.png")
            assertTrue("screenshot $name is captured", device.takeScreenshot(file))
            assertTrue("screenshot $name is nonempty", file.isFile && file.length() > 0)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.png")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, evidenceRelativePath)
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

        try {
            assertTrue("MediaStore evidence export requires Android 10+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            context.contentResolver.delete(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(evidenceRelativePath),
            )
            repository.createProfile(profile)
            restoreDisplay()
            shell("settings put system font_scale 1.0")
            for (dark in listOf(false, true)) {
                val theme = if (dark) "dark" else "light"
                shell("cmd uimode night ${if (dark) "yes" else "no"}")
                ActivityScenario.launch<LayoutEditorActivity>(
                    Intent(context, LayoutEditorActivity::class.java)
                        .putExtra(LayoutEditorActivity.EXTRA_PROFILE_ID, profile.id),
                ).use {
                    compose.awaitEditorTag("layout-editor-visual-footer")
                    compose.onNodeWithTag("layout-editor-button-count").assertIsDisplayed()
                    compose.onNodeWithTag("layout-editor-button-capture-0").assertIsDisplayed()
                    assertEquals(profile, repository.loadProfile(profile.id))
                    capture("editor-visual-57-unselected-$theme")

                    compose.onNodeWithTag("layout-editor-button-capture-0")
                        .performScrollTo().performClick()
                    compose.onNodeWithTag("layout-editor-selected-button-controls")
                        .performScrollTo().assertIsDisplayed()
                    compose.onNodeWithTag("layout-editor-visual-footer").assertIsDisplayed()
                    capture("editor-visual-57-selected-$theme")
                    assertEquals("Selection alone does not mutate the saved profile",
                        profile, repository.loadProfile(profile.id))
                }
            }

            shell("wm size 840x1920")
            shell("wm density 420")
            shell("settings put system font_scale 1.5")
            for (dark in listOf(false, true)) {
                val theme = if (dark) "dark" else "light"
                shell("cmd uimode night ${if (dark) "yes" else "no"}")
                ActivityScenario.launch<MainInsetsFixtureActivity>(
                    MainInsetsFixtureActivity.configuredIntent(context, 1.5f, dark, widthDp = 320),
                ).use { scenario ->
                    scenario.onActivity { activity ->
                        activity.navigation.select(ShellNavigation.Destination.SCRIPTS)
                        ActionsViewRenderer.renderScripts(
                            activity,
                            activity.findViewById(R.id.scripts_list),
                            listOf(SavedActionScript(57, "Synthetic inert script")),
                            false, false, false, null,
                        )
                        assertEquals(320, activity.resources.configuration.screenWidthDp)
                        assertEquals(1.5f, activity.resources.configuration.fontScale)
                    }
                    capture("scripts-tab-320-font150-$theme")

                    val script = HostItem().apply {
                        setAlias("Synthetic audit script")
                        setIP("printf 'inert λ fixture'\\n  exact spacing  ")
                        setUsername("fixture-57")
                    }
                    var editDialog: AlertDialog? = null
                    scenario.onActivity { activity ->
                        activity.setContentView(R.layout.activity_script_manager)
                        val manager = activity.findViewById<ViewGroup>(R.id.list_edit_root)
                        SystemWindowInsets.applyTo(activity.window, manager)
                        val count = manager.findViewById<android.widget.TextView>(R.id.scripts_manager_count)
                        count.text = activity.resources.getQuantityString(R.plurals.ng_scripts_count, 1, 1)
                        manager.findViewById<View>(R.id.scripts_manager_empty).visibility = View.GONE
                        val listener = object : MyInterface {
                            override fun performCallback() = Unit
                            override fun performCallback2(i: Int?) {
                                assertEquals(0, i)
                                editDialog = syntheticEditDialog(activity, script)
                            }
                        }
                        val list = manager.findViewById<ListView>(R.id.myListView)
                        list.adapter = HostListItemAdapter(
                            activity, listOf(script), listener, ListEditActivity.ActivityType.type_script,
                        )
                        manager.findViewById<MaterialButton>(R.id.scripts_manager_add).setOnClickListener { }
                        assertEquals("Back", manager.findViewById<View>(R.id.scripts_manager_back)
                            .contentDescription.toString())
                    }
                    capture("scripts-manager-320-font150-$theme")

                    scenario.onActivity { activity ->
                        val edit = activity.findViewById<ListView>(R.id.myListView).getChildAt(0)
                            .findViewById<MaterialButton>(R.id.script_manager_edit)
                        assertTrue("the synthetic Edit row opens its production form", edit.performClick())
                    }
                    assertNotNull("synthetic edit form was shown", editDialog)
                    capture("scripts-edit-form-320-font150-$theme")

                    var confirmation: AlertDialog? = null
                    scenario.onActivity {
                        val editor = requireNotNull(editDialog)
                        editor.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                            confirmation = syntheticDeleteConfirmation(it.context, script.getAlias())
                        }
                        assertTrue("Delete opens a confirmation without changing storage",
                            editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick())
                    }
                    assertNotNull("synthetic delete confirmation was shown", confirmation)
                    capture("scripts-delete-confirm-320-font150-$theme")
                    scenario.onActivity {
                        requireNotNull(confirmation).getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                        requireNotNull(editDialog).getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
                    }
                }

                ActivityScenario.launch<NativeSettingsActivity>(
                    Intent(context, NativeSettingsActivity::class.java),
                ).use { scenario ->
                    scenario.onActivity { activity ->
                        val back = activity.findViewById<View>(R.id.settings_back)
                        assertEquals("Back", back.contentDescription.toString())
                        assertTrue("Back target remains at least 48dp",
                            back.height >= 48 * activity.resources.displayMetrics.density - 1)
                    }
                    capture("native-settings-back-320-font150-$theme")
                }
            }
        } finally {
            restoreDisplay()
            shell("cmd uimode night $originalNightMode")
            AtomicFile(File(profileDirectory, "${profile.id}.profile.json")).delete()
            AtomicFile(File(profileDirectory, "${profile.id}.draft.json")).delete()
            restoreAtomicSnapshot(catalogFile, originalCatalog)
        }
    }

    private fun syntheticEditDialog(activity: MainInsetsFixtureActivity, script: HostItem): AlertDialog {
        val layout = activity.layoutInflater.inflate(R.layout.manage_script_item, null)
        val name = layout.findViewById<EditText>(R.id.new_script_alias)
        val command = layout.findViewById<EditText>(R.id.new_script_command)
        name.setText(script.getAlias())
        command.setText(script.getIP())
        command.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        command.typeface = Typeface.MONOSPACE
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.ng_script_manager_edit_title)
            .setView(layout)
            .setPositiveButton(R.string.ng_script_manager_save, null)
            .setNegativeButton(R.string.ng_script_manager_delete, null)
            .setNeutralButton(R.string.ng_script_manager_cancel, null)
            .create()
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
        layout.isFocusableInTouchMode = true
        layout.requestFocus()
        dialog.show()
        styleDialogButton(dialog.getButton(AlertDialog.BUTTON_POSITIVE), activity,
            R.color.ng_accent, R.color.ng_accent, R.color.ng_on_accent)
        styleDialogButton(dialog.getButton(AlertDialog.BUTTON_NEGATIVE), activity,
            R.color.ng_danger_soft, R.color.ng_danger, R.color.ng_danger)
        styleDialogButton(dialog.getButton(AlertDialog.BUTTON_NEUTRAL), activity,
            R.color.ng_surface, R.color.ng_border, R.color.ng_text_primary)
        return dialog
    }

    private fun syntheticDeleteConfirmation(context: Context, name: String): AlertDialog {
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.ng_script_delete_title)
            .setMessage(context.getString(R.string.ng_script_delete_message, name))
            .setNegativeButton(R.string.ng_script_manager_cancel, null)
            .setPositiveButton(R.string.ng_script_manager_delete, null)
            .create()
        dialog.show()
        styleDialogButton(dialog.getButton(AlertDialog.BUTTON_POSITIVE), context,
            R.color.ng_danger_soft, R.color.ng_danger, R.color.ng_danger)
        styleDialogButton(dialog.getButton(AlertDialog.BUTTON_NEGATIVE), context,
            R.color.ng_surface, R.color.ng_border, R.color.ng_text_primary)
        return dialog
    }

    private fun styleDialogButton(button: Button, context: Context, background: Int, stroke: Int, foreground: Int) {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + .5f).toInt()
        button.background = GradientDrawable().apply {
            setColor(context.getColor(background))
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), context.getColor(stroke))
        }
        button.setTextColor(context.getColor(foreground))
        button.setAllCaps(false)
        button.setTextSize(13f)
        button.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        button.minWidth = dp(48)
        button.minimumWidth = dp(48)
        button.minHeight = dp(48)
        button.minimumHeight = dp(48)
        button.setPadding(dp(12), dp(8), dp(12), dp(8))
        button.elevation = 0f
    }

    private fun readAtomicSnapshot(file: File): ByteArray? = try {
        AtomicFile(file).openRead().use { it.readBytes() }
    } catch (missing: FileNotFoundException) {
        if (file.exists() || File("${file.path}.bak").exists()) throw missing
        null
    }

    private fun restoreAtomicSnapshot(file: File, bytes: ByteArray?) {
        val atomic = AtomicFile(file)
        if (bytes == null) {
            atomic.delete()
            return
        }
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (failure: Throwable) {
            atomic.failWrite(output)
            throw failure
        }
    }
}
