package com.github.squi2rel.vp.creation;

import com.github.squi2rel.vp.video.VideoScreen;
import com.github.squi2rel.vp.ClientPacketHandler;
import com.github.squi2rel.vp.ClientPermissionCache;
import com.github.squi2rel.vp.i18n.VpTexts;
import com.github.squi2rel.vp.network.RequestResultStatus;
import com.github.squi2rel.vp.permission.VideoPermissionAction;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

public class VideoCreationScreen extends Screen {
    private final VideoCreationEditor editor;
    private final VideoCreationEditor.Draft draft;

    private TextFieldWidget nameField;
    private TextFieldWidget sourceField;
    private ButtonWidget targetButton;
    private ButtonWidget screenModeButton;
    private ButtonWidget areaButton;
    private ButtonWidget sourceButton;
    private ButtonWidget selectionButton;
    private ButtonWidget confirmButton;

    public VideoCreationScreen(VideoCreationEditor editor) {
        super(VpTexts.tr("screen.videoplayer.creation", "VideoPlayer Creation"));
        this.editor = editor;
        this.draft = editor.draft().copy();
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(320, width - 40);
        int left = (width - panelWidth) / 2;
        int top = Math.max(24, height / 2 - 112);
        int row = top + 24;

        nameField = new TextFieldWidget(textRenderer, left + 88, row, panelWidth - 88, 20, VpTexts.tr("label.videoplayer.name", "Name"));
        nameField.setMaxLength(VideoScreen.MAX_NAME_LENGTH);
        nameField.setText(draft.name);
        addDrawableChild(nameField);

        row += 28;
        targetButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            draft.target = draft.target.next();
            if (draft.target == VideoCreationEditor.Target.SCREEN && draft.areaName.isEmpty()) {
                draft.areaName = editor.areaNames().stream().findFirst().orElse("");
            }
            draft.name = suggestedName();
            nameField.setText(draft.name);
            syncButtons();
        }).dimensions(left + 88, row, panelWidth - 88, 20).build());

        row += 28;
        areaButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            List<String> names = editor.areaNames();
            if (names.isEmpty()) return;
            int index = names.indexOf(draft.areaName);
            draft.areaName = names.get((index + 1 + names.size()) % names.size());
            draft.source = "";
            syncButtons();
        }).dimensions(left + 88, row, panelWidth - 88, 20).build());

        row += 28;
        screenModeButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            draft.screenMode = draft.screenMode.next();
            syncButtons();
        }).dimensions(left + 88, row, panelWidth - 88, 20).build());

        row += 28;
        sourceField = new TextFieldWidget(textRenderer, left + 88, row, panelWidth - 168, 20, VpTexts.tr("label.videoplayer.source", "Source"));
        sourceField.setMaxLength(VideoScreen.MAX_NAME_LENGTH);
        sourceField.setText(draft.source);
        addDrawableChild(sourceField);
        sourceButton = addDrawableChild(ButtonWidget.builder(VpTexts.tr("button.videoplayer.select", "Select"), button -> {
            List<String> names = editor.realScreenNames(draft.areaName);
            if (names.isEmpty()) {
                draft.source = "";
            } else {
                String current = sourceField.getText().trim();
                int index = names.indexOf(current);
                draft.source = names.get((index + 1 + names.size()) % names.size());
            }
            sourceField.setText(draft.source);
            syncButtons();
        }).dimensions(left + panelWidth - 72, row, 72, 20).build());

        row += 34;
        selectionButton = addDrawableChild(ButtonWidget.builder(VpTexts.tr("button.videoplayer.start_selection", "Start Selection"), button -> {
            copyFieldsToDraft();
            editor.beginSelection(draft);
        }).dimensions(left, row, 96, 20).build());
        addDrawableChild(ButtonWidget.builder(VpTexts.tr("button.videoplayer.clear_selection", "Clear Selection"), button -> {
            editor.clearSelection();
            syncButtons();
        }).dimensions(left + 104, row, 96, 20).build());
        confirmButton = addDrawableChild(ButtonWidget.builder(VpTexts.tr("button.videoplayer.create", "Create"), button -> {
            copyFieldsToDraft();
            editor.confirm(result -> {
                if (ClientPacketHandler.denied(result)) {
                    button.setMessage(VpTexts.tr("error.videoplayer.permission_denied", "Permission denied"));
                    return;
                }
                if (result != null && result.status() == RequestResultStatus.OK) close();
            });
            syncButtons();
        }).dimensions(left + panelWidth - 96, row, 96, 20).build());

        row += 28;
        addDrawableChild(ButtonWidget.builder(VpTexts.tr("button.videoplayer.close", "Close"), button -> close()).dimensions(left, row, panelWidth, 20).build());

        syncButtons();
        setInitialFocus(nameField);
    }

    @Override
    public void tick() {
        copyFieldsToDraft();
        syncButtons();
    }

    @Override
    public void close() {
        client.setScreen(null);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);

        int panelWidth = Math.min(320, width - 40);
        int left = (width - panelWidth) / 2;
        int top = Math.max(24, height / 2 - 112);
        int bottom = top + 228;

        context.fill(left - 12, top - 12, left + panelWidth + 12, bottom, 0xCC101010);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, top - 2, 0xFFFFFFFF);
        context.drawTextWithShadow(textRenderer, VpTexts.tr("label.videoplayer.name", "Name"), left, top + 28, 0xFFE0E0E0);
        context.drawTextWithShadow(textRenderer, VpTexts.tr("label.videoplayer.type", "Type"), left, top + 56, 0xFFE0E0E0);
        context.drawTextWithShadow(textRenderer, VpTexts.tr("label.videoplayer.area", "Area"), left, top + 84, 0xFFE0E0E0);
        context.drawTextWithShadow(textRenderer, VpTexts.tr("label.videoplayer.mode", "Mode"), left, top + 112, 0xFFE0E0E0);
        context.drawTextWithShadow(textRenderer, VpTexts.tr("label.videoplayer.source", "Source"), left, top + 140, 0xFFE0E0E0);

        String points = editor.pointProgress();
        int statusColor = editor.statusError() ? 0xFFFF5555 : 0xFF55FF55;
        context.drawTextWithShadow(textRenderer, VpTexts.tr("label.videoplayer.selection_points", "Selection: %s", points), left, top + 172, 0xFFE0E0E0);
        context.drawTextWithShadow(textRenderer, editor.status(), left + 72, top + 172, statusColor);

        if (draft.target == VideoCreationEditor.Target.SCREEN && draft.areaName.isEmpty()) {
            context.drawTextWithShadow(textRenderer, VpTexts.tr("error.videoplayer.need_area_first", "Enter or create an Area first").formatted(Formatting.RED), left, top + 190, 0xFFFF5555);
        } else if (draft.target == VideoCreationEditor.Target.SCREEN) {
            context.drawTextWithShadow(textRenderer, VpTexts.tr("hint.videoplayer.select_points", "Left-click points, right-click undo, press V to return and confirm"), left, top + 190, 0xFFB0B0B0);
        } else {
            context.drawTextWithShadow(textRenderer, VpTexts.tr("hint.videoplayer.area_two_blocks", "Area uses two blocks to create a bounding box"), left, top + 190, 0xFFB0B0B0);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    private void copyFieldsToDraft() {
        draft.name = nameField == null ? draft.name : nameField.getText().trim();
        draft.source = sourceField == null ? draft.source : sourceField.getText().trim();
        editor.draft().copyFrom(draft);
    }

    private void syncButtons() {
        if (nameField != null && !nameField.getText().equals(draft.name)) {
            draft.name = nameField.getText().trim();
        }
        if (sourceField != null && !sourceField.getText().equals(draft.source)) {
            draft.source = sourceField.getText().trim();
        }
        editor.draft().copyFrom(draft);
        boolean screen = draft.target == VideoCreationEditor.Target.SCREEN;
        targetButton.setMessage(VpTexts.tr("label.videoplayer.type_value", "Type: %s", draft.target.label()));
        areaButton.setMessage(draft.areaName.isEmpty()
                ? VpTexts.tr("label.videoplayer.area_none", "Area: None")
                : VpTexts.tr("label.videoplayer.area_value", "Area: %s", draft.areaName));
        areaButton.active = screen && !editor.areaNames().isEmpty();
        screenModeButton.setMessage(VpTexts.tr("label.videoplayer.mode_value", "Mode: %s", draft.screenMode.label()));
        screenModeButton.active = screen;
        sourceField.visible = screen;
        sourceField.active = screen;
        sourceButton.visible = screen;
        sourceButton.active = screen;
        selectionButton.active = canSelect();
        confirmButton.active = editor.ready() && canSubmit();
    }

    private boolean canSelect() {
        if (draft.target == VideoCreationEditor.Target.AREA) {
            return ClientPermissionCache.allowedOrUnknown(VideoPermissionAction.CREATE_AREA, "", "");
        }
        return ClientPermissionCache.allowedOrUnknown(VideoPermissionAction.CREATE_SCREEN, draft.areaName, "");
    }

    private boolean canSubmit() {
        return canSelect();
    }

    private String suggestedName() {
        if (draft.target == VideoCreationEditor.Target.AREA) {
            return editor.suggestedAreaName();
        }
        return editor.suggestedScreenName(draft.areaName);
    }
}
