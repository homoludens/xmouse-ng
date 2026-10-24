package com.stripe1.xmouse;

import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

/** Keeps the joystick surface in the vertical space left below its fixed control row. */
public final class JoystickSurfaceLayout {
    private JoystickSurfaceLayout() {}

    public static void addTo(LinearLayout controls, View surface) {
        controls.addView(surface, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }
}
