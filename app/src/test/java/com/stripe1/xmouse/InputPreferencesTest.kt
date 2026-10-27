package com.stripe1.xmouse

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class InputPreferencesTest {
    @Test fun defaultsMatchTheOriginalRuntimeWithoutCreatingOrChangingStoredValues() {
        val saved = mutableMapOf<String, Any>()
        val input = InputPreferences.read(saved)
        assertEquals(1f, input.pointerSpeed, 0f)
        assertFalse(input.invertScroll)
        assertTrue(input.naturalScrolling())
        assertTrue(input.keyboardAutoClear)
        assertFalse(input.keyboardSendEnterAfterText)
        assertEquals(3, input.pointerChoiceIndex())
        assertTrue(saved.isEmpty())
    }

    @Test fun everyLegacyListScalarRestoresExactlyAndNewWritesKeepItsOriginalStringFormat() {
        val values = listOf(".25f", ".3333f", ".5f", "1.0f", "1.5f", "2.0f", "3.0f")
        values.forEachIndexed { index, value ->
            val saved = mapOf(InputPreferences.POINTER_SPEED to InputPreferences.pointerChoiceForWrite(value))
            val input = InputPreferences.read(saved)
            assertEquals(value.toFloat(), input.pointerSpeed, 0f)
            assertEquals(index, input.pointerChoiceIndex())
            assertEquals(value, saved[InputPreferences.POINTER_SPEED])
        }
        // Resource choices and the validated write boundary must never drift apart.
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(root(), "app/src/main/res/values/strings_activity_settings.xml"))
        val arrays = document.getElementsByTagName("string-array")
        val array = (0 until arrays.length).map { arrays.item(it) as org.w3c.dom.Element }
            .single { it.getAttribute("name") == "pref_sensitivity_list_values" }.getElementsByTagName("item")
        assertEquals(values, (0 until array.length).map { array.item(it).textContent })
    }

    @Test fun legacyNonListScalarsAreNeverClampedQuantizedOrRewrittenByReading() {
        for (value in listOf("1.25f", "0.33330000", "4.5f", "0.125", "1e-2", "1.0F")) {
            val saved = mutableMapOf<String, Any>(InputPreferences.POINTER_SPEED to value)
            assertEquals(value.toFloat(), InputPreferences.read(saved).pointerSpeed, 0f)
            assertEquals(value, saved[InputPreferences.POINTER_SPEED])
        }
        assertEquals(-1, InputPreferences.read(mapOf(InputPreferences.POINTER_SPEED to "1.25f")).pointerChoiceIndex())
    }

    @Test fun invalidOrWrongTypedStoredValuesFallBackIndependentlyWithoutOverwritingData() {
        for (value in listOf<Any>("", "wrong", "NaN", "Infinity", "-Infinity", "1e99", "0.0f", "-1f", 1f, false)) {
            val saved = mutableMapOf<String, Any>(InputPreferences.POINTER_SPEED to value,
                InputPreferences.INVERT_SCROLL to true, InputPreferences.KEYBOARD_AUTO_CLEAR to false)
            val input = InputPreferences.read(saved)
            assertEquals(1f, input.pointerSpeed, 0f)
            assertFalse(input.naturalScrolling()); assertFalse(input.keyboardAutoClear)
            assertEquals(value, saved[InputPreferences.POINTER_SPEED])
        }
        val input = InputPreferences.read(mapOf(InputPreferences.POINTER_SPEED to "2.0f",
            InputPreferences.INVERT_SCROLL to "true", InputPreferences.KEYBOARD_AUTO_CLEAR to 0))
        assertEquals(2f, input.pointerSpeed, 0f)
        assertTrue(input.naturalScrolling()); assertTrue(input.keyboardAutoClear)
    }

    @Test fun newPointerWritesRejectUnconfiguredOutOfRangeAndNoncanonicalValues() {
        for (value in listOf("0f", "-1f", "0.1f", "4f", "1.25f", "NaN", "Infinity", "1.0", "")) {
            assertThrows(IllegalArgumentException::class.java) { InputPreferences.pointerChoiceForWrite(value) }
        }
    }

    @Test fun naturalScrollIsTheInverseOfTheOriginalInvertKeyAndAutoclearReloadsIndependently() {
        for (natural in listOf(false, true)) for (autoClear in listOf(false, true)) {
            val saved = mutableMapOf<String, Any>("setting_keyboard_batch" to false, "keyboard_layout_locked" to true)
            saved[InputPreferences.POINTER_SPEED] = InputPreferences.pointerChoiceForWrite(".3333f")
            saved[InputPreferences.INVERT_SCROLL] = InputPreferences.invertedForNaturalScrolling(natural)
            saved[InputPreferences.KEYBOARD_AUTO_CLEAR] = autoClear
            val restarted = InputPreferences.read(saved.toMap())
            assertEquals(.3333f, restarted.pointerSpeed, 0f)
            assertEquals(natural, restarted.naturalScrolling())
            assertEquals(!natural, restarted.invertScroll)
            assertEquals(autoClear, restarted.keyboardAutoClear)
            assertFalse(restarted.keyboardSendEnterAfterText)
            assertEquals(false, saved["setting_keyboard_batch"])
            assertEquals(true, saved["keyboard_layout_locked"])
        }
    }

    @Test fun sendEnterPreferenceIsPersistedSeparatelyAndDefaultsOffForLegacyStores() {
        for (sendEnter in listOf(false, true)) {
            val saved = mutableMapOf<String, Any>(InputPreferences.KEYBOARD_SEND_ENTER_AFTER_TEXT to sendEnter)
            assertEquals(sendEnter, InputPreferences.read(saved).keyboardSendEnterAfterText)
            assertEquals(sendEnter, InputPreferences.read(saved.toMap()).keyboardSendEnterAfterText)
            assertEquals(sendEnter, saved[InputPreferences.KEYBOARD_SEND_ENTER_AFTER_TEXT])
        }
        assertFalse(InputPreferences.read(mapOf(InputPreferences.KEYBOARD_SEND_ENTER_AFTER_TEXT to "true"))
            .keyboardSendEnterAfterText)
    }

    @Test fun primaryAndAdvancedRoutesRetainAuthButExposeNoObsoleteInstantOrHiddenGridControl() {
        fun source(path: String) = File(root(), "app/src/main/$path").readText()
        val main = source("java/com/stripe1/xmouse/MainActivity.java")
        assertTrue(main.contains("openExternalScreen(new Intent(this, NativeSettingsActivity.class))"))
        assertTrue(main.indexOf("shellNavigation.open(ShellNavigation.Overlay.EXTERNAL)", main.indexOf("private void openExternalScreen")) <
            main.indexOf("startActivity(intent)", main.indexOf("private void openExternalScreen")))
        val resume = main.substring(main.indexOf("protected void onResume()"), main.indexOf("private void requestAutomaticForegroundSession"))
        assertTrue(resume.indexOf("getPreferences();") < resume.indexOf("shellNavigation.close(ShellNavigation.Overlay.EXTERNAL)"))
        assertTrue(resume.contains("if (!returningFromExternal) scheduleAutomaticForegroundSessionAfterVisibleFrame"))
        assertTrue(main.contains("InputPreferences.read(prefs.getAll())"))
        assertTrue(main.contains("setting_sensitivity = input.pointerSpeed;"))
        assertTrue(main.contains("setting_invert_scroll = input.invertScroll;"))
        assertTrue(main.contains("setting_keyboard_autoclear = input.keyboardAutoClear;"))
        assertTrue(main.contains("setting_keyboard_send_enter_after_text = input.keyboardSendEnterAfterText;"))
        assertTrue(main.contains("inputTokenFor(role)"))
        assertTrue(main.contains("InlineTextDispatch.send(inputTool, text, setting_keyboard_send_enter_after_text)"))
        assertFalse(main.contains("setting_keyboard_batch"))
        assertFalse(source("res/xml/pref_keyboard.xml").contains("android:key=\"setting_keyboard_batch\""))
        assertFalse(source("res/xml/pref_keyboard.xml").contains("android:key=\"keyboard_layout_locked\""))
        assertTrue(source("java/com/stripe1/xmouse/NativeSettingsActivity.kt").contains("SettingsActivity::class.java"))
        for (key in listOf("pref_usekeyauth", "pref_addkeybutton", "pref_key_passphrase"))
            assertTrue(source("res/xml/pref_auth.xml").contains("android:key=\"$key\""))
    }

    private fun root(): File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main/res/values/colors.xml").isFile }
}
