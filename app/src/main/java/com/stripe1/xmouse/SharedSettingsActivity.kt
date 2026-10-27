package com.stripe1.xmouse

import android.content.Intent
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import com.stripe1.xmouse.secrets.CredentialUi
import com.stripe1.xmouse.secrets.PrivateKeySettingsController

/** The single View/XML host used by every settings entry point. */
abstract class SharedSettingsActivity : AppCompatActivity() {
    internal var credentialUi: CredentialUi? = null
    internal var keyImport: PrivateKeySettingsController? = null
    private var pendingSectionTitle: Int? = null

    protected open fun defaultSection(): String = NativeSettingsActivity.SECTION_ALL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        pendingSectionTitle?.let { findViewById<TextView>(R.id.settings_title).setText(it) }
        SystemWindowInsets.applyTo(window, findViewById(R.id.settings_root))
        findViewById<View>(R.id.settings_back).setOnClickListener { finish() }

        if (savedInstanceState == null) {
            val section = intent.getStringExtra(NativeSettingsActivity.EXTRA_SECTION) ?: defaultSection()
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_preference_container, UnifiedSettingsFragment.newInstance(section))
                .commit()
        }
    }

    internal fun showSectionTitle(title: Int) {
        pendingSectionTitle = title
        findViewById<TextView>(R.id.settings_title)?.setText(title)
    }

    internal fun defaultPreferences() = PreferenceManager.getDefaultSharedPreferences(this)

    @Deprecated("Legacy document-picker result route")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val handled = keyImport?.onActivityResult(requestCode, resultCode, data) == true ||
            credentialUi?.onActivityResult(requestCode, resultCode, data) == true
        if (!handled) super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

/** Legacy launch target kept as an authentication-only alias into the shared settings UI. */
class SettingsActivity : SharedSettingsActivity() {
    override fun defaultSection(): String = NativeSettingsActivity.SECTION_AUTHENTICATION

    fun findPreference(key: CharSequence): Preference = requireNotNull(
        (supportFragmentManager.findFragmentById(R.id.settings_preference_container) as? UnifiedSettingsFragment)
            ?.findSettingPreference(key.toString()),
    ) { "No settings preference with key $key" }

    fun settingsFragmentForTesting(): UnifiedSettingsFragment = requireNotNull(
        supportFragmentManager.findFragmentById(R.id.settings_preference_container) as? UnifiedSettingsFragment,
    )

    @Deprecated("Legacy document-picker result route")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
    }
}
