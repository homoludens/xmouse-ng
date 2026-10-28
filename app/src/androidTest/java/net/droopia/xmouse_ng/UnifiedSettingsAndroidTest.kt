package net.droopia.xmouse_ng

import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.preference.PreferenceManager
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.view.isNotEmpty
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UnifiedSettingsAndroidTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val preferences get() = PreferenceManager.getDefaultSharedPreferences(context)

    @Test fun everySettingsRouteUsesSharedHeaderAndRetainsExplicitInputBindings() {
        val keys = listOf(
            InputPreferences.POINTER_SPEED, InputPreferences.INVERT_SCROLL,
            InputPreferences.KEYBOARD_AUTO_CLEAR, InputPreferences.KEYBOARD_SEND_ENTER_AFTER_TEXT,
            "delay_list", "mdelay_list", "autologin_checkbox", "setting_shell",
            "setting_dotool_initial", "setting_xdotool_initial", "pref_usekeyauth", AppearancePreferences.KEY,
        )
        val before = keys.associateWith { preferences.all[it] }
        val initialNightMode = AppCompatDelegate.getDefaultNightMode()
        try {
            preferences.edit(commit = true) {
                putString(InputPreferences.POINTER_SPEED, "1.37f")
                .putBoolean(InputPreferences.INVERT_SCROLL, true)
                .putBoolean(InputPreferences.KEYBOARD_AUTO_CLEAR, false)
                .putBoolean(InputPreferences.KEYBOARD_SEND_ENTER_AFTER_TEXT, false)
                .putString("delay_list", "275").putString("mdelay_list", "123")
                .putBoolean("autologin_checkbox", true).putString("setting_shell", "/bin/zsh")
                .putString("setting_dotool_initial", "ydotool")
                .putString("setting_xdotool_initial", "custom initialization")
                .putBoolean("pref_usekeyauth", false)
                .putString(AppearancePreferences.KEY, "system")
            }
            val renderedValues = keys.associateWith { preferences.all[it] }

            val pointerIntent = Intent(context, NativeSettingsActivity::class.java)
                .putExtra(NativeSettingsActivity.EXTRA_SECTION, NativeSettingsActivity.SECTION_POINTER)
            ActivityScenario.launch<NativeSettingsActivity>(pointerIntent).use { scenario ->
                scenario.onActivity { activity ->
                    val fragment = fragment(activity)
                    assertEquals("Touchpad settings", activity.findViewById<TextView>(R.id.settings_title).text.toString())
                    assertNotNull(pref(fragment, InputPreferences.POINTER_SPEED))
                    assertNotNull(pref(fragment, InputPreferences.INVERT_SCROLL))
                    assertNotNull(pref(fragment, "delay_list"))
                    assertNull(fragment.findSettingPreference(InputPreferences.KEYBOARD_AUTO_CLEAR))
                    assertFalse((pref(fragment, InputPreferences.INVERT_SCROLL) as SwitchPreferenceCompat).isChecked)
                    val speed = pref(fragment, InputPreferences.POINTER_SPEED) as ListPreference
                    assertEquals("1.37f", speed.value)
                    assertEquals("Saved value: 1.37f", speed.summary.toString())
                    assertPreferenceRowStyle(activity, fragment, "Mouse sensitivity")
                    assertEquals(renderedValues, keys.associateWith { preferences.all[it] })

                    assertTrue(rowFor(fragment, "Mouse sensitivity").performClick())
                }
                selectDialogOption("High, 2X")
                scenario.onActivity { activity ->
                    val fragment = fragment(activity)
                    val speed = pref(fragment, InputPreferences.POINTER_SPEED) as ListPreference
                    assertEquals("2.0f", speed.value)
                    assertEquals("2.0f", preferences.all[InputPreferences.POINTER_SPEED])
                    val natural = pref(fragment, InputPreferences.INVERT_SCROLL)
                    assertTrue(rowFor(fragment, "Natural scrolling").performClick())
                    assertEquals(false, preferences.all[InputPreferences.INVERT_SCROLL])
                }
            }

            val beforeAll = keys.associateWith { preferences.all[it] }
            ActivityScenario.launch<NativeSettingsActivity>(Intent(context, NativeSettingsActivity::class.java)).use { scenario ->
                scenario.onActivity { activity ->
                    val fragment = fragment(activity)
                    assertEquals("Settings", activity.findViewById<TextView>(R.id.settings_title).text.toString())
                    for (key in listOf("settings_link_touchpad", "settings_link_keyboard", "settings_link_authentication",
                        "settings_link_appearance", "settings_link_about")) assertNotNull(pref(fragment, key))
                    assertNull(fragment.findSettingPreference(InputPreferences.POINTER_SPEED))
                    assertNull(fragment.keyImportForTesting)
                    assertNull(fragment.credentialUiForTesting)
                    assertEquals(beforeAll, keys.associateWith { preferences.all[it] })
                    assertTrue(rowFor(fragment, "Touchpad settings").performClick())
                }
                val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                assertTrue("Settings index link opens its section",
                    device.wait(Until.hasObject(By.text("Mouse sensitivity")), 5_000))
                device.pressBack()
                device.waitForIdle()
                scenario.onActivity { activity ->
                    assertEquals("Settings", activity.findViewById<TextView>(R.id.settings_title).text.toString())
                }
            }

            val keyboardIntent = Intent(context, NativeSettingsActivity::class.java)
                .putExtra(NativeSettingsActivity.EXTRA_SECTION, NativeSettingsActivity.SECTION_KEYBOARD)
            ActivityScenario.launch<NativeSettingsActivity>(keyboardIntent).use { scenario ->
                scenario.onActivity { activity ->
                    val fragment = fragment(activity)
                    assertEquals("Keyboard settings", activity.findViewById<TextView>(R.id.settings_title).text.toString())
                    val autoClear = pref(fragment, InputPreferences.KEYBOARD_AUTO_CLEAR) as SwitchPreferenceCompat
                    val sendEnter = pref(fragment, InputPreferences.KEYBOARD_SEND_ENTER_AFTER_TEXT) as SwitchPreferenceCompat
                    assertFalse(autoClear.isChecked); assertFalse(sendEnter.isChecked)
                    assertNotNull(fragment.findSettingPreference("ng_settings_keyboard_send_note"))
                    assertNull(fragment.findSettingPreference(InputPreferences.POINTER_SPEED))
                    assertPreferenceRowStyle(activity, fragment, "Auto clear keystrokes")
                    assertTrue(rowFor(fragment, "Auto clear keystrokes").performClick())
                    assertTrue(rowFor(fragment, "Send Enter after text").performClick())
                    assertTrue(preferences.getBoolean(InputPreferences.KEYBOARD_AUTO_CLEAR, false))
                    assertTrue(preferences.getBoolean(InputPreferences.KEYBOARD_SEND_ENTER_AFTER_TEXT, false))
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    val fragment = fragment(activity)
                    assertTrue((pref(fragment, InputPreferences.KEYBOARD_AUTO_CLEAR) as SwitchPreferenceCompat).isChecked)
                    assertTrue((pref(fragment, InputPreferences.KEYBOARD_SEND_ENTER_AFTER_TEXT) as SwitchPreferenceCompat).isChecked)
                    val back = activity.findViewById<View>(R.id.settings_back)
                    assertEquals("Back", back.contentDescription)
                    assertEquals("Back", back.tooltipText)
                    back.performClick()
                }
            }

            val directAuthIntent = Intent(context, NativeSettingsActivity::class.java)
                .putExtra(NativeSettingsActivity.EXTRA_SECTION, NativeSettingsActivity.SECTION_AUTHENTICATION)
            ActivityScenario.launch<NativeSettingsActivity>(directAuthIntent).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals("Authentication & SSH", activity.findViewById<TextView>(R.id.settings_title).text.toString())
                    assertNotNull(pref(fragment(activity), "pref_usekeyauth"))
                }
            }

            ActivityScenario.launch<SettingsActivity>(Intent(context, SettingsActivity::class.java)).use { scenario ->
                scenario.onActivity { activity ->
                    val fragment = activity.settingsFragmentForTesting()
                    assertEquals("Authentication & SSH", activity.findViewById<TextView>(R.id.settings_title).text.toString())
                    assertNotNull(pref(fragment, "pref_usekeyauth"))
                    assertNotNull(pref(fragment, "pref_addkeybutton"))
                    assertFalse(pref(fragment, "pref_key_passphrase").isPersistent)
                    assertNotNull(pref(fragment, "autologin_checkbox"))
                    assertNotNull(pref(fragment, "setting_shell"))
                    val useKey = pref(fragment, "pref_usekeyauth") as androidx.preference.CheckBoxPreference
                    val privateKey = pref(fragment, "pref_addkeybutton")
                    val phrase = pref(fragment, "pref_key_passphrase")
                    assertFalse(useKey.isChecked); assertFalse(privateKey.isEnabled); assertFalse(phrase.isEnabled)
                    assertPreferenceRowStyle(activity, fragment, "Use an SSH key")
                    assertTrue(rowFor(fragment, "Use an SSH key").performClick())
                    assertTrue(useKey.isChecked); assertTrue(privateKey.isEnabled); assertTrue(phrase.isEnabled)
                    assertTrue(rowFor(fragment, "Use an SSH key").performClick())
                    assertFalse(useKey.isChecked); assertFalse(privateKey.isEnabled); assertFalse(phrase.isEnabled)
                    assertNull(fragment.findSettingPreference(InputPreferences.POINTER_SPEED))
                }
            }

            val appearanceIntent = Intent(context, NativeSettingsActivity::class.java)
                .putExtra(NativeSettingsActivity.EXTRA_SECTION, NativeSettingsActivity.SECTION_APPEARANCE)
            ActivityScenario.launch<NativeSettingsActivity>(appearanceIntent).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals("Appearance", activity.findViewById<TextView>(R.id.settings_title).text.toString())
                    val preference = pref(fragment(activity), AppearancePreferences.KEY) as ListPreference
                    assertEquals("system", preference.value)
                    assertNull(fragment(activity).findSettingPreference("pref_usekeyauth"))
                }
            }

            val aboutIntent = Intent(context, NativeSettingsActivity::class.java)
                .putExtra(NativeSettingsActivity.EXTRA_SECTION, NativeSettingsActivity.SECTION_ABOUT)
            ActivityScenario.launch<NativeSettingsActivity>(aboutIntent).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals("About XMouse-NG", activity.findViewById<TextView>(R.id.settings_title).text.toString())
                    val fragment = fragment(activity)
                    assertPreferenceRowStyle(activity, fragment, context.getString(R.string.app_name))
                    assertEquals(context.packageName, pref(fragment, "ng_about_package").summary.toString())
                    assertTrue(pref(fragment, "ng_about_package").summary.toString().contains(context.packageName))
                    assertFalse(pref(fragment, "ng_about_package").isSelectable)
                }
            }
        } finally {
            preferences.edit(commit = true) {
                for ((key, value) in before) when (value) {
                    null -> remove(key)
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                    else -> remove(key)
                }
            }
            AppCompatDelegate.setDefaultNightMode(initialNightMode)
        }
    }

    @Test fun appearanceSelectionSurvivesActivityRecreationAndAboutStaysInformational() {
        val original = preferences.all[AppearancePreferences.KEY]
        val originalNightMode = AppCompatDelegate.getDefaultNightMode()
        try {
            preferences.edit(commit = true) { putString(AppearancePreferences.KEY, "light") }
            val intent = Intent(context, NativeSettingsActivity::class.java)
                .putExtra(NativeSettingsActivity.EXTRA_SECTION, NativeSettingsActivity.SECTION_APPEARANCE)
            ActivityScenario.launch<NativeSettingsActivity>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    val appearance = pref(fragment(activity), AppearancePreferences.KEY) as ListPreference
                    assertEquals("light", appearance.value)
                    assertPreferenceRowStyle(activity, fragment(activity), "Theme")
                    assertTrue(rowFor(fragment(activity), "Theme").performClick())
                }
                selectDialogOption("Dark")
                scenario.onActivity { activity ->
                    val appearance = pref(fragment(activity), AppearancePreferences.KEY) as ListPreference
                    assertEquals("dark", preferences.getString(AppearancePreferences.KEY, null))
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    val appearance = pref(fragment(activity), AppearancePreferences.KEY) as ListPreference
                    assertEquals("Appearance", activity.findViewById<TextView>(R.id.settings_title).text.toString())
                    assertEquals("dark", appearance.value)
                    assertEquals("Dark", appearance.entry.toString())
                }
            }

            val aboutIntent = Intent(context, NativeSettingsActivity::class.java)
                .putExtra(NativeSettingsActivity.EXTRA_SECTION, NativeSettingsActivity.SECTION_ABOUT)
            ActivityScenario.launch<NativeSettingsActivity>(aboutIntent).use { scenario ->
                scenario.onActivity { activity ->
                    val about = fragment(activity)
                    assertEquals("About XMouse-NG", activity.findViewById<TextView>(R.id.settings_title).text.toString())
                    assertEquals(context.packageName, pref(about, "ng_about_package").summary.toString())
                    assertFalse(pref(about, "ng_about_package").isSelectable)
                    assertTrue(pref(about, "ng_about_package").isEnabled)
                    assertNotNull(pref(about, "ng_about_version"))
                }
            }
        } finally {
            preferences.edit(commit = true) {
                when (original) {
                    null -> remove(AppearancePreferences.KEY)
                    is String -> putString(AppearancePreferences.KEY, original)
                    is Boolean -> putBoolean(AppearancePreferences.KEY, original)
                    is Int -> putInt(AppearancePreferences.KEY, original)
                    is Long -> putLong(AppearancePreferences.KEY, original)
                    is Float -> putFloat(AppearancePreferences.KEY, original)
                    else -> remove(AppearancePreferences.KEY)
                }
            }
            AppCompatDelegate.setDefaultNightMode(originalNightMode)
        }
    }

    @Test fun malformedLegacyInputTypesRemainUntouchedWhenRealPreferenceScreensOpen() {
        val keys = listOf(InputPreferences.POINTER_SPEED, InputPreferences.INVERT_SCROLL,
            InputPreferences.KEYBOARD_AUTO_CLEAR, InputPreferences.KEYBOARD_SEND_ENTER_AFTER_TEXT)
        val before = keys.associateWith { preferences.all[it] }
        try {
            preferences.edit(commit = true) {
                putInt(InputPreferences.POINTER_SPEED, 17)
                putString(InputPreferences.INVERT_SCROLL, "true")
                putString(InputPreferences.KEYBOARD_AUTO_CLEAR, "false")
                putFloat(InputPreferences.KEYBOARD_SEND_ENTER_AFTER_TEXT, 1f)
            }
            val malformed = keys.associateWith { preferences.all[it] }
            val pointerIntent = Intent(context, NativeSettingsActivity::class.java)
                .putExtra(NativeSettingsActivity.EXTRA_SECTION, NativeSettingsActivity.SECTION_POINTER)
            ActivityScenario.launch<NativeSettingsActivity>(pointerIntent).use { scenario ->
                scenario.onActivity { activity ->
                    val fragment = fragment(activity)
                    assertEquals("1.0f", (pref(fragment, InputPreferences.POINTER_SPEED) as ListPreference).value)
                    assertTrue((pref(fragment, InputPreferences.INVERT_SCROLL) as SwitchPreferenceCompat).isChecked)
                    assertEquals(malformed, keys.associateWith { preferences.all[it] })
                }
            }
            val keyboardIntent = Intent(context, NativeSettingsActivity::class.java)
                .putExtra(NativeSettingsActivity.EXTRA_SECTION, NativeSettingsActivity.SECTION_KEYBOARD)
            ActivityScenario.launch<NativeSettingsActivity>(keyboardIntent).use { scenario ->
                scenario.onActivity { activity ->
                    val fragment = fragment(activity)
                    assertTrue((pref(fragment, InputPreferences.KEYBOARD_AUTO_CLEAR) as SwitchPreferenceCompat).isChecked)
                    assertFalse((pref(fragment, InputPreferences.KEYBOARD_SEND_ENTER_AFTER_TEXT) as SwitchPreferenceCompat).isChecked)
                    assertEquals(malformed, keys.associateWith { preferences.all[it] })
                }
            }
        } finally {
            preferences.edit(commit = true) {
                for ((key, value) in before) when (value) {
                    null -> remove(key)
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                    else -> remove(key)
                }
            }
        }
    }

    @Test fun settingsHeaderRowsInsetsAndLargeFontsFitNarrowLightAndDarkWindows() {
        val routes = listOf(
            Triple(NativeSettingsActivity.SECTION_ALL, "Settings", "Touchpad settings"),
            Triple(NativeSettingsActivity.SECTION_POINTER, "Touchpad settings", "Mouse sensitivity"),
            Triple(NativeSettingsActivity.SECTION_KEYBOARD, "Keyboard settings", "Auto clear keystrokes"),
            Triple(NativeSettingsActivity.SECTION_AUTHENTICATION, "Authentication & SSH", "Use an SSH key"),
            Triple(NativeSettingsActivity.SECTION_APPEARANCE, "Appearance", "Theme"),
            Triple(NativeSettingsActivity.SECTION_ABOUT, "About XMouse-NG", context.getString(R.string.app_name)),
        )
        for (dark in listOf(false, true)) for ((route, title, styledRow) in routes) {
            ActivityScenario.launch<SettingsInsetsFixtureActivity>(
                SettingsInsetsFixtureActivity.configuredIntent(context, 1.5f, dark, 320, route),
            ).use { scenario ->
                scenario.onActivity { activity ->
                    val root = activity.findViewById<ViewGroup>(R.id.settings_root)
                    val back = activity.findViewById<View>(R.id.settings_back)
                    val density = activity.resources.displayMetrics.density
                    assertEquals(1.5f, activity.resources.configuration.fontScale, 0f)
                    assertEquals(320, activity.resources.configuration.screenWidthDp)
                    assertEquals(title, activity.findViewById<TextView>(R.id.settings_title).text.toString())
                    assertTrue(back.width >= 48 * density - 1)
                    assertTrue(back.height >= 48 * density - 1)
                    assertTrue(back.isFocusable)
                    assertTrue(back.isClickable)
                    assertEquals("Back", back.contentDescription)
                    val expectedHeading = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 20f,
                        activity.resources.displayMetrics)
                    assertEquals(expectedHeading, activity.findViewById<TextView>(R.id.settings_title).textSize, 1f)

                    val systemBars = (24 * density).toInt()
                    val ime = (150 * density).toInt()
                    ViewCompat.dispatchApplyWindowInsets(root, WindowInsetsCompat.Builder()
                        .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, systemBars, 0, systemBars))
                        .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime)).build())
                    assertEquals(systemBars, root.paddingTop)
                    assertEquals(ime, root.paddingBottom)

                    val width = (320 * density).toInt()
                    val height = (640 * density).toInt()
                    root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, width, height)
                    val recycler = activity.findViewById<RecyclerView>(androidx.preference.R.id.recycler_view)
                    assertTrue(recycler.width <= width)
                    assertTrue(recycler.isNotEmpty())
                    for (index in 0 until recycler.childCount) {
                        val row = recycler.getChildAt(index)
                        assertTrue("Preference row must remain comfortably tappable", row.height >= 48 * density - 1)
                        assertTrue(row.width <= width)
                    }
                    assertPreferenceRowStyle(activity, fragment(activity), styledRow)
                    val expected = activity.getColor(R.color.ng_background)
                    val actualBackground = root.background ?: activity.window.decorView.background
                    assertEquals(expected, (actualBackground as ColorDrawable).color)
                }
                if (route == NativeSettingsActivity.SECTION_ALL) {
                    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                    device.pressDPadDown()
                    device.pressDPadDown()
                    scenario.onActivity { activity ->
                        val recycler = activity.findViewById<RecyclerView>(androidx.preference.R.id.recycler_view)
                        var focused = activity.currentFocus
                        while (focused != null && focused !== recycler) focused = focused.parent as? View
                        assertSame("D-pad moves focus from the header into the preference list", recycler, focused)
                    }
                    assertNotNull("Back remains exposed to accessibility services", device.findObject(By.desc("Back")))
                }
            }
        }
    }

    @Test fun capturesRepresentativeSettingsScreensForRuntimeEvidence() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("captureUnifiedSettings") == "true")
        assumeTrue("MediaStore screenshot export requires Android 10+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)

        val directory = File(context.getExternalFilesDir(null), "unified-settings-runtime-20261027")
        assertTrue("Create owned-emulator evidence directory", directory.mkdirs() || directory.isDirectory)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val appearanceBefore = preferences.all[AppearancePreferences.KEY]
        val scenes = listOf(
            Triple(NativeSettingsActivity.SECTION_ALL, false, "settings-index-light.png"),
            Triple(NativeSettingsActivity.SECTION_POINTER, false, "touchpad-light.png"),
            Triple(NativeSettingsActivity.SECTION_KEYBOARD, false, "keyboard-light.png"),
            Triple(NativeSettingsActivity.SECTION_AUTHENTICATION, false, "authentication-light.png"),
            Triple(NativeSettingsActivity.SECTION_AUTHENTICATION, true, "authentication-dark.png"),
            Triple(NativeSettingsActivity.SECTION_APPEARANCE, false, "appearance-light.png"),
            Triple(NativeSettingsActivity.SECTION_ABOUT, false, "about-light.png"),
        )
        for ((section, dark, filename) in scenes) {
            ActivityScenario.launch<SettingsInsetsFixtureActivity>(
                SettingsInsetsFixtureActivity.configuredIntent(context, 1f, dark, 411, section),
            ).use { scenario ->
                scenario.onActivity { activity ->
                    val expected = when (section) {
                        NativeSettingsActivity.SECTION_POINTER -> "Touchpad settings"
                        NativeSettingsActivity.SECTION_KEYBOARD -> "Keyboard settings"
                        NativeSettingsActivity.SECTION_AUTHENTICATION -> "Authentication & SSH"
                        NativeSettingsActivity.SECTION_APPEARANCE -> "Appearance"
                        NativeSettingsActivity.SECTION_ABOUT -> "About XMouse-NG"
                        else -> "Settings"
                    }
                    assertEquals(expected, activity.findViewById<TextView>(R.id.settings_title).text.toString())
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                device.waitForIdle()
                Thread.sleep(500)
                captureEvidenceScreenshot(context, device, directory, filename)

                if (section == NativeSettingsActivity.SECTION_APPEARANCE) {
                    scenario.onActivity { activity ->
                        assertTrue(rowFor(fragment(activity), "Theme").performClick())
                    }
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    device.waitForIdle()
                    assertNotNull("Theme chooser displays its available choices",
                        device.wait(Until.findObject(By.text("Dark")), 5_000))
                    captureEvidenceScreenshot(context, device, directory, "appearance-theme-dialog-light.png")
                    device.pressBack()
                    device.waitForIdle()
                    assertEquals("Opening and dismissing the theme chooser does not write a preference",
                        appearanceBefore, preferences.all[AppearancePreferences.KEY])
                }
            }
        }
    }

    private fun captureEvidenceScreenshot(context: Context, device: UiDevice, directory: File, filename: String) {
        val screenshot = File(directory, filename)
        assertTrue("Capture $filename on the owned emulator", device.takeScreenshot(screenshot))
        assertTrue("Screenshot is non-empty: $filename", screenshot.length() > 0)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "unified-$filename")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/XMouseNGUnifiedSettings")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values,
        )) { "MediaStore insert failed for $filename" }
        try {
            context.contentResolver.openOutputStream(uri, "w")!!.use { output ->
                screenshot.inputStream().use { it.copyTo(output) }
            }
            val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            assertEquals("Publish $filename to emulator Pictures", 1,
                context.contentResolver.update(uri, published, null, null))
        } catch (failure: Throwable) {
            context.contentResolver.delete(uri, null, null)
            throw failure
        }
    }

    @Test fun actualPointerViewStillUsesSavedScalarAndOriginalNaturalScrollingMapping() {
        ActivityScenario.launch<MainInsetsFixtureActivity>(Intent(context, MainInsetsFixtureActivity::class.java)).use { scenario ->
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

    private fun fragment(activity: SharedSettingsActivity) = requireNotNull(
        activity.supportFragmentManager.findFragmentById(R.id.settings_preference_container) as? UnifiedSettingsFragment,
    )

    private fun pref(fragment: UnifiedSettingsFragment, key: String): Preference =
        requireNotNull(fragment.findSettingPreference(key)) { "Missing preference $key" }

    private fun rowFor(fragment: UnifiedSettingsFragment, title: String): View {
        val recycler = requireNotNull(fragment.view).findViewById<RecyclerView>(androidx.preference.R.id.recycler_view)
        return (0 until recycler.childCount).map(recycler::getChildAt).firstOrNull {
            it.findViewById<TextView>(android.R.id.title)?.text?.toString() == title
        } ?: error("Visible preference row not found: $title")
    }

    private fun selectDialogOption(label: String) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val option = device.wait(Until.findObject(By.text(label)), 5_000)
        assertNotNull("Preference dialog option should appear: $label", option)
        requireNotNull(option).click()
        device.waitForIdle()
    }

    private fun assertPreferenceRowStyle(activity: SharedSettingsActivity,
                                         fragment: UnifiedSettingsFragment, title: String) {
        val row = rowFor(fragment, title)
        val text = requireNotNull(row.findViewById<TextView>(android.R.id.title))
        val summary = requireNotNull(row.findViewById<TextView>(android.R.id.summary))
        assertTrue("Preference row target must be at least 48dp", row.height >= 48 * activity.resources.displayMetrics.density - 1)
        val metrics = activity.resources.displayMetrics
        assertEquals(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, metrics), text.textSize, 1f)
        assertEquals(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, metrics), summary.textSize, 1f)
    }
}
