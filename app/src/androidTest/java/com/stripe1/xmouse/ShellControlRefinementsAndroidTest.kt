package com.stripe1.xmouse

import android.content.ContentValues
import android.content.Context
import android.graphics.Rect
import android.annotation.SuppressLint
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.stripe1.xmouse.layout.LayoutButton
import com.stripe1.xmouse.layout.LayoutProfile
import com.stripe1.xmouse.layout.SavedActionScript
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real touch and production-layout regressions for the native shell controls. */
@RunWith(AndroidJUnit4::class)
class ShellControlRefinementsAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun awaitFixture(
        scenario: ActivityScenario<MainInsetsFixtureActivity>,
        message: String,
        predicate: (MainInsetsFixtureActivity) -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var reached = false
        while (!reached && System.nanoTime() < deadline) {
            scenario.onActivity { reached = predicate(it) }
            if (!reached) Thread.sleep(20)
        }
        assertTrue(message, reached)
    }

    private fun tap(
        scenario: ActivityScenario<MainInsetsFixtureActivity>,
        device: UiDevice,
        id: Int,
    ) {
        var x = 0
        var y = 0
        scenario.onActivity { activity ->
            val target = activity.findViewById<View>(id)
            val bounds = Rect()
            assertTrue("target $id is visible before the physical tap", target.getGlobalVisibleRect(bounds))
            assertTrue("target $id is enabled before the physical tap", target.isEnabled)
            x = bounds.centerX()
            y = bounds.centerY()
        }
        assertTrue("a real UiAutomator tap is injected at $id", device.click(x, y))
    }

    @Test fun pageTitlesAreRemovedAndActionsControlsShareACompactRow() {
        for (dark in listOf(false, true)) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            ActivityScenario.launch<MainInsetsFixtureActivity>(
                MainInsetsFixtureActivity.configuredIntent(context, 1.5f, dark, widthDp = 320),
            ).use { scenario ->
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertEquals(320, activity.resources.configuration.screenWidthDp)
                    assertEquals(1.5f, activity.resources.configuration.fontScale)
                    val root = activity.findViewById<ViewGroup>(R.id.native_shell)
                    val density = activity.resources.displayMetrics.density
                    val width = (320 * density).toInt()
                    val touchpad = root.findViewById<ViewGroup>(R.id.touchpad_destination)
                    val touchpadTexts = visibleTexts(touchpad)
                    assertFalse("Touchpad has no in-page title", touchpadTexts.any { it.equals("Touchpad", true) })
                    assertEquals("removing the title leaves no reserved heading row", 0,
                        root.findViewById<View>(R.id.touchpad_mode_host).top)

                    val send = root.findViewById<MaterialButton>(R.id.keyboard_send)
                    assertEquals("Send", send.text.toString())
                    assertNull("Send is text-only", send.icon)
                    assertTrue("Send remains a full touch target",
                        send.height >= 48 * activity.resources.displayMetrics.density - 1)

                    val bottom = root.findViewById<BottomNavigationView>(R.id.bottom_navigation)
                    assertEquals(listOf("Touchpad", "Actions", "Scripts"),
                        (0 until bottom.menu.size()).map { bottom.menu.getItem(it).title.toString() })
                    bottom.selectedItemId = R.id.nav_actions
                    val actions = root.findViewById<ViewGroup>(R.id.actions_destination)
                    val actionTexts = visibleTexts(actions)
                    assertFalse("Actions has no in-page title", actionTexts.any { it.equals("Actions", true) })
                    assertFalse("the picker has no separate Layout label", actionTexts.any { it.equals("Layout", true) })
                    val picker = root.findViewById<View>(R.id.actions_layout_picker)
                    val edit = root.findViewById<View>(R.id.actions_edit)
                    assertTrue(picker.isShown && edit.isShown)
                    assertEquals("Edit is beside the layout picker", picker.top, edit.top)
                    assertTrue("Edit follows the picker in the same row", picker.right <= edit.left)
                    assertTrue("the picker begins at the top of Actions without a title gap",
                        picker.top < 16 * activity.resources.displayMetrics.density)

                    bottom.selectedItemId = R.id.nav_scripts
                    root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, width, root.measuredHeight)
                    val scripts = root.findViewById<ViewGroup>(R.id.scripts_destination)
                    assertTrue("the third destination is reachable", scripts.isShown)
                    assertTrue("Scripts retains its page heading", visibleTexts(scripts).contains("Saved scripts"))
                    val add = root.findViewById<View>(R.id.scripts_add)
                    val manage = root.findViewById<View>(R.id.scripts_manage)
                    assertEquals("Add script", (add as android.widget.Button).text.toString())
                    assertEquals("Manage", (manage as android.widget.Button).text.toString())
                    assertNotNull("Add script keeps its visible add icon", (add as MaterialButton).icon)
                    assertNotNull("Manage keeps its visible script icon", (manage as MaterialButton).icon)
                    assertEquals("Manage scripts", manage.contentDescription.toString())
                    assertEquals("Manage scripts", manage.tooltipText.toString())
                    for (control in listOf(add, manage)) {
                        assertTrue("Scripts buttons retain 48dp targets", control.height >= 48 * activity.resources.displayMetrics.density - 1)
                        val bounds = Rect()
                        assertTrue("Scripts controls remain visible at 320dp and large font", control.getGlobalVisibleRect(bounds))
                        assertTrue("Scripts controls are not clipped horizontally", bounds.left >= 0 && bounds.right <= width)
                    }

                    val script = SavedActionScript(914, "Synthetic script with a long visible title")
                    val scriptList = root.findViewById<LinearLayout>(R.id.scripts_list)
                    ActionsViewRenderer.renderScripts(activity, scriptList, listOf(script), false, false, false, null)
                    root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec((720 * activity.resources.displayMetrics.density).toInt(), View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, width, root.measuredHeight)
                    val run = requireNotNull(scriptList.findViewWithTag<View>(script.id))
                    val controls = run.parent as ViewGroup
                    assertEquals("Run and Edit remain separate actions", 2, controls.childCount)
                    assertEquals(activity.getString(R.string.ng_run_named_script, script.name),
                        controls.getChildAt(0).contentDescription.toString())
                    assertEquals(activity.getString(R.string.ng_edit_named_script, script.name),
                        controls.getChildAt(1).contentDescription.toString())
                    for (index in 0 until controls.childCount) {
                        val action = controls.getChildAt(index)
                        assertTrue("script action retains a 48dp target", action.width >= 48 * activity.resources.displayMetrics.density - 1 &&
                            action.height >= 48 * activity.resources.displayMetrics.density - 1)
                        val bounds = Rect()
                        assertTrue("script action is visible", action.getGlobalVisibleRect(bounds))
                        assertTrue("script action remains within the narrow screen", bounds.left >= 0 && bounds.right <= width)
                    }
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    @Test fun firstPhysicalTapOpensHamburgerAndComputersAfterComposerFocusAndQuickEdit() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val device = UiDevice.getInstance(instrumentation)
        ActivityScenario.launch<MainInsetsFixtureActivity>(
            MainInsetsFixtureActivity.configuredIntent(context, 1f, false, widthDp = 320),
        ).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val input = activity.findViewById<android.widget.EditText>(R.id.keyboard_input)
                input.setText("draft remains local")
                assertTrue("composer focus is established before the menu tap", input.requestFocus())
                val menu = activity.findViewById<View>(R.id.header_menu)
                val selector = activity.findViewById<View>(R.id.computer_selector)
                assertTrue(menu.isFocusable)
                assertFalse("hamburger does not steal the first touch to enter touch-focus mode",
                    menu.isFocusableInTouchMode)
                assertTrue(selector.isFocusable)
                assertFalse("computer selector remains a normal touch target", selector.isFocusableInTouchMode)
            }

            tap(scenario, device, R.id.header_menu)
            awaitFixture(scenario, "one physical tap opens the hamburger after composer focus") {
                it.findViewById<DrawerLayout>(R.id.main_safe_area).isDrawerOpen(GravityCompat.START)
            }
            tap(scenario, device, R.id.drawer_close)
            awaitFixture(scenario, "the drawer closes after its close control is tapped") {
                !it.findViewById<DrawerLayout>(R.id.main_safe_area).isDrawerVisible(GravityCompat.START) &&
                    it.navigation.topOverlay() == null
            }

            tap(scenario, device, R.id.computer_selector)
            awaitFixture(scenario, "one physical tap opens Computers") {
                it.navigation.topOverlay() == ShellNavigation.Overlay.COMPUTERS &&
                    it.findViewById<View>(R.id.shell_overlay).isShown
            }
            tap(scenario, device, R.id.overlay_close)
            awaitFixture(scenario, "the Computers sheet closes") { it.navigation.topOverlay() == null }
            tap(scenario, device, R.id.computer_selector)
            awaitFixture(scenario, "the selector still opens after its overlay just closed") {
                it.navigation.topOverlay() == ShellNavigation.Overlay.COMPUTERS
            }
            tap(scenario, device, R.id.overlay_close)
            awaitFixture(scenario, "the repeated Computers sheet closes") { it.navigation.topOverlay() == null }

            scenario.onActivity { activity ->
                activity.actionProfiles = listOf(LayoutProfile(
                    id = "tap-fixture", name = "Tap fixture",
                    buttons = listOf(LayoutButton("fixture-action", "Fixture", "key Escape", 5)),
                ))
                activity.selectedActionProfile = activity.actionProfiles.single()
                activity.findViewById<BottomNavigationView>(R.id.bottom_navigation).selectedItemId = R.id.nav_actions
                activity.renderActionsFixture()
                activity.findViewById<View>(R.id.actions_edit).performClick()
                assertTrue("the fixture enters the retained inline quick-edit mode", activity.actionsEditing)
                assertTrue("hamburger remains available in nonmodal Actions edit mode",
                    activity.findViewById<View>(R.id.header_menu).isEnabled)
            }

            tap(scenario, device, R.id.header_menu)
            awaitFixture(scenario, "one physical hamburger tap works in Actions quick-edit mode") {
                it.actionsEditing && it.navigation.topOverlay() == ShellNavigation.Overlay.MENU &&
                    it.navigation.inputRole() == ShellNavigation.InputRole.NONE &&
                    it.findViewById<DrawerLayout>(R.id.main_safe_area).isDrawerOpen(GravityCompat.START)
            }
            tap(scenario, device, R.id.drawer_computers)
            awaitFixture(scenario, "Computers opens from the drawer without ending quick edit") {
                it.actionsEditing && it.navigation.topOverlay() == ShellNavigation.Overlay.COMPUTERS &&
                    it.navigation.inputRole() == ShellNavigation.InputRole.NONE &&
                    !it.findViewById<DrawerLayout>(R.id.main_safe_area).isDrawerVisible(GravityCompat.START)
            }
            tap(scenario, device, R.id.overlay_close)
            awaitFixture(scenario, "closing Computers restores the nonmodal edit layer") {
                it.actionsEditing && it.navigation.topOverlay() == ShellNavigation.Overlay.ACTION_EDIT &&
                    it.navigation.inputRole() == ShellNavigation.InputRole.NONE &&
                    it.findViewById<View>(R.id.header_menu).isEnabled
            }
            tap(scenario, device, R.id.header_menu)
            awaitFixture(scenario, "hamburger can be tapped again after returning from Computers") {
                it.navigation.topOverlay() == ShellNavigation.Overlay.MENU &&
                    it.findViewById<DrawerLayout>(R.id.main_safe_area).isDrawerOpen(GravityCompat.START)
            }
        }
    }

    @Test fun modalActionDialogStillBlocksUnderlyingHeaderButRestoresMenuAfterDismissal() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch<MainInsetsFixtureActivity>(
            MainInsetsFixtureActivity.configuredIntent(context, 1f, false),
        ).use { scenario ->
            scenario.onActivity { activity ->
                activity.actionProfiles = listOf(LayoutProfile(id = "modal-fixture", name = "Modal fixture"))
                activity.selectedActionProfile = activity.actionProfiles.single()
                activity.renderActionsFixture()
                activity.findViewById<BottomNavigationView>(R.id.bottom_navigation).selectedItemId = R.id.nav_actions
                activity.findViewById<View>(R.id.actions_edit).performClick()
                activity.findViewById<View>(R.id.actions_layout_picker).performClick()
                assertNotNull(activity.actionPicker)
                assertEquals(ShellNavigation.InputRole.NONE, activity.navigation.inputRole())
                assertFalse("a real picker dialog keeps the underlying hamburger disabled",
                    activity.findViewById<View>(R.id.header_menu).isEnabled)
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                    activity.findViewById<View>(R.id.shell_header).importantForAccessibility)
            }
            scenario.onActivity { requireNotNull(it.actionPicker).dismiss() }
            awaitFixture(scenario, "dismissing the modal restores the nonmodal quick-edit shell") {
                it.actionPicker == null && it.actionsEditing &&
                    it.navigation.topOverlay() == ShellNavigation.Overlay.ACTION_EDIT &&
                    it.findViewById<View>(R.id.header_menu).isEnabled
            }
            scenario.onActivity { activity ->
                assertEquals(ShellNavigation.InputRole.NONE, activity.navigation.inputRole())
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO,
                    activity.findViewById<View>(R.id.shell_header).importantForAccessibility)
            }
        }
    }

    @Test fun captureRefinedShellPagesInBothThemesAtNarrowLargeFontScale() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("captureNativeDesign") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val device = UiDevice.getInstance(instrumentation)
        val localDirectory = File(context.getExternalFilesDir(null), "shell-control-refinements").apply { mkdirs() }

        fun capture(name: String) {
            instrumentation.waitForIdleSync()
            device.waitForIdle()
            Thread.sleep(400)
            val file = File(localDirectory, "$name.png")
            assertTrue("capture $name", device.takeScreenshot(file))
            assertTrue("screenshots are exportable to the owned emulator gallery",
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.png")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/XMouseNGShellControlRefinements")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = requireNotNull(context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values,
            )) { "MediaStore insert failed for $name" }
            try {
                context.contentResolver.openOutputStream(uri, "w")!!.use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                }
                assertEquals(1, context.contentResolver.update(uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
            } catch (failure: Throwable) {
                context.contentResolver.delete(uri, null, null)
                throw failure
            }
        }

        for (dark in listOf(false, true)) {
            val theme = if (dark) "dark" else "light"
            ActivityScenario.launch<MainInsetsFixtureActivity>(
                MainInsetsFixtureActivity.configuredIntent(context, 1.5f, dark, widthDp = 320),
            ).use { scenario ->
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertEquals(320, activity.resources.configuration.screenWidthDp)
                    assertEquals(1.5f, activity.resources.configuration.fontScale)
                }
                capture("shell-touchpad-$theme")
                scenario.onActivity { it.findViewById<View>(R.id.header_menu).performClick() }
                awaitFixture(scenario, "narrow $theme drawer opens for the screenshot") {
                    it.findViewById<DrawerLayout>(R.id.main_safe_area).isDrawerOpen(GravityCompat.START)
                }
                scenario.onActivity { activity ->
                    val authentication = activity.findViewById<TextView>(R.id.drawer_authentication)
                    assertEquals(activity.getString(R.string.ng_authentication_settings),
                        authentication.text.toString())
                    assertTrue("long drawer label wraps instead of being forced into a single line",
                        authentication.layout.lineCount >= 2)
                    assertTrue("wrapped drawer label has enough height for all text lines",
                        authentication.height >= authentication.paddingTop + authentication.layout.height +
                            authentication.paddingBottom)
                    assertTrue("expanded drawer row preserves a 48dp minimum target",
                        authentication.height >= 48 * activity.resources.displayMetrics.density - 1)
                }
                capture("shell-drawer-$theme")
                scenario.onActivity { it.findViewById<View>(R.id.drawer_close).performClick() }
                awaitFixture(scenario, "narrow $theme drawer closes after its screenshot") {
                    !it.findViewById<DrawerLayout>(R.id.main_safe_area).isDrawerVisible(GravityCompat.START)
                }
                scenario.onActivity { activity ->
                    activity.actionProfiles = listOf(LayoutProfile(
                        id = "capture-$theme", name = "Shell layout",
                        buttons = listOf(
                            LayoutButton("capture-escape", "Escape", "key Escape", 4, "#E0EFE8"),
                            LayoutButton("capture-copy", "Copy", "key ctrl+c", 6, "#E8EFFA"),
                        ),
                    ))
                    activity.selectedActionProfile = activity.actionProfiles.single()
                    activity.findViewById<BottomNavigationView>(R.id.bottom_navigation).selectedItemId = R.id.nav_actions
                    activity.renderActionsFixture()
                }
                capture("shell-actions-$theme")
                scenario.onActivity { activity ->
                    activity.findViewById<BottomNavigationView>(R.id.bottom_navigation).selectedItemId = R.id.nav_scripts
                    ActionsViewRenderer.renderScripts(activity, activity.findViewById(R.id.scripts_list),
                        listOf(SavedActionScript(51, "Synthetic saved script")), false, false, false, null)
                }
                capture("shell-scripts-$theme")
            }
        }
    }

    private fun visibleTexts(view: View): List<String> {
        if (view.visibility != View.VISIBLE) return emptyList()
        val current = (view as? TextView)?.text?.toString()?.let(::listOf).orEmpty()
        if (view !is ViewGroup) return current
        return current + (0 until view.childCount).flatMap { visibleTexts(view.getChildAt(it)) }
    }
}
