package com.github.squi2rel.vp.creation;

import com.github.squi2rel.vp.i18n.VpTexts;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;

import java.util.function.Consumer;

class VpButtonWidget extends ClickableWidget {
    private final VpUiTheme theme;
    private final Consumer<VpButtonWidget> onPress;
    private boolean selected;
    private boolean danger;
    private boolean clipped;
    private int clipLeft;
    private int clipTop;
    private int clipRight;
    private int clipBottom;
    private Text temporaryMessage;
    private long temporaryMessageUntil;

    VpButtonWidget(int x, int y, int width, int height, Text message, Consumer<VpButtonWidget> onPress, VpUiTheme theme) {
        super(x, y, width, height, message);
        this.theme = theme;
        this.onPress = onPress;
    }

    VpButtonWidget selected(boolean selected) {
        this.selected = selected;
        return this;
    }

    VpButtonWidget danger(boolean danger) {
        this.danger = danger;
        return this;
    }

    VpButtonWidget clip(int left, int top, int right, int bottom) {
        this.clipped = true;
        this.clipLeft = left;
        this.clipTop = top;
        this.clipRight = right;
        this.clipBottom = bottom;
        return this;
    }

    void showTemporaryLabel(String label, long millis) {
        temporaryMessage = Text.literal(label == null ? "" : label);
        temporaryMessageUntil = System.currentTimeMillis() + Math.max(0, millis);
    }

    void showTemporaryLabel(Text label, long millis) {
        temporaryMessage = label == null ? Text.empty() : label;
        temporaryMessageUntil = System.currentTimeMillis() + Math.max(0, millis);
    }

    void showPermissionDenied() {
        showTemporaryLabel(VpTexts.tr("error.videoplayer.permission_denied", "Permission denied"), 1500L);
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return super.isMouseOver(mouseX, mouseY) && (!clipped || insideClip(mouseX, mouseY));
    }

    @Override
    protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
        int fill = fillColor();
        int border = borderColor();
        int textColor = textColor();
        VpUiRenderer.drawBox(context, getX(), getY(), getWidth(), getHeight(), fill, border);
        TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
        drawButtonText(context, textRenderer, textColor);
    }

    @Override
    public void onClick(Click click, boolean doubleClick) {
        onPress.accept(this);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (!active || !visible || !input.isEnterOrSpace()) return false;
        onPress.accept(this);
        return true;
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        appendDefaultNarrations(builder);
    }

    private void drawButtonText(DrawContext context, TextRenderer textRenderer, int color) {
        int left = getX() + 4;
        int right = getRight() - 4;
        int innerWidth = Math.max(1, right - left);
        String label = displayMessage().getString();
        String visibleLabel = textRenderer.getWidth(label) > innerWidth ? textRenderer.trimToWidth(label, innerWidth) : label;
        Text visibleText = Text.literal(visibleLabel);
        int textWidth = textRenderer.getWidth(visibleLabel);
        int textX = left + Math.max(0, (innerWidth - textWidth) / 2);
        int textY = getY() + Math.max(1, (getHeight() - textRenderer.fontHeight) / 2);
        if (theme.textShadow()) {
            context.drawTextWithShadow(textRenderer, visibleText, textX, textY, color);
            return;
        }
        context.drawText(textRenderer, visibleText, textX, textY, color, false);
    }

    private int fillColor() {
        int base = VpUiRenderer.darken(theme.nodeBodyColor(), 0.04f);
        if (danger && (selected || isHovered())) {
            return VpUiRenderer.blend(base, theme.errorColor(), selected ? 0.22f : 0.12f);
        }
        if (selected) {
            return VpUiRenderer.blend(base, theme.accentColor(), 0.20f);
        }
        if (!active) {
            return VpUiRenderer.blend(base, theme.canvasBackgroundColor(), 0.36f);
        }
        if (isHovered()) {
            return VpUiRenderer.blend(base, theme.accentColor(), 0.11f);
        }
        return base;
    }

    private Text displayMessage() {
        if (temporaryMessage != null && System.currentTimeMillis() < temporaryMessageUntil) {
            return temporaryMessage;
        }
        temporaryMessage = null;
        return getMessage();
    }

    private int borderColor() {
        if (danger && (selected || isHovered())) return theme.errorColor();
        if (selected) return theme.accentColor();
        if (!active) return VpUiRenderer.blend(theme.panelBorderColor(), theme.canvasBackgroundColor(), 0.45f);
        if (isHovered()) return VpUiRenderer.blend(theme.panelBorderColor(), theme.accentColor(), 0.48f);
        return theme.panelBorderColor();
    }

    private int textColor() {
        if (danger && selected) return theme.errorColor();
        if (selected) return theme.primaryTextColor();
        if (!active) return VpUiRenderer.blend(theme.secondaryTextColor(), theme.canvasBackgroundColor(), 0.45f);
        return isHovered() ? theme.primaryTextColor() : theme.secondaryTextColor();
    }

    private boolean insideClip(double mouseX, double mouseY) {
        return mouseX >= clipLeft
                && mouseY >= clipTop
                && mouseX < clipRight
                && mouseY < clipBottom;
    }
}
