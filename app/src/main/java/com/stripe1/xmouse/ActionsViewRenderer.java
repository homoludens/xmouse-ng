package com.stripe1.xmouse;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Button;
import android.app.AlertDialog;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.stripe1.xmouse.layout.ActionTilePresentation;
import com.stripe1.xmouse.layout.ActionTileSize;
import com.stripe1.xmouse.layout.LayoutProfile;
import com.stripe1.xmouse.layout.SavedActionScript;

import java.util.List;
import java.util.function.Consumer;

/** Native View renderer shared by the Actions screen and its isolated test fixture. */
public final class ActionsViewRenderer {
    public interface ActionListener {
        void onAction(View tile, com.stripe1.xmouse.layout.LayoutButton button);
    }

    public interface ScriptListener {
        void onRun(View view, SavedActionScript script);
        void onEdit(View view, SavedActionScript script);
    }

    private ActionsViewRenderer() { }

    public static void renderProfileControls(View root, LayoutProfile profile, boolean editing, boolean canEdit) {
        Context context = root.getContext();
        Button picker = root.findViewById(R.id.actions_layout_picker);
        picker.setText(profile == null ? context.getString(R.string.ng_actions_choose_layout) : profile.getName());
        picker.setContentDescription(profile == null ? context.getString(R.string.ng_actions_choose_layout)
                : context.getString(R.string.ng_actions_selected_layout, profile.getName(), profile.getId()));
        picker.setEnabled(canEdit);
        Button edit = root.findViewById(R.id.actions_edit);
        edit.setText(editing ? R.string.ng_done_actions : R.string.ng_edit_actions_short);
        edit.setContentDescription(context.getString(editing ? R.string.ng_actions_editing_hint : R.string.ng_edit_actions));
        edit.setEnabled(canEdit);
        root.findViewById(R.id.actions_add).setEnabled(canEdit);
    }

    public static AlertDialog profilePicker(Context context, List<LayoutProfile> profiles, LayoutProfile selected,
                                             Consumer<String> onSelected, Runnable onManage) {
        String[] labels = new String[profiles.size()];
        int selectedIndex = -1;
        for (int index = 0; index < profiles.size(); index++) {
            LayoutProfile profile = profiles.get(index);
            labels[index] = context.getString(R.string.ng_actions_layout_id, profile.getName(), profile.getId());
            if (selected != null && profile.getId().equals(selected.getId())) selectedIndex = index;
        }
        return new AlertDialog.Builder(context).setTitle(R.string.ng_actions_choose_layout)
                .setSingleChoiceItems(labels, selectedIndex, (dialog, which) -> {
                    if (which >= 0 && which < profiles.size()) onSelected.accept(profiles.get(which).getId());
                    dialog.dismiss();
                })
                .setNeutralButton(R.string.ng_actions_more_manage_layouts, (dialog, which) -> onManage.run())
                .setNegativeButton(R.string.ng_action_editor_cancel, null).create();
    }

    public static void renderTiles(Context context, LinearLayout container, LayoutProfile profile,
                                   boolean editMode, ActionListener listener) {
        renderTiles(context, container, profile, editMode, R.string.ng_actions_empty, listener);
    }

