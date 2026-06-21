package com.github.squi2rel.vp.creation;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;

class VpTextFieldWidget extends TextFieldWidget {
    private static final int PADDING_X = 4;
    private static final int PADDING_Y = 4;

    private final TextRenderer textRenderer;
    private int frameX;
    private int frameY;
    private final int frameWidth;
    private final int frameHeight;
    private final VpUiTheme theme;
    private int visibleStart;
    private int selectionEnd;
    private boolean clipped;
    private int clipLeft;
    private int clipTop;
    private int clipRight;
    private int clipBottom;

    VpTextFieldWidget(TextRenderer textRenderer, int x, int y, int width, int height, Text message, VpUiTheme theme) {
        super(textRenderer, x + PADDING_X, y + PADDING_Y, Math.max(1, width - PADDING_X * 2), textRenderer.fontHeight, message);
        this.textRenderer = textRenderer;
        this.frameX = x;
        this.frameY = y;
        this.frameWidth = Math.max(40, width);
        this.frameHeight = Math.max(16, height);
        this.theme = theme;
        setDrawsBackground(false);
        setEditableColor(theme.primaryTextColor());
        setUneditableColor(VpUiRenderer.blend(theme.secondaryTextColor(), theme.canvasBackgroundColor(), 0.42f));
    }

    VpTextFieldWidget clip(int left, int top, int right, int bottom) {
        this.clipped = true;
        this.clipLeft = left;
        this.clipTop = top;
        this.clipRight = right;
        this.clipBottom = bottom;
        return this;
    }

    @Override
    public void setX(int x) {
        frameX = x;
        super.setX(x + PADDING_X);
    }

    @Override
    public void setY(int y) {
        frameY = y;
        super.setY(y + PADDING_Y);
    }

