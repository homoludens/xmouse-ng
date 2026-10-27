package com.stripe1.xmouse

import androidx.appcompat.app.AppCompatDelegate
import org.junit.Assert.assertEquals
import org.junit.Test

class AppearancePreferencesTest {
    @Test fun missingAndUnknownModesFollowTheSystemWhileAllExplicitModesMapToAppCompat() {
        assertEquals(AppearancePreferences.Mode.SYSTEM, AppearancePreferences.parse(null))
        assertEquals(AppearancePreferences.Mode.SYSTEM, AppearancePreferences.parse("unknown"))
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            AppearancePreferences.parse("system").appCompatNightMode())
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO,
            AppearancePreferences.parse("light").appCompatNightMode())
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES,
            AppearancePreferences.parse("dark").appCompatNightMode())
        assertEquals("appearance_mode", AppearancePreferences.KEY)
    }
}
