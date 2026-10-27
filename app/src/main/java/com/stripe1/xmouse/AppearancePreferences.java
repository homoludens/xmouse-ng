package com.stripe1.xmouse;

import android.content.SharedPreferences;
import androidx.appcompat.app.AppCompatDelegate;

/** Persisted app appearance. Unknown or missing values preserve the system-following default. */
public final class AppearancePreferences {
    public static final String KEY = "appearance_mode";

    public enum Mode {
        SYSTEM("system", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
        LIGHT("light", AppCompatDelegate.MODE_NIGHT_NO),
        DARK("dark", AppCompatDelegate.MODE_NIGHT_YES);

        private final String preferenceValue;
        @AppCompatDelegate.NightMode private final int nightMode;

        Mode(String preferenceValue, int nightMode) {
            this.preferenceValue = preferenceValue;
            this.nightMode = nightMode;
        }

        public String preferenceValue() { return preferenceValue; }
        @AppCompatDelegate.NightMode public int appCompatNightMode() { return nightMode; }
    }

    private AppearancePreferences() { }

    public static Mode parse(String value) {
        for (Mode mode : Mode.values()) if (mode.preferenceValue.equals(value)) return mode;
        return Mode.SYSTEM;
    }

    public static Mode read(SharedPreferences preferences) {
        String saved;
        try { saved = preferences.getString(KEY, null); }
        catch (ClassCastException ignored) { saved = null; }
        return parse(saved);
    }

    public static void save(SharedPreferences preferences, Mode mode) {
        preferences.edit().putString(KEY, mode.preferenceValue()).apply();
    }

    public static void apply(Mode mode) {
        AppCompatDelegate.setDefaultNightMode(mode.appCompatNightMode());
    }
}