    @Override
    public void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
        int fill = VpUiRenderer.darken(theme.nodeBodyColor(), active ? 0.02f : 0.10f);
        int border = isFocused() ? theme.accentColor() : theme.panelBorderColor();
        if (!active) {
            border = VpUiRenderer.blend(border, theme.canvasBackgroundColor(), 0.45f);
        }
        VpUiRenderer.drawBox(context, frameX, frameY, frameWidth, frameHeight, fill, border);
        renderTextContent(context);
    }

    @Override
    public void setText(String text) {
        super.setText(text);
        syncSelectionState();
    }

    @Override
    public void write(String text) {
        super.write(text);
        syncSelectionState();
    }

    @Override
    public void setSelectionStart(int selectionStart) {
        super.setSelectionStart(selectionStart);
        syncSelectionStart();
    }

    @Override
    public void setSelectionEnd(int selectionEnd) {
        super.setSelectionEnd(selectionEnd);
        this.selectionEnd = clampIndex(selectionEnd);
        updateVisibleStart(this.selectionEnd);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        boolean handled = super.keyPressed(input);
        if (handled) syncSelectionState();
        return handled;
    }

    @Override
    public boolean charTyped(CharInput input) {
        boolean handled = super.charTyped(input);
        if (handled) syncSelectionState();
        return handled;
    }

    @Override
    public void onClick(Click click, boolean doubleClick) {
        super.onClick(click, doubleClick);
        syncSelectionState();
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return visible
                && mouseX >= frameX
                && mouseY >= frameY
                && mouseX < frameX + frameWidth
                && mouseY < frameY + frameHeight
                && (!clipped || insideClip(mouseX, mouseY));
    }

    private boolean insideClip(double mouseX, double mouseY) {
        return mouseX >= clipLeft
                && mouseY >= clipTop
                && mouseX < clipRight
                && mouseY < clipBottom;
    }

    private void renderTextContent(DrawContext context) {
        String text = getText();
        int cursor = clampIndex(getCursor());
        int safeVisibleStart = clampIndex(visibleStart);
        String visibleText = textRenderer.trimToWidth(text.substring(safeVisibleStart), getInnerWidth());
        int visibleEnd = Math.min(text.length(), safeVisibleStart + visibleText.length());
        int innerX = getX();
        int innerY = getY();
        int right = innerX + getInnerWidth();
        int textColor = active ? theme.primaryTextColor() : VpUiRenderer.blend(theme.secondaryTextColor(), theme.canvasBackgroundColor(), 0.42f);

        context.enableScissor(innerX, frameY + 1, right, frameY + frameHeight - 1);
        renderSelection(context, text, safeVisibleStart, visibleEnd, innerX, right);
        drawText(context, visibleText, innerX, innerY, textColor);
        renderCursor(context, text, cursor, safeVisibleStart, visibleEnd, innerX, innerY, right, textColor);
        context.disableScissor();
    }

    private void renderSelection(DrawContext context, String text, int visibleStart, int visibleEnd, int innerX, int right) {
        if (!isFocused() || selectionEnd == getCursor()) {
            return;
        }
        int start = Math.min(clampIndex(getCursor()), clampIndex(selectionEnd));
        int end = Math.max(clampIndex(getCursor()), clampIndex(selectionEnd));
        int visibleSelectionStart = Math.max(visibleStart, Math.min(visibleEnd, start));
        int visibleSelectionEnd = Math.max(visibleStart, Math.min(visibleEnd, end));
        if (visibleSelectionEnd <= visibleSelectionStart) {
            return;
        }

        int x1 = innerX + textRenderer.getWidth(text.substring(visibleStart, visibleSelectionStart));
        int x2 = innerX + textRenderer.getWidth(text.substring(visibleStart, visibleSelectionEnd));
        context.fill(Math.max(innerX, x1), frameY + 2, Math.min(right, x2), frameY + frameHeight - 2,
                VpUiRenderer.blend(theme.accentColor(), theme.nodeBodyColor(), 0.24f));
    }

    private void renderCursor(DrawContext context, String text, int cursor, int visibleStart, int visibleEnd, int innerX, int innerY, int right, int color) {
        if (!isFocused() || (System.currentTimeMillis() / 530L) % 2L != 0L) {
            return;
        }
        if (cursor < visibleStart || cursor > visibleEnd) {
            return;
        }
        int cursorX = innerX + textRenderer.getWidth(text.substring(visibleStart, cursor));
        cursorX = Math.clamp(cursorX, innerX, right - 1);
        context.fill(cursorX, frameY + 2, cursorX + 1, frameY + frameHeight - 2, color);
    }

    private void drawText(DrawContext context, String text, int x, int y, int color) {
        if (text.isEmpty()) {
            return;
        }
        if (theme.textShadow()) {
            context.drawTextWithShadow(textRenderer, text, x, y, color);
            return;
        }
        context.drawText(textRenderer, text, x, y, color, false);
    }

    private void syncSelectionState() {
        syncSelectionStart();
        if (getSelectedText().isEmpty()) {
            selectionEnd = getCursor();
        } else {
            selectionEnd = inferSelectionEnd();
            updateVisibleStart(selectionEnd);
        }
    }

    private void syncSelectionStart() {
        int cursor = clampIndex(getCursor());
        if (selectionEnd > getText().length()) {
            selectionEnd = cursor;
        }
        updateVisibleStart(cursor);
    }

    private int inferSelectionEnd() {
        String text = getText();
        String selected = getSelectedText();
        int cursor = clampIndex(getCursor());
        int length = selected.length();
        if (cursor + length <= text.length() && text.substring(cursor, cursor + length).equals(selected)) {
            return cursor + length;
        }
        if (cursor - length >= 0 && text.substring(cursor - length, cursor).equals(selected)) {
            return cursor - length;
        }
        return cursor;
    }

    private void updateVisibleStart(int targetIndex) {
        String text = getText();
        int target = clampIndex(targetIndex);
        visibleStart = Math.clamp(visibleStart, 0, text.length());
        if (target < visibleStart) {
            visibleStart = target;
            return;
        }
        while (visibleStart < target && textRenderer.getWidth(text.substring(visibleStart, target)) > getInnerWidth()) {
            visibleStart++;
        }
    }

    private int clampIndex(int index) {
        return Math.clamp(index, 0, getText().length());
    }
}