    public static void renderTiles(Context context, LinearLayout container, LayoutProfile profile,
                                   boolean editMode, int emptyMessage, ActionListener listener) {
        container.removeAllViews();
        if (profile == null) {
            addTileMessage(context, container, emptyMessage);
            return;
        }
        if (profile.getButtons().isEmpty()) {
            addTileMessage(context, container, R.string.ng_actions_empty);
            return;
        }

        List<List<ActionTilePresentation>> rows = com.stripe1.xmouse.layout.ActionPresentation.INSTANCE.tileRows(profile);
        for (List<ActionTilePresentation> tiles : rows) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowParams.bottomMargin = dp(context, 8);
            container.addView(row, rowParams);

            for (int index = 0; index < tiles.size(); index++) {
                ActionTilePresentation presentation = tiles.get(index);
                View tile = createActionTile(context, presentation, editMode);
                if (listener != null) listener.onAction(tile, presentation.getButton());
                LinearLayout.LayoutParams tileParams;
                if (presentation.getSize() == ActionTileSize.WIDE) {
                    tileParams = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                } else {
                    tileParams = new LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                }
                if (index > 0) tileParams.setMarginStart(dp(context, 8));
                row.addView(tile, tileParams);
            }
            if (tiles.size() == 1 && tiles.get(0).getSize() == ActionTileSize.REGULAR) {
                View spacer = new View(context);
                row.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
            }
        }
    }

    private static void addTileMessage(Context context, LinearLayout container, int message) {
        TextView empty = new TextView(context);
        empty.setText(message);
        empty.setTextColor(context.getColor(R.color.ng_text_secondary));
        empty.setTextSize(14);
        empty.setPadding(dp(context, 4), dp(context, 6), dp(context, 4), dp(context, 10));
        container.addView(empty, matchWrap());
    }

    public static void renderScripts(Context context, LinearLayout container,
                                     List<SavedActionScript> scripts, boolean loading,
                                     boolean failed, boolean editMode, ScriptListener listener) {
        container.removeAllViews();
        if (loading) {
            addScriptMessage(context, container, R.string.ng_scripts_loading);
            return;
        }
        if (failed) {
            addScriptMessage(context, container, R.string.ng_script_read_failed);
            return;
        }
        if (scripts == null || scripts.isEmpty()) {
            addScriptMessage(context, container, R.string.ng_no_scripts);
            return;
        }
        for (SavedActionScript script : scripts) {
            MaterialCardView card = new MaterialCardView(context);
            card.setCardBackgroundColor(context.getColor(R.color.ng_surface));
            card.setStrokeColor(ColorStateList.valueOf(context.getColor(R.color.ng_border)));
            card.setStrokeWidth(dp(context, 1));
            card.setRadius(dp(context, 14));
            card.setCardElevation(0f);
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardParams.bottomMargin = dp(context, 8);

            LinearLayout content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8));
            TextView name = new TextView(context);
            name.setText(script.getName());
            name.setTextColor(context.getColor(R.color.ng_text_primary));
            name.setTextSize(16);
            name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            name.setContentDescription(context.getString(R.string.ng_script_named, script.getName()));
            content.addView(name, matchWrap());

            LinearLayout controls = new LinearLayout(context);
            controls.setOrientation(LinearLayout.HORIZONTAL);
            controls.setGravity(Gravity.CENTER_VERTICAL);
            controls.setPadding(0, dp(context, 4), 0, 0);
            MaterialButton run = scriptButton(context, R.string.ng_run_script);
            run.setEnabled(!editMode);
            run.setTag(script.getId());
            run.setContentDescription(context.getString(R.string.ng_run_named_script, script.getName()));
            run.setOnClickListener(view -> { if (listener != null) listener.onRun(view, script); });
            MaterialButton edit = scriptButton(context, R.string.ng_edit_script);
            edit.setTag(script.getId());
            edit.setContentDescription(context.getString(R.string.ng_edit_named_script, script.getName()));
            edit.setOnClickListener(view -> { if (listener != null) listener.onEdit(view, script); });
            controls.addView(run, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            editParams.setMarginStart(dp(context, 8));
            controls.addView(edit, editParams);
            content.addView(controls, matchWrap());
            card.addView(content);
            container.addView(card, cardParams);
        }
    }

    private static View createActionTile(Context context, ActionTilePresentation presentation, boolean editMode) {
        com.stripe1.xmouse.layout.LayoutButton action = presentation.getButton();
        MaterialCardView card = new MaterialCardView(context);
        int background;
        try { background = Color.parseColor(action.getColor()); }
        catch (IllegalArgumentException ignored) { background = context.getColor(R.color.ng_surface); }
        int effectiveBackground = composite(background, context.getColor(R.color.ng_surface));
        int foreground = contrastColor(effectiveBackground);
        card.setCardBackgroundColor(background);
        card.setStrokeColor(ColorStateList.valueOf(context.getColor(R.color.ng_border)));
        card.setStrokeWidth(dp(context, 1));
        card.setRadius(dp(context, 14));
        card.setCardElevation(0f);
        card.setClickable(true);
        card.setFocusable(true);
        card.setMinimumHeight(dp(context, 82));
        card.setTag(action.getId());
        card.setContentDescription(context.getString(R.string.ng_action_tile_accessibility,
                action.getTitle(), presentation.getSubtitle(), action.getId()));

        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setGravity(Gravity.CENTER_VERTICAL);
        body.setPadding(dp(context, 12), dp(context, 10), dp(context, 10), dp(context, 10));
        ImageView icon = new ImageView(context);
        icon.setImageResource(R.drawable.ic_ng_actions);
        icon.setImageTintList(ColorStateList.valueOf(foreground));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(context, 22), dp(context, 22));
        iconParams.setMarginEnd(dp(context, 10));
        body.addView(icon, iconParams);

        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(context);
        title.setText(action.getTitle());
        title.setTextColor(foreground);
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setMaxLines(2);
        labels.addView(title, matchWrap());
        TextView subtitle = new TextView(context);
        subtitle.setText(editMode ? context.getString(R.string.ng_tap_to_edit_action) : presentation.getSubtitle());
        subtitle.setTextColor(foreground);
        subtitle.setTextSize(12);
        subtitle.setMaxLines(2);
        labels.addView(subtitle, matchWrap());
        body.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(body);
        return card;
    }

    private static int contrastColor(int background) {
        double blackContrast = (luminance(background) + 0.05) / 0.05;
        double whiteContrast = 1.05 / (luminance(background) + 0.05);
        return blackContrast >= whiteContrast ? Color.BLACK : Color.WHITE;
    }

    private static double luminance(int color) {
        double red = linear(Color.red(color) / 255.0);
        double green = linear(Color.green(color) / 255.0);
        double blue = linear(Color.blue(color) / 255.0);
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue;
    }

    private static double linear(double component) {
        return component <= 0.04045 ? component / 12.92 : Math.pow((component + 0.055) / 1.055, 2.4);
    }

    /** Include stored ARGB alpha in contrast selection against the active theme surface. */
    private static int composite(int foreground, int background) {
        int alpha = Color.alpha(foreground);
        if (alpha == 255) return foreground;
        int inverse = 255 - alpha;
        return Color.rgb(
                (Color.red(foreground) * alpha + Color.red(background) * inverse) / 255,
                (Color.green(foreground) * alpha + Color.green(background) * inverse) / 255,
                (Color.blue(foreground) * alpha + Color.blue(background) * inverse) / 255);
    }

    private static MaterialButton scriptButton(Context context, int label) {
        MaterialButton button = new MaterialButton(context, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(label);
        button.setAllCaps(false);
        button.setMinHeight(dp(context, 48));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setCornerRadius(dp(context, 12));
        button.setStrokeColor(ColorStateList.valueOf(context.getColor(R.color.ng_border)));
        button.setBackgroundTintList(ColorStateList.valueOf(context.getColor(R.color.ng_surface_soft)));
        button.setTextColor(context.getColor(R.color.ng_text_primary));
        return button;
    }

    private static void addScriptMessage(Context context, LinearLayout container, int text) {
        TextView message = new TextView(context);
        message.setText(text);
        message.setTextColor(context.getColor(R.color.ng_text_secondary));
        message.setTextSize(14);
        message.setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 8));
        container.addView(message, matchWrap());
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
