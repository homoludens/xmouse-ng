package com.stripe1.xmouse

import android.os.Bundle
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

/** Test-only host for measuring the real main-screen XML without starting session or data code. */
class MainInsetsFixtureActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        JoystickSurfaceLayout.addTo(
            findViewById<LinearLayout>(R.id.js),
            MyJSView(this, null),
        )
        SystemWindowInsets.applyToAndConsume(window, findViewById(R.id.main_safe_area))
    }
}
