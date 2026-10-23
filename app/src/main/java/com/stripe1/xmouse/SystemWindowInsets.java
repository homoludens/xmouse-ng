package com.stripe1.xmouse;

import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/** Applies one stable, physical-edge inset baseline to a View-based activity root. */
public final class SystemWindowInsets {
    private SystemWindowInsets() { }

    public static void applyTo(Window window, View root) {
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        final InsetEdges base = new InsetEdges(
                root.getPaddingLeft(), root.getPaddingTop(), root.getPaddingRight(), root.getPaddingBottom());
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets displayCutout = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout());
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            InsetEdges padding = InsetPaddingCalculator.calculate(
                    base,
                    edges(systemBars),
                    edges(displayCutout),
                    edges(ime));
            view.setPadding(padding.getLeft(), padding.getTop(), padding.getRight(), padding.getBottom());
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private static InsetEdges edges(Insets insets) {
        return new InsetEdges(insets.left, insets.top, insets.right, insets.bottom);
    }
}
