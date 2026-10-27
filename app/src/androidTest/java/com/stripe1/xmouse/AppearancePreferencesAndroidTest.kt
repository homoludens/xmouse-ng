package com.stripe1.xmouse

import android.content.Context
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stripe1.xmouse.ui.XMouseNgTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class AppearancePreferencesAndroidTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun appearanceChoicePersistsAndMapsToTheNativeAppCompatModes() {
        val preferences = context.getSharedPreferences("appearance-prefs-integration-test", Context.MODE_PRIVATE)
        val originalNightMode = AppCompatDelegate.getDefaultNightMode()
        try {
            preferences.edit().clear().apply()
            assertEquals(AppearancePreferences.Mode.SYSTEM, AppearancePreferences.parse(null))
            for (mode in AppearancePreferences.Mode.values()) {
                AppearancePreferences.save(preferences, mode)
                assertEquals(mode, AppearancePreferences.read(preferences))
                assertEquals(mode.preferenceValue(), preferences.getString(AppearancePreferences.KEY, null))
                instrumentation.runOnMainSync { AppearancePreferences.apply(mode) }
                assertEquals(mode.appCompatNightMode(), AppCompatDelegate.getDefaultNightMode())
            }
            assertEquals(AppearancePreferences.Mode.SYSTEM, AppearancePreferences.parse("not-a-mode"))
        } finally {
            preferences.edit().clear().apply()
            instrumentation.runOnMainSync { AppCompatDelegate.setDefaultNightMode(originalNightMode) }
        }
    }

    @Test fun composeEditorThemeUsesTheSameLightAndDarkSemanticColorResources() {
        for (dark in listOf(false, true)) {
            val intent = MainInsetsFixtureActivity.configuredIntent(context, 1f, dark)
            ActivityScenario.launch<MainInsetsFixtureActivity>(intent).use { scenario ->
                val backgroundColor = AtomicInteger(Int.MIN_VALUE)
                scenario.onActivity { activity ->
                    activity.setContent {
                        XMouseNgTheme {
                            val background = MaterialTheme.colorScheme.background
                            SideEffect { backgroundColor.set(background.toArgb()) }
                            Box(Modifier.size(1.dp).background(background))
                        }
                    }
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertEquals(activity.getColor(R.color.ng_background), backgroundColor.get())
                }
            }
        }
    }
}
