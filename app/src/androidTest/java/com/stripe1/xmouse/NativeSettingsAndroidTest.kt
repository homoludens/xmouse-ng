package com.stripe1.xmouse

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Production binding, unique fixture preferences only. Never starts Main/settings credential code. */
@RunWith(AndroidJUnit4::class)
class NativeSettingsAndroidTest {
    @Test fun savedBindingsChoicesBackAndAdvancedRemainReachableWithInsetsAndLargeFontsInBothThemes() {
        for (dark in listOf(false, true)) {
            val intent = MainInsetsFixtureActivity.configuredIntent(ApplicationProvider.getApplicationContext(), 1.5f, dark)
            ActivityScenario.launch<MainInsetsFixtureActivity>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    val preferenceName = "native-settings-fixture-${UUID.randomUUID()}"
                    val preferences = activity.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
                    try {
                        assertTrue(preferences.edit().putString(InputPreferences.POINTER_SPEED, ".3333f")
                            .putBoolean(InputPreferences.INVERT_SCROLL, true)
                            .putBoolean(InputPreferences.KEYBOARD_AUTO_CLEAR, false)
                            .putBoolean("setting_keyboard_batch", false).commit())
                        activity.setContentView(R.layout.activity_native_settings)
                        val root = activity.findViewById<ViewGroup>(R.id.settings_root)
                        SystemWindowInsets.applyToAndConsume(activity.window, root)
                        var back = 0; var advanced = 0
                        val binding = NativeSettingsBinding(root, preferences, { back++ }, { advanced++ })
                        val before = preferences.all.toMap()
                        binding.reload()
                        assertEquals("Rendering must not rewrite values", before, preferences.all)
                        val natural = root.findViewById<SwitchMaterial>(R.id.settings_natural_scroll)
                        val autoClear = root.findViewById<SwitchMaterial>(R.id.settings_auto_clear)
                        assertFalse(natural.isChecked); assertFalse(autoClear.isChecked)
                        assertEquals("Natural scrolling", natural.text.toString())
                        assertEquals("Clear text after Send", autoClear.text.toString())
                        val pointer = root.findViewById<Button>(R.id.settings_pointer_speed)
                        assertEquals("Pointer speed\nLower, .33X", pointer.text.toString())
                        natural.performClick(); autoClear.performClick()
                        assertFalse(preferences.getBoolean(InputPreferences.INVERT_SCROLL, true))
                        assertTrue(preferences.getBoolean(InputPreferences.KEYBOARD_AUTO_CLEAR, false))
                        assertTrue(root.findViewById<TextView>(R.id.settings_scroll_description).text.startsWith("On: swipe up to scroll down"))
                        pointer.performClick()
                        val dialog = NativeSettingsBinding::class.java.getDeclaredField("dialog").apply { isAccessible = true }
                            .get(binding) as AlertDialog
                        assertEquals(1, dialog.listView.checkedItemPosition)
                        val row = dialog.listView.adapter.getView(6, null, dialog.listView)
                        dialog.listView.performItemClick(row, 6, 6)
                        assertEquals("3.0f", preferences.getString(InputPreferences.POINTER_SPEED, null))
                        binding.reload()
                        assertEquals("Pointer speed\nSuper, 3X", pointer.text.toString())
                        assertFalse(preferences.getBoolean("setting_keyboard_batch", true))

                        val density = activity.resources.displayMetrics.density
                        val bars = (24 * density).toInt(); val ime = (160 * density).toInt()
                        ViewCompat.dispatchApplyWindowInsets(root, WindowInsetsCompat.Builder()
                            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, bars, 0, bars))
                            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime)).build())
                        assertEquals(bars, root.paddingTop); assertEquals(ime, root.paddingBottom)
                        val width = (320 * density).toInt(); val height = (480 * density).toInt()
                        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                        root.layout(0, 0, width, height)
                        for (id in listOf(R.id.settings_back, R.id.settings_pointer_speed, R.id.settings_natural_scroll,
                            R.id.settings_auto_clear, R.id.settings_advanced)) {
                            val control = root.findViewById<TextView>(id)
                            assertTrue(control.width >= 48 * density - 1)
                            assertTrue(control.height >= 48 * density - 1)
                            assertTrue(control.text.isNotBlank())
                            assertTrue(control.width <= width)
                            control.layout?.let { layout ->
                                for (line in 0 until layout.lineCount) assertEquals(0, layout.getEllipsisCount(line))
                            }
                        }
                        val scroll = root.findViewById<ScrollView>(R.id.settings_scroll)
                        assertTrue(scroll.getChildAt(0).height > scroll.height)
                        scroll.isSmoothScrollingEnabled = false
                        scroll.fullScroll(View.FOCUS_DOWN); assertTrue(scroll.scrollY > 0)
                        root.findViewById<View>(R.id.settings_advanced).performClick()
                        root.findViewById<View>(R.id.settings_back).performClick()
                        assertEquals(1, advanced); assertEquals(1, back)
                        assertEquals(activity.getColor(R.color.ng_background),
                            (root.background as android.graphics.drawable.ColorDrawable).color)
                        binding.dismissDialog()
                    } finally {
                        // Only this test's unique preference file is removed; no default preferences/vault/DB access.
                        activity.deleteSharedPreferences(preferenceName)
                    }
                }
            }
        }
    }

    @Test fun actualPointerViewUsesRestoredScalarAndOriginalNaturalScrollingMapping() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainInsetsFixtureActivity::class.java)
        ActivityScenario.launch<MainInsetsFixtureActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val oldSpeed = MainActivity.setting_sensitivity
                val oldInvert = MainActivity.setting_invert_scroll
                val calls = mutableListOf<String>()
                val tool = object : IDoTool {
                    override fun mouseMoveRelative(dx: Float, dy: Float) { calls += "move:$dx,$dy" }
                    override fun mouseWheelUp() { calls += "up" }
                    override fun mouseWheelDown() { calls += "down" }
                    override fun mouseClick(btn: Int) { fail("Unexpected click") }
                    override fun mouseDown(btn: Int) { fail("Unexpected hold") }
                    override fun mouseUp(btn: Int) { fail("Unexpected release") }
                    override fun key(key: String) { fail("Unexpected key") }
                    override fun type(text: String) { fail("Unexpected typing") }
                }
                try {
                    for (natural in listOf(false, true)) {
                        val restored = InputPreferences.read(mapOf(InputPreferences.POINTER_SPEED to "1.5f",
                            InputPreferences.INVERT_SCROLL to InputPreferences.invertedForNaturalScrolling(natural)))
                        MainActivity.setting_sensitivity = restored.pointerSpeed
                        MainActivity.setting_invert_scroll = restored.invertScroll
                        val pointer = MyMouseView(activity, null)
                        MyMouseView::class.java.getDeclaredField("gestureTool").apply { isAccessible = true }.set(pointer, tool)
                        calls.clear()
                        pointer.OnXMouseMoved(4f, -8f, false)
                        pointer.OnXMouseMoved(0f, -20f, true)
                        pointer.OnXMouseMoved(0f, 20f, true)
                        assertEquals(listOf("move:6.0,-12.0", if (natural) "down" else "up",
                            if (natural) "up" else "down"), calls)
                    }
                } finally {
                    MainActivity.setting_sensitivity = oldSpeed
                    MainActivity.setting_invert_scroll = oldInvert
                }
            }
        }
    }
}
