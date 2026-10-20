package com.stripe1.xmouse;

import android.view.View;

import androidx.activity.OnBackPressedCallback;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

/** Handles only an open drawer; a closed drawer leaves the Activity's Back fallback intact. */
final class DrawerBackCallback extends OnBackPressedCallback {
    private final DrawerLayout drawer;

    DrawerBackCallback(DrawerLayout drawer) {
        super(drawer.isDrawerOpen(GravityCompat.START));
        this.drawer = drawer;
        drawer.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerOpened(View drawerView) {
                updateEnabledState();
            }

            @Override
            public void onDrawerClosed(View drawerView) {
                updateEnabledState();
            }
        });
        // DrawerLayout can restore an open drawer before its first layout without an open event.
        drawer.addOnLayoutChangeListener((view, left, top, right, bottom,
                                         oldLeft, oldTop, oldRight, oldBottom) -> updateEnabledState());
    }

    private void updateEnabledState() {
        setEnabled(drawer.isDrawerOpen(GravityCompat.START));
    }

    @Override
    public void handleOnBackPressed() {
        drawer.closeDrawer(GravityCompat.START);
    }
}
