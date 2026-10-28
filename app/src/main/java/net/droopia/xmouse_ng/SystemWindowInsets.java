package net.droopia.xmouse_ng;

import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;

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
        final DrawerMargins drawerMargins = root instanceof DrawerLayout
                ? new DrawerMargins((DrawerLayout) root) : null;
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
            if (drawerMargins != null) drawerMargins.apply(padding);
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

    /** DrawerLayout positions content using child margins, not its own padding. */
    private static final class DrawerMargins {
        private final DrawerLayout drawer;
        private final InsetEdges[] baseMargins;

        DrawerMargins(DrawerLayout drawer) {
            this.drawer = drawer;
            baseMargins = new InsetEdges[drawer.getChildCount()];
            for (int index = 0; index < drawer.getChildCount(); index++) {
                ViewGroup.LayoutParams params = drawer.getChildAt(index).getLayoutParams();
                if (params instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
                    baseMargins[index] = new InsetEdges(margins.leftMargin, margins.topMargin,
                            margins.rightMargin, margins.bottomMargin);
                } else {
                    baseMargins[index] = new InsetEdges(0, 0, 0, 0);
                }
            }
        }

        void apply(InsetEdges insets) {
            for (int index = 0; index < drawer.getChildCount() && index < baseMargins.length; index++) {
                View child = drawer.getChildAt(index);
                ViewGroup.LayoutParams params = child.getLayoutParams();
                if (!(params instanceof ViewGroup.MarginLayoutParams)) continue;
                ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
                InsetEdges base = baseMargins[index];
                margins.leftMargin = base.getLeft() + insets.getLeft();
                margins.topMargin = base.getTop() + insets.getTop();
                margins.rightMargin = base.getRight() + insets.getRight();
                margins.bottomMargin = base.getBottom() + insets.getBottom();
                child.setLayoutParams(margins);
            }
        }
    }
}
