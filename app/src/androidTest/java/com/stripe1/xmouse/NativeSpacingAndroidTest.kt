package com.stripe1.xmouse

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.stripe1.xmouse.layout.LayoutButton
import com.stripe1.xmouse.layout.LayoutProfile
import com.stripe1.xmouse.layout.SavedActionScript
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Measures the production renderers without sessions, user stores, or remote commands. */
@RunWith(AndroidJUnit4::class)
class NativeSpacingAndroidTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun standardScaleMatchesReferenceGuttersRhythmAndThreeColumnKeysInBothThemes() {
        for (dark in listOf(false, true)) {
            val intent = MainInsetsFixtureActivity.configuredIntent(context, 1f, dark, 390)
            ActivityScenario.launch<MainInsetsFixtureActivity>(intent).use { scenario ->
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    val root = activity.findViewById<ViewGroup>(R.id.native_shell)
                    measure(root, 390, 800)
                    val density = root.resources.displayMetrics.density
                    fun px(dp: Int) = (dp * density + .5f).toInt()
                    val header = root.findViewById<View>(R.id.shell_header)
                    assertTrue("normal header is compact rather than two stacked control rows", header.height <= px(80))
                    assertEquals(LinearLayout.HORIZONTAL, root.findViewById<LinearLayout>(R.id.header_controls).orientation)
                    assertEquals(1, root.findViewById<TextView>(R.id.computer_name).maxLines)
                    val surface = bounds(root, R.id.mouse_surface_host)
                    assertEquals(px(18), surface.left)
                    assertEquals(root.width - px(18), surface.right)
                    val left = bounds(root, R.id.firstMouseButton)
                    val middle = bounds(root, R.id.secondMouseButton)
                    val right = bounds(root, R.id.thirdMouseButton)
                    assertEquals(px(9), left.top - surface.bottom)
                    assertEquals(px(6), middle.left - left.right)
                    assertEquals(px(6), right.left - middle.right)
                    assertEquals(px(14), bounds(root, R.id.touchpad_quick_keys).top - left.bottom)
                    assertEquals(px(14), bounds(root, R.id.type_on_computer).top - bounds(root, R.id.touchpad_quick_keys).bottom)
                    assertEquals(0, (root.findViewById<View>(R.id.type_on_computer) as MaterialButton).strokeWidth)
                    assertEquals(0f, root.findViewById<View>(R.id.firstMouseButton).elevation, 0f)
                    val bottom = root.findViewById<BottomNavigationView>(R.id.bottom_navigation)
                    assertEquals(activity.getColor(R.color.ng_surface), (bottom.background as ColorDrawable).color)
                    assertTrue(bottom.isItemActiveIndicatorEnabled)
                    assertEquals(px(58), bottom.itemActiveIndicatorWidth)
                    assertEquals(px(31), bottom.itemActiveIndicatorHeight)

                    activity.navigation.select(ShellNavigation.Destination.KEYS)
                    measure(root, 390, 800)
                    val content = root.findViewById<LinearLayout>(R.id.keys_catalog_content)
                    val rows = (0 until content.childCount).map { content.getChildAt(it) }.filterIsInstance<LinearLayout>()
                    assertEquals("three two-row sections match the reference Common grid", 6, rows.size)
                    assertTrue(rows.all { it.childCount == 3 })
                    assertEquals("Switch", root.findViewById<Button>(R.id.key_switch).text.toString())
                    assertEquals("Switch / Alt+Tab", root.findViewById<Button>(R.id.key_switch).contentDescription.toString())
                    val first = rows.first().getChildAt(0)
                    assertEquals(px(8), rows.first().getChildAt(1).left - first.right)
                    assertTrue(first.height >= px(58))
                    assertTrue(rows.last().getChildAt(2).width > 0)
                    assertFalse("missing final key remains an empty cell, not a wider Print button", rows.last().getChildAt(2).isClickable)
                }
            }
        }
    }

    @Test fun compactLargeFontLayoutKeepsTargetsAndPreservesStoredActionsWhileUsingReferenceTileSpacing() {
        for (dark in listOf(false, true)) {
            val intent = MainInsetsFixtureActivity.configuredIntent(context, 1.5f, dark, 320)
            ActivityScenario.launch<MainInsetsFixtureActivity>(intent).use { scenario ->
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    val root = activity.findViewById<ViewGroup>(R.id.native_shell)
                    measure(root, 320, 720)
                    val density = root.resources.displayMetrics.density
                    fun px(dp: Int) = (dp * density + .5f).toInt()
                    assertEquals(LinearLayout.VERTICAL, root.findViewById<LinearLayout>(R.id.header_controls).orientation)
                    assertEquals(px(14), bounds(root, R.id.mouse_surface_host).left)
                    for (id in listOf(R.id.header_settings, R.id.toolbar, R.id.connection_status,
                            R.id.firstMouseButton, R.id.secondMouseButton, R.id.thirdMouseButton,
                            R.id.touchpad_key_escape, R.id.touchpad_key_switch, R.id.touchpad_key_super)) {
                        val view = root.findViewById<View>(id)
                        assertTrue("target width $id", view.width >= px(48))
                        assertTrue("target height $id", view.height >= px(48))
                    }
                    activity.navigation.select(ShellNavigation.Destination.KEYS)
                    measure(root, 320, 720)
                    val keys = root.findViewById<LinearLayout>(R.id.keys_catalog_content)
                    val firstRow = (0 until keys.childCount).map { keys.getChildAt(it) }.filterIsInstance<LinearLayout>().first()
                    assertEquals("large fonts retain the readable two-column fallback", 2, firstRow.childCount)

                    val profile = LayoutProfile("spacing-only", "Custom layout", buttons = listOf(
                        LayoutButton("one", "First action", "key Alt+F4", 5, "#E0EFE8"),
                        LayoutButton("two", "Second action", "xdotool custom  raw\ntext", 7, "#83AABBCC"),
                    ))
                    activity.selectedActionProfile = profile
                    activity.navigation.select(ShellNavigation.Destination.ACTIONS)
                    measure(root, 320, 720)
                    val row = root.findViewById<LinearLayout>(R.id.actions_tiles).getChildAt(0) as LinearLayout
                    assertEquals(px(10), row.getChildAt(1).left - row.getChildAt(0).right)
                    assertEquals("wrapping titles do not leave neighboring tiles at different heights", row.getChildAt(0).height, row.getChildAt(1).height)
                    assertEquals(row.getChildAt(0).top, row.getChildAt(1).top)
                    assertTrue(row.getChildAt(0).height >= px(104))
                    val body = (row.getChildAt(0) as ViewGroup).getChildAt(0)
                    assertEquals(px(15), body.paddingLeft)
                    assertEquals(profile, activity.selectedActionProfile)
                    assertEquals("xdotool custom  raw\ntext", profile.buttons[1].command)
                    assertEquals(7, profile.buttons[1].width)
                    assertEquals("#83AABBCC", profile.buttons[1].color)
                }
            }
        }
    }

    @Test fun savedScriptRowRemainsCompactAndKeepsExplicitRunAndEditTargets() {
        ActivityScenario.launch<MainInsetsFixtureActivity>(MainInsetsFixtureActivity.configuredIntent(context, 1f, false, 390)).use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(R.id.native_shell)
                activity.navigation.select(ShellNavigation.Destination.ACTIONS)
                val list = root.findViewById<LinearLayout>(R.id.actions_script_list)
                val script = SavedActionScript(41, "Synthetic script")
                val events = mutableListOf<String>()
                ActionsViewRenderer.renderScripts(activity, list, listOf(script), false, false, false,
                    object : ActionsViewRenderer.ScriptListener {
                        override fun onRun(view: View, selected: SavedActionScript) { assertEquals(script, selected); events += "run" }
                        override fun onEdit(view: View, selected: SavedActionScript) { assertEquals(script, selected); events += "edit" }
                    })
                measure(root, 390, 800)
                val density = root.resources.displayMetrics.density
                val card = list.getChildAt(0) as ViewGroup
                assertTrue("normal script row uses one 48dp control row plus 8dp insets", card.height <= 66 * density)
                val content = card.getChildAt(0) as LinearLayout
                assertEquals(LinearLayout.HORIZONTAL, content.orientation)
                val controls = content.getChildAt(1) as ViewGroup
                assertEquals(2, controls.childCount)
                assertTrue(events.isEmpty())
                for (i in 0 until controls.childCount) {
                    val button = controls.getChildAt(i) as Button
                    assertTrue(button.width >= 48 * density - 1)
                    assertTrue(button.height >= 48 * density - 1)
                    assertTrue(button.contentDescription.isNotBlank())
                    button.performClick()
                }
                assertEquals(listOf("run", "edit"), events)
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
