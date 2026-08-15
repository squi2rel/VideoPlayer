package com.github.squi2rel.vp.creation;

import com.github.squi2rel.vp.NativeDownloadConfig;
import com.github.squi2rel.vp.NativePackageManager;
import com.github.squi2rel.vp.VideoPlayerClient;
import com.github.squi2rel.vp.VideoPlayerMain;
import com.github.squi2rel.vp.YtDlpManager;
import com.github.squi2rel.vp.i18n.VpTexts;
import com.github.squi2rel.vp.i18n.VpTranslation;
import com.github.squi2rel.vp.i18n.VpTranslations;
import com.github.squi2rel.vp.video.AudioChannelMode;
import com.github.squi2rel.vp.video.MpvVideoBackend;
import com.github.squi2rel.vp.video.VideoBackends;
import com.github.squi2rel.vp.video.VlcDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class StartupGuideScreen extends Screen {
    private enum BackendRefreshResult {
        AVAILABLE,
        RETRY_SUCCEEDED,
        RETRY_FAILED,
        RESTART_REQUIRED
    }

    private record InstallationState(String vlcPlatform, boolean vlcInstalled, String mpvPlatform, boolean mpvInstalled) {
    }

    private record AvailabilityState(boolean vlcAvailable, boolean mpvAvailable) {
    }

    private static final VpUiTheme THEME = VpUiTheme.classic();
    private static final int PANEL_WIDTH = 460;
    private static final int PANEL_HEIGHT = 286;
    private static final int MIN_PANEL_HEIGHT = 168;
    private static final int CONTROL_HEIGHT = 18;
    private static final int ROW_HEIGHT = 52;
    private static final int YTDLP_START_Y = 62;
    private static final int BACKEND_START_Y = YTDLP_START_Y + ROW_HEIGHT;
    private static final int BACKEND_BUTTON_COUNT = 4;
    private static final int GAP = 8;
    private static final int SCROLL_STEP = 24;
    private static final Map<String, CompletableFuture<NativePackageManager.DownloadResult>> NATIVE_DOWNLOAD_TASKS = new ConcurrentHashMap<>();

    private final Screen parent;
    private final Map<String, Integer> browserIndex = new HashMap<>();

    private VpButtonWidget vlcDownload;
    private VpButtonWidget vlcCopyLink;
    private VpButtonWidget vlcPlatform;
    private VpButtonWidget vlcSelect;
    private VpButtonWidget mpvDownload;
    private VpButtonWidget mpvCopyLink;
    private VpButtonWidget mpvPlatform;
    private VpButtonWidget mpvSelect;
    private VpButtonWidget ytdlpDownload;
    private VpButtonWidget ytdlpCopyLink;
    private VpButtonWidget ytdlpPlatform;
    private VpButtonWidget audioChannelMode;
    private VpButtonWidget done;
    private VpButtonWidget skip;
    private VpTextFieldWidget proxyField;
    private VpTextFieldWidget ytdlPathField;

    private CompletableFuture<NativePackageManager.DownloadResult> downloadTask;
    private CompletableFuture<YtDlpManager.Detection> ytdlpDetectionTask;
    private CompletableFuture<InstallationState> installationStateTask;
    private CompletableFuture<AvailabilityState> availabilityTask;
    private String activeBackend = "";
    private VpTranslation status = VpTranslation.EMPTY;
    private int sourceIndex;
    private int sourceCount;
    private String sourceName = "";
    private long bytesRead;
    private long totalBytes;
    private boolean vlcAvailable;
    private boolean mpvAvailable;
    private boolean vlcInstalled;
    private boolean mpvInstalled;
    private boolean ytdlpAvailable;
    private String ytdlpVersion = "";
    private String selectedYtdlpPlatform = NativeDownloadConfig.platformKey();
    private String selectedVlcPlatform = NativeDownloadConfig.platformKey();
    private String selectedMpvPlatform = NativeDownloadConfig.platformKey();
    private int contentScroll;
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;
    private int contentLeft;
    private int contentTop;
    private int contentRight;
    private int contentBottom;
    private int contentHeight;

    public StartupGuideScreen(Screen parent) {
        super(VpTexts.tr("screen.videoplayer.startup_guide", "VideoPlayer Guide"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        syncSelectedPlatformsWithConfig();
        computeLayout();
        int buttonW = buttonWidth();
        int buttonX = buttonGroupX();

        InputRowLayout inputRow = inputRowLayout();
        proxyField = new VpTextFieldWidget(font, inputRow.proxyFieldX(), contentTop + 4, inputRow.proxyFieldWidth(), CONTROL_HEIGHT,
                VpTexts.tr("label.videoplayer.proxy", "Proxy"), THEME);
        proxyField.setMaxLength(220);
        proxyField.setValue(currentProxy());
        ytdlPathField = new VpTextFieldWidget(font, inputRow.ytdlFieldX(), contentTop + 4, inputRow.ytdlFieldWidth(), CONTROL_HEIGHT,
                VpTexts.tr("label.videoplayer.ytdl_path", "yt-dlp"), THEME);
        ytdlPathField.setMaxLength(4096);
        ytdlPathField.setValue(currentYtdlPath());

        audioChannelMode = button("", contentRight - audioChannelModeButtonWidth(), contentTop + 37,
                audioChannelModeButtonWidth(), this::cycleAudioChannelMode);

        ytdlpPlatform = button("", buttonX, contentTop + YTDLP_START_Y, buttonW, () -> {});
        ytdlpDownload = button(VpTexts.tr("button.videoplayer.download", "Download"), buttonX + (buttonW + GAP) * 2,
                contentTop + YTDLP_START_Y, buttonW, this::startYtdlpDownload);
        ytdlpCopyLink = button(VpTexts.tr("button.videoplayer.copy_link", "Copy Link"), buttonX + (buttonW + GAP) * 3,
                contentTop + YTDLP_START_Y, buttonW, this::copyYtdlpSourceLink);
        mpvPlatform = button("", buttonX, contentTop + BACKEND_START_Y, buttonW, () -> cyclePlatform(VideoBackends.MPV));
        mpvSelect = button(VpTexts.tr("button.videoplayer.select", "Select"), buttonX + buttonW + GAP, contentTop + BACKEND_START_Y, buttonW, () -> selectBackend(VideoBackends.MPV));
        mpvDownload = button(VpTexts.tr("button.videoplayer.download", "Download"), buttonX + (buttonW + GAP) * 2, contentTop + BACKEND_START_Y, buttonW, () -> startDownload(VideoBackends.MPV));
        mpvCopyLink = button(VpTexts.tr("button.videoplayer.copy_link", "Copy Link"), buttonX + (buttonW + GAP) * 3, contentTop + BACKEND_START_Y, buttonW, () -> copySourceLink(VideoBackends.MPV));
        vlcPlatform = button("", buttonX, contentTop + vlcStartY(), buttonW, () -> cyclePlatform(VideoBackends.VLC));
        vlcSelect = button(VpTexts.tr("button.videoplayer.select", "Select"), buttonX + buttonW + GAP, contentTop + vlcStartY(), buttonW, () -> selectBackend(VideoBackends.VLC));
        vlcDownload = button(VpTexts.tr("button.videoplayer.download", "Download"), buttonX + (buttonW + GAP) * 2, contentTop + vlcStartY(), buttonW, () -> startDownload(VideoBackends.VLC));
        vlcCopyLink = button(VpTexts.tr("button.videoplayer.copy_link", "Copy Link"), buttonX + (buttonW + GAP) * 3, contentTop + vlcStartY(), buttonW, () -> copySourceLink(VideoBackends.VLC));

        int footerY = panelTop + panelHeight - 28;
        skip = button(VpTexts.tr("button.videoplayer.skip", "Skip"), panelLeft + 24, footerY, 92, this::finish);
        done = button(VpTexts.tr("button.videoplayer.done", "Done"), panelLeft + panelWidth - 116, footerY, 92, this::finish);

        addRenderableWidget(proxyField);
        addRenderableWidget(ytdlPathField);
        addRenderableWidget(audioChannelMode);
        addRenderableWidget(ytdlpPlatform);
        addRenderableWidget(ytdlpDownload);
        addRenderableWidget(ytdlpCopyLink);
        addRenderableWidget(mpvPlatform);
        addRenderableWidget(mpvSelect);
        addRenderableWidget(mpvDownload);
        addRenderableWidget(mpvCopyLink);
        addRenderableWidget(vlcPlatform);
        addRenderableWidget(vlcSelect);
        addRenderableWidget(vlcDownload);
        addRenderableWidget(vlcCopyLink);
        addRenderableWidget(skip);
        addRenderableWidget(done);

        setMpvVisible(!VideoPlayerMain.android);
        layoutWidgets();
        refreshAvailability();
        refreshInstallationState();
        refreshYtdlpAvailability();
        syncButtons();
    }

    @Override
    public void tick() {
        syncButtons();
    }

    @Override
    public void onClose() {
        finish();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        computeLayout();
        if (!inside(mouseX, mouseY, contentLeft - 6, contentTop - 4, contentRight + 6, contentBottom + 4)) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }
        int next = Math.clamp(contentScroll + (int) Math.round(-verticalAmount * SCROLL_STEP), 0, maxContentScroll());
        if (next == contentScroll) {
            return false;
        }
        contentScroll = next;
        layoutWidgets();
        return true;
    }

    @Override
    public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
        computeLayout();
        layoutWidgets();
        context.fill(0, 0, width, height, 0xB0000000);

        context.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, THEME.panelBackgroundColor());
        context.renderOutline(panelLeft, panelTop, panelWidth, panelHeight, THEME.panelBorderColor());
        drawCenteredText(context, title, width / 2, panelTop + 12, THEME.primaryTextColor());

        VpUiRenderer.drawBox(context, contentLeft - 6, contentTop - 4, contentRight - contentLeft + 12, contentBottom - contentTop + 8,
                VpUiRenderer.darken(THEME.nodeBodyColor(), 0.06f), THEME.panelBorderColor());
        context.enableScissor(contentLeft, contentTop, contentRight, contentBottom);
        drawScrollableContent(context, mouseX, mouseY, delta);
        context.disableScissor();
        drawScrollbar(context);

        Component line = statusLine();
        if (!line.getString().isBlank()) {
            drawText(context, trimToWidth(line, Math.max(40, panelWidth - 48)), panelLeft + 24, panelTop + panelHeight - 42, THEME.secondaryTextColor());
        }

        renderWidget(skip, context, mouseX, mouseY, delta);
        renderWidget(done, context, mouseX, mouseY, delta);
    }

    private VpButtonWidget button(String label, int x, int y, int width, Runnable action) {
        return new VpButtonWidget(x, y, width, CONTROL_HEIGHT, Component.literal(label), ignored -> action.run(), THEME);
    }

    private VpButtonWidget button(Component label, int x, int y, int width, Runnable action) {
        return new VpButtonWidget(x, y, width, CONTROL_HEIGHT, label, ignored -> action.run(), THEME);
    }

    private void computeLayout() {
        panelWidth = Math.min(PANEL_WIDTH, Math.max(220, width - 32));
        panelHeight = Math.min(PANEL_HEIGHT, Math.max(120, height - 24));
        if (height >= MIN_PANEL_HEIGHT + 24) {
            panelHeight = Math.max(MIN_PANEL_HEIGHT, panelHeight);
        }
        panelLeft = (width - panelWidth) / 2;
        panelTop = Math.max(8, (height - panelHeight) / 2);
        contentLeft = panelLeft + 24;
        contentRight = panelLeft + panelWidth - 24;
        contentTop = panelTop + 34;
        contentBottom = Math.max(contentTop + CONTROL_HEIGHT, panelTop + panelHeight - 50);
        contentHeight = vlcStartY() + ROW_HEIGHT + 12;
        contentScroll = Math.clamp(contentScroll, 0, maxContentScroll());
    }

    private int vlcStartY() {
        return BACKEND_START_Y + (VideoPlayerMain.android ? 0 : ROW_HEIGHT);
    }

    private void setMpvVisible(boolean visible) {
        mpvPlatform.visible = visible;
        mpvSelect.visible = visible;
        mpvDownload.visible = visible;
        mpvCopyLink.visible = visible;
    }

    private void layoutWidgets() {
        if (proxyField == null || ytdlPathField == null || audioChannelMode == null || ytdlpPlatform == null || mpvPlatform == null || skip == null || done == null) {
            return;
        }

        int y = contentY();
        int buttonX = buttonGroupX();
        int buttonW = buttonWidth();
        InputRowLayout inputRow = inputRowLayout();
        proxyField.setX(inputRow.proxyFieldX());
        proxyField.setY(y + 4);
        proxyField.clip(contentLeft, contentTop, contentRight, contentBottom);
        ytdlPathField.setX(inputRow.ytdlFieldX());
        ytdlPathField.setY(y + 4);
        ytdlPathField.clip(contentLeft, contentTop, contentRight, contentBottom);

        audioChannelMode.setX(contentRight - audioChannelModeButtonWidth());
        audioChannelMode.setY(y + 37);
        audioChannelMode.clip(contentLeft, contentTop, contentRight, contentBottom);

        ytdlpPlatform.setX(buttonX);
        ytdlpPlatform.setY(y + YTDLP_START_Y);
        ytdlpPlatform.clip(contentLeft, contentTop, contentRight, contentBottom);
        ytdlpDownload.setX(buttonX + (buttonW + GAP) * 2);
        ytdlpDownload.setY(y + YTDLP_START_Y);
        ytdlpDownload.clip(contentLeft, contentTop, contentRight, contentBottom);
        ytdlpCopyLink.setX(buttonX + (buttonW + GAP) * 3);
        ytdlpCopyLink.setY(y + YTDLP_START_Y);
        ytdlpCopyLink.clip(contentLeft, contentTop, contentRight, contentBottom);

        layoutBackendButtons(mpvPlatform, mpvSelect, mpvDownload, mpvCopyLink, buttonX, y + BACKEND_START_Y, buttonW);
        layoutBackendButtons(vlcPlatform, vlcSelect, vlcDownload, vlcCopyLink, buttonX, y + vlcStartY(), buttonW);

        int footerY = panelTop + panelHeight - 28;
        skip.setX(panelLeft + 24);
        skip.setY(footerY);
        done.setX(panelLeft + panelWidth - 116);
        done.setY(footerY);
    }

    private void layoutBackendButtons(VpButtonWidget platform, VpButtonWidget select, VpButtonWidget download, VpButtonWidget copyLink,
                                      int x, int y, int width) {
        platform.setX(x);
        platform.setY(y);
        platform.clip(contentLeft, contentTop, contentRight, contentBottom);
        select.setX(x + width + GAP);
        select.setY(y);
        select.clip(contentLeft, contentTop, contentRight, contentBottom);
        download.setX(x + (width + GAP) * 2);
        download.setY(y);
        download.clip(contentLeft, contentTop, contentRight, contentBottom);
        copyLink.setX(x + (width + GAP) * 3);
        copyLink.setY(y);
        copyLink.clip(contentLeft, contentTop, contentRight, contentBottom);
    }

    private void drawScrollableContent(GuiGraphics context, int mouseX, int mouseY, float delta) {
        int y = contentY();
        InputRowLayout inputRow = inputRowLayout();
        drawText(context, VpTexts.tr("label.videoplayer.proxy_colon", "Proxy:"), contentLeft, y + 5, THEME.secondaryTextColor());
        renderWidget(proxyField, context, mouseX, mouseY, delta);
        drawText(context, VpTexts.tr("label.videoplayer.ytdl_path_colon", "YTDL:"), inputRow.ytdlLabelX(), y + 5, THEME.secondaryTextColor());
        renderWidget(ytdlPathField, context, mouseX, mouseY, delta);

        int infoY = y + 28;
        int infoW = Math.max(40, (contentRight - contentLeft - GAP) / 2);
        drawText(context, trimToWidth(VpTexts.tr("label.videoplayer.system", "System: %s", NativeDownloadConfig.osKey()), infoW), contentLeft, infoY, THEME.secondaryTextColor());
        drawText(context, trimToWidth(VpTexts.tr("label.videoplayer.recommended", "Recommended: %s", platformLabel(NativePackageManager.platformKey())), infoW), contentLeft + infoW + GAP, infoY, THEME.secondaryTextColor());
        int audioButtonWidth = audioChannelModeButtonWidth();
        int backendInfoWidth = Math.max(40, contentRight - contentLeft - audioButtonWidth - GAP);
        drawText(context, trimToWidth(VpTexts.tr("label.videoplayer.current_backend", "Current backend: %s", VideoBackends.normalize(VideoPlayerClient.config.videoBackend)), backendInfoWidth), contentLeft, infoY + 14, THEME.secondaryTextColor());
        renderWidget(audioChannelMode, context, mouseX, mouseY, delta);

        drawYtdlp(context, contentLeft, y + YTDLP_START_Y);
        renderWidget(ytdlpPlatform, context, mouseX, mouseY, delta);
        renderWidget(ytdlpDownload, context, mouseX, mouseY, delta);
        renderWidget(ytdlpCopyLink, context, mouseX, mouseY, delta);

        if (!VideoPlayerMain.android) {
            drawBackend(context, contentLeft, y + BACKEND_START_Y, VideoBackends.MPV, VpTexts.tr("label.videoplayer.mpv_recommended", "MPV Recommended"), mpvAvailable);
            renderWidget(mpvPlatform, context, mouseX, mouseY, delta);
            renderWidget(mpvSelect, context, mouseX, mouseY, delta);
            renderWidget(mpvDownload, context, mouseX, mouseY, delta);
            renderWidget(mpvCopyLink, context, mouseX, mouseY, delta);
        }

        drawBackend(context, contentLeft, y + vlcStartY(), VideoBackends.VLC, Component.literal("VLC"), vlcAvailable);
        renderWidget(vlcPlatform, context, mouseX, mouseY, delta);
        renderWidget(vlcSelect, context, mouseX, mouseY, delta);
        renderWidget(vlcDownload, context, mouseX, mouseY, delta);
        renderWidget(vlcCopyLink, context, mouseX, mouseY, delta);
    }

    private void drawBackend(GuiGraphics context, int x, int y, String backend, Component label, boolean available) {
        int color = available ? THEME.executionColor() : THEME.errorColor();
        int count = sourceCount(backend);
        String platform = selectedPlatform(backend);
        Component installed = backendInstalled(backend)
                ? VpTexts.tr("label.videoplayer.installed", "Installed")
                : VpTexts.tr("label.videoplayer.not_installed", "Not installed");
        Component sources = count <= 0
                ? VpTexts.tr("label.videoplayer.no_sources", "No sources configured")
                : VpTexts.tr("label.videoplayer.source_count", "%s sources", count);
        int textW = Math.max(40, buttonGroupX() - x - GAP);
        Component visibleLabel = trimToWidth(label, Math.max(32, textW - 54));
        int statusX = x + Math.max(52, font.width(visibleLabel) + 8);
        Component availability = available
                ? VpTexts.tr("label.videoplayer.available", "Available")
                : VpTexts.tr("label.videoplayer.unavailable", "Unavailable");
        int platformX = statusX + font.width(availability) + 8;
        drawText(context, visibleLabel, x, y, THEME.primaryTextColor());
        drawText(context, availability, statusX, y, color);
        drawText(context, trimToWidth(platformText(platform), Math.max(24, textW - (platformX - x))), platformX, y, THEME.secondaryTextColor());
        drawText(context, trimToWidth(VpTexts.tr("label.videoplayer.install_source_status", "%s / %s", installed.getString(), sources.getString()), textW), x, y + 16, THEME.secondaryTextColor());
    }

    private boolean backendInstalled(String backend) {
        return VideoBackends.MPV.equals(VideoBackends.normalize(backend)) ? mpvInstalled : vlcInstalled;
    }

    private void drawYtdlp(GuiGraphics context, int x, int y) {
        int color = ytdlpAvailable ? THEME.executionColor() : THEME.errorColor();
        int count = ytdlpSources().size();
        int textW = Math.max(40, buttonGroupX() - x - GAP);
        Component availability = ytdlpDetectionTask != null
                ? VpTexts.tr("label.videoplayer.checking", "Checking")
                : ytdlpAvailable
                ? VpTexts.tr("label.videoplayer.available", "Available")
                : VpTexts.tr("label.videoplayer.unavailable", "Unavailable");
        drawText(context, Component.literal("yt-dlp"), x, y, THEME.primaryTextColor());
        drawText(context, availability, x + 48, y, color);
        Component detail = ytdlpVersion.isBlank()
                ? VpTexts.tr("label.videoplayer.source_count", "%s sources", count)
                : VpTexts.tr("label.videoplayer.ytdlp_version", "Version %s", ytdlpVersion);
        drawText(context, trimToWidth(detail, textW), x, y + 16, THEME.secondaryTextColor());
    }

    private void drawScrollbar(GuiGraphics context) {
        int viewportHeight = contentBottom - contentTop;
        int maxScroll = maxContentScroll();
        if (maxScroll <= 0 || viewportHeight <= 0) {
            return;
        }
        int x = contentRight + 2;
        int thumbHeight = Math.max(14, viewportHeight * viewportHeight / Math.max(viewportHeight, contentHeight));
        int thumbTravel = Math.max(1, viewportHeight - thumbHeight);
        int thumbY = contentTop + thumbTravel * Math.clamp(contentScroll, 0, maxScroll) / maxScroll;
        int trackColor = VpUiRenderer.withAlpha(VpUiRenderer.blend(THEME.panelBorderColor(), THEME.panelBackgroundColor(), 0.55f), 0x88);
        int thumbColor = VpUiRenderer.withAlpha(VpUiRenderer.blend(THEME.secondaryTextColor(), THEME.accentColor(), 0.35f), 0xDD);
        VpUiRenderer.drawBox(context, x, contentTop, 4, viewportHeight, trackColor, trackColor);
        VpUiRenderer.drawBox(context, x, thumbY, 4, thumbHeight, thumbColor, thumbColor);
    }

    private int contentY() {
        return contentTop - contentScroll;
    }

    private int maxContentScroll() {
        return Math.max(0, contentHeight - Math.max(1, contentBottom - contentTop));
    }

    private int buttonGroupX() {
        return contentRight - 8 - buttonWidth() * BACKEND_BUTTON_COUNT - GAP * (BACKEND_BUTTON_COUNT - 1);
    }

    private int buttonWidth() {
        int groupW = Math.min(contentRight - contentLeft - 16, 256);
        return Math.max(34, (groupW - GAP * (BACKEND_BUTTON_COUNT - 1)) / BACKEND_BUTTON_COUNT);
    }

    private int audioChannelModeButtonWidth() {
        return Math.min(132, Math.max(88, (contentRight - contentLeft) / 3));
    }

    private void renderWidget(Renderable widget, GuiGraphics context, int mouseX, int mouseY, float delta) {
        if (widget != null) {
            widget.render(context, mouseX, mouseY, delta);
        }
    }

    private void drawText(GuiGraphics context, Component text, int x, int y, int color) {
        if (THEME.textShadow()) {
            context.drawString(font, text, x, y, color);
            return;
        }
        context.drawString(font, text, x, y, color, false);
    }

    private void drawCenteredText(GuiGraphics context, Component text, int centerX, int y, int color) {
        drawText(context, text, centerX - font.width(text) / 2, y, color);
    }

    private Component trimToWidth(Component text, int maxWidth) {
        return Component.literal(trimToWidth(text.getString(), maxWidth));
    }

    private String trimToWidth(String text, int maxWidth) {
        String value = text == null ? "" : text;
        if (font.width(value) <= maxWidth) return value;
        String suffix = "...";
        return font.plainSubstrByWidth(value, Math.max(0, maxWidth - font.width(suffix))) + suffix;
    }

    private Component statusLine() {
        if (downloadTask == null) return VpTexts.text(status);
        String backend = activeBackend.equals(YtDlpManager.TOOL_NAME)
                ? "yt-dlp"
                : activeBackend.equals(VideoBackends.MPV) ? "MPV" : "VLC";
        String namedSource = sourceName.isBlank() ? "" : " " + sourceName;
        String source = sourceCount <= 0 ? "" : " " + sourceIndex + "/" + sourceCount;
        String progress = "";
        if (totalBytes > 0) {
            long percent = Math.clamp(Math.round(bytesRead * 100.0 / totalBytes), 0, 100);
            progress = " " + percent + "%";
        }
        return VpTexts.tr("message.videoplayer.native.status_line", "%s%s%s%s %s", backend, namedSource, source, progress, VpTexts.text(status).getString());
    }

    private void syncButtons() {
        boolean idle = downloadTask == null;
        setMpvVisible(!VideoPlayerMain.android);
        if (VideoPlayerMain.android) {
            mpvPlatform.active = false;
            mpvSelect.active = false;
            mpvDownload.active = false;
            mpvCopyLink.active = false;
        } else {
            syncBackendButtons(VideoBackends.MPV, mpvPlatform, mpvSelect, mpvDownload, mpvCopyLink, idle);
        }
        syncBackendButtons(VideoBackends.VLC, vlcPlatform, vlcSelect, vlcDownload, vlcCopyLink, idle);
        AudioChannelMode configuredAudioChannelMode = AudioChannelMode.normalize(VideoPlayerClient.config.audioChannelMode);
        boolean audioRestartRequired = configuredAudioChannelMode != VideoPlayerClient.activeAudioChannelMode();
        audioChannelMode.active = idle;
        audioChannelMode.selected(audioRestartRequired);
        audioChannelMode.setMessage(VpTexts.tr(
                audioRestartRequired
                        ? "button.videoplayer.audio_channel_mode_restart_required"
                        : "button.videoplayer.audio_channel_mode",
                audioRestartRequired ? "Restart: %s" : "Audio: %s",
                audioChannelModeLabel(configuredAudioChannelMode).getString()
        ));
        int ytdlpCount = ytdlpSources().size();
        ytdlpPlatform.active = false;
        ytdlpPlatform.selected(false);
        ytdlpPlatform.setMessage(platformText(selectedYtdlpPlatform));
        ytdlpDownload.active = idle && ytdlpCount > 0;
        ytdlpCopyLink.active = idle && ytdlpCount > 0;
        ytdlpDownload.setMessage(ytdlpCount > 0
                ? VpTexts.tr("button.videoplayer.download", "Download")
                : VpTexts.tr("button.videoplayer.not_configured", "Not configured"));
        ytdlpCopyLink.setMessage(ytdlpCount > 1
                ? VpTexts.tr("button.videoplayer.copy_link_index", "Copy %s/%s", browserIndex.getOrDefault(YtDlpManager.TOOL_NAME, 0) + 1, ytdlpCount)
                : VpTexts.tr("button.videoplayer.copy_link", "Copy Link"));
        if (proxyField != null) proxyField.active = idle;
        if (ytdlPathField != null) ytdlPathField.active = idle;
        skip.active = idle;
        done.active = idle;
    }

    private void syncBackendButtons(String backend, VpButtonWidget platform, VpButtonWidget select, VpButtonWidget download, VpButtonWidget copyLink, boolean idle) {
        int count = sourceCount(backend);
        List<String> platforms = platformOptions(backend);
        String selectedPlatform = selectedPlatform(backend);
        boolean selectedBackend = VideoBackends.normalize(backend).equals(VideoBackends.normalize(VideoPlayerClient.config.videoBackend));
        platform.active = idle && platforms.size() > 1;
        platform.selected(!selectedPlatform.equals(NativePackageManager.platformKey()));
        platform.setMessage(platformText(selectedPlatform));
        select.active = idle && !selectedBackend;
        select.selected(selectedBackend);
        select.setMessage(VpTexts.tr("button.videoplayer.select", "Select"));
        download.active = idle && count > 0;
        copyLink.active = idle && count > 0;
        download.setMessage(count > 0 ? VpTexts.tr("button.videoplayer.download", "Download") : VpTexts.tr("button.videoplayer.not_configured", "Not configured"));
        copyLink.setMessage(count > 1
                ? VpTexts.tr("button.videoplayer.copy_link_index", "Copy %s/%s", browserIndex.getOrDefault(backend, 0) + 1, count)
                : VpTexts.tr("button.videoplayer.copy_link", "Copy Link"));
    }

    private void startDownload(String backend) {
        if (downloadTask != null) return;
        if (VideoPlayerMain.android && VideoBackends.MPV.equals(backend)) return;
        String platform = selectedPlatform(backend);
        List<NativeDownloadConfig.DownloadSource> sources = sources(backend);
        if (sources.isEmpty()) return;
        String proxy = persistProxy();
        persistYtdlPath();
        activeBackend = backend;
        status = VpTranslation.of("message.videoplayer.native.prepare_download", "Preparing download");
        sourceIndex = 0;
        sourceCount = sources.size();
        sourceName = "";
        bytesRead = 0;
        totalBytes = -1;

        String taskKey = nativeTaskKey(backend, platform);
        CompletableFuture<NativePackageManager.DownloadResult> sharedTask = NATIVE_DOWNLOAD_TASKS.computeIfAbsent(taskKey,
                ignored -> CompletableFuture.supplyAsync(() -> NativePackageManager.downloadAndInstall(backend, platform, sources, proxy, progress ->
                        Minecraft.getInstance().execute(() -> {
                            sourceIndex = progress.sourceIndex();
                            sourceCount = progress.sourceCount();
                            sourceName = progress.sourceName();
                            bytesRead = progress.bytesRead();
                            totalBytes = progress.totalBytes();
                            status = progress.message();
                        }))));
        downloadTask = sharedTask;
        sharedTask.whenComplete((result, error) -> {
            NATIVE_DOWNLOAD_TASKS.remove(taskKey, sharedTask);
            Minecraft.getInstance().execute(() -> {
                downloadTask = null;
                if (error != null) {
                    status = VpTranslations.from(error, "error.videoplayer.native.download_failed", "Download failed: %s", error.getMessage() == null ? "" : error.getMessage());
                    return;
                }
                status = result.message();
                if (result.success()) {
                    markBackendInstalled(backend, true);
                    BackendRefreshResult refreshResult = refreshBackendAfterRuntimeChange(backend);
                    if (refreshResult == BackendRefreshResult.RESTART_REQUIRED) {
                        status = VpTranslation.of("message.videoplayer.native.restart_required",
                                "%s 运行库已安装。重启 Minecraft 后使用新运行库。", backendName(backend));
                    } else if (refreshResult == BackendRefreshResult.RETRY_FAILED) {
                        status = VpTranslation.of("error.videoplayer.native.load_failed_after_install",
                                "%s 运行库已安装但加载失败。请重启 Minecraft 或检查游戏日志。", backendName(backend));
                    }
                    if (!backendAvailable(VideoPlayerClient.config.videoBackend) && backendAvailable(backend)) {
                        VideoPlayerClient.config.videoBackend = VideoBackends.normalize(backend);
                        VideoPlayerClient.saveConfig();
                    }
                }
                syncButtons();
            });
        });
    }

    private void startYtdlpDownload() {
        if (downloadTask != null) return;
        List<NativeDownloadConfig.DownloadSource> sources = ytdlpSources();
        if (sources.isEmpty()) return;
        String proxy = persistProxy();
        persistYtdlPath();
        activeBackend = YtDlpManager.TOOL_NAME;
        status = VpTranslation.of("message.videoplayer.native.prepare_download", "Preparing download");
        sourceIndex = 0;
        sourceCount = sources.size();
        sourceName = "";
        bytesRead = 0;
        totalBytes = -1;
        NativeDownloadConfig config = nativeDownloads();
        downloadTask = CompletableFuture.supplyAsync(() -> YtDlpManager.downloadAndInstall(config, selectedYtdlpPlatform, proxy, progress ->
                Minecraft.getInstance().execute(() -> {
                    sourceIndex = progress.sourceIndex();
                    sourceCount = progress.sourceCount();
                    sourceName = progress.sourceName();
                    bytesRead = progress.bytesRead();
                    totalBytes = progress.totalBytes();
                    status = progress.message();
                })));
        downloadTask.whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
            downloadTask = null;
            if (error != null) {
                status = VpTranslations.from(error, "error.videoplayer.native.download_failed", "Download failed: %s",
                        error.getMessage() == null ? "" : error.getMessage());
                return;
            }
            status = result.message();
            if (result.success()) {
                ytdlPathField.setValue("");
                VideoPlayerClient.config.mpvYtdlPath = "";
                VideoPlayerClient.saveConfig();
                VideoPlayerClient.applyNativePlatformConfig();
                refreshYtdlpAvailability();
            }
        }));
    }

    private void copySourceLink(String backend) {
        List<NativeDownloadConfig.DownloadSource> sources = sources(backend);
        if (sources.isEmpty()) return;
        int index = Math.floorMod(browserIndex.getOrDefault(backend, 0), sources.size());
        String url = sources.get(index).url;
        browserIndex.put(backend, (index + 1) % sources.size());
        copyLink(url);
    }

    private void copyYtdlpSourceLink() {
        List<NativeDownloadConfig.DownloadSource> sources = ytdlpSources();
        if (sources.isEmpty()) return;
        int index = Math.floorMod(browserIndex.getOrDefault(YtDlpManager.TOOL_NAME, 0), sources.size());
        browserIndex.put(YtDlpManager.TOOL_NAME, (index + 1) % sources.size());
        copyLink(sources.get(index).url);
    }

    private void copyLink(String url) {
        try {
            Minecraft.getInstance().keyboardHandler.setClipboard(url);
            status = VpTranslation.of("message.videoplayer.native.link_copied", "Download link copied");
        } catch (RuntimeException e) {
            status = VpTranslation.of("error.videoplayer.copy_link_failed", "Unable to copy link: %s", e.getMessage());
        }
    }

    private void selectBackend(String backend) {
        if (VideoPlayerClient.config == null) return;
        String normalized = VideoBackends.normalize(backend);
        VideoPlayerClient.config.videoBackend = normalized;
        VideoPlayerClient.saveConfig();
        refreshAvailability();
        if (VideoBackends.MPV.equals(normalized) && !mpvAvailable) {
            status = VpTranslation.of(
                    "message.videoplayer.backend_mpv_unavailable",
                    "MPV is unavailable. Download the MPV runtime below; new videos use VLC until installation finishes."
            );
        } else {
            status = VpTranslation.of("message.videoplayer.backend_set", "Playback backend set to %s. Only newly started videos are affected.", normalized);
        }
        syncButtons();
    }

    private void cycleAudioChannelMode() {
        if (VideoPlayerClient.config == null) return;
        AudioChannelMode configured = AudioChannelMode.normalize(VideoPlayerClient.config.audioChannelMode);
        AudioChannelMode next = configured == AudioChannelMode.STEREO ? AudioChannelMode.AUTO : AudioChannelMode.STEREO;
        VideoPlayerClient.config.audioChannelMode = next.configValue();
        VideoPlayerClient.saveConfig();
        boolean restartRequired = next != VideoPlayerClient.activeAudioChannelMode();
        status = VpTranslation.of(
                restartRequired ? "message.videoplayer.audio_channel_mode_restart_required" : "message.videoplayer.audio_channel_mode_set",
                restartRequired
                        ? "Audio channel mode saved as %s. Restart Minecraft to apply."
                        : "Audio channel mode saved as %s and is already active.",
                audioChannelModeLabel(next).getString()
        );
        syncButtons();
    }

    private Component audioChannelModeLabel(AudioChannelMode mode) {
        return VpTexts.tr(
                "label.videoplayer.audio_channel_mode." + mode.configValue(),
                mode == AudioChannelMode.AUTO ? "Auto" : "Stereo"
        );
    }

    private int sourceCount(String backend) {
        return sources(backend).size();
    }

    private List<NativeDownloadConfig.DownloadSource> sources(String backend) {
        return sources(backend, selectedPlatform(backend));
    }

    private List<NativeDownloadConfig.DownloadSource> sources(String backend, String platform) {
        if (VideoPlayerMain.android && VideoBackends.MPV.equals(backend)) return List.of();
        return nativeDownloads().sources(backend, platform);
    }

    private String currentProxy() {
        if (VideoPlayerClient.config == null || VideoPlayerClient.config.nativeDownloadProxy == null) return "";
        return VideoPlayerClient.config.nativeDownloadProxy;
    }

    private String persistProxy() {
        String proxy = proxyField == null ? currentProxy() : proxyField.getValue().trim();
        if (VideoPlayerClient.config != null && !Objects.equals(VideoPlayerClient.config.nativeDownloadProxy, proxy)) {
            VideoPlayerClient.config.nativeDownloadProxy = proxy;
            VideoPlayerClient.saveConfig();
        }
        return proxy;
    }

    private String currentYtdlPath() {
        if (VideoPlayerClient.config == null || VideoPlayerClient.config.mpvYtdlPath == null) return "";
        String path = VideoPlayerClient.config.mpvYtdlPath.trim();
        return YtDlpManager.isCurrentManagedExecutable(path) ? "" : path;
    }

    private void persistYtdlPath() {
        String path = ytdlPathField == null ? currentYtdlPath() : ytdlPathField.getValue().trim();
        if (YtDlpManager.isCurrentManagedExecutable(path)) path = "";
        if (VideoPlayerClient.config != null && !Objects.equals(VideoPlayerClient.config.mpvYtdlPath, path)) {
            VideoPlayerClient.config.mpvYtdlPath = path;
            VideoPlayerClient.saveConfig();
        }
    }

    private List<String> platformOptions(String backend) {
        if (VideoPlayerMain.android) {
            return VideoBackends.MPV.equals(backend) ? List.of() : List.of(NativeDownloadConfig.ANDROID_ARM64);
        }
        String selected = selectedPlatform(backend);
        String recommended = NativePackageManager.platformKey();
        List<String> result = new ArrayList<>();
        for (String platform : nativeDownloads().platformsForCurrentOs()) {
            if (platform.equals(selected) || platform.equals(recommended) || !sources(backend, platform).isEmpty()) {
                result.add(platform);
            }
        }
        if (!result.contains(recommended)) {
            result.add(0, recommended);
        }
        if (!result.contains(selected)) {
            result.add(0, selected);
        }
        return result;
    }

    private List<NativeDownloadConfig.DownloadSource> ytdlpSources() {
        return nativeDownloads().tool(YtDlpManager.TOOL_NAME).sources(selectedYtdlpPlatform);
    }

    private NativeDownloadConfig nativeDownloads() {
        return VideoPlayerClient.nativeDownloadConfig();
    }

    private void cyclePlatform(String backend) {
        List<String> options = platformOptions(backend);
        if (options.size() <= 1) return;
        int index = options.indexOf(selectedPlatform(backend));
        selectPlatform(backend, options.get(Math.floorMod(index + 1, options.size())));
    }

    private void selectPlatform(String backend, String platform) {
        String normalized = NativeDownloadConfig.normalizePlatformForCurrentOs(platform);
        String previous = selectedPlatform(backend);
        if (VideoBackends.MPV.equals(VideoBackends.normalize(backend))) {
            selectedMpvPlatform = normalized;
            VideoPlayerClient.config.nativeMpvPlatform = normalized;
        } else {
            selectedVlcPlatform = normalized;
            VideoPlayerClient.config.nativeVlcPlatform = normalized;
        }
        VideoPlayerClient.applyNativePlatformConfig();
        VideoPlayerClient.saveConfig();
        browserIndex.remove(backend);
        refreshInstallationState();
        BackendRefreshResult refreshResult = Objects.equals(previous, normalized)
                ? BackendRefreshResult.AVAILABLE
                : refreshBackendAfterRuntimeChange(backend);
        if (refreshResult == BackendRefreshResult.RESTART_REQUIRED) {
            status = VpTranslation.of("message.videoplayer.native.platform_restart_required",
                    "%s platform saved as %s. Restart Minecraft to use it.", backendName(backend), platformLabel(normalized));
        } else if (refreshResult == BackendRefreshResult.RETRY_FAILED) {
            status = VpTranslation.of("error.videoplayer.native.platform_load_failed",
                    "%s platform saved as %s, but the runtime is unavailable.", backendName(backend), platformLabel(normalized));
        } else {
            status = VpTranslation.of("message.videoplayer.native.platform_selected", "%s platform: %s",
                    backendName(backend), platformLabel(normalized));
        }
    }

    private String selectedPlatform(String backend) {
        return VideoBackends.MPV.equals(VideoBackends.normalize(backend)) ? selectedMpvPlatform : selectedVlcPlatform;
    }

    private void syncSelectedPlatformsWithConfig() {
        if (VideoPlayerClient.config == null) return;
        if (VideoPlayerMain.android) {
            selectedVlcPlatform = NativeDownloadConfig.ANDROID_ARM64;
            selectedMpvPlatform = NativeDownloadConfig.ANDROID_ARM64;
        } else {
            selectedVlcPlatform = NativeDownloadConfig.normalizePlatformForCurrentOs(VideoPlayerClient.config.nativeVlcPlatform);
            selectedMpvPlatform = NativeDownloadConfig.normalizePlatformForCurrentOs(VideoPlayerClient.config.nativeMpvPlatform);
        }
        VideoPlayerClient.config.nativeVlcPlatform = selectedVlcPlatform;
        VideoPlayerClient.config.nativeMpvPlatform = selectedMpvPlatform;
        VideoPlayerClient.applyNativePlatformConfig();
    }

    private String platformLabel(String platform) {
        return platformText(platform).getString();
    }

    private Component platformText(String platform) {
        String arch = NativeDownloadConfig.archFromPlatform(platform);
        if (arch.isBlank()) return Component.literal(platform == null ? "" : platform);
        return platform.equals(NativePackageManager.platformKey())
                ? VpTexts.tr("label.videoplayer.platform_recommended", "%s Recommended", arch)
                : Component.literal(arch);
    }

    private void refreshAvailability() {
        if (VideoPlayerMain.android) {
            vlcAvailable = vlcInstalled;
            mpvAvailable = false;
            return;
        }
        if (availabilityTask != null) return;
        availabilityTask = CompletableFuture.supplyAsync(() -> new AvailabilityState(
                VlcDecoder.isAvailable(),
                MpvVideoBackend.isAvailable()
        ));
        availabilityTask.whenComplete((state, error) -> Minecraft.getInstance().execute(() -> {
            availabilityTask = null;
            if (error != null || state == null) {
                vlcAvailable = false;
                mpvAvailable = false;
            } else {
                vlcAvailable = state.vlcAvailable();
                mpvAvailable = state.mpvAvailable();
            }
            syncButtons();
        }));
    }

    private void refreshInstallationState() {
        if (installationStateTask != null) return;
        String vlcPlatform = selectedVlcPlatform;
        String mpvPlatform = selectedMpvPlatform;
        installationStateTask = CompletableFuture.supplyAsync(() -> new InstallationState(
                vlcPlatform,
                NativePackageManager.isInstalled(VideoBackends.VLC, vlcPlatform),
                mpvPlatform,
                !VideoPlayerMain.android && NativePackageManager.isInstalled(VideoBackends.MPV, mpvPlatform)
        ));
        installationStateTask.whenComplete((state, error) -> Minecraft.getInstance().execute(() -> {
            installationStateTask = null;
            if (error != null || state == null) return;
            if (!Objects.equals(state.vlcPlatform(), selectedVlcPlatform)
                    || !Objects.equals(state.mpvPlatform(), selectedMpvPlatform)) {
                refreshInstallationState();
                return;
            }
            vlcInstalled = state.vlcInstalled();
            mpvInstalled = state.mpvInstalled();
            if (VideoPlayerMain.android) {
                vlcAvailable = vlcInstalled;
                mpvAvailable = false;
            }
            syncButtons();
        }));
    }

    private void refreshYtdlpAvailability() {
        if (ytdlpDetectionTask != null) return;
        String configured = ytdlPathField == null ? currentYtdlPath() : ytdlPathField.getValue().trim();
        ytdlpDetectionTask = CompletableFuture.supplyAsync(() -> YtDlpManager.detect(configured));
        ytdlpDetectionTask.whenComplete((detection, error) -> Minecraft.getInstance().execute(() -> {
            ytdlpDetectionTask = null;
            ytdlpAvailable = error == null && detection != null && detection.available();
            ytdlpVersion = ytdlpAvailable ? detection.version() : "";
            VideoPlayerClient.applyNativePlatformConfig();
        }));
    }

    private boolean backendAvailable(String backend) {
        return VideoBackends.MPV.equals(VideoBackends.normalize(backend)) ? mpvAvailable : vlcAvailable;
    }

    private void markBackendInstalled(String backend, boolean installed) {
        if (VideoBackends.MPV.equals(backend)) {
            mpvInstalled = installed;
        } else {
            vlcInstalled = installed;
        }
    }

    private String nativeTaskKey(String backend, String platform) {
        return NativeDownloadConfig.normalizeBackend(backend) + ":" + platform;
    }

    private BackendRefreshResult refreshBackendAfterRuntimeChange(String backend) {
        if (VideoPlayerMain.android) {
            mpvAvailable = false;
            if (VideoBackends.MPV.equals(backend)) return BackendRefreshResult.RETRY_FAILED;
            VlcDecoder.resetLoadState();
            vlcAvailable = vlcInstalled && VlcDecoder.isAvailable();
            return vlcAvailable ? BackendRefreshResult.RETRY_SUCCEEDED : BackendRefreshResult.RETRY_FAILED;
        }
        if (backendLoaded(backend)) return BackendRefreshResult.RESTART_REQUIRED;
        boolean available;
        if (VideoBackends.MPV.equals(VideoBackends.normalize(backend))) {
            MpvVideoBackend.resetAvailability();
            available = MpvVideoBackend.isAvailable();
            mpvAvailable = available;
        } else {
            VlcDecoder.resetLoadState();
            available = VlcDecoder.isAvailable();
            vlcAvailable = available;
        }
        return available ? BackendRefreshResult.RETRY_SUCCEEDED : BackendRefreshResult.RETRY_FAILED;
    }

    private boolean backendLoaded(String backend) {
        return VideoBackends.MPV.equals(VideoBackends.normalize(backend))
                ? MpvVideoBackend.isLoaded()
                : VlcDecoder.isLoaded();
    }

    private String backendName(String backend) {
        return VideoBackends.MPV.equals(VideoBackends.normalize(backend)) ? "MPV" : "VLC";
    }

    private void finish() {
        persistProxy();
        persistYtdlPath();
        VideoPlayerClient.markStartupGuideShown();
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    private InputRowLayout inputRowLayout() {
        int rowW = Math.max(1, contentRight - contentLeft);
        int proxyLabelW = 34;
        int ytdlLabelW = 36;
        int inputGap = 6;
        int pairGap = 8;
        int fieldSpace = Math.max(80, rowW - proxyLabelW - ytdlLabelW - inputGap * 2 - pairGap);
        int proxyW = Math.max(40, fieldSpace / 2);
        int ytdlW = Math.max(40, fieldSpace - proxyW);
        int proxyFieldX = contentLeft + proxyLabelW + inputGap;
        int ytdlLabelX = proxyFieldX + proxyW + pairGap;
        int ytdlFieldX = ytdlLabelX + ytdlLabelW + inputGap;
        return new InputRowLayout(proxyFieldX, proxyW, ytdlLabelX, ytdlFieldX, ytdlW);
    }

    private record InputRowLayout(int proxyFieldX, int proxyFieldWidth, int ytdlLabelX, int ytdlFieldX, int ytdlFieldWidth) {
    }

    private boolean inside(double mouseX, double mouseY, int left, int top, int right, int bottom) {
        return mouseX >= left && mouseY >= top && mouseX < right && mouseY < bottom;
    }
}
