package net.droopia.xmouse_ng

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Displays the bundled policy as selectable native text; it has no browser or network path. */
class PrivacyPolicyActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_privacy_policy)
        SystemWindowInsets.applyTo(window, findViewById(R.id.privacy_policy_root))
        findViewById<View>(R.id.privacy_policy_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.privacy_policy_text).text =
            resources.openRawResource(R.raw.privacy_policy).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
