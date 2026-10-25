package com.stripe1.xmouse

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated production rendering; never initializes MainActivity, SSH, credentials, or user data. */
@RunWith(AndroidJUnit4::class)
class NativeShellAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun narrowLargeFontHeaderAndLabeledBottomRoutesRemainReachableInBothThemes() {
        for (dark in listOf(false, true)) {
            val intent = Intent(ApplicationProvider.getApplicationContext(), MainInsetsFixtureActivity::class.java)
                .putExtra("fixture.fontScale", 1.5f).putExtra("fixture.dark", dark)
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
                }
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
}
