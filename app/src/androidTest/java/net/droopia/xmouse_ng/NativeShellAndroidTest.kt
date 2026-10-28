package net.droopia.xmouse_ng

import android.content.Intent
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.card.MaterialCardView
import net.droopia.xmouse_ng.layout.ActionPresentation
import net.droopia.xmouse_ng.layout.LayoutButton
import net.droopia.xmouse_ng.layout.LayoutProfile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated production rendering; never initializes MainActivity, SSH, credentials, or user data. */
@RunWith(AndroidJUnit4::class)
class NativeShellAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun awaitFixture(
        scenario: ActivityScenario<MainInsetsFixtureActivity>,
        message: String,
        predicate: (MainInsetsFixtureActivity) -> Boolean,
    ) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        var reached = false
        while (!reached && System.nanoTime() < deadline) {
            scenario.onActivity { reached = predicate(it) }
            if (!reached) Thread.sleep(20)
        }
        assertTrue(message, reached)
    }

    @Test fun nativeComputerFormLayoutHasPersistentLabelsBlankPasswordToggleAndScrollableNarrowLargeFontTargets() {
        for (dark in listOf(false, true)) {
            val intent = MainInsetsFixtureActivity.configuredIntent(ApplicationProvider.getApplicationContext(), 1.5f, dark)
            ActivityScenario.launch<MainInsetsFixtureActivity>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    // Layout-only fixture: no repository, host trust, discovery, or SSH is initialized.
                    activity.setContentView(R.layout.activity_computer_editor)
                    val root = activity.findViewById<ViewGroup>(R.id.computer_editor_root)
                    val density = activity.resources.displayMetrics.density
                    val width = (320 * density).toInt(); val height = (420 * density).toInt()
                    root.findViewById<View>(R.id.computer_port_field).visibility = View.VISIBLE
                    root.findViewById<View>(R.id.computer_port_label).visibility = View.VISIBLE
                    root.findViewById<View>(R.id.computer_port_help).visibility = View.VISIBLE
                    root.findViewById<EditText>(R.id.new_host_port).setText("22")
                    root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, width, height)
                    for ((id, label) in listOf(R.id.computer_name_label to "Display name (optional)",
                        R.id.computer_host_label to "Hostname or IP address", R.id.computer_user_label to "Username",
                        R.id.computer_password_label to "Password", R.id.computer_port_label to "SSH port")) {
                        val field = root.findViewById<TextView>(id)
                        assertEquals(label, field.text.toString()); assertEquals(View.VISIBLE, field.visibility)
                        assertTrue(field.width <= width)
                    }
                    for (id in listOf(R.id.computer_name_field, R.id.computer_host_field,
                        R.id.computer_user_field, R.id.computer_password_field, R.id.computer_port_field)) {
                        assertTrue("neutral field stays inside the narrow viewport", root.findViewById<View>(id).width <= width)
                    }
                    val password = root.findViewById<EditText>(R.id.new_host_password)
                    assertEquals("", password.text.toString()); assertFalse(password.isSaveEnabled)
                    assertEquals(com.google.android.material.textfield.TextInputLayout.END_ICON_PASSWORD_TOGGLE,
                        root.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.computer_password_field).endIconMode)
                    assertEquals("22", root.findViewById<EditText>(R.id.new_host_port).text.toString())
                    for (id in listOf(R.id.computer_test, R.id.computer_save, R.id.computer_advanced, R.id.computer_auth_settings))
                        assertTrue(root.findViewById<View>(id).height >= 48 * density - 1)
                    val actions = root.findViewById<ViewGroup>(R.id.computer_editor_actions)
                    assertEquals(actions, root.findViewById<View>(R.id.computer_test).parent.parent)
                    assertEquals(View.GONE, root.findViewById<View>(R.id.computer_delete).visibility)
                    root.findViewById<EditText>(R.id.new_host_alias).setText("Display name remains external")
                    assertEquals("Display name (optional)", root.findViewById<TextView>(R.id.computer_name_label).text.toString())
                    val scroll = root.findViewById<android.widget.ScrollView>(R.id.computer_editor_scroll)
                    assertTrue(scroll.getChildAt(0).height > scroll.height)
                    scroll.isSmoothScrollingEnabled = false
                    scroll.fullScroll(View.FOCUS_DOWN)
                    assertTrue(scroll.scrollY > 0)
                }
            }
        }
    }

    @Test fun narrowLargeFontHeaderAndLabeledBottomRoutesRemainReachableInBothThemes() {
        for (dark in listOf(false, true)) {
            val intent = MainInsetsFixtureActivity.configuredIntent(ApplicationProvider.getApplicationContext(), 1.5f, dark)
            ActivityScenario.launch<MainInsetsFixtureActivity>(intent).use { scenario ->
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val root = activity.findViewById<ViewGroup>(R.id.native_shell)
                    assertEquals(1.5f, activity.resources.configuration.fontScale)
                    assertEquals(if (dark) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO,
                        activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    val density = activity.resources.displayMetrics.density
                    val width = (320 * density).toInt()
                    val height = (720 * density).toInt()
                    root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, width, height)
                    val header = root.findViewById<View>(R.id.shell_header)
                    val bottom = root.findViewById<BottomNavigationView>(R.id.bottom_navigation)
                    for (id in listOf(R.id.header_menu, R.id.computer_selector, R.id.header_connection_button)) {
                        val control = root.findViewById<View>(id)
                        assertTrue("header target width $id", control.width >= 48 * density - 1)
                        assertTrue("header target height $id", control.height >= 48 * density - 1)
                        val bounds = android.graphics.Rect()
                        control.getDrawingRect(bounds)
                        root.offsetDescendantRectToMyCoords(control, bounds)
                        assertTrue(bounds.left >= 0 && bounds.right <= width && bounds.top >= 0 && bounds.bottom <= header.bottom)
                    }
                    assertTrue(header.bottom <= bottom.top)
                    assertEquals(height, bottom.bottom)
                    assertEquals(listOf("Touchpad", "Actions", "Scripts"),
                        (0 until bottom.menu.size()).map { bottom.menu.getItem(it).title.toString() })
                    listOf(R.id.nav_actions, R.id.nav_scripts, R.id.nav_touchpad).forEach { id ->
                        bottom.selectedItemId = id
                        assertTrue(bottom.menu.findItem(id).isChecked)
                        assertEquals(NativeShellViews.destination(id), activity.navigation.destination())
                    }
                    assertEquals("XMouse-NG", activity.getString(R.string.app_name))
                    assertEquals("Connect", root.findViewById<Button>(R.id.header_connection_button).text.toString())

                    val surface = root.findViewById<FrameLayout>(R.id.mouse_surface_host)
                    val pointer = (0 until surface.childCount).map { surface.getChildAt(it) }
                        .filterIsInstance<MyMouseView>().single()
                    assertEquals("scroll controls must be drawn and hit-tested above the pointer", 0, surface.indexOfChild(pointer))
                    assertTrue("real pointer host has positive width", pointer.width > 0)
                    assertTrue("real pointer host has positive height", pointer.height > 0)
                    for (id in listOf(R.id.firstMouseButton, R.id.secondMouseButton, R.id.thirdMouseButton)) {
                        val button = root.findViewById<View>(id)
                        val location = IntArray(2)
                        val surfaceLocation = IntArray(2)
                        button.getLocationOnScreen(location)
                        surface.getLocationOnScreen(surfaceLocation)
                        assertTrue("mouse click control $id is below the touchpad", location[1] >= surfaceLocation[1] + surface.height)
                        assertTrue("mouse click target $id is at least 48dp high", button.height >= 48 * density - 1)
                    }
                    val scrollUp = root.findViewById<View>(R.id.fourthMouseButton)
                    val scrollDown = root.findViewById<View>(R.id.fifthMouseButton)
                    val scrollUpLocation = IntArray(2)
                    val scrollDownLocation = IntArray(2)
                    val surfaceLocation = IntArray(2)
                    scrollUp.getLocationOnScreen(scrollUpLocation)
                    scrollDown.getLocationOnScreen(scrollDownLocation)
                    surface.getLocationOnScreen(surfaceLocation)
                    assertTrue("scroll up stays in the right strip",
                        surfaceLocation[0] + surface.width - scrollUpLocation[0] - scrollUp.width <= 1)
                    assertTrue("scroll down stays in the right strip",
                        surfaceLocation[0] + surface.width - scrollDownLocation[0] - scrollDown.width <= 1)
                    assertTrue(scrollUpLocation[1] < scrollDownLocation[1])
                    for (id in listOf(R.id.typing_key_escape, R.id.typing_key_tab,
                        R.id.typing_key_backspace, R.id.typing_key_enter, R.id.touchpad_clipboard_send)) {
                        val button = root.findViewById<Button>(id)
                        val accessibleName = button.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                            ?: button.text.toString()
                        assertTrue("quick key $id has an accessible name", accessibleName.isNotBlank())
                        assertTrue("quick key $id is a 48dp target", button.height >= 48 * density - 1)
                        if (id == R.id.typing_key_escape) {
                            assertEquals("Esc", button.text.toString())
                            assertTextFits(button)
                        } else {
                            assertNotNull("icon control $id has a recognizable icon", (button as com.google.android.material.button.MaterialButton).icon)
                        }
                    }
                    assertEquals("Send clipboard to computer", root.findViewById<View>(R.id.touchpad_clipboard_send).tooltipText)
                    assertTrue("inline multiline field is present", root.findViewById<EditText>(R.id.keyboard_input).height > 0)
                    assertTrue("explicit Send remains a 48dp target",
                        root.findViewById<View>(R.id.keyboard_send).height >= 48 * density - 1)
                }
            }
        }
    }

    @Test fun threeTabNavigationRetiresCapabilitiesAndRetainsTheHiddenSharedComposerDraft() {
        for (dark in listOf(false, true)) {
            val intent = MainInsetsFixtureActivity.configuredIntent(ApplicationProvider.getApplicationContext(), 1.5f, dark)
            ActivityScenario.launch<MainInsetsFixtureActivity>(intent).use { scenario ->
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val root = activity.findViewById<ViewGroup>(R.id.native_shell)
                    val density = activity.resources.displayMetrics.density
                    val width = (320 * density).toInt()
                    val height = (720 * density).toInt()
                    root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, width, height)
                    val bottom = root.findViewById<BottomNavigationView>(R.id.bottom_navigation)
                    assertEquals(listOf("Touchpad", "Actions", "Scripts"),
                        (0 until bottom.menu.size()).map { bottom.menu.getItem(it).title.toString() })
                    val input = root.findViewById<EditText>(R.id.keyboard_input)
                    input.setText("retained across all three tabs")
                    assertTrue(input.isShown)
                    assertFalse("initial render does not focus the inline field", input.hasFocus())
                    assertEquals(ShellNavigation.InputRole.POINTER, activity.navigation.inputRole())
                    activity.shellEvents.clear()
                    bottom.selectedItemId = R.id.nav_actions
                    assertEquals(listOf("retire", "render", "refresh"), activity.shellEvents)
                    assertEquals(ShellNavigation.InputRole.ACTIONS, activity.navigation.inputRole())
                    assertTrue("the shared composer remains visible on Actions", input.isShown)
                    activity.shellEvents.clear()
                    bottom.selectedItemId = R.id.nav_scripts
                    root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, width, height)
                     assertEquals(listOf("retire", "render", "refresh"), activity.shellEvents)
                     assertEquals(ShellNavigation.InputRole.NONE, activity.navigation.inputRole())
                     assertTrue("Scripts has its own content surface", root.findViewById<View>(R.id.scripts_destination).isShown)
                     val addScript = root.findViewById<Button>(R.id.scripts_add)
                     val manageScripts = root.findViewById<Button>(R.id.scripts_manage)
                     assertTrue("Scripts exposes Add", addScript.isShown)
                     assertTrue("Scripts exposes Manage", manageScripts.isShown)
                     assertEquals(activity.getString(R.string.ng_add_script), addScript.text.toString())
                     assertEquals(activity.getString(R.string.ng_manage_scripts), manageScripts.contentDescription.toString())
                     assertEquals(activity.getString(R.string.ng_manage_scripts), manageScripts.tooltipText.toString())
                     assertTrue("Add script remains a 48dp target", addScript.height >= 48 * density - 1)
                     assertTrue("Manage scripts remains a 48dp target", manageScripts.height >= 48 * density - 1)
                     assertFalse("the composer is hidden rather than granted a stale Actions role", input.isShown)
                    assertEquals("the unsent draft remains in memory while hidden", "retained across all three tabs",
                        input.text.toString())
                    activity.shellEvents.clear()
                    bottom.selectedItemId = R.id.nav_touchpad
                    assertEquals(listOf("retire", "render", "refresh"), activity.shellEvents)
                    assertEquals(ShellNavigation.InputRole.POINTER, activity.navigation.inputRole())
                    assertTrue(input.isShown)
                    assertEquals("the hidden composer draft returns on Touchpad", "retained across all three tabs",
                        input.text.toString())
                    assertFalse("returning to Touchpad does not focus or open the IME", input.hasFocus())
                    assertTrue(activity.navigation.overlays().isEmpty())
                    val quickKeys = root.findViewById<View>(R.id.touchpad_quick_keys)
                    val quickKeysBounds = android.graphics.Rect()
                    val inputBounds = android.graphics.Rect()
                    val sendBounds = android.graphics.Rect()
                    val composerBounds = android.graphics.Rect()
                    quickKeys.getDrawingRect(quickKeysBounds)
                    input.getDrawingRect(inputBounds)
                    root.findViewById<View>(R.id.keyboard_send).getDrawingRect(sendBounds)
                    root.findViewById<View>(R.id.typing_composer_row).getDrawingRect(composerBounds)
                    root.offsetDescendantRectToMyCoords(quickKeys, quickKeysBounds)
                    root.offsetDescendantRectToMyCoords(input, inputBounds)
                    root.offsetDescendantRectToMyCoords(root.findViewById(R.id.keyboard_send), sendBounds)
                    root.offsetDescendantRectToMyCoords(root.findViewById(R.id.typing_composer_row), composerBounds)
                    assertTrue("quick keys are above the input", quickKeysBounds.bottom < inputBounds.top)
                    assertTrue("Send is beside the input", inputBounds.right < sendBounds.left)
                    assertTrue("shared composer is above navigation", composerBounds.bottom <= bottom.top)
                    for (id in listOf(R.id.typing_key_escape, R.id.typing_key_tab,
                        R.id.typing_key_backspace, R.id.typing_key_enter, R.id.touchpad_clipboard_send)) {
                        assertTrue("inline quick key $id remains a 48dp target",
                            root.findViewById<View>(id).height >= 48 * density - 1)
                    }
                }
            }
        }
    }

    @Test fun inlineTypingUsesSendImeAndSharedComposerWithoutFocusOrTypingOverlay() {
        ActivityScenario.launch(MainInsetsFixtureActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(R.id.native_shell)
                val input = activity.findViewById<EditText>(R.id.keyboard_input)
                assertTrue("fixture inflates the multiline Send-aware composer", input is SendImeEditText)
                assertTrue(input.isShown)
                assertTrue(input.inputType and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0)
                assertFalse("typing content is not saved into a state bundle", input.isSaveEnabled)
                assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, input.importantForAutofill)
                assertEquals("Type on computer", input.hint.toString())
                assertEquals("Type on computer", input.contentDescription.toString())
                assertFalse("the inline field does not auto-focus on first navigation", input.hasFocus())
                assertEquals(ShellNavigation.InputRole.POINTER, activity.navigation.inputRole())
                assertTrue(activity.navigation.overlays().isEmpty())
                for ((id, label) in listOf(
                    R.id.typing_key_escape to R.string.ng_escape,
                    R.id.typing_key_tab to R.string.ng_tab,
                    R.id.typing_key_backspace to R.string.ng_backspace,
                    R.id.typing_key_enter to R.string.ng_enter,
                )) {
                    val button = activity.findViewById<Button>(id)
                    val accessibleName = button.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                        ?: button.text.toString()
                    assertEquals(activity.getString(label), accessibleName)
                    assertTrue("typing quick key $id is at least 48dp high", button.height >= 48 * activity.resources.displayMetrics.density - 1)
                }
                val send = activity.findViewById<Button>(R.id.keyboard_send)
                assertEquals(activity.getString(R.string.ng_send), send.text.toString())
                assertTrue("Send remains a full touch target", send.height >= 48 * activity.resources.displayMetrics.density - 1)

                input.setText("fixture edit remains local")
                assertTrue(input.requestFocus())
                assertEquals("fixture edit remains local", input.text.toString())
                assertTrue("inline composer is shared outside the scrollable destinations",
                    root.findViewById<View>(R.id.touchpad_destination).height > 0)
                assertTrue("shared composer is above bottom navigation",
                    root.findViewById<View>(R.id.typing_dock).bottom <= root.findViewById<View>(R.id.bottom_navigation).top)
                val editorInfo = EditorInfo()
                assertNotNull("the actual fixture view creates an input connection", input.onCreateInputConnection(editorInfo))
                assertEquals(EditorInfo.IME_ACTION_SEND, editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION)
                assertEquals("multiline must not suppress the IME Send action", 0,
                    editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION)
                assertTrue("input connection remains multiline for line breaks and paste",
                    editorInfo.inputType and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0)
                assertTrue("input connection keeps the no-extract-UI request",
                    editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_EXTRACT_UI != 0)
            }
        }
    }

    @Test fun hamburgerDrawerSuspendsInputAndBackRestoresTheSharedDraftAndDestination() {
        val device = UiDevice.getInstance(instrumentation)
        ActivityScenario.launch(MainInsetsFixtureActivity::class.java).use { scenario ->
            instrumentation.waitForIdleSync()
            assertTrue("the focus regression explicitly enters keyboard navigation",
                device.pressKeyCode(KeyEvent.KEYCODE_TAB))
            awaitFixture(scenario, "keyboard navigation is active before drawer interactions") {
                !it.findViewById<View>(R.id.native_shell).isInTouchMode
            }
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(R.id.keyboard_input).setText("draft across tabs and drawer")
                activity.findViewById<View>(R.id.header_menu).performClick()
            }
            awaitFixture(scenario, "hamburger drawer opens") {
                it.findViewById<DrawerLayout>(R.id.main_safe_area).isDrawerOpen(GravityCompat.START)
            }
            scenario.onActivity { activity ->
                val drawerLayout = activity.findViewById<DrawerLayout>(R.id.main_safe_area)
                val input = activity.findViewById<EditText>(R.id.keyboard_input)
                assertTrue(drawerLayout.isDrawerOpen(GravityCompat.START))
                assertEquals(ShellNavigation.Overlay.MENU, activity.navigation.topOverlay())
                assertEquals(ShellNavigation.InputRole.NONE, activity.navigation.inputRole())
                for ((id, label) in listOf(
                    R.id.drawer_computers to "Computers",
                    R.id.drawer_pointer_settings to "Touchpad settings",
                    R.id.drawer_keyboard_settings to "Keyboard settings",
                    R.id.drawer_authentication to "Authentication & SSH settings",
                    R.id.drawer_appearance to "Appearance",
                    R.id.drawer_about to "About XMouse-NG",
                )) assertEquals(label, activity.findViewById<Button>(id).text.toString())
                val drawerContent = activity.findViewById<View>(R.id.shell_navigation_drawer)
                for (removed in listOf("Session controls", "Scripts", "Saved scripts", "Manage scripts",
                    "Add script", "Legacy layout tools", "Layouts & actions", "Send clipboard to computer")) {
                    assertNull("the drawer no longer exposes $removed", findText(drawerContent, removed))
                }
                assertEquals("draft across tabs and drawer", input.text.toString())
                activity.onBackPressedDispatcher.onBackPressed()
            }
            awaitFixture(scenario, "Back closes the hamburger drawer") {
                !it.findViewById<DrawerLayout>(R.id.main_safe_area).isDrawerVisible(GravityCompat.START)
            }
            scenario.onActivity { activity ->
                val drawer = activity.findViewById<DrawerLayout>(R.id.main_safe_area)
                val input = activity.findViewById<EditText>(R.id.keyboard_input)
                assertFalse(drawer.isDrawerOpen(GravityCompat.START))
                assertNull(activity.navigation.topOverlay())
                assertEquals(ShellNavigation.InputRole.POINTER, activity.navigation.inputRole())
                activity.findViewById<BottomNavigationView>(R.id.bottom_navigation).selectedItemId = R.id.nav_actions
                assertEquals("draft across tabs and drawer", input.text.toString())
                assertTrue(input.isShown)
                assertFalse(input.hasFocus())
                activity.findViewById<View>(R.id.header_menu).performClick()
            }
            awaitFixture(scenario, "drawer reopens before outside dismissal") {
                it.findViewById<DrawerLayout>(R.id.main_safe_area).isDrawerOpen(GravityCompat.START)
            }
            var outsideX = 0
            var outsideY = 0
            var activeRight = 0
            scenario.onActivity { activity ->
                val root = activity.findViewById<View>(R.id.native_shell)
                val rootLocation = IntArray(2)
                root.getLocationOnScreen(rootLocation)
                activeRight = rootLocation[0] + root.width
                val drawer = activity.findViewById<View>(R.id.shell_navigation_drawer)
                val location = IntArray(2)
                drawer.getLocationOnScreen(location)
                outsideX = location[0] + drawer.width +
                    ((activeRight - location[0] - drawer.width) / 2).coerceAtLeast(1)
                outsideY = location[1] + drawer.height / 2
            }
            assertTrue("the live app window has a visible scrim area beside the drawer", outsideX < activeRight)
            device.click(outsideX, outsideY)
            awaitFixture(scenario, "outside tap closes the drawer") {
                !it.findViewById<DrawerLayout>(R.id.main_safe_area).isDrawerVisible(GravityCompat.START)
            }
            scenario.onActivity { activity ->
                assertNull("outside dismissal closes the drawer overlay", activity.navigation.topOverlay())
                assertEquals("outside dismissal retains the local composer draft",
                    "draft across tabs and drawer", activity.findViewById<EditText>(R.id.keyboard_input).text.toString())
            }
        }
    }

    @Test fun drawerSelectionKeepsFocusOnTheOpenedComputersSheet() {
        ActivityScenario.launch(MainInsetsFixtureActivity::class.java).use { scenario ->
            instrumentation.waitForIdleSync()
            val device = UiDevice.getInstance(instrumentation)
            assertTrue("the keyboard-navigation focus regression injects Tab", device.pressKeyCode(KeyEvent.KEYCODE_TAB))
            awaitFixture(scenario, "Tab enters keyboard navigation before checking focus restoration") {
                !it.findViewById<View>(R.id.native_shell).isInTouchMode
            }
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.header_menu).performClick()
            }
            awaitFixture(scenario, "hamburger drawer opens for Computers selection") {
                it.findViewById<DrawerLayout>(R.id.main_safe_area).isDrawerOpen(GravityCompat.START)
            }
            scenario.onActivity { it.findViewById<View>(R.id.drawer_computers).performClick() }
            awaitFixture(scenario, "drawer selection closes into Computers without stealing sheet focus") {
                !it.findViewById<DrawerLayout>(R.id.main_safe_area).isDrawerVisible(GravityCompat.START) &&
                    it.navigation.topOverlay() == ShellNavigation.Overlay.COMPUTERS &&
                    it.findViewById<View>(R.id.overlay_close).hasFocus()
            }
            scenario.onActivity { activity ->
                assertFalse(activity.findViewById<DrawerLayout>(R.id.main_safe_area).isDrawerOpen(GravityCompat.START))
                assertEquals(ShellNavigation.Overlay.COMPUTERS, activity.navigation.topOverlay())
                assertTrue("selection keeps focus in the modal sheet after the drawer animation",
                    activity.findViewById<View>(R.id.overlay_close).hasFocus())
                assertFalse("the hidden header must not steal focus from the sheet",
                    activity.findViewById<View>(R.id.header_menu).hasFocus())
            }
        }
    }

    @Test fun sameActivityOverlaysBackAndRecreationRestoreNavigationButNotTypingText() {
        ActivityScenario.launch(MainInsetsFixtureActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<BottomNavigationView>(R.id.bottom_navigation).selectedItemId = R.id.nav_actions
                activity.findViewById<View>(R.id.computer_selector).performClick()
                assertEquals(ShellNavigation.InputRole.NONE, activity.navigation.inputRole())
                assertTrue(activity.findViewById<View>(R.id.shell_overlay).isShown)
                activity.onBackPressedDispatcher.onBackPressed()
                assertEquals(ShellNavigation.Destination.ACTIONS, activity.navigation.destination())
                activity.onBackPressedDispatcher.onBackPressed()
                assertEquals(ShellNavigation.Destination.TOUCHPAD, activity.navigation.destination())
                activity.findViewById<EditText>(R.id.keyboard_input).setText("fixture text must not be bundled")
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals(ShellNavigation.Destination.TOUCHPAD, activity.navigation.destination())
                assertTrue(activity.navigation.overlays().isEmpty())
                assertEquals(ShellNavigation.InputRole.POINTER, activity.navigation.inputRole())
                assertEquals("", activity.findViewById<EditText>(R.id.keyboard_input).text.toString())
                assertFalse("restoring the inline input does not open the IME", activity.findViewById<EditText>(R.id.keyboard_input).hasFocus())
            }
        }
    }

    @Test fun quickComputersUsesModalBottomSheetCardAndKeepsRealManagementRoutesLabeled() {
        val device = UiDevice.getInstance(instrumentation)
        ActivityScenario.launch(MainInsetsFixtureActivity::class.java).use { scenario ->
            instrumentation.waitForIdleSync()
            assertTrue("the keyboard-focus scenario enters navigation mode",
                device.pressKeyCode(KeyEvent.KEYCODE_TAB))
            awaitFixture(scenario, "keyboard navigation is active before opening Computers") {
                !it.findViewById<View>(R.id.native_shell).isInTouchMode
            }
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(R.id.native_shell)
                val composer = root.findViewById<EditText>(R.id.keyboard_input)
                composer.setText("computer sheet retains the composer draft")
                activity.findViewById<View>(R.id.computer_selector).performClick()
            }
            awaitFixture(scenario, "Computers sheet is laid out at the shell bottom") {
                val root = it.findViewById<ViewGroup>(R.id.native_shell)
                val overlay = root.findViewById<FrameLayout>(R.id.shell_overlay)
                val sheet = root.findViewById<LinearLayout>(R.id.computers_sheet)
                root.height > 0 && overlay.height == root.height && sheet.height > 0 && sheet.bottom == overlay.height
            }
            awaitFixture(scenario, "modal close control receives keyboard focus") {
                it.findViewById<View>(R.id.overlay_close).hasFocus()
            }
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(R.id.native_shell)
                val overlay = root.findViewById<FrameLayout>(R.id.shell_overlay)
                val sheet = root.findViewById<LinearLayout>(R.id.computers_sheet)
                val composer = root.findViewById<EditText>(R.id.keyboard_input)
                assertEquals(ShellNavigation.Overlay.COMPUTERS, activity.navigation.topOverlay())
                assertTrue(overlay.isShown)
                assertEquals(root.height, overlay.height)
                assertTrue("the sheet stays anchored at the modal root bottom", sheet.bottom == overlay.height)
                assertTrue("the sheet has rounded-card elevation", sheet.elevation > 0f)
                assertTrue("the sheet has a card surface", sheet.background != null)
                assertEquals("Computers", root.findViewById<TextView>(R.id.overlay_title).text.toString())
                for ((id, title) in listOf(
                    R.id.computers_connect to "Connect",
                    R.id.computers_add to "Add computer",
                    R.id.computers_find to "Find computers",
                    R.id.computers_manage to "Manage computers",
                )) assertEquals(title, root.findViewById<Button>(id).text.toString())
                assertTrue("modal close control receives keyboard focus", root.findViewById<View>(R.id.overlay_close).hasFocus())
                assertEquals("opening Computers retains the draft", "computer sheet retains the composer draft",
                    composer.text.toString())
                overlay.performClick()
                assertNull(activity.navigation.topOverlay())
                assertEquals("closing Computers retains the draft", "computer sheet retains the composer draft",
                    composer.text.toString())
                activity.findViewById<View>(R.id.computer_selector).performClick()
                root.findViewById<View>(R.id.overlay_close).performClick()
                assertNull(activity.navigation.topOverlay())
            }
        }
    }

    @Test fun cancellingLegacyActionReorderNeverDropsEditsOrDeletesAnItem() {
        ActivityScenario.launch(MainInsetsFixtureActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val grid = activity.findViewById<CoolDragAndDropGridView>(R.id.key_drag_DragAndDropGridView)
                var cancellations = 0
                grid.setDragAndDropListener(object : CoolDragAndDropGridView.DragAndDropListener {
                    override fun onDragItem(from: Int) = Unit
                    override fun onDraggingItem(from: Int, to: Int) = Unit
                    override fun isDragAndDropEnabled(position: Int) = true
                    override fun onDropItem(from: Int, to: Int) = fail("cancel must not reorder/save")
                    override fun onEditItem(position: Int) = fail("cancel must not open an editor")
                    override fun onDeleteItem(position: Int) = fail("cancel must not delete")
                    override fun onDragCancelled() { cancellations++ }
                })
                grid.startDragAndDrop()
                grid.cancelLegacyDragAndDrop()
                grid.cancelLegacyDragAndDrop()
                assertEquals(1, cancellations)
                grid.startDragAndDrop()
                val up = android.view.MotionEvent.obtain(1, 2, android.view.MotionEvent.ACTION_UP, 0f, 0f, 0)
                try { assertTrue(grid.onInterceptTouchEvent(up)) } finally { up.recycle() }
                assertEquals("release before first drag MOVE also closes the edit layer", 2, cancellations)
            }
        }
    }

    @Test fun actualActionTilesMatchTenColumnPreviewWithTitlesColorsAndNormalEditCallbacks() {
        for (dark in listOf(false, true)) {
            val intent = MainInsetsFixtureActivity.configuredIntent(ApplicationProvider.getApplicationContext(), 1.5f, dark)
            ActivityScenario.launch<MainInsetsFixtureActivity>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    val root = activity.findViewById<ViewGroup>(R.id.native_shell)
                    val width = (320 * activity.resources.displayMetrics.density).toInt()
                    val height = (720 * activity.resources.displayMetrics.density).toInt()
                    activity.findViewById<BottomNavigationView>(R.id.bottom_navigation).selectedItemId = R.id.nav_actions
                    val profile = LayoutProfile(
                        id = "fixture-actions",
                        name = "Same name",
                        buttons = listOf(
                            LayoutButton("regular-1", "Synthetic Alpha", "key alt+F4", 5, "#E0EFE8"),
                            LayoutButton("regular-2", "Regular two", "printf exact", 2, "#80ABCDEF"),
                            LayoutButton("wide", "Wide action", "printf wide", 10, "#212121"),
                            LayoutButton("custom", "Custom span", "printf custom", 7, "#FFFFFF"),
                            LayoutButton("narrow", "Narrow", "printf narrow", 1, "#E0EFE8"),
                        ),
                    )
                    val original = profile.copy(buttons = profile.buttons.toList())
                    assertEquals(listOf("fixture-actions", "other-id"), ActionPresentation.layoutChoices(listOf(
                        profile,
                        LayoutProfile(id = "other-id", name = "Same name"),
                    )).map { it.id })
                    assertEquals(listOf("Same name", "Same name"), ActionPresentation.layoutChoices(listOf(
                        profile,
                        LayoutProfile(id = "other-id", name = "Same name"),
                    )).map { it.name })

                    val tiles = activity.findViewById<LinearLayout>(R.id.actions_tiles)
                    val callbacks = mutableListOf<String>()
                    fun render(editMode: Boolean) {
                        ActionsViewRenderer.renderTiles(activity, tiles, profile, editMode) { tile, button ->
                            tile.setOnClickListener {
                                callbacks += "${if (editMode) "edit" else "run"}:${button.id}"
                            }
                        }
                    }
                    render(false)
                    root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, width, height)

                    assertEquals(3, tiles.childCount)
                    val rows = (0 until tiles.childCount).map { tiles.getChildAt(it) as LinearLayout }
                    assertEquals(3, rows[0].childCount)
                    assertEquals(1, rows[1].childCount)
                    assertEquals(3, rows[2].childCount)
                    assertEquals(listOf(5f, 2f, 3f), (0..2).map {
                        (rows[0].getChildAt(it).layoutParams as LinearLayout.LayoutParams).weight
                    })
                    assertEquals(10f, (rows[1].getChildAt(0).layoutParams as LinearLayout.LayoutParams).weight, 0f)
                    assertEquals(listOf(7f, 1f, 2f), (0..2).map {
                        (rows[2].getChildAt(it).layoutParams as LinearLayout.LayoutParams).weight
                    })
                    val density = activity.resources.displayMetrics.density
                    fun px(dp: Int) = (dp * density + .5f).toInt()
                    assertEquals("editor preview row spacing", px(8), rows[1].top - rows[0].bottom)

                    val firstSlot = rows[0].getChildAt(0) as FrameLayout
                    val secondSlot = rows[0].getChildAt(1) as FrameLayout
                    val wideSlot = rows[1].getChildAt(0) as FrameLayout
                    val customSlot = rows[2].getChildAt(0) as FrameLayout
                    val narrowSlot = rows[2].getChildAt(1) as FrameLayout
                    val first = firstSlot.getChildAt(0) as MaterialCardView
                    val second = secondSlot.getChildAt(0) as MaterialCardView
                    val wide = wideSlot.getChildAt(0) as MaterialCardView
                    val custom = customSlot.getChildAt(0) as MaterialCardView
                    val narrow = narrowSlot.getChildAt(0) as MaterialCardView
                    assertEquals(listOf("regular-1", "regular-2", "wide", "custom", "narrow"),
                        listOf(first.tag, second.tag, wide.tag, custom.tag, narrow.tag))
                    assertEquals(px(56), first.height)
                    assertEquals(px(2), first.left)
                    assertEquals("each tile retains its independent 2dp side margin",
                        firstSlot.width - 2 * px(2), first.width)
                    assertTrue("a stored one-column span may remain narrower than a 48dp target", narrow.width < px(48))
                    assertEquals(px(8).toFloat(), first.radius, 1f)
                    assertEquals(android.graphics.Color.parseColor("#E0EFE8"), first.cardBackgroundColor.defaultColor)
                    assertEquals(android.graphics.Color.parseColor("#80ABCDEF"), second.cardBackgroundColor.defaultColor)

                    for ((card, title, widthColumns) in listOf(
                        Triple(first, "Synthetic Alpha", 5),
                        Triple(second, "Regular two", 2),
                        Triple(wide, "Wide action", 10),
                        Triple(custom, "Custom span", 7),
                        Triple(narrow, "Narrow", 1),
                    )) {
                        assertTrue("$title is a clickable action", card.isClickable)
                        assertEquals("The tile contains its title only (no icon or command subtitle)", 1, card.childCount)
                        val label = card.getChildAt(0) as TextView
                        assertEquals(title, label.text.toString())
                        assertEquals(1, label.maxLines)
                        assertEquals(android.text.TextUtils.TruncateAt.END, label.ellipsize)
                        assertEquals(android.view.Gravity.CENTER, label.gravity)
                        assertTrue("$title accessibility includes its identity and exact logical width",
                            card.contentDescription.toString().contains("action ID ${card.tag}") &&
                                card.contentDescription.toString().contains("width $widthColumns columns"))
                        assertTrue("tile text uses legible dark or light foreground",
                            label.currentTextColor == android.graphics.Color.BLACK || label.currentTextColor == android.graphics.Color.WHITE)
                    }
                    assertTrue(first.contentDescription.toString().contains("button 1 of 5"))
                    assertTrue(callbacks.isEmpty())
                    assertTrue(first.performClick())
                    assertEquals(listOf("run:regular-1"), callbacks)

                    render(true)
                    root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, width, height)
                    val editingFirst = ((tiles.getChildAt(0) as LinearLayout).getChildAt(0) as FrameLayout)
                        .getChildAt(0) as MaterialCardView
                    assertTrue("edit state is disclosed accessibly", editingFirst.contentDescription.toString().contains("Tap to edit"))
                    assertTrue(editingFirst.performClick())
                    assertEquals(listOf("run:regular-1", "edit:regular-1"), callbacks)
                    assertEquals("Rendering must not change IDs, order, commands, widths, or colors", original, profile)
                }
            }
        }
    }

    @Test fun actionsEmptyEditDoneAndIdBasedPickerShareProductionRenderingWithoutCommands() {
        ActivityScenario.launch(MainInsetsFixtureActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<BottomNavigationView>(R.id.bottom_navigation).selectedItemId = R.id.nav_actions
                val tiles = activity.findViewById<LinearLayout>(R.id.actions_tiles)
                assertNotNull(findText(tiles, activity.getString(R.string.ng_actions_empty)))
                assertNull("normal Actions does not expose Add action",
                    findText(activity.findViewById(R.id.actions_destination), activity.getString(R.string.ng_add_action)))
                activity.actionProfiles = (0..5).map { LayoutProfile(id = "fixture-profile-$it", name = "Same name") }
                activity.selectedActionProfile = activity.actionProfiles.first()
                activity.renderActionsFixture()
                val edit = activity.findViewById<Button>(R.id.actions_edit)
                edit.performClick()
                assertEquals("Done", edit.text.toString())
                assertEquals(ShellNavigation.InputRole.NONE, activity.navigation.inputRole())
                edit.performClick()
                assertEquals("Edit", edit.text.toString())
                assertEquals(ShellNavigation.InputRole.ACTIONS, activity.navigation.inputRole())
                activity.findViewById<View>(R.id.actions_layout_picker).performClick()
                val dialog = activity.actionPicker!!
                assertEquals("four virtual presets precede saved layouts", 10, dialog.listView.adapter.count)
                assertEquals("Built-in · Common", dialog.listView.adapter.getItem(0).toString())
                assertTrue(dialog.listView.adapter.getItem(8).toString().contains("fixture-profile-4"))
                dialog.listView.performItemClick(null, 8, 8)
                assertEquals("fixture-profile-4", activity.selectedActionProfile!!.id)
                assertTrue(activity.findViewById<View>(R.id.actions_layout_picker).contentDescription.toString().contains("fixture-profile-4"))
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals(ShellNavigation.InputRole.ACTIONS, activity.navigation.inputRole())
            }
        }
    }

    @Test fun virtualPresetPickerRendersReadOnlyTenColumnTilesAndRetainsComposerAcrossIdCollision() {
        val intent = MainInsetsFixtureActivity.configuredIntent(
            ApplicationProvider.getApplicationContext(), 1f, false)
        ActivityScenario.launch<MainInsetsFixtureActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val collision = LayoutProfile(id = "builtin-common", name = "My saved Common",
                    buttons = listOf(LayoutButton("saved-button", "Saved button", "key Escape", 5, "#FFFFFF")))
                activity.actionProfiles = listOf(collision)
                val input = activity.findViewById<EditText>(R.id.keyboard_input)
                input.setText("composer stays while choosing a preset")
                activity.findViewById<BottomNavigationView>(R.id.bottom_navigation).selectedItemId = R.id.nav_actions
                activity.findViewById<View>(R.id.actions_layout_picker).performClick()
                val picker = requireNotNull(activity.actionPicker)
                assertEquals(5, picker.listView.adapter.count)
                assertEquals("Built-in · Common", picker.listView.adapter.getItem(0).toString())
                assertTrue(picker.listView.adapter.getItem(4).toString().contains("builtin-common"))
                picker.listView.performItemClick(null, 0, 0)
            }
            awaitFixture(scenario, "built-in preset picker closes after selection") {
                it.actionPicker == null && it.navigation.topOverlay() == null
            }
            scenario.onActivity { activity ->
                val collision = activity.actionProfiles.single()
                val input = activity.findViewById<EditText>(R.id.keyboard_input)
                assertEquals("builtin-common", activity.selectedActionPreset?.id())
                assertEquals("Common", activity.selectedActionProfile?.name)
                assertEquals("composer stays while choosing a preset", input.text.toString())
                assertTrue(input.isShown)
                assertEquals(ShellNavigation.InputRole.ACTIONS, activity.navigation.inputRole())
                assertEquals("Read-only", activity.findViewById<Button>(R.id.actions_edit).text.toString())
                assertFalse(activity.findViewById<View>(R.id.actions_edit).isEnabled)
                assertTrue(activity.findViewById<View>(R.id.actions_layout_picker).isEnabled)

                val tiles = activity.findViewById<ViewGroup>(R.id.actions_tiles)
                val copy = requireNotNull(findTaggedView(tiles, "common-copy"))
                val copyCard = copy as MaterialCardView
                assertEquals("a preset uses a normal five-of-ten editor span", 5,
                    (((copyCard.parent as FrameLayout).layoutParams as android.widget.LinearLayout.LayoutParams).weight).toInt())
                assertEquals(1, copyCard.childCount)
                assertEquals("Copy", (copyCard.getChildAt(0) as TextView).text.toString())
                assertEquals("#EEF1F4", String.format("#%06X", 0xFFFFFF and copyCard.cardBackgroundColor.defaultColor))
                val close = requireNotNull(findTaggedView(tiles, "common-close")) as MaterialCardView
                assertEquals("#FBE9E7", String.format("#%06X", 0xFFFFFF and close.cardBackgroundColor.defaultColor))

                activity.findViewById<View>(R.id.actions_layout_picker).performClick()
                requireNotNull(activity.actionPicker).listView.performItemClick(null, 4, 4)
            }
            awaitFixture(scenario, "saved layout picker closes after selection") {
                it.actionPicker == null && it.navigation.topOverlay() == null
            }
            scenario.onActivity { activity ->
                val input = activity.findViewById<EditText>(R.id.keyboard_input)
                val collision = activity.actionProfiles.single()
                assertNull("a colliding saved id remains a distinct user-layout choice", activity.selectedActionPreset)
                assertEquals(collision, activity.selectedActionProfile)
                assertTrue(activity.findViewById<View>(R.id.actions_edit).isEnabled)
                assertEquals("composer stays while choosing a preset", input.text.toString())
            }
        }
    }

    @Test fun actionEditorFormKeepsPresetDefaultsAndOptionalRgbScrollableAtNarrowWidth() {
        for (dark in listOf(false, true)) {
        val intent = MainInsetsFixtureActivity.configuredIntent(ApplicationProvider.getApplicationContext(), 1.5f, dark)
        ActivityScenario.launch<MainInsetsFixtureActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContentView(R.layout.activity_action_editor)
                val root = activity.findViewById<ViewGroup>(R.id.action_editor_root)
                val density = activity.resources.displayMetrics.density
                val width = (320 * density).toInt()
                val height = (420 * density).toInt()
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
                assertEquals("", root.findViewById<EditText>(R.id.action_custom_color).text.toString())
                assertEquals(View.GONE, root.findViewById<View>(R.id.action_custom_color_field).visibility)
                assertTrue(root.findViewById<View>(R.id.action_editor_save).height >= 48 * density - 1)
                assertTrue(root.findViewById<View>(R.id.action_color_advanced_toggle).height >= 48 * density - 1)
                assertEquals(3, root.findViewById<android.widget.RadioGroup>(R.id.action_size_group).childCount)
                for (id in listOf(R.id.action_type_shortcut, R.id.action_type_advanced,
                    R.id.action_size_regular, R.id.action_size_wide, R.id.action_color_white,
                    R.id.action_editor_open_advanced, R.id.action_editor_new_script)) {
                    assertTrue("form target $id remains 48dp", root.findViewById<View>(id).height >= 48 * density - 1)
                }
                val scroll = root.findViewById<android.widget.ScrollView>(R.id.action_editor_scroll)
                assertTrue("long action forms remain scrollable with the IME", scroll.getChildAt(0).height > scroll.height)
                // Layout-only fixture does not install Activity listeners or open repositories.
                root.findViewById<View>(R.id.action_custom_color_field).visibility = View.VISIBLE
                assertEquals(View.VISIBLE, root.findViewById<View>(R.id.action_custom_color_field).visibility)
            }
        }
        }
    }

    private fun findText(parent: View, text: String): TextView? {
        if (parent is TextView && parent.text.toString().contains(text)) return parent
        if (parent !is ViewGroup) return null
        for (index in 0 until parent.childCount) findText(parent.getChildAt(index), text)?.let { return it }
        return null
    }

    private fun findTaggedView(parent: View, tag: Any): View? {
        if (parent.tag == tag) return parent
        if (parent !is ViewGroup) return null
        for (index in 0 until parent.childCount) {
            findTaggedView(parent.getChildAt(index), tag)?.let { return it }
        }
        return null
    }

    private fun assertTextFits(button: Button) {
        assertTrue("${button.text} must not clip vertically", button.layout.height <=
            button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
        for (line in 0 until button.layout.lineCount) assertEquals(0, button.layout.getEllipsisCount(line))
        val visible = android.graphics.Rect()
        assertTrue(button.getLocalVisibleRect(visible))
        assertEquals("${button.text} must not be clipped by its weighted parent row", button.height, visible.height())
    }
}
