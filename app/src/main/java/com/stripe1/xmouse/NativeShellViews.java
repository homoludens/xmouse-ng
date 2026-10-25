package com.stripe1.xmouse;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import java.util.List;

/** Shared production/isolated-fixture rendering. Input is retired by ShellNavigation before render. */
public final class NativeShellViews {
    public interface KeyButtonBinder {
        void bind(View button, String command);
    }

    private static final KeyButtonBinder NO_KEY_BINDER = (button, command) -> { };

    private NativeShellViews() { }

    public static void bind(View root, ShellNavigation navigation) {
        bind(root, navigation, NO_KEY_BINDER);
    }

    public static void bind(View root, ShellNavigation navigation, KeyButtonBinder keyButtonBinder) {
        BottomNavigationView bottom = root.findViewById(R.id.bottom_navigation);
        bottom.setOnItemSelectedListener(item -> {
            navigation.select(destination(item.getItemId()));
            return navigation.topOverlay() == null;
        });
        root.findViewById(R.id.computer_selector).setOnClickListener(v -> navigation.open(ShellNavigation.Overlay.COMPUTERS));
        root.findViewById(R.id.type_on_computer).setOnClickListener(v -> navigation.open(ShellNavigation.Overlay.TYPING));
        root.findViewById(R.id.keys_type).setOnClickListener(v -> navigation.open(ShellNavigation.Overlay.TYPING));
        root.findViewById(R.id.overlay_close).setOnClickListener(v -> navigation.closeTop());
        root.findViewById(R.id.jsToggle).setOnClickListener(v -> navigation.setJoystick(true));
        root.findViewById(R.id.js_jsToggle).setOnClickListener(v -> navigation.setJoystick(false));
        MaterialButtonToggleGroup categories = root.findViewById(R.id.keys_category_group);
        categories.addOnButtonCheckedListener((group, checkedId, checked) -> {
            if (!checked || Boolean.TRUE.equals(group.getTag())) return;
            KeyCatalog.Category category = categoryForId(checkedId);
            if (category != null) navigation.selectKeyCategory(category);
        });
        render(root, navigation, keyButtonBinder);
    }

    public static ShellNavigation.Destination destination(int id) {
        if (id == R.id.nav_keys) return ShellNavigation.Destination.KEYS;
        if (id == R.id.nav_actions) return ShellNavigation.Destination.ACTIONS;
        return ShellNavigation.Destination.TOUCHPAD;
    }

    public static void render(View root, ShellNavigation state) {
        render(root, state, NO_KEY_BINDER);
    }

    public static void render(View root, ShellNavigation state, KeyButtonBinder keyButtonBinder) {
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
        renderKeyCategory(root, state, keyButtonBinder);
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

    private static void renderKeyCategory(View root, ShellNavigation state, KeyButtonBinder binder) {
        MaterialButtonToggleGroup group = root.findViewById(R.id.keys_category_group);
        int selectedId = categoryId(state.keyCategory());
        if (group.getCheckedButtonId() != selectedId) {
            group.setTag(Boolean.TRUE);
            group.check(selectedId);
            group.setTag(null);
        }

        LinearLayout content = root.findViewById(R.id.keys_catalog_content);
        if (state.keyCategory().equals(content.getTag())) return;

        // ShellNavigation retires the old KEYS capability before calling this renderer. Rebuild
        // only when the category actually changes, then let its normal refresh hook issue a fresh one.
        content.removeAllViews();
        List<KeyCatalog.Section> sections = KeyCatalog.sections(state.keyCategory());
        for (int sectionIndex = 0; sectionIndex < sections.size(); sectionIndex++) {
            KeyCatalog.Section section = sections.get(sectionIndex);
            if (!section.label().isEmpty()) {
                TextView heading = new TextView(root.getContext());
                heading.setText(section.label());
                heading.setTextAppearance(R.style.TextAppearance_XMouseNg_KeySection);
                heading.setPadding(0, dp(root, sectionIndex == 0 ? 8 : 14), 0, dp(root, 6));
                content.addView(heading, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            addSectionGrid(root, content, section, binder);
        }
        content.setTag(state.keyCategory());
    }

    private static void addSectionGrid(View root, LinearLayout content, KeyCatalog.Section section,
                                       KeyButtonBinder binder) {
        List<KeyCatalog.Entry> entries = section.entries();
        int columns = section.columns();
        for (int start = 0; start < entries.size(); start += columns) {
            LinearLayout row = new LinearLayout(root.getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowParams.bottomMargin = dp(root, 8);
            content.addView(row, rowParams);

            int end = Math.min(start + columns, entries.size());
            for (int i = start; i < end; i++) {
                KeyCatalog.Entry entry = entries.get(i);
                MaterialButton button = createKeyButton(root, entry);
                binder.bind(button, entry.command());
                LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (i > start) buttonParams.setMarginStart(dp(root, 6));
                row.addView(button, buttonParams);
            }
        }
    }

    private static MaterialButton createKeyButton(View root, KeyCatalog.Entry entry) {
        MaterialButton button = new MaterialButton(root.getContext());
        boolean destructive = entry.destructive();
        int background = root.getContext().getColor(destructive ? R.color.ng_danger_soft : R.color.ng_surface_soft);
        int foreground = root.getContext().getColor(destructive ? R.color.ng_danger : R.color.ng_text_primary);
        int border = root.getContext().getColor(destructive ? R.color.ng_danger_soft : R.color.ng_border);
        button.setText(entry.label());
        button.setContentDescription(entry.label());
        button.setAllCaps(false);
        button.setSingleLine(false);
        button.setMaxLines(3);
        button.setEllipsize(null);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(root, 5), dp(root, 8), dp(root, 5), dp(root, 8));
        button.setMinHeight(dp(root, 48));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setCornerRadius(dp(root, 12));
        button.setStrokeWidth(dp(root, 1));
        button.setStrokeColor(ColorStateList.valueOf(border));
        button.setBackgroundTintList(ColorStateList.valueOf(background));
        button.setTextColor(foreground);
        button.setId(legacyId(entry.command()));
        return button;
    }

    private static int legacyId(String command) {
        if ("key Escape".equals(command)) return R.id.key_escape;
        if ("key alt+Tab".equals(command)) return R.id.key_switch;
        if ("key super".equals(command)) return R.id.key_super;
        return View.generateViewId();
    }

    private static int categoryId(KeyCatalog.Category category) {
        switch (category) {
            case COMMON: return R.id.keys_category_common;
            case NAVIGATE: return R.id.keys_category_navigate;
            case MEDIA: return R.id.keys_category_media;
            case FUNCTION: return R.id.keys_category_function;
            default: throw new IllegalArgumentException("unsupported Keys category");
        }
    }

    private static KeyCatalog.Category categoryForId(int id) {
        if (id == R.id.keys_category_common) return KeyCatalog.Category.COMMON;
        if (id == R.id.keys_category_navigate) return KeyCatalog.Category.NAVIGATE;
        if (id == R.id.keys_category_media) return KeyCatalog.Category.MEDIA;
        if (id == R.id.keys_category_function) return KeyCatalog.Category.FUNCTION;
        return null;
    }

    private static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }

    private static void visible(View root, int id, boolean visible) {
        root.findViewById(id).setVisibility(visible ? View.VISIBLE : View.GONE);
    }
}
