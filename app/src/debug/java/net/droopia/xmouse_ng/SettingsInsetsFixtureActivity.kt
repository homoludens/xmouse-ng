package net.droopia.xmouse_ng

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle

/** Configurable test host for measuring the real production settings fragment. */
class SettingsInsetsFixtureActivity : SharedSettingsActivity() {
    companion object {
        private val nextConfiguration = java.util.concurrent.atomic.AtomicReference<Configuration?>()

        fun configuredIntent(
            context: Context,
            fontScale: Float,
            dark: Boolean,
            widthDp: Int,
            section: String = NativeSettingsActivity.SECTION_ALL,
        ): Intent {
            nextConfiguration.set(Configuration(context.resources.configuration).apply {
                this.fontScale = fontScale
                screenWidthDp = widthDp
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            })
            return Intent(context, SettingsInsetsFixtureActivity::class.java)
                .putExtra("fixture.dark", dark)
                .putExtra(NativeSettingsActivity.EXTRA_SECTION, section)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        val configuration = nextConfiguration.getAndSet(null)
        super.attachBaseContext(newBase)
        if (configuration != null) applyOverrideConfiguration(Configuration().apply {
            fontScale = configuration.fontScale
            screenWidthDp = configuration.screenWidthDp
            uiMode = configuration.uiMode
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        delegate.localNightMode = if (intent.getBooleanExtra("fixture.dark", false))
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        super.onCreate(savedInstanceState)
    }
}
