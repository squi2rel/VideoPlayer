package com.github.squi2rel.vp.creation;

import com.github.squi2rel.vp.i18n.VpTexts;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;

import java.util.function.LongConsumer;
import java.util.function.Supplier;

class VpProgressSliderWidget extends SliderWidget {
    private final Supplier<ProgressState> source;
    private final LongConsumer onPreview;
    private final LongConsumer onCommit;
    private final Runnable onDragStart;
    private final Runnable onDragEnd;
    private final VpUiTheme theme;
    private ProgressState state = ProgressState.disabled();
    private boolean dragging;
    private long dragProgress;

    VpProgressSliderWidget(int x, int y, int width, int height, Supplier<ProgressState> source,
                           LongConsumer onPreview, LongConsumer onCommit,
                           Runnable onDragStart, Runnable onDragEnd, VpUiTheme theme) {
        super(x, y, Math.max(80, width), height, Text.empty(), 0.0);
        this.source = source;
        this.onPreview = onPreview;
        this.onCommit = onCommit;
        this.onDragStart = onDragStart;
        this.onDragEnd = onDragEnd;
        this.theme = theme;
        updateState();
        updateMessage();
    }

    boolean dragging() {
        return dragging;
    }

    long dragProgress() {
        return dragProgress;
    }

    boolean containsPoint(double mouseX, double mouseY) {
        return visible
                && mouseX >= getX()
                && mouseY >= getY()
                && mouseX < getX() + getWidth()
                && mouseY < getY() + getHeight();
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        updateState();
        if (!active || click.button() != 0 || !isMouseOver(click.x(), click.y())) {
            return false;
        }
        dragging = true;
        onDragStart.run();
        boolean handled = super.mouseClicked(click, doubleClick);
        if (!handled) {
            dragging = false;
            onDragEnd.run();
            return false;
        }
        previewCurrentValue();
        return true;
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        if (!dragging || !active || click.button() != 0) {
            return false;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public void onRelease(Click click) {
        boolean wasDragging = dragging;
        super.onRelease(click);
        if (!wasDragging) return;
        dragging = false;
        onCommit.accept(dragProgress);
        onDragEnd.run();
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        updateState();
        if (!active) return false;
        boolean handled = super.keyPressed(input);
        if (handled) {
            previewCurrentValue();
            onCommit.accept(dragProgress);
        }
        return handled;
    }

    @Override
    protected void applyValue() {
        previewCurrentValue();
    }

    @Override
    protected void updateMessage() {
        if (!state.available) {
            setMessage(VpTexts.tr("label.videoplayer.not_adjustable", "Not adjustable"));
            return;
        }
        if (!state.seekable) {
            setMessage(Text.literal(formatDuration(state.total, state.total)));
            return;
        }
        long progress = dragging ? dragProgress : state.progress;
        setMessage(Text.literal(formatDuration(progress, state.total) + "/" + formatDuration(state.total, state.total)));
    }

    @Override
    public void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
        updateState();
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
        context.fill(trackX, trackY, trackX + fillW, trackY + 2, active ? theme.accentColor() : VpUiRenderer.blend(theme.accentColor(), theme.canvasBackgroundColor(), 0.55f));

        int knobX = trackX + Math.clamp(fillW, 0, trackW) - 2;
        context.fill(knobX, trackY - 2, knobX + 4, trackY + 4, active ? theme.primaryTextColor() : VpUiRenderer.blend(theme.primaryTextColor(), theme.canvasBackgroundColor(), 0.55f));

        TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
        int textColor = state.available ? theme.secondaryTextColor() : VpUiRenderer.blend(theme.secondaryTextColor(), theme.canvasBackgroundColor(), 0.45f);
        drawProgressText(context, textRenderer, textColor);
    }

    private void updateState() {
        state = source.get();
        active = state.seekable;
        if (!dragging) {
            value = !state.available ? 0 : !state.seekable ? 1 : Math.clamp((double) state.progress / (double) state.total, 0.0, 1.0);
            dragProgress = state.progress;
        }
        updateMessage();
    }

    private void previewCurrentValue() {
        if (!state.seekable || state.total <= 0) return;
        dragProgress = Math.clamp(Math.round(value * state.total), 0, state.total);
        updateMessage();
        onPreview.accept(dragProgress);
    }

    private void drawProgressText(DrawContext context, TextRenderer textRenderer, int textColor) {
        if (!state.available) {
            drawText(context, textRenderer, getMessage().getString(), getX() + 4, textColor);
            return;
        }

        String totalText = trimText(textRenderer, formatDuration(state.total, state.total), getWidth() - 8);
        int totalX = getX() + getWidth() - 4 - textRenderer.getWidth(totalText);
        drawText(context, textRenderer, totalText, totalX, textColor);
        if (!state.seekable) return;

        String progressText = formatDuration(dragging ? dragProgress : state.progress, state.total);
        int maxProgressWidth = Math.max(0, totalX - getX() - 8);
        String visibleProgressText = trimText(textRenderer, progressText, maxProgressWidth);
        drawText(context, textRenderer, visibleProgressText, getX() + 4, textColor);
    }

    private void drawText(DrawContext context, TextRenderer textRenderer, String text, int x, int color) {
        if (text == null || text.isEmpty()) return;
        if (theme.textShadow()) {
            context.drawTextWithShadow(textRenderer, text, x, getY() + 2, color);
            return;
        }
        context.drawText(textRenderer, text, x, getY() + 2, color, false);
    }

    private static String trimText(TextRenderer textRenderer, String text, int maxWidth) {
        if (maxWidth <= 0) return "";
        return textRenderer.getWidth(text) > maxWidth ? textRenderer.trimToWidth(text, maxWidth) : text;
    }

    private static String formatDuration(long millis, long totalMillis) {
        long safeMillis = Math.max(0, millis);
        long totalSeconds = safeMillis / 1000;
        long seconds = totalSeconds % 60;
        long minutes = (totalSeconds / 60) % 60;
        long hours = totalSeconds / 3600;
        boolean showHours = hours > 0 || totalMillis >= 3_600_000L;
        if (showHours) {
            return "%d:%02d:%02d".formatted(hours, minutes, seconds);
        }
        return "%d:%02d".formatted(minutes, seconds);
    }

    record ProgressState(boolean available, boolean seekable, long progress, long total) {
        static ProgressState disabled() {
            return new ProgressState(false, false, 0, 0);
        }

        static ProgressState of(long progress, long total) {
            long safeTotal = Math.max(0, total);
            if (safeTotal <= 0) return disabled();
            return new ProgressState(true, true, Math.clamp(progress, 0, safeTotal), safeTotal);
        }

        static ProgressState readonly(long total) {
            long safeTotal = Math.max(0, total);
            if (safeTotal <= 0) return disabled();
            return new ProgressState(true, false, safeTotal, safeTotal);
        }
    }
}
