package com.stripe1.xmouse;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Button;
import android.app.AlertDialog;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.stripe1.xmouse.layout.LayoutButton;
import com.stripe1.xmouse.layout.LayoutButtonPlacement;
import com.stripe1.xmouse.layout.LayoutEditorGrid;
import com.stripe1.xmouse.layout.LayoutProfile;
import com.stripe1.xmouse.layout.ActionPresetCatalog;
import com.stripe1.xmouse.layout.SavedActionScript;

import java.util.List;
import java.util.function.Consumer;

/** Native View renderer shared by the shell destinations and their isolated test fixtures. */
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
        renderProfileControls(root, profile, editing, canEdit, canEdit, false);
    }

    public static void renderProfileControls(View root, LayoutProfile profile, boolean editing, boolean canEdit,
                                             boolean canChoose, boolean builtInReadOnly) {
        Context context = root.getContext();
        Button picker = root.findViewById(R.id.actions_layout_picker);
        picker.setText(profile == null ? context.getString(R.string.ng_actions_choose_layout)
                : builtInReadOnly ? context.getString(R.string.ng_actions_builtin_selected, profile.getName())
                : profile.getName());
        picker.setContentDescription(profile == null ? context.getString(R.string.ng_actions_choose_layout)
                : builtInReadOnly ? context.getString(R.string.ng_actions_selected_builtin, profile.getName())
                : context.getString(R.string.ng_actions_selected_layout, profile.getName(), profile.getId()));
        picker.setEnabled(canChoose);
        Button edit = root.findViewById(R.id.actions_edit);
        edit.setText(builtInReadOnly ? R.string.ng_actions_read_only
                : editing ? R.string.ng_done_actions : R.string.ng_edit_actions_short);
        edit.setContentDescription(context.getString(builtInReadOnly ? R.string.ng_actions_read_only_hint
                : editing ? R.string.ng_actions_editing_hint : R.string.ng_edit_actions));
        edit.setEnabled(canEdit);
    }

    /** Tagged choice so a virtual preset can never be mistaken for a same-ID saved profile. */
    public static final class PickerChoice {
        private final String presetId;
        private final String profileId;
        private final String label;

        private PickerChoice(String presetId, String profileId, String label) {
            this.presetId = presetId;
            this.profileId = profileId;
            this.label = label;
        }

        public String presetId() { return presetId; }
        public String profileId() { return profileId; }
        public String label() { return label; }
        public boolean isPreset() { return presetId != null; }
    }

    public static AlertDialog profilePicker(Context context, List<ActionPresetCatalog.Preset> presets,
                                             List<LayoutProfile> profiles, LayoutProfile selected,
                                             String selectedPresetId, Consumer<PickerChoice> onSelected,
                                             Runnable onManage) {
        List<PickerChoice> choices = new java.util.ArrayList<>();
        List<String> choiceLabels = new java.util.ArrayList<>();
        int selectedIndex = -1;
        for (ActionPresetCatalog.Preset preset : presets) {
            String label = context.getString(R.string.ng_actions_builtin_choice, preset.title());
            if (selectedPresetId != null && selectedPresetId.equals(preset.id())) selectedIndex = choices.size();
            choices.add(new PickerChoice(preset.id(), null, label));
            choiceLabels.add(label);
        }
        for (int index = 0; index < profiles.size(); index++) {
            LayoutProfile profile = profiles.get(index);
            String label = context.getString(R.string.ng_actions_layout_id, profile.getName(), profile.getId());
            if (selectedPresetId == null && selected != null && profile.getId().equals(selected.getId()))
                selectedIndex = choices.size();
            choices.add(new PickerChoice(null, profile.getId(), label));
            choiceLabels.add(label);
        }
        String[] labels = choiceLabels.toArray(new String[0]);
        return new AlertDialog.Builder(context).setTitle(R.string.ng_actions_choose_layout)
                .setSingleChoiceItems(labels, selectedIndex, (dialog, which) -> {
                    if (which >= 0 && which < choices.size()) onSelected.accept(choices.get(which));
                    dialog.dismiss();
                })
                .setNeutralButton(R.string.ng_layout_action_manage, (dialog, which) -> onManage.run())
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

        List<LayoutButtonPlacement> placements = LayoutEditorGrid.INSTANCE.placements(profile);
        int rowCount = placements.get(placements.size() - 1).getRow() + 1;
        java.util.Map<String, LayoutButton> buttonsById = new java.util.HashMap<>();
        for (LayoutButton button : profile.getButtons()) buttonsById.put(button.getId(), button);

        int gap = context.getResources().getDimensionPixelSize(R.dimen.ng_action_gap);
        int buttonHeight = dp(context, 56);
        for (int rowIndex = 0; rowIndex < rowCount; rowIndex++) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setBaselineAligned(false);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, buttonHeight);
            if (rowIndex < rowCount - 1) rowParams.bottomMargin = gap;
            container.addView(row, rowParams);

            int nextColumn = 0;
            for (LayoutButtonPlacement placement : placements) {
                if (placement.getRow() != rowIndex) continue;
                if (placement.getColumn() > nextColumn) {
                    addGridSpacer(context, row, placement.getColumn() - nextColumn, buttonHeight);
                }
                LayoutButton button = buttonsById.get(placement.getButtonId());
                int position = profile.getButtons().indexOf(button) + 1;
                FrameLayout slot = new FrameLayout(context);
                LinearLayout.LayoutParams slotParams = new LinearLayout.LayoutParams(
                        0, buttonHeight, placement.getWidthColumns());
                View tile = createActionTile(context, button, position, profile.getButtons().size(), editMode);
                FrameLayout.LayoutParams tileParams = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                tileParams.setMargins(dp(context, 2), 0, dp(context, 2), 0);
                slot.addView(tile, tileParams);
                row.addView(slot, slotParams);
                if (listener != null) listener.onAction(tile, button);
                nextColumn = placement.getColumn() + placement.getWidthColumns();
            }
            if (nextColumn < profile.getColumnCount()) {
                addGridSpacer(context, row,
                        profile.getColumnCount() - nextColumn, buttonHeight);
            }
        }
    }

    /** Render a virtual category with the same ten-column, title-only action tiles as saved data. */
    public static void renderPreset(Context context, LinearLayout container,
                                    ActionPresetCatalog.Preset preset, LayoutProfile profile,
                                    ActionListener listener) {
        container.removeAllViews();
        if (preset == null || profile == null) {
            addTileMessage(context, container, R.string.ng_actions_empty);
            return;
        }
        java.util.Map<String, LayoutButton> buttons = new java.util.HashMap<>();
        for (LayoutButton button : profile.getButtons()) buttons.put(button.getId(), button);
        boolean renderedSection = false;
        for (ActionPresetCatalog.Section section : preset.sections()) {
            if (!section.title().isEmpty()) {
                TextView heading = new TextView(context);
                heading.setText(section.title());
                heading.setTextAppearance(context, R.style.TextAppearance_XMouseNg_Section);
                LinearLayout.LayoutParams headingParams = matchWrap();
                headingParams.topMargin = renderedSection ? dp(context, 14) : dp(context, 2);
                headingParams.bottomMargin = dp(context, 6);
                container.addView(heading, headingParams);
            }
            LinearLayout sectionTiles = new LinearLayout(context);
            sectionTiles.setOrientation(LinearLayout.VERTICAL);
            container.addView(sectionTiles, matchWrap());
            java.util.List<LayoutButton> sectionButtons = new java.util.ArrayList<>();
            for (ActionPresetCatalog.Entry entry : section.entries()) {
                LayoutButton button = buttons.get(entry.id());
                if (button != null) sectionButtons.add(button);
            }
            LayoutProfile sectionProfile = new LayoutProfile(profile.getId(), profile.getName(),
                    profile.getColumnCount(), sectionButtons);
            renderTiles(context, sectionTiles, sectionProfile, false, R.string.ng_actions_empty, listener);
            renderedSection = true;
        }
    }

    private static void addGridSpacer(Context context, LinearLayout row, int columns, int height) {
        View spacer = new View(context);
        spacer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(spacer, new LinearLayout.LayoutParams(0, height, columns));
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
        TextView count = container.getRootView().findViewById(R.id.scripts_count);
        if (count != null) {
            if (loading || failed || scripts == null) {
                count.setVisibility(View.GONE);
            } else {
                count.setText(context.getResources().getQuantityString(
                        R.plurals.ng_scripts_count, scripts.size(), scripts.size()));
                count.setContentDescription(count.getText());
                count.setVisibility(View.VISIBLE);
            }
        }
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
            content.setOrientation(LinearLayout.HORIZONTAL);
            content.setGravity(Gravity.CENTER_VERTICAL);
            content.setBaselineAligned(false);
            content.setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8));
            LinearLayout labels = new LinearLayout(context);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setMinimumWidth(0);
            TextView name = new TextView(context);
            name.setText(script.getName());
            name.setTextColor(context.getColor(R.color.ng_text_primary));
            name.setTextSize(14);
            name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            name.setMinWidth(0);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setContentDescription(context.getString(R.string.ng_script_named, script.getName()));
            labels.addView(name, matchWrap());
            TextView subtitle = new TextView(context);
            subtitle.setText(R.string.ng_script_ssh_label);
            subtitle.setTextSize(12);
            subtitle.setTextColor(context.getColor(R.color.ng_text_secondary));
            labels.addView(subtitle, matchWrap());
            content.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            LinearLayout controls = new LinearLayout(context);
            controls.setOrientation(LinearLayout.HORIZONTAL);
            controls.setBaselineAligned(false);
            controls.setGravity(Gravity.CENTER_VERTICAL);
            controls.setPadding(dp(context, 8), 0, 0, 0);
            MaterialButton run = scriptButton(context, R.string.ng_run_script, R.drawable.ic_ng_play, false);
            run.setEnabled(!editMode);
            run.setTag(script.getId());
            String runDescription = context.getString(R.string.ng_run_named_script, script.getName());
            run.setContentDescription(runDescription);
            run.setTooltipText(runDescription);
            run.setOnClickListener(view -> { if (listener != null) listener.onRun(view, script); });
            MaterialButton edit = scriptButton(context, R.string.ng_edit_script, R.drawable.ic_ng_edit, true);
            edit.setTag(script.getId());
            String editDescription = context.getString(R.string.ng_edit_named_script, script.getName());
            edit.setContentDescription(editDescription);
            edit.setTooltipText(editDescription);
            edit.setOnClickListener(view -> { if (listener != null) listener.onEdit(view, script); });
            controls.addView(run, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            editParams.setMarginStart(dp(context, 4));
            controls.addView(edit, editParams);
            content.addView(controls, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            card.addView(content);
            container.addView(card, cardParams);
        }
    }

    private static View createActionTile(Context context, LayoutButton action, int position,
                                         int total, boolean editMode) {
        MaterialCardView card = new MaterialCardView(context);
        int background;
        try { background = Color.parseColor(action.getColor()); }
        catch (IllegalArgumentException ignored) { background = context.getColor(R.color.ng_surface); }
        int effectiveBackground = composite(background, context.getColor(R.color.ng_surface));
        int foreground = contrastColor(effectiveBackground);
        card.setCardBackgroundColor(background);
        card.setStrokeColor(ColorStateList.valueOf(context.getColor(R.color.ng_border)));
        card.setStrokeWidth(dp(context, 1));
        card.setRadius(dp(context, 8));
        card.setCardElevation(0f);
        card.setClickable(true);
        card.setFocusable(true);
        card.setMinimumWidth(0);
        card.setMinimumHeight(dp(context, 56));
        card.setTag(action.getId());
        TextView title = new TextView(context);
        title.setText(action.getTitle());
        title.setTextColor(foreground);
        title.setTextSize(11);
        title.setLetterSpacing(0.04f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setGravity(Gravity.CENTER);
        title.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        title.setMinWidth(0);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.setContentPadding(dp(context, 2), 0, dp(context, 2), 0);
        card.addView(title, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        String accessibility = context.getString(R.string.ng_action_tile_accessibility,
                action.getTitle(), position, total, action.getWidth(), action.getId());
        if (editMode) accessibility += ", " + context.getString(R.string.ng_tap_to_edit_action);
        card.setContentDescription(accessibility);
        card.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
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

    private static MaterialButton scriptButton(Context context, int label, int icon, boolean accent) {
        MaterialButton button = new MaterialButton(context, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(label);
        button.setIconResource(icon);
        button.setIconTint(ColorStateList.valueOf(context.getColor(
                accent ? R.color.ng_accent : R.color.ng_text_secondary)));
        button.setIconSize(dp(context, 16));
        button.setIconPadding(dp(context, 4));
        button.setAllCaps(false);
        button.setMaxLines(1);
        button.setMinWidth(dp(context, 48));
        button.setMinimumWidth(dp(context, 48));
        button.setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8));
        button.setTextSize(13);
        button.setLetterSpacing(0f);
        button.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        button.setStateListAnimator(null);
        button.setElevation(0f);
        button.setMinHeight(dp(context, 48));
        button.setMinimumHeight(dp(context, 48));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setCornerRadius(dp(context, 12));
        button.setStrokeColor(ColorStateList.valueOf(context.getColor(
                accent ? R.color.ng_accent : R.color.ng_border)));
        button.setStrokeWidth(dp(context, 1));
        button.setBackgroundTintList(ColorStateList.valueOf(context.getColor(
                accent ? R.color.ng_accent_soft : R.color.ng_surface_soft)));
        button.setTextColor(context.getColor(accent ? R.color.ng_accent : R.color.ng_text_primary));
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
