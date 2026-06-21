package com.github.squi2rel.vp.creation;

import com.github.squi2rel.vp.ClientPacketHandler;
import com.github.squi2rel.vp.ClientPermissionCache;
import com.github.squi2rel.vp.i18n.VpTexts;
import com.github.squi2rel.vp.permission.VideoPermissionAction;
import com.github.squi2rel.vp.video.ClientVideoScreen;
import com.github.squi2rel.vp.video.VideoScreen;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class IdlePlayListScreen extends Screen {
    private static final int GAP = 8;
    private static final int CONTROL_HEIGHT = 18;
    private static final int ROW_HEIGHT = 20;
    private static final int LABEL_OFFSET = 11;
    private static final VpUiTheme THEME = VpUiTheme.classic();

    private final Screen parent;
    private final ClientVideoScreen screen;
    private TextFieldWidget urlField;
    private String urlDraft = "";
    private int listScroll;
    private int listTop;
    private int listBottom;
    private int listX;
    private int listW;

    public IdlePlayListScreen(Screen parent, ClientVideoScreen screen) {
        this(parent, screen, "", 0);
    }

    private IdlePlayListScreen(Screen parent, ClientVideoScreen screen, String urlDraft, int listScroll) {
        super(VpTexts.tr("screen.videoplayer.idle_play_list", "Idle Play List"));
        this.parent = parent;
        this.screen = screen;
        this.urlDraft = urlDraft == null ? "" : urlDraft;
        this.listScroll = Math.max(0, listScroll);
    }

    @Override
    protected void init() {
        computeLayout();
        int x = listX;
        int contentW = listW;
        int row = 54;

        int addW = 64;
        int urlW = Math.max(80, contentW - addW - GAP);
        urlField = new VpTextFieldWidget(textRenderer, x, row, urlW, CONTROL_HEIGHT, Text.empty(), THEME);
        urlField.setMaxLength(VideoScreen.MAX_IDLE_PLAY_URL_LENGTH);
        urlField.setText(urlDraft);
        addDrawableChild(urlField);
        VpButtonWidget add = button(VpTexts.tr("button.videoplayer.add", "Add"), x + urlW + GAP, row, addW, this::addIdlePlayUrl);
        add.active = canEditIdlePlay();

        row += 28;
        int modeW = Math.max(80, (contentW - GAP) / 2);
        VpButtonWidget mode = button(VpTexts.tr("label.videoplayer.mode_value", "Mode: %s",
                        (screen == null || !screen.idlePlayRandom
                                ? VpTexts.tr("label.videoplayer.sequential", "Sequential")
                                : VpTexts.tr("label.videoplayer.random", "Random")).getString()),
                x, row, modeW, this::toggleIdlePlayMode)
                .selected(screen != null && screen.idlePlayRandom);
        mode.active = canEditIdlePlay();
        VpButtonWidget clear = button(VpTexts.tr("button.videoplayer.clear", "Clear"), x + modeW + GAP, row, modeW, this::clearIdlePlay).danger(true);
        clear.active = canEditIdlePlay() && !screen.idlePlayUrls.isEmpty();

        int closeW = 72;
        button(VpTexts.tr("button.videoplayer.close", "Close"), x + Math.max(0, contentW - closeW), Math.max(108, height - 40), closeW, this::close);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        if (client != null) {
            client.setScreen(parent);
        }
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, VpUiRenderer.withAlpha(THEME.canvasBackgroundColor(), 0xCC));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        computeLayout();
        renderBackground(context, mouseX, mouseY, delta);
        int panelX = 18;
        int panelY = 18;
        int panelW = Math.max(260, width - 36);
        int panelH = Math.max(120, height - 36);
        VpUiRenderer.drawBox(context, panelX, panelY, panelW, panelH, THEME.panelBackgroundColor(), THEME.panelBorderColor());

        drawCenteredText(context, title, width / 2, 28, THEME.primaryTextColor());
        drawLabel(context, "URL", listX, 54 - LABEL_OFFSET, THEME.secondaryTextColor());
        drawLabel(context, VpTexts.tr("label.videoplayer.play_mode", "Play Mode"), listX, 82 - LABEL_OFFSET, THEME.secondaryTextColor());
        drawLabel(context, VpTexts.tr("label.videoplayer.idle_play_list", "Idle Play List"), listX, listTop - LABEL_OFFSET, THEME.secondaryTextColor());

        super.render(context, mouseX, mouseY, delta);
        drawIdleList(context, mouseX, mouseY);
        drawScrollbar(context);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        if (click.button() == 0 && clickListDelete(click.x(), click.y())) {
            return true;
        }
        return super.mouseClicked(click, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (!inside(mouseX, mouseY, listX, listTop, listX + listW, listBottom)) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }
        int delta = (int) Math.round(-verticalAmount * ROW_HEIGHT);
        int next = Math.clamp(listScroll + delta, 0, maxListScroll());
        if (next == listScroll) {
            return false;
        }
        if (urlField != null) {
            urlDraft = urlField.getText();
        }
        listScroll = next;
        return true;
    }

    private void computeLayout() {
        int panelX = 18;
        int panelW = Math.max(260, width - 36);
        listX = panelX + 12;
        listW = Math.max(120, panelW - 24);
        listTop = 122;
        listBottom = Math.max(listTop + ROW_HEIGHT, height - 58);
        listScroll = Math.clamp(listScroll, 0, maxListScroll());
    }

    private void drawIdleList(DrawContext context, int mouseX, int mouseY) {
        VpUiRenderer.drawBox(context, listX, listTop, listW, listBottom - listTop, VpUiRenderer.darken(THEME.nodeBodyColor(), 0.06f), THEME.panelBorderColor());
        context.enableScissor(listX + 1, listTop + 1, listX + listW - 1, listBottom - 1);
        if (screen == null || screen.idlePlayUrls.isEmpty()) {
            drawLabel(context, VpTexts.tr("message.videoplayer.idle_play_empty", "Idle list is empty"), listX + 8, listTop + 8, THEME.secondaryTextColor());
            context.disableScissor();
            return;
        }

        int removeW = 24;
        int textW = Math.max(40, listW - removeW - 24);
        for (int i = 0; i < screen.idlePlayUrls.size(); i++) {
            int rowY = listTop + 4 + i * ROW_HEIGHT - listScroll;
            if (rowY + ROW_HEIGHT < listTop || rowY > listBottom) {
                continue;
            }
            int fill = i % 2 == 0 ? VpUiRenderer.withAlpha(THEME.nodeBodyColor(), 0x80) : VpUiRenderer.withAlpha(THEME.nodeHeaderColor(), 0x66);
            context.fill(listX + 4, rowY - 1, listX + listW - 4, rowY + ROW_HEIGHT - 2, fill);
            drawLabel(context, (i + 1) + ". " + trimToWidth(screen.idlePlayUrls.get(i), textW), listX + 8, rowY + 4, THEME.secondaryTextColor());
            drawRemoveButton(context, deleteButtonX(), rowY, removeW, canEditIdlePlay(), inside(mouseX, mouseY, deleteButtonX(), rowY, deleteButtonX() + removeW, rowY + CONTROL_HEIGHT));
        }
        context.disableScissor();
    }

    private void drawRemoveButton(DrawContext context, int x, int y, int width, boolean active, boolean hovered) {
        int fill = VpUiRenderer.darken(THEME.nodeBodyColor(), 0.04f);
        if (hovered && active) {
            fill = VpUiRenderer.blend(fill, THEME.errorColor(), 0.12f);
        }
        int border = active && hovered ? THEME.errorColor() : THEME.panelBorderColor();
        int text = active ? (hovered ? THEME.primaryTextColor() : THEME.secondaryTextColor()) : VpUiRenderer.blend(THEME.secondaryTextColor(), THEME.canvasBackgroundColor(), 0.45f);
        VpUiRenderer.drawBox(context, x, y, width, CONTROL_HEIGHT, fill, border);
        drawCenteredText(context, Text.literal("-"), x + width / 2, y + 5, text);
    }

    private boolean clickListDelete(double mouseX, double mouseY) {
        if (!canEditIdlePlay() || screen.idlePlayUrls.isEmpty()) {
            return false;
        }
        if (!inside(mouseX, mouseY, deleteButtonX(), listTop + 4, deleteButtonX() + 24, listBottom)) {
            return false;
        }
        double localY = mouseY - listTop - 4 + listScroll;
        if (localY < 0) {
            return false;
        }
        int index = (int) (localY / ROW_HEIGHT);
        if (index < 0 || index >= screen.idlePlayUrls.size()) {
            return false;
        }
        int rowY = listTop + 4 + index * ROW_HEIGHT - listScroll;
        if (!inside(mouseX, mouseY, deleteButtonX(), rowY, deleteButtonX() + 24, rowY + CONTROL_HEIGHT)) {
            return false;
        }
        removeIdlePlayUrl(index);
        return true;
    }

    private void drawScrollbar(DrawContext context) {
        int contentHeight = listContentHeight();
        int viewportHeight = listBottom - listTop;
        int maxScroll = Math.max(0, contentHeight - viewportHeight);
        if (maxScroll <= 0) {
            return;
        }
        int x = listX + listW - 5;
        int trackColor = VpUiRenderer.withAlpha(VpUiRenderer.blend(THEME.panelBorderColor(), THEME.panelBackgroundColor(), 0.55f), 0x88);
        int thumbColor = VpUiRenderer.withAlpha(VpUiRenderer.blend(THEME.secondaryTextColor(), THEME.accentColor(), 0.35f), 0xDD);
        int thumbHeight = Math.max(14, viewportHeight * viewportHeight / Math.max(viewportHeight, contentHeight));
        int thumbTravel = Math.max(1, viewportHeight - thumbHeight);
        int thumbY = listTop + thumbTravel * Math.clamp(listScroll, 0, maxScroll) / maxScroll;
        VpUiRenderer.drawBox(context, x, listTop, 4, viewportHeight, trackColor, trackColor);
        VpUiRenderer.drawBox(context, x, thumbY, 4, thumbHeight, thumbColor, thumbColor);
    }

    private int deleteButtonX() {
        return listX + listW - 34;
    }

    private int maxListScroll() {
        return Math.max(0, listContentHeight() - Math.max(1, listBottom - listTop));
    }

    private int listContentHeight() {
        return screen == null || screen.idlePlayUrls.isEmpty() ? ROW_HEIGHT : screen.idlePlayUrls.size() * ROW_HEIGHT + 8;
    }

    private void addIdlePlayUrl(VpButtonWidget button) {
        if (screen == null || urlField == null) return;
        String url = urlField.getText().trim();
        if (url.isEmpty()) {
            sendLocalError(VpTexts.tr("error.videoplayer.idle_play_url_empty", "IdlePlay URL must not be empty"));
            return;
        }
        if (screen.idlePlayUrls.size() >= VideoScreen.MAX_IDLE_PLAY_ITEMS) {
            sendLocalError(VpTexts.tr("error.videoplayer.idle_play_too_many", "IdlePlay can contain at most %s entries", VideoScreen.MAX_IDLE_PLAY_ITEMS));
            return;
        }
        ArrayList<String> urls = new ArrayList<>(screen.idlePlayUrls);
        urls.add(url);
        urlDraft = "";
        urlField.setText("");
        sendIdlePlayConfig(urls, screen.idlePlayRandom, button);
    }

    private void removeIdlePlayUrl(int index) {
        if (screen == null || index < 0 || index >= screen.idlePlayUrls.size()) return;
        ArrayList<String> urls = new ArrayList<>(screen.idlePlayUrls);
        urls.remove(index);
        sendIdlePlayConfig(urls, screen.idlePlayRandom, null);
    }

    private void clearIdlePlay(VpButtonWidget button) {
        if (screen == null) return;
        sendIdlePlayConfig(List.of(), screen.idlePlayRandom, button);
    }

    private void toggleIdlePlayMode(VpButtonWidget button) {
        if (screen == null) return;
        sendIdlePlayConfig(screen.idlePlayUrls, !screen.idlePlayRandom, button);
    }

    private void sendIdlePlayConfig(List<String> urls, boolean random, VpButtonWidget button) {
        if (screen == null) return;
        String currentUrl = urlField == null ? urlDraft : urlField.getText();
        ClientPacketHandler.setIdlePlay(screen, urls, random, result -> {
            if (ClientPacketHandler.denied(result) && button != null) button.showPermissionDenied();
        });
        listScroll = Math.clamp(listScroll, 0, maxListScroll());
        if (client != null) {
            client.setScreen(new IdlePlayListScreen(parent, screen, currentUrl, listScroll));
        }
    }

    private void sendLocalError(Text message) {
        if (client != null && client.player != null) {
            client.player.sendMessage(message.copy().formatted(Formatting.RED), false);
        }
    }

    private VpButtonWidget button(Text label, int x, int y, int width, Runnable action) {
        VpButtonWidget button = new VpButtonWidget(x, y, Math.max(34, width), CONTROL_HEIGHT, label, b -> action.run(), THEME);
        addDrawableChild(button);
        return button;
    }

    private VpButtonWidget button(Text label, int x, int y, int width, Consumer<VpButtonWidget> action) {
        VpButtonWidget button = new VpButtonWidget(x, y, Math.max(34, width), CONTROL_HEIGHT, label, action, THEME);
        addDrawableChild(button);
        return button;
    }

    private VpButtonWidget button(String label, int x, int y, int width, Runnable action) {
        VpButtonWidget button = new VpButtonWidget(x, y, Math.max(34, width), CONTROL_HEIGHT, Text.literal(label), b -> action.run(), THEME);
        addDrawableChild(button);
        return button;
    }

    private VpButtonWidget button(String label, int x, int y, int width, Consumer<VpButtonWidget> action) {
        VpButtonWidget button = new VpButtonWidget(x, y, Math.max(34, width), CONTROL_HEIGHT, Text.literal(label), action, THEME);
        addDrawableChild(button);
        return button;
    }

    private boolean canEditIdlePlay() {
        return screen != null && ClientPermissionCache.allowedOrUnknown(VideoPermissionAction.SET_IDLE_PLAY, screen);
    }

    private void drawLabel(DrawContext context, String label, int x, int y, int color) {
        drawLabel(context, Text.literal(label), x, y, color);
    }

    private void drawLabel(DrawContext context, Text label, int x, int y, int color) {
        if (THEME.textShadow()) {
            context.drawTextWithShadow(textRenderer, label, x, y, color);
            return;
        }
        context.drawText(textRenderer, label, x, y, color, false);
    }

    private void drawCenteredText(DrawContext context, Text text, int centerX, int y, int color) {
        int x = centerX - textRenderer.getWidth(text) / 2;
        drawLabel(context, text, x, y, color);
    }

    private String trimToWidth(String text, int maxWidth) {
        String value = text == null ? "" : text;
        if (textRenderer.getWidth(value) <= maxWidth) return value;
        String suffix = "...";
        return textRenderer.trimToWidth(value, Math.max(0, maxWidth - textRenderer.getWidth(suffix))) + suffix;
    }

    private boolean inside(double mouseX, double mouseY, int left, int top, int right, int bottom) {
        return mouseX >= left && mouseY >= top && mouseX < right && mouseY < bottom;
    }
}
