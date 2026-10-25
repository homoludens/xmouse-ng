package com.stripe1.xmouse;

import android.view.View;
import com.google.android.material.bottomnavigation.BottomNavigationView;

/** Shared production/isolated-fixture rendering. Input is retired by ShellNavigation before render. */
public final class NativeShellViews {
    private NativeShellViews() { }

    public static void bind(View root, ShellNavigation navigation) {
        BottomNavigationView bottom = root.findViewById(R.id.bottom_navigation);
        bottom.setOnItemSelectedListener(item -> {
            navigation.select(destination(item.getItemId()));
            return navigation.topOverlay() == null;
        });
        root.findViewById(R.id.computer_selector).setOnClickListener(v -> navigation.open(ShellNavigation.Overlay.COMPUTERS));
        root.findViewById(R.id.type_on_computer).setOnClickListener(v -> navigation.open(ShellNavigation.Overlay.TYPING));
        root.findViewById(R.id.overlay_close).setOnClickListener(v -> navigation.closeTop());
        root.findViewById(R.id.jsToggle).setOnClickListener(v -> navigation.setJoystick(true));
        root.findViewById(R.id.js_jsToggle).setOnClickListener(v -> navigation.setJoystick(false));
        render(root, navigation);
    }

    public static ShellNavigation.Destination destination(int id) {
        if (id == R.id.nav_keys) return ShellNavigation.Destination.KEYS;
        if (id == R.id.nav_actions) return ShellNavigation.Destination.ACTIONS;
        return ShellNavigation.Destination.TOUCHPAD;
    }

    public static void render(View root, ShellNavigation state) {
        boolean overlay = state.topOverlay() != null;
        boolean panel = state.topOverlay() == ShellNavigation.Overlay.COMPUTERS || state.topOverlay() == ShellNavigation.Overlay.TYPING;
        visible(root, R.id.destination_container, !panel);
        visible(root, R.id.shell_overlay, panel);
        visible(root, R.id.computers_content, state.topOverlay() == ShellNavigation.Overlay.COMPUTERS);
        visible(root, R.id.typing_content, state.topOverlay() == ShellNavigation.Overlay.TYPING);
        ((android.widget.TextView) root.findViewById(R.id.overlay_title)).setText(
                state.topOverlay() == ShellNavigation.Overlay.TYPING ? R.string.ng_typing : R.string.ng_computers);
        ((android.widget.TextView) root.findViewById(R.id.touchpad_title)).setText(
                state.joystick() ? R.string.mouse_control_joystick_short : R.string.ng_touchpad);
        visible(root, R.id.touchpad_destination, state.destination() == ShellNavigation.Destination.TOUCHPAD);
        root.findViewById(R.id.mouse).setVisibility(state.joystick() ? View.INVISIBLE : View.VISIBLE);
        root.findViewById(R.id.js).setVisibility(state.joystick() ? View.VISIBLE : View.INVISIBLE);
        visible(root, R.id.jsToggle, !state.joystick());
        visible(root, R.id.keys_destination, state.destination() == ShellNavigation.Destination.KEYS);
        visible(root, R.id.actions_destination, state.destination() == ShellNavigation.Destination.ACTIONS);
        View header = root.findViewById(R.id.shell_header);
        header.setImportantForAccessibility(overlay ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        root.findViewById(R.id.computer_selector).setEnabled(!overlay);
        root.findViewById(R.id.header_settings).setEnabled(!overlay);
        root.findViewById(R.id.connection_status).setEnabled(!overlay);
        root.findViewById(R.id.toolbar).setEnabled(!overlay);
        // A parent's enabled flag does not disable Toolbar's child overflow button. Keep the
        // popup anchor while its own menu is open, but remove background access behind layers.
        root.findViewById(R.id.toolbar).setVisibility(overlay && state.topOverlay() != ShellNavigation.Overlay.MENU
                ? View.INVISIBLE : View.VISIBLE);
        BottomNavigationView bottom = root.findViewById(R.id.bottom_navigation);
        int selected = state.destination() == ShellNavigation.Destination.KEYS ? R.id.nav_keys
                : state.destination() == ShellNavigation.Destination.ACTIONS ? R.id.nav_actions : R.id.nav_touchpad;
        bottom.getMenu().findItem(selected).setChecked(true);
        for (int i = 0; i < bottom.getMenu().size(); i++) bottom.getMenu().getItem(i).setEnabled(!overlay);
    }

    private static void visible(View root, int id, boolean visible) {
        root.findViewById(id).setVisibility(visible ? View.VISIBLE : View.GONE);
    }
}
