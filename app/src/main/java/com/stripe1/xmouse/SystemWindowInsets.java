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
        applyTo(window, root, false);
    }

    /** Applies insets to a safe-area container and consumes only those handled here. */
    public static void applyToAndConsume(Window window, View root) {
        applyTo(window, root, true);
    }

    private static void applyTo(Window window, View root, boolean consumeInsets) {
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
            return consumeInsets ? withoutHandledInsets(windowInsets) : windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private static WindowInsetsCompat withoutHandledInsets(WindowInsetsCompat windowInsets) {
        return new WindowInsetsCompat.Builder(windowInsets)
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.NONE)
                .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.NONE)
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE)
                .build();
    }

    private static InsetEdges edges(Insets insets) {
        return new InsetEdges(insets.left, insets.top, insets.right, insets.bottom);
    }
}
