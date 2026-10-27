package com.stripe1.xmouse

import android.content.Intent
import android.os.Bundle
import android.preference.PreferenceManager
import androidx.appcompat.app.AppCompatActivity

/** Full-height native Settings; Advanced retains the existing global authentication owner. */
class NativeSettingsActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_SECTION = "native-settings-section"
        const val SECTION_ALL = "all"
        const val SECTION_POINTER = "pointer"
        const val SECTION_KEYBOARD = "keyboard"
    }

    private lateinit var binding: NativeSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_native_settings)
        SystemWindowInsets.applyToAndConsume(window, findViewById(R.id.settings_root))
        binding = NativeSettingsBinding(findViewById(R.id.settings_root),
            PreferenceManager.getDefaultSharedPreferences(this), { finish() },
            { startActivity(Intent(this, SettingsActivity::class.java)) },
            intent.getStringExtra(EXTRA_SECTION) ?: SECTION_ALL)
    }

    override fun onResume() { super.onResume(); binding.reload() }
    override fun onStop() { binding.dismissDialog(); super.onStop() }
}
