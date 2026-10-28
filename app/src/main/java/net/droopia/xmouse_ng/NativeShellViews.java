package net.droopia.xmouse_ng;

import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.shape.ShapeAppearanceModel;

/** Shared production/isolated-fixture rendering. Input is retired by ShellNavigation before render. */
public final class NativeShellViews {
    private NativeShellViews() { }

    public static void bind(View root, ShellNavigation navigation) {
        android.content.res.Configuration configuration = root.getResources().getConfiguration();
        ((TextView) root.findViewById(R.id.computer_name)).setMaxLines(1);
        View drawerContent = root.getRootView().findViewById(R.id.shell_navigation_drawer);
        DrawerLayout drawer = root.getRootView().findViewById(R.id.main_safe_area);
        if (drawerContent != null && drawer != null) {
            ViewGroup.LayoutParams params = drawerContent.getLayoutParams();
            params.width = dp(root, Math.min(318, Math.max(240, configuration.screenWidthDp - 42)));
            drawerContent.setLayoutParams(params);
            View headerMenu = root.findViewById(R.id.header_menu);
            headerMenu.setOnClickListener(v -> {
                if (drawer.isDrawerOpen(GravityCompat.START)) {
                    drawer.closeDrawer(GravityCompat.START);
                } else {
                    if (navigation.topOverlay() != ShellNavigation.Overlay.MENU)
                        navigation.open(ShellNavigation.Overlay.MENU);
                    drawer.openDrawer(GravityCompat.START);
                }
            });
            root.getRootView().findViewById(R.id.drawer_close).setOnClickListener(
                    v -> drawer.closeDrawer(GravityCompat.START));
            drawer.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
                @Override public void onDrawerOpened(View drawerView) {
                    if (navigation.topOverlay() != ShellNavigation.Overlay.MENU)
                        navigation.open(ShellNavigation.Overlay.MENU);
                    View first = root.getRootView().findViewById(R.id.drawer_computers);
                    if (first != null && !root.isInTouchMode()) first.requestFocus();
                }

                @Override public void onDrawerClosed(View drawerView) {
                    if (navigation.topOverlay() == ShellNavigation.Overlay.MENU)
                        navigation.close(ShellNavigation.Overlay.MENU);
                    if (navigation.topOverlay() == ShellNavigation.Overlay.COMPUTERS) {
                        View close = root.findViewById(R.id.overlay_close);
                        if (!root.isInTouchMode()) close.post(() -> {
                            if (navigation.topOverlay() == ShellNavigation.Overlay.COMPUTERS && close.isShown())
                                close.requestFocus();
                        });
                    } else if (navigation.topOverlay() == null) {
                        if (!root.isInTouchMode()) headerMenu.requestFocus();
                    } else if (navigation.topOverlay() == ShellNavigation.Overlay.ACTION_EDIT) {
                        if (!root.isInTouchMode()) headerMenu.requestFocus();
                    }
                }
            });
        }
        View shellOverlay = root.findViewById(R.id.shell_overlay);
        View computersSheet = root.findViewById(R.id.computers_sheet);
        shellOverlay.setOnClickListener(v -> {
            if (navigation.topOverlay() == ShellNavigation.Overlay.COMPUTERS)
                navigation.close(ShellNavigation.Overlay.COMPUTERS);
        });
        computersSheet.setOnClickListener(v -> { });
        BottomNavigationView bottom = root.findViewById(R.id.bottom_navigation);
        // A flat navigation surface must not acquire Material's dark elevation overlay.
        bottom.setBackground(new ColorDrawable(root.getContext().getColor(R.color.ng_surface)));
        bottom.setStateListAnimator(null);
        bottom.setElevation(0f);
        bottom.setItemIconSize(dp(root, 20));
        bottom.setItemActiveIndicatorEnabled(true);
        bottom.setItemActiveIndicatorWidth(dp(root, 58));
        bottom.setItemActiveIndicatorHeight(dp(root, 31));
        bottom.setItemActiveIndicatorColor(ColorStateList.valueOf(root.getContext().getColor(R.color.ng_accent_soft)));
        bottom.setItemActiveIndicatorShapeAppearance(ShapeAppearanceModel.builder().setAllCornerSizes(dp(root, 18)).build());
        bottom.setItemPaddingTop(dp(root, 7));
        bottom.setItemPaddingBottom(dp(root, 7));
        bottom.setOnItemSelectedListener(item -> {
            navigation.select(destination(item.getItemId()));
            return navigation.topOverlay() == null;
        });
        root.findViewById(R.id.computer_selector).setOnClickListener(v -> navigation.open(ShellNavigation.Overlay.COMPUTERS));
        root.findViewById(R.id.overlay_close).setOnClickListener(v -> navigation.closeTop());
        render(root, navigation);
    }

    public static ShellNavigation.Destination destination(int id) {
        if (id == R.id.nav_actions) return ShellNavigation.Destination.ACTIONS;
        if (id == R.id.nav_scripts) return ShellNavigation.Destination.SCRIPTS;
        return ShellNavigation.Destination.TOUCHPAD;
    }

    public static void render(View root, ShellNavigation state) {
        render(root, state, false);
    }

    /** ACTION_EDIT is an inline mode unless an owned modal dialog is currently showing. */
    public static void render(View root, ShellNavigation state, boolean modalActionEdit) {
        boolean overlay = state.topOverlay() != null;
        boolean panel = state.topOverlay() == ShellNavigation.Overlay.COMPUTERS;
        boolean blockingHeader = overlay && (state.topOverlay() != ShellNavigation.Overlay.ACTION_EDIT || modalActionEdit);
        View shellOverlay = root.findViewById(R.id.shell_overlay);
        boolean wasPanel = shellOverlay.getVisibility() == View.VISIBLE;
        if (!panel && wasPanel) root.findViewById(R.id.overlay_close).clearFocus();
        View computersSheet = root.findViewById(R.id.computers_sheet);
        ViewGroup.LayoutParams sheetParams = computersSheet.getLayoutParams();
        int availableHeight = root.getHeight() > 0 ? root.getHeight() : root.getResources().getDisplayMetrics().heightPixels;
        sheetParams.height = Math.min(availableHeight, Math.max(dp(root, 320), Math.round(availableHeight * .82f)));
        computersSheet.setLayoutParams(sheetParams);
        visible(root, R.id.destination_container, !panel);
        visible(root, R.id.typing_dock, !panel && state.destination() != ShellNavigation.Destination.SCRIPTS);
        visible(root, R.id.shell_overlay, panel);
        visible(root, R.id.computers_content, state.topOverlay() == ShellNavigation.Overlay.COMPUTERS);
        ((TextView) root.findViewById(R.id.overlay_title)).setText(R.string.ng_computers);
        visible(root, R.id.touchpad_destination, state.destination() == ShellNavigation.Destination.TOUCHPAD);
        visible(root, R.id.actions_destination, state.destination() == ShellNavigation.Destination.ACTIONS);
        visible(root, R.id.scripts_destination, state.destination() == ShellNavigation.Destination.SCRIPTS);
        View header = root.findViewById(R.id.shell_header);
        header.setImportantForAccessibility(blockingHeader ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        root.findViewById(R.id.computer_selector).setEnabled(!overlay);
        root.findViewById(R.id.header_menu).setEnabled(!blockingHeader);
        View connectionButton = root.findViewById(R.id.header_connection_button);
        connectionButton.setClickable(!overlay);
        connectionButton.setFocusable(!overlay);
        BottomNavigationView bottom = root.findViewById(R.id.bottom_navigation);
        bottom.setImportantForAccessibility(overlay ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        int selected = state.destination() == ShellNavigation.Destination.ACTIONS ? R.id.nav_actions
                : state.destination() == ShellNavigation.Destination.SCRIPTS ? R.id.nav_scripts : R.id.nav_touchpad;
        bottom.getMenu().findItem(selected).setChecked(true);
        for (int i = 0; i < bottom.getMenu().size(); i++) bottom.getMenu().getItem(i).setEnabled(!overlay);
        if (panel && !wasPanel) {
            View close = root.findViewById(R.id.overlay_close);
            if (!root.isInTouchMode()) close.post(() -> {
                if (root.findViewById(R.id.shell_overlay).getVisibility() == View.VISIBLE && close.isShown())
                    close.requestFocus();
            });
        }
        else if (!panel && wasPanel) {
            View selector = root.findViewById(R.id.computer_selector);
            if (state.topOverlay() == null && !root.isInTouchMode()) selector.requestFocus();
        }
    }

    private static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }

    private static void visible(View root, int id, boolean visible) {
        root.findViewById(id).setVisibility(visible ? View.VISIBLE : View.GONE);
    }
}
