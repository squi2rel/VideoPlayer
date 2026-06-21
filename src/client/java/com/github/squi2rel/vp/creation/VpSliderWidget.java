package com.github.squi2rel.vp.creation;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

import java.util.function.IntConsumer;

class VpSliderWidget extends SliderWidget {
    private final VpUiTheme theme;
    private final IntConsumer onPreview;
    private final IntConsumer onCommit;
    private final TextFormatter messageFormatter;
    private int intValue;
    private boolean clipped;
    private int clipLeft;
    private int clipTop;
    private int clipRight;
    private int clipBottom;

    VpSliderWidget(int x, int y, int width, int height, String label, int value,
                   IntConsumer onPreview, IntConsumer onCommit, VpUiTheme theme) {
        this(x, y, width, height, label, value, onPreview, onCommit, value1 -> Text.literal(label + ": " + value1 + "%"), theme);
    }

    VpSliderWidget(int x, int y, int width, int height, Text label, int value,
                   IntConsumer onPreview, IntConsumer onCommit, VpUiTheme theme) {
        this(x, y, width, height, "", value, onPreview, onCommit, value1 -> label.copy().append(": " + value1 + "%"), theme);
    }

    VpSliderWidget(int x, int y, int width, int height, String label, int value,
                   IntConsumer onPreview, IntConsumer onCommit, TextFormatter messageFormatter, VpUiTheme theme) {
        super(x, y, Math.max(60, width), height, Text.empty(), Math.clamp(value, 0, 100) / 100.0);
        this.theme = theme;
        this.onPreview = onPreview;
        this.onCommit = onCommit;
        this.messageFormatter = messageFormatter;
        this.intValue = Math.clamp(value, 0, 100);
        updateMessage();
    }

    VpSliderWidget clip(int left, int top, int right, int bottom) {
        this.clipped = true;
        this.clipLeft = left;
        this.clipTop = top;
        this.clipRight = right;
        this.clipBottom = bottom;
        return this;
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return super.isMouseOver(mouseX, mouseY) && (!clipped || insideClip(mouseX, mouseY));
    }

    @Override
    public void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
        int fill = VpUiRenderer.darken(theme.nodeBodyColor(), active ? 0.04f : 0.12f);
        int border = isHovered() || isFocused() ? VpUiRenderer.blend(theme.panelBorderColor(), theme.accentColor(), 0.48f) : theme.panelBorderColor();
        if (!active) border = VpUiRenderer.blend(border, theme.canvasBackgroundColor(), 0.45f);
        VpUiRenderer.drawBox(context, getX(), getY(), getWidth(), getHeight(), fill, border);

        int trackX = getX() + 6;
        int trackY = getY() + getHeight() - 6;
        int trackW = Math.max(1, getWidth() - 12);
        int trackColor = VpUiRenderer.blend(theme.panelBorderColor(), theme.canvasBackgroundColor(), 0.20f);
        int fillW = Math.round(trackW * (float) value);
        context.fill(trackX, trackY, trackX + trackW, trackY + 2, trackColor);
        context.fill(trackX, trackY, trackX + fillW, trackY + 2, theme.accentColor());

        int knobX = trackX + Math.clamp(fillW, 0, trackW) - 2;
        context.fill(knobX, trackY - 2, knobX + 4, trackY + 4, theme.primaryTextColor());

        TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
        String text = getMessage().getString();
        String visibleText = textRenderer.getWidth(text) > getWidth() - 8 ? textRenderer.trimToWidth(text, getWidth() - 8) : text;
        int textX = getX() + 4;
        int textY = getY() + 2;
        int textColor = active ? theme.secondaryTextColor() : VpUiRenderer.blend(theme.secondaryTextColor(), theme.canvasBackgroundColor(), 0.45f);
        if (theme.textShadow()) {
            context.drawTextWithShadow(textRenderer, visibleText, textX, textY, textColor);
            return;
        }
        context.drawText(textRenderer, visibleText, textX, textY, textColor, false);
    }

    @Override
    protected void updateMessage() {
        setMessage(messageFormatter.apply(intValue));
    }

    @Override
    protected void applyValue() {
        int next = Math.clamp((int) Math.round(value * 100.0), 0, 100);
        if (next == intValue) return;
        intValue = next;
        updateMessage();
        onPreview.accept(intValue);
    }

    @Override
    public void onRelease(Click click) {
        super.onRelease(click);
        onCommit.accept(intValue);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        boolean handled = super.keyPressed(input);
        if (handled) onCommit.accept(intValue);
        return handled;
    }

    private boolean insideClip(double mouseX, double mouseY) {
        return mouseX >= clipLeft
                && mouseY >= clipTop
                && mouseX < clipRight
                && mouseY < clipBottom;
    }

    @FunctionalInterface
    interface TextFormatter {
        Text apply(int value);
    }
}
