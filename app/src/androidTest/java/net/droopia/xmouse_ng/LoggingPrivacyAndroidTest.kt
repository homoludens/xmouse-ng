package net.droopia.xmouse_ng

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.ParcelFileDescriptor
import android.preference.PreferenceManager
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.droopia.xmouse_ng.secrets.AndroidCredentials
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Exercises app-owned logging on Android with synthetic markers and no connected transport. */
@RunWith(AndroidJUnit4::class)
class LoggingPrivacyAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val target get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun actualKeyboardInvalidCommandAndLayoutFailureDoNotLogRawValues() {
        val preferences = PreferenceManager.getDefaultSharedPreferences(target)
        val oldAutoConnect = preferences.boolSnapshot("autologin_checkbox")
        val oldBatchMode = preferences.boolSnapshot("setting_keyboard_batch")
        val oldAutoClear = preferences.boolSnapshot("keyboard_autoclear")
        val layoutFile = File(target.filesDir, "keyLayoutFile.csv")
        val oldLayout = layoutFile.takeIf(File::isFile)?.readBytes()
        val typedSentinel = "XMouseTestOnlyTypedContent_${UUID.randomUUID()}"
        val errorSentinel = "XMouseTestOnlyLayoutError_${UUID.randomUUID()}"
        var appPid = -1

        try {
            check(preferences.edit()
                .putBoolean("autologin_checkbox", false)
                .putBoolean("setting_keyboard_batch", false)
                .putBoolean("keyboard_autoclear", true)
                .commit())
            shell("logcat -c")

            ActivityScenario.launch<MainActivity>(Intent(target, MainActivity::class.java)).use { scenario ->
                scenario.onActivity { activity ->
                    assertFalse("The test must not establish an SSH session",
                        net.droopia.xmouse_ng.session.SessionForegroundService.hasRetainedSession(activity))

                    val adapter = CustomKeyboardButtonAdapter(activity, emptyList())
                    adapter.execCommand("type", activity.doTool, activity.commandTransport)

                    activity.findViewById<EditText>(R.id.keyboard_input).setText(typedSentinel)
                    val malformedLayout = "title<xmousesep>synthetic<xmousesep>$errorSentinel<xmousesep>#ffffff"
                    assertNull(activity.loadKeyboardLayout(activity, malformedLayout))

                    assertFalse("Input must not connect or execute remotely",
                        net.droopia.xmouse_ng.session.SessionForegroundService.hasRetainedSession(activity))
                    appPid = (target.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                        .runningAppProcesses
                        ?.firstOrNull { it.processName == target.packageName }
                        ?.pid ?: -1
                    assertTrue("Could not identify the app process for log capture", appPid > 0)
                }
                instrumentation.waitForIdleSync()
            }

            val logs = shell("logcat -d --pid=$appPid -v brief")
            assertFalse("Raw keyboard text reached app logcat", logs.contains(typedSentinel))
            assertFalse("Malformed layout data reached app logcat", logs.contains(errorSentinel))
            assertTrue("Invalid-command path did not emit its fixed event", logs.contains("invalid_custom_command"))
            assertTrue("Layout failure path did not emit its fixed event", logs.contains("layout_parse_failed"))
        } finally {
            try {
                awaitCredentialWorker()
            } finally {
                restoreBoolean(preferences, "autologin_checkbox", oldAutoConnect)
                restoreBoolean(preferences, "setting_keyboard_batch", oldBatchMode)
                restoreBoolean(preferences, "keyboard_autoclear", oldAutoClear)
                if (oldLayout == null) layoutFile.delete() else layoutFile.writeBytes(oldLayout)
            }
        }
    }

    private fun shell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

    private fun restoreBoolean(preferences: SharedPreferences, key: String, snapshot: BooleanSnapshot) {
        val editor = preferences.edit()
        if (snapshot.present) editor.putBoolean(key, snapshot.value) else editor.remove(key)
        assertTrue("Could not restore test preference $key", editor.commit())
    }

    private fun awaitCredentialWorker() {
        val finished = java.util.concurrent.CountDownLatch(1)
        AndroidCredentials.get(target).execute { finished.countDown() }
        assertTrue("The application credential worker did not become idle", finished.await(10, java.util.concurrent.TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
    }

    private fun SharedPreferences.boolSnapshot(key: String) =
        BooleanSnapshot(contains(key), getBoolean(key, false))

    private data class BooleanSnapshot(val present: Boolean, val value: Boolean)
}
