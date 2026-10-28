package net.droopia.xmouse_ng

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.card.MaterialCardView
import net.droopia.xmouse_ng.layout.LayoutButton
import net.droopia.xmouse_ng.layout.LayoutProfile
import net.droopia.xmouse_ng.layout.SavedActionScript
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Measures the production renderers without sessions, user stores, or remote commands. */
@RunWith(AndroidJUnit4::class)
class NativeSpacingAndroidTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun standardScaleUsesUniformTouchpadSpacingAndAlignedComposerGuttersInBothThemes() {
        for (dark in listOf(false, true)) {
            val intent = MainInsetsFixtureActivity.configuredIntent(context, 1f, dark, 390)
            ActivityScenario.launch<MainInsetsFixtureActivity>(intent).use { scenario ->
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    val root = activity.findViewById<ViewGroup>(R.id.native_shell)
                    measure(root, 390, 800)
                    val density = root.resources.displayMetrics.density
                    fun px(dp: Int) = (dp * density + .5f).toInt()
                    val pageGutter = activity.resources.getDimensionPixelSize(R.dimen.ng_page_gutter)
                    val header = root.findViewById<View>(R.id.shell_header)
                    assertTrue("normal header is compact rather than two stacked control rows", header.height <= px(80))
                    assertEquals(1, root.findViewById<TextView>(R.id.computer_name).maxLines)
                    assertEquals("header outer padding follows the shared 12dp spacing", px(12),
                        (header as LinearLayout).paddingTop)
                    assertEquals("header-to-touchpad spacing follows the shared 12dp spacing", px(12),
                        header.paddingBottom)
                    assertEquals("header horizontal inset follows the shared page gutter", pageGutter,
                        header.paddingStart)
                    assertEquals("header horizontal inset follows the shared page gutter", pageGutter,
                        header.paddingEnd)
                    val touchpadContent = root.findViewById<android.widget.ScrollView>(R.id.touchpad_destination).getChildAt(0)
                    assertEquals("Touchpad retains the resolved shared page gutter", pageGutter, touchpadContent.paddingStart)
                    assertEquals("Touchpad retains the resolved shared page gutter", pageGutter, touchpadContent.paddingEnd)
                    assertEquals("Touchpad does not add extra bottom padding", 0, touchpadContent.paddingBottom)
                    val surface = bounds(root, R.id.mouse_surface_host)
                    assertEquals(pageGutter, surface.left)
                    assertEquals(root.width - pageGutter, surface.right)
                    val left = bounds(root, R.id.firstMouseButton)
                    val middle = bounds(root, R.id.secondMouseButton)
                    val right = bounds(root, R.id.thirdMouseButton)
                    assertEquals("touch surface to click row uses the shared 12dp spacing",
                        px(12), left.top - surface.bottom)
                    assertEquals("left and middle click controls share 12dp spacing", px(12),
                        middle.left - left.right)
                    assertEquals("middle and right click controls share 12dp spacing", px(12),
                        right.left - middle.right)
                    val quickBounds = bounds(root, R.id.touchpad_quick_keys)
                    assertEquals("click row to quick keys uses the shared 12dp spacing",
                        px(12), quickBounds.top - left.bottom)
                    assertEquals("the Touchpad quick-key row contains five controls", 5,
                        root.findViewById<ViewGroup>(R.id.touchpad_quick_keys).childCount)
                    val quickKeys = root.findViewById<ViewGroup>(R.id.touchpad_quick_keys)
                    for (index in 1 until quickKeys.childCount) {
                        assertEquals("quick keys share 12dp spacing", px(12),
                            quickKeys.getChildAt(index).left - quickKeys.getChildAt(index - 1).right)
                    }
                    assertEquals("quick keys to the shared composer uses the shared 12dp spacing",
                        px(12), bounds(root, R.id.keyboard_input).top - quickBounds.bottom)
                    assertEquals("composer field-to-Send gap uses the shared 12dp spacing", px(12),
                        bounds(root, R.id.keyboard_send).left - bounds(root, R.id.keyboard_input).right)
                    assertEquals("touch surface starts at the shared page gutter", pageGutter, surface.left)
                    assertEquals("click controls align to the touch surface", surface.left,
                        bounds(root, R.id.mouse_click_controls).left)
                    assertEquals("quick keys align to the touch surface", surface.left, quickBounds.left)
                    assertEquals("composer field aligns to the touch surface", surface.left,
                        bounds(root, R.id.keyboard_input).left)
                    assertEquals("right edge of controls shares the page gutter",
                        root.width - pageGutter, surface.right)
                    assertEquals("quick keys share the right content edge", surface.right, quickBounds.right)
                    assertEquals("composer send control shares the right content edge", surface.right,
                        bounds(root, R.id.keyboard_send).right)
                    val dock = root.findViewById<LinearLayout>(R.id.typing_dock)
                    assertEquals("composer uses the shared 12dp top spacing", px(12), dock.paddingTop)
                    assertEquals("composer uses the shared 12dp bottom spacing", px(12), dock.paddingBottom)
                    assertEquals("composer dock uses the shared horizontal gutter", pageGutter, dock.paddingStart)
                    assertEquals("composer dock uses the shared horizontal gutter", pageGutter, dock.paddingEnd)
                    assertTrue("five controls fit as equal-weight targets at the measured width",
                        quickKeys.childCount == 5 && (0 until quickKeys.childCount).all { index ->
                            (quickKeys.getChildAt(index).layoutParams as LinearLayout.LayoutParams).weight == 1f &&
                                quickKeys.getChildAt(index).width >= px(48)
                        })
                    assertEquals(listOf("Esc", "Tab", "Backspace", "Enter"),
                        listOf(R.id.typing_key_escape, R.id.typing_key_tab,
                            R.id.typing_key_backspace, R.id.typing_key_enter).map { id ->
                            val control = root.findViewById<android.widget.Button>(id)
                            control.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                                ?: control.text.toString()
                        })
                    assertTrue("inline typing field is multiline", root.findViewById<View>(R.id.keyboard_input).height >= px(52))
                    assertTrue("explicit Send remains a full touch target", root.findViewById<View>(R.id.keyboard_send).height >= px(48))
                    assertEquals(0f, root.findViewById<View>(R.id.firstMouseButton).elevation, 0f)
                    val bottom = root.findViewById<BottomNavigationView>(R.id.bottom_navigation)
                    assertEquals(activity.getColor(R.color.ng_surface), (bottom.background as ColorDrawable).color)
                    assertTrue(bottom.isItemActiveIndicatorEnabled)
                    assertEquals(px(58), bottom.itemActiveIndicatorWidth)
                    assertEquals(px(31), bottom.itemActiveIndicatorHeight)

                }
            }
        }
    }

    @Test fun compactLargeFontActionsMatchPreviewGeometryAndPreserveStoredValues() {
        for (dark in listOf(false, true)) {
            val intent = MainInsetsFixtureActivity.configuredIntent(context, 1.5f, dark, 320)
            ActivityScenario.launch<MainInsetsFixtureActivity>(intent).use { scenario ->
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    val root = activity.findViewById<ViewGroup>(R.id.native_shell)
                    measure(root, 320, 720)
                    val density = root.resources.displayMetrics.density
                    fun px(dp: Int) = (dp * density + .5f).toInt()
                    val gutter = activity.resources.getDimensionPixelSize(R.dimen.ng_page_gutter)
                    val gap = activity.resources.getDimensionPixelSize(R.dimen.ng_touchpad_control_gap)
                    assertEquals("Touchpad uses the normalized 12dp control gap resource", px(12), gap)
                    val surface = bounds(root, R.id.mouse_surface_host)
                    assertEquals("320dp Touchpad left edge uses the resolved page gutter", gutter, surface.left)
                    assertEquals("320dp Touchpad right edge uses the resolved page gutter", root.width - gutter, surface.right)
                    val leftClick = bounds(root, R.id.firstMouseButton)
                    val middleClick = bounds(root, R.id.secondMouseButton)
                    val rightClick = bounds(root, R.id.thirdMouseButton)
                    assertEquals("narrow Touchpad surface-to-click gap", gap, leftClick.top - surface.bottom)
                    assertEquals("narrow Touchpad click-target gap", gap, middleClick.left - leftClick.right)
                    assertEquals("narrow Touchpad click-target gap", gap, rightClick.left - middleClick.right)
                    val quickKeys = root.findViewById<ViewGroup>(R.id.touchpad_quick_keys)
                    val quick = bounds(root, R.id.touchpad_quick_keys)
                    assertEquals("narrow Touchpad click-to-quick-key gap", gap, quick.top - leftClick.bottom)
                    assertEquals("narrow Touchpad quick keys align with the page gutter", surface.left, quick.left)
                    assertEquals("narrow Touchpad quick keys align with the page gutter", surface.right, quick.right)
                    assertEquals("narrow Touchpad quick-key-to-composer gap", gap,
                        bounds(root, R.id.keyboard_input).top - quick.bottom)
                    assertEquals("narrow composer shares the left content edge", surface.left,
                        bounds(root, R.id.keyboard_input).left)
                    assertEquals("narrow composer send shares the right content edge", surface.right,
                        bounds(root, R.id.keyboard_send).right)
                    assertTrue("all five narrow quick keys remain equal-weight 48dp targets",
                        quickKeys.childCount == 5 && (0 until quickKeys.childCount).all { index ->
                            val control = quickKeys.getChildAt(index)
                            (control.layoutParams as LinearLayout.LayoutParams).weight == 1f && control.width >= px(48)
                        })
                    for (index in 1 until quickKeys.childCount) {
                        assertEquals("narrow quick-key horizontal gap", gap,
                            quickKeys.getChildAt(index).left - quickKeys.getChildAt(index - 1).right)
                    }
                    for (id in listOf(R.id.header_menu, R.id.computer_selector, R.id.header_connection_button,
                            R.id.keyboard_input, R.id.keyboard_send,
                            R.id.firstMouseButton, R.id.secondMouseButton, R.id.thirdMouseButton,
                            R.id.typing_key_escape, R.id.typing_key_tab, R.id.typing_key_backspace,
                            R.id.typing_key_enter, R.id.touchpad_clipboard_send)) {
                        val view = root.findViewById<View>(id)
                        assertTrue("target width $id", view.width >= px(48))
                        assertTrue("target height $id", view.height >= px(48))
                    }
                    val clipboard = root.findViewById<View>(R.id.touchpad_clipboard_send)
                    assertEquals("Send clipboard to computer", clipboard.contentDescription.toString())
                    assertEquals("Send clipboard to computer", clipboard.tooltipText.toString())
                    val profile = LayoutProfile("spacing-only", "Custom layout", buttons = listOf(
                        LayoutButton("one", "First action", "key Alt+F4", 5, "#E0EFE8"),
                        LayoutButton("two", "Second action", "xdotool custom  raw\ntext", 7, "#83AABBCC"),
                    ))
                    val original = profile.copy(buttons = profile.buttons.toList())
                    activity.selectedActionProfile = profile
                    activity.navigation.select(ShellNavigation.Destination.ACTIONS)
                    measure(root, 320, 720)
                    val tiles = root.findViewById<LinearLayout>(R.id.actions_tiles)
                    assertEquals(2, tiles.childCount)
                    val firstRow = tiles.getChildAt(0) as LinearLayout
                    val secondRow = tiles.getChildAt(1) as LinearLayout
                    assertEquals(px(56), firstRow.height)
                    assertEquals(px(56), secondRow.height)
                    assertEquals("preview rows are separated by 8dp", px(8), secondRow.top - firstRow.bottom)
                    assertEquals(listOf(5f, 5f), (0..1).map {
                        (firstRow.getChildAt(it).layoutParams as LinearLayout.LayoutParams).weight
                    })
                    assertEquals(listOf(7f, 3f), (0..1).map {
                        (secondRow.getChildAt(it).layoutParams as LinearLayout.LayoutParams).weight
                    })
                    val firstSlot = firstRow.getChildAt(0) as FrameLayout
                    val firstCard = firstSlot.getChildAt(0) as MaterialCardView
                    assertEquals(px(56), firstCard.height)
                    assertEquals(px(8).toFloat(), firstCard.radius, 1f)
                    val title = firstCard.getChildAt(0) as TextView
                    assertEquals("First action", title.text.toString())
                    assertEquals(1, title.maxLines)
                    assertEquals(android.text.TextUtils.TruncateAt.END, title.ellipsize)
                    assertEquals(android.view.Gravity.CENTER, title.gravity)
                    assertEquals(profile, activity.selectedActionProfile)
                    assertEquals(original, profile)
                    assertEquals("xdotool custom  raw\ntext", profile.buttons[1].command)
                    assertEquals(7, profile.buttons[1].width)
                    assertEquals("#83AABBCC", profile.buttons[1].color)
                }
            }
        }
    }

    @Test fun savedScriptRowRemainsCompactAndKeepsExplicitRunAndEditTargets() {
        for (dark in listOf(false, true)) {
            for ((fontScale, width, height) in listOf(Triple(1f, 390, 800), Triple(1.5f, 320, 720))) {
                ActivityScenario.launch<MainInsetsFixtureActivity>(
                    MainInsetsFixtureActivity.configuredIntent(context, fontScale, dark, width),
                ).use { scenario ->
                    scenario.onActivity { activity ->
                        val root = activity.findViewById<ViewGroup>(R.id.native_shell)
                        activity.navigation.select(ShellNavigation.Destination.SCRIPTS)
                        val list = root.findViewById<LinearLayout>(R.id.scripts_list)
                        val fullName = "Synthetic script name that wraps without losing its accessible text"
                        val script = SavedActionScript(41, fullName)
                        val events = mutableListOf<String>()
                        ActionsViewRenderer.renderScripts(activity, list, listOf(script), false, false, false,
                            object : ActionsViewRenderer.ScriptListener {
                                override fun onRun(view: View, selected: SavedActionScript) {
                                    assertEquals(script, selected); events += "run"
                                }
                                override fun onEdit(view: View, selected: SavedActionScript) {
                                    assertEquals(script, selected); events += "edit"
                                }
                            })
                        measure(root, width, height)
                        val density = root.resources.displayMetrics.density
                        val card = list.getChildAt(0) as ViewGroup
                        val maxCardDp = if (fontScale == 1f) 68 else 96
                        assertTrue("script row remains compact while accommodating fontScale=$fontScale " +
                            "(measured ${card.height / density}dp, maximum ${maxCardDp}dp)",
                            card.height <= maxCardDp * density)
                        val cardBounds = Rect()
                        assertTrue("script card is visible", card.getGlobalVisibleRect(cardBounds))
                        assertTrue("script card stays within the viewport", cardBounds.left >= 0 && cardBounds.right <= width * density)
                        val content = card.getChildAt(0) as LinearLayout
                        assertEquals(LinearLayout.HORIZONTAL, content.orientation)
                        val labels = content.getChildAt(0) as ViewGroup
                        val title = labels.getChildAt(0) as TextView
                        assertEquals(2, title.maxLines)
                        assertEquals(android.text.TextUtils.TruncateAt.END, title.ellipsize)
                        assertEquals(activity.getString(R.string.ng_script_named, fullName), title.contentDescription)
                        assertEquals(activity.resources.getQuantityString(R.plurals.ng_scripts_count, 1, 1),
                            root.findViewById<TextView>(R.id.scripts_count).text.toString())
                        val controls = content.getChildAt(1) as ViewGroup
                        assertEquals(2, controls.childCount)
                        assertTrue(events.isEmpty())
                        for ((index, label) in listOf("Run", "Edit").withIndex()) {
                            val button = controls.getChildAt(index) as com.google.android.material.button.MaterialButton
                            assertEquals(label, button.text.toString())
                            assertNotNull("script action keeps a visible icon and text label", button.icon)
                            assertTrue(button.width >= 48 * density - 1)
                            assertTrue(button.height >= 48 * density - 1)
                            assertTrue(button.contentDescription.isNotBlank())
                            val bounds = Rect()
                            assertTrue("script action is visible", button.getGlobalVisibleRect(bounds))
                            assertTrue("script action remains within the viewport",
                                bounds.left >= 0 && bounds.right <= width * density)
                            button.performClick()
                        }
                        assertEquals(listOf("run", "edit"), events)
                    }
                }
            }
        }
    }

    private fun measure(root: View, width: Int, height: Int) {
        val density = root.resources.displayMetrics.density
        root.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
    }

    private fun bounds(root: ViewGroup, id: Int): Rect {
        val view = root.findViewById<View>(id)
        return Rect().also { view.getDrawingRect(it); root.offsetDescendantRectToMyCoords(view, it) }
    }
}
