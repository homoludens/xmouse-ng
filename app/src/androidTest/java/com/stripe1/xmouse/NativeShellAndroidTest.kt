package com.stripe1.xmouse

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.stripe1.xmouse.layout.ActionPresentation
import com.stripe1.xmouse.layout.LayoutButton
import com.stripe1.xmouse.layout.LayoutProfile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated production rendering; never initializes MainActivity, SSH, credentials, or user data. */
@RunWith(AndroidJUnit4::class)
class NativeShellAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

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
                    root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, width, height)
                    for ((id, label) in listOf(R.id.computer_name_field to "Display name (optional)",
                        R.id.computer_host_field to "Hostname or IP address", R.id.computer_user_field to "Username",
                        R.id.computer_password_field to "Password", R.id.computer_port_field to "SSH port")) {
                        val field = root.findViewById<com.google.android.material.textfield.TextInputLayout>(id)
                        assertEquals(label, field.hint.toString()); assertFalse(field.isExpandedHintEnabled)
                        assertTrue(field.width <= width)
                    }
                    val password = root.findViewById<EditText>(R.id.new_host_password)
                    assertEquals("", password.text.toString()); assertFalse(password.isSaveEnabled)
                    assertEquals(com.google.android.material.textfield.TextInputLayout.END_ICON_PASSWORD_TOGGLE,
                        root.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.computer_password_field).endIconMode)
                    assertEquals("22", root.findViewById<EditText>(R.id.new_host_port).text.toString())
                    for (id in listOf(R.id.computer_test, R.id.computer_save, R.id.computer_advanced, R.id.computer_auth_settings))
                        assertTrue(root.findViewById<View>(id).height >= 48 * density - 1)
                    val scroll = root.getChildAt(1) as android.widget.ScrollView
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
                    for (id in listOf(R.id.computer_selector, R.id.header_settings, R.id.toolbar, R.id.connection_status)) {
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
                    assertEquals(listOf("Touchpad", "Keys", "Actions"),
                        (0 until bottom.menu.size()).map { bottom.menu.getItem(it).title.toString() })
                    listOf(R.id.nav_keys, R.id.nav_actions, R.id.nav_touchpad).forEach { id ->
                        bottom.selectedItemId = id
                        assertTrue(bottom.menu.findItem(id).isChecked)
                        assertEquals(NativeShellViews.destination(id), activity.navigation.destination())
                    }
                    assertEquals("XMouse-NG", activity.getString(R.string.app_name))
                    assertEquals("● Disconnected", root.findViewById<TextView>(R.id.connection_status).text.toString())

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
                    for (id in listOf(R.id.touchpad_key_escape, R.id.touchpad_key_switch, R.id.touchpad_key_super)) {
                        val button = root.findViewById<Button>(id)
                        assertTrue("quick key $id is labeled", button.text.isNotBlank())
                        assertTrue("quick key $id is a 48dp target", button.height >= 48 * density - 1)
                        assertTextFits(button)
                    }
                    assertTrue("typing access remains a 48dp target",
                        root.findViewById<View>(R.id.type_on_computer).height >= 48 * density - 1)
                }
            }
        }
    }

    @Test fun keysCategoriesRenderAccessibleCatalogAndRetireBeforeReplacingControls() {
        for (dark in listOf(false, true)) {
            val intent = MainInsetsFixtureActivity.configuredIntent(ApplicationProvider.getApplicationContext(), 1.5f, dark)
            ActivityScenario.launch<MainInsetsFixtureActivity>(intent).use { scenario ->
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val root = activity.findViewById<ViewGroup>(R.id.native_shell)
                    val density = activity.resources.displayMetrics.density
                    val width = (320 * density).toInt()
                    val height = (720 * density).toInt()
                    fun measureShell() {
                        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                        root.layout(0, 0, width, height)
                    }
                    measureShell()

                    val bottom = root.findViewById<BottomNavigationView>(R.id.bottom_navigation)
                    bottom.selectedItemId = R.id.nav_keys
                    measureShell()
                    val keysType = root.findViewById<Button>(R.id.keys_type)
                    assertEquals("Type", keysType.text.toString())
                    assertTrue("compact Type action is a 48dp target", keysType.height >= 48 * density - 1)
                    keysType.performClick()
                    assertEquals(ShellNavigation.Overlay.TYPING, activity.navigation.topOverlay())
                    assertEquals(ShellNavigation.InputRole.TYPING, activity.navigation.inputRole())
                    root.findViewById<Button>(R.id.overlay_close).performClick()
                    assertEquals(ShellNavigation.InputRole.KEYS, activity.navigation.inputRole())
                    measureShell()
                    val tabs = root.findViewById<MaterialButtonToggleGroup>(R.id.keys_category_group)
                    val categoryScroll = root.findViewById<HorizontalScrollView>(R.id.keys_category_scroll)
                    assertTrue("category strip remains horizontally scrollable at 320dp and large fonts",
                        tabs.width >= categoryScroll.width)
                    assertEquals(listOf("Common", "Navigate", "Media", "F1–F12"),
                        (0 until tabs.childCount).map { (tabs.getChildAt(it) as MaterialButton).text.toString() })

                    val commonEscape = root.findViewById<Button>(R.id.key_escape)
                    assertTrue(commonEscape.isShown)
                    activity.shellEvents.clear()
                    root.findViewById<MaterialButton>(R.id.keys_category_media).performClick()
                    measureShell()
                    assertEquals(KeyCatalog.Category.MEDIA, activity.navigation.keyCategory())
                    assertEquals(listOf("retire", "render", "refresh"), activity.shellEvents)
                    assertFalse("the old category controls are detached before the new KEYS capability is used",
                        commonEscape.isShown)
                    assertTrue(root.findViewById<MaterialButton>(R.id.keys_category_media).isChecked)

                    for (category in KeyCatalog.categories()) {
                        val categoryId = when (category) {
                            KeyCatalog.Category.COMMON -> R.id.keys_category_common
                            KeyCatalog.Category.NAVIGATE -> R.id.keys_category_navigate
                            KeyCatalog.Category.MEDIA -> R.id.keys_category_media
                            KeyCatalog.Category.FUNCTION -> R.id.keys_category_function
                        }
                        root.findViewById<MaterialButton>(categoryId).performClick()
                        measureShell()
                        assertEquals(category, activity.navigation.keyCategory())
                        val content = root.findViewById<ViewGroup>(R.id.keys_catalog_content)
                        val buttons = mutableListOf<Button>()
                        collectButtons(content, buttons)
                        val expected = KeyCatalog.sections(category).flatMap { it.entries() }.map { it.label() }
                        assertEquals("rendered labels for ${category.label()}", expected,
                            buttons.map { it.contentDescription.toString() })
                        for (button in buttons) {
                            assertTrue("${button.text} is an accessible 48dp touch target",
                                button.height >= 48 * density - 1)
                            assertTrue("${button.text} remains attached and visible", button.isShown)
                            val layout = button.layout
                            assertNotNull("${button.text} has a measured text layout", layout)
                            for (line in 0 until layout.lineCount) {
                                assertEquals("${button.text} is not ellipsized", 0, layout.getEllipsisCount(line))
                            }
                            assertTrue("${button.text} text fits vertically", layout.height <=
                                button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
                            assertTrue("${button.text} is not clipped by its weighted row", button.height <= (button.parent as View).height)
                        }
                    }
                }
            }
        }
    }

    @Test fun typingUsesLabeledMultilineImeAndReachableExplicitQuickKeys() {
        ActivityScenario.launch(MainInsetsFixtureActivity::class.java).use { scenario ->
            scenario.onActivity { it.findViewById<View>(R.id.type_on_computer).performClick() }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(R.id.native_shell)
                val input = activity.findViewById<EditText>(R.id.keyboard_input)
                assertTrue(input.isShown)
                assertTrue(input.inputType and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0)
                assertFalse("typing content is not saved into a state bundle", input.isSaveEnabled)
                assertEquals(activity.getString(R.string.ng_typing_label),
                    activity.findViewById<TextView>(R.id.keyboard_input_label).text.toString())
                for ((id, label) in listOf(
                    R.id.typing_key_escape to R.string.ng_escape,
                    R.id.typing_key_tab to R.string.ng_tab,
                    R.id.typing_key_backspace to R.string.ng_backspace,
                    R.id.typing_key_enter to R.string.ng_enter,
                )) {
                    val button = activity.findViewById<Button>(id)
                    assertEquals(activity.getString(label), button.text.toString())
                    assertTrue("typing quick key $id is at least 48dp high", button.height >= 48 * activity.resources.displayMetrics.density - 1)
                }
                val send = activity.findViewById<Button>(R.id.keyboard_send)
                assertEquals(activity.getString(R.string.ng_send), send.text.toString())
                assertTrue("Send is a full touch target", send.height >= 48 * activity.resources.displayMetrics.density - 1)

                input.setText("fixture edit remains local")
                assertEquals("fixture edit remains local", input.text.toString())
                assertTrue("scrollable typing flow stays within the shell", root.findViewById<View>(R.id.typing_content).height > 0)
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
                activity.findViewById<View>(R.id.type_on_computer).performClick()
                assertEquals(ShellNavigation.InputRole.TYPING, activity.navigation.inputRole())
                activity.findViewById<EditText>(R.id.keyboard_input).setText("fixture text must not be bundled")
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals(ShellNavigation.Overlay.TYPING, activity.navigation.topOverlay())
                assertEquals("", activity.findViewById<EditText>(R.id.keyboard_input).text.toString())
                activity.onBackPressedDispatcher.onBackPressed()
                assertEquals(ShellNavigation.InputRole.POINTER, activity.navigation.inputRole())
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

    @Test fun actualActionTilesRenderAsTwoColumnsWithCustomWidthsAndThemeContrast() {
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
                        ),
                    )
                    assertEquals(listOf("fixture-actions", "other-id"), ActionPresentation.layoutChoices(listOf(
                        profile,
                        LayoutProfile(id = "other-id", name = "Same name"),
                    )).map { it.id })
                    assertEquals(listOf("Same name", "Same name"), ActionPresentation.layoutChoices(listOf(
                        profile,
                        LayoutProfile(id = "other-id", name = "Same name"),
                    )).map { it.name })

                    val tiles = activity.findViewById<LinearLayout>(R.id.actions_tiles)
                    ActionsViewRenderer.renderTiles(activity, tiles, profile, false, null)
                    root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, width, height)

                    assertEquals(3, tiles.childCount)
                    val rows = (0 until tiles.childCount).map { tiles.getChildAt(it) as LinearLayout }
                    val firstRowCards = (0 until rows[0].childCount).mapNotNull { rows[0].getChildAt(it) as? MaterialCardView }
                    val wideRowCards = (0 until rows[1].childCount).mapNotNull { rows[1].getChildAt(it) as? MaterialCardView }
                    val customRowCards = (0 until rows[2].childCount).mapNotNull { rows[2].getChildAt(it) as? MaterialCardView }
                    assertEquals(2, firstRowCards.size)
                    assertEquals(1, wideRowCards.size)
                    assertEquals(1, customRowCards.size)
                    assertEquals("regular-1", firstRowCards[0].tag)
                    assertEquals("regular-2", firstRowCards[1].tag)
                    assertEquals("wide", wideRowCards.single().tag)
                    assertEquals("custom", customRowCards.single().tag)
                    assertTrue("wide tile spans its full row", wideRowCards.single().width >= firstRowCards[0].width * 2)
                    assertTrue("custom span is disclosed in subtitle",
                        findText(customRowCards.single(), "Custom size (7/10)") != null)
                    for (card in firstRowCards + wideRowCards + customRowCards) {
                        assertTrue("tile ${card.tag} (${card.height}px) remains a 48dp+ target", card.height >= 48 * activity.resources.displayMetrics.density - 1)
                        assertTrue("tile accessibility includes its stable ID", card.contentDescription.toString().contains(card.tag.toString()))
                    }
                    val longTitle = requireNotNull(findText(firstRowCards[0], "Synthetic Alpha"))
                    assertTrue("the action title fits its text height", longTitle.layout.height <= longTitle.height)
                    assertEquals("both words remain readable at 320dp/1.5x", longTitle.text.length,
                        longTitle.layout.getLineEnd(longTitle.layout.lineCount - 1))
                    for (line in 0 until longTitle.layout.lineCount) {
                        assertEquals("the representative action title must not be ellipsized", 0,
                            longTitle.layout.getEllipsisCount(line))
                    }
                    val onAccent = firstRowCards[0].contentDescription
                    assertTrue(onAccent.toString().contains("alt+F4"))
                    val subtitle = findText(firstRowCards[0], "alt+F4")
                    assertNotNull(subtitle)
                    assertTrue("tile text uses legible dark or light foreground",
                        subtitle!!.currentTextColor == android.graphics.Color.BLACK || subtitle.currentTextColor == android.graphics.Color.WHITE)
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
                assertEquals(6, dialog.listView.adapter.count)
                assertTrue(dialog.listView.adapter.getItem(4).toString().contains("fixture-profile-4"))
                dialog.listView.performItemClick(null, 4, 4)
                assertEquals("fixture-profile-4", activity.selectedActionProfile!!.id)
                assertTrue(activity.findViewById<View>(R.id.actions_layout_picker).contentDescription.toString().contains("fixture-profile-4"))
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals(ShellNavigation.InputRole.ACTIONS, activity.navigation.inputRole())
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

    private fun collectButtons(parent: ViewGroup, result: MutableList<Button>) {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child is Button) result += child
            if (child is ViewGroup) collectButtons(child, result)
        }
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
