package com.github.squi2rel.vp.creation;

import com.github.squi2rel.vp.NativeDownloadConfig;
import com.github.squi2rel.vp.NativePackageManager;
import com.github.squi2rel.vp.VideoPlayerClient;
import com.github.squi2rel.vp.i18n.VpTexts;
import com.github.squi2rel.vp.i18n.VpTranslation;
import com.github.squi2rel.vp.i18n.VpTranslations;
import com.github.squi2rel.vp.video.MpvVideoBackend;
import com.github.squi2rel.vp.video.StreamListener;
import com.github.squi2rel.vp.video.VideoBackends;
import com.github.squi2rel.vp.video.VlcDecoder;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public class StartupGuideScreen extends Screen {
    private static final VpUiTheme THEME = VpUiTheme.classic();
    private static final int PANEL_WIDTH = 460;
    private static final int PANEL_HEIGHT = 286;
    private static final int MIN_PANEL_HEIGHT = 168;
    private static final int CONTROL_HEIGHT = 18;
    private static final int ROW_HEIGHT = 52;
    private static final int BACKEND_START_Y = 62;
    private static final int BACKEND_BUTTON_COUNT = 4;
    private static final int GAP = 8;
    private static final int SCROLL_STEP = 24;

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
    private VpButtonWidget done;
    private VpButtonWidget skip;
    private VpTextFieldWidget proxyField;
    private VpTextFieldWidget ytdlPathField;

    private CompletableFuture<NativePackageManager.DownloadResult> downloadTask;
    private String activeBackend = "";
    private VpTranslation status = VpTranslation.EMPTY;
    private int sourceIndex;
    private int sourceCount;
    private long bytesRead;
    private long totalBytes;
    private boolean vlcAvailable;
    private boolean mpvAvailable;
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
        proxyField = new VpTextFieldWidget(textRenderer, inputRow.proxyFieldX(), contentTop + 4, inputRow.proxyFieldWidth(), CONTROL_HEIGHT,
                VpTexts.tr("label.videoplayer.proxy", "Proxy"), THEME);
        proxyField.setMaxLength(220);
        proxyField.setText(currentProxy());
        ytdlPathField = new VpTextFieldWidget(textRenderer, inputRow.ytdlFieldX(), contentTop + 4, inputRow.ytdlFieldWidth(), CONTROL_HEIGHT,
                VpTexts.tr("label.videoplayer.ytdl_path", "yt-dlp"), THEME);
        ytdlPathField.setMaxLength(260);
        ytdlPathField.setText(currentYtdlPath());

        mpvPlatform = button("", buttonX, contentTop + BACKEND_START_Y, buttonW, () -> cyclePlatform(VideoBackends.MPV));
        mpvSelect = button(VpTexts.tr("button.videoplayer.select", "Select"), buttonX + buttonW + GAP, contentTop + BACKEND_START_Y, buttonW, () -> selectBackend(VideoBackends.MPV));
        mpvDownload = button(VpTexts.tr("button.videoplayer.download", "Download"), buttonX + (buttonW + GAP) * 2, contentTop + BACKEND_START_Y, buttonW, () -> startDownload(VideoBackends.MPV));
        mpvCopyLink = button(VpTexts.tr("button.videoplayer.copy_link", "Copy Link"), buttonX + (buttonW + GAP) * 3, contentTop + BACKEND_START_Y, buttonW, () -> copySourceLink(VideoBackends.MPV));
        vlcPlatform = button("", buttonX, contentTop + BACKEND_START_Y + ROW_HEIGHT, buttonW, () -> cyclePlatform(VideoBackends.VLC));
        vlcSelect = button(VpTexts.tr("button.videoplayer.select", "Select"), buttonX + buttonW + GAP, contentTop + BACKEND_START_Y + ROW_HEIGHT, buttonW, () -> selectBackend(VideoBackends.VLC));
        vlcDownload = button(VpTexts.tr("button.videoplayer.download", "Download"), buttonX + (buttonW + GAP) * 2, contentTop + BACKEND_START_Y + ROW_HEIGHT, buttonW, () -> startDownload(VideoBackends.VLC));
        vlcCopyLink = button(VpTexts.tr("button.videoplayer.copy_link", "Copy Link"), buttonX + (buttonW + GAP) * 3, contentTop + BACKEND_START_Y + ROW_HEIGHT, buttonW, () -> copySourceLink(VideoBackends.VLC));

        int footerY = panelTop + panelHeight - 28;
        skip = button(VpTexts.tr("button.videoplayer.skip", "Skip"), panelLeft + 24, footerY, 92, this::finish);
        done = button(VpTexts.tr("button.videoplayer.done", "Done"), panelLeft + panelWidth - 116, footerY, 92, this::finish);

        addDrawableChild(proxyField);
        addDrawableChild(ytdlPathField);
        addDrawableChild(mpvPlatform);
        addDrawableChild(mpvSelect);
        addDrawableChild(mpvDownload);
        addDrawableChild(mpvCopyLink);
        addDrawableChild(vlcPlatform);
        addDrawableChild(vlcSelect);
        addDrawableChild(vlcDownload);
        addDrawableChild(vlcCopyLink);
        addDrawableChild(skip);
        addDrawableChild(done);

        layoutWidgets();
        refreshAvailability();
        syncButtons();
    }

    @Override
    public void tick() {
        syncButtons();
    }

    @Override
    public void close() {
        finish();
    }

    @Override
    public boolean shouldPause() {
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
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        computeLayout();
        layoutWidgets();
        context.fill(0, 0, width, height, 0xB0000000);

        context.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, THEME.panelBackgroundColor());
        context.drawStrokedRectangle(panelLeft, panelTop, panelWidth, panelHeight, THEME.panelBorderColor());
        drawCenteredText(context, title, width / 2, panelTop + 12, THEME.primaryTextColor());

        VpUiRenderer.drawBox(context, contentLeft - 6, contentTop - 4, contentRight - contentLeft + 12, contentBottom - contentTop + 8,
                VpUiRenderer.darken(THEME.nodeBodyColor(), 0.06f), THEME.panelBorderColor());
        context.enableScissor(contentLeft, contentTop, contentRight, contentBottom);
        drawScrollableContent(context, mouseX, mouseY, delta);
        context.disableScissor();
        drawScrollbar(context);

        Text line = statusLine();
        if (!line.getString().isBlank()) {
            drawText(context, trimToWidth(line, Math.max(40, panelWidth - 48)), panelLeft + 24, panelTop + panelHeight - 42, THEME.secondaryTextColor());
        }

        renderWidget(skip, context, mouseX, mouseY, delta);
        renderWidget(done, context, mouseX, mouseY, delta);
    }

    private VpButtonWidget button(String label, int x, int y, int width, Runnable action) {
        return new VpButtonWidget(x, y, width, CONTROL_HEIGHT, Text.literal(label), ignored -> action.run(), THEME);
    }

    private VpButtonWidget button(Text label, int x, int y, int width, Runnable action) {
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
        contentHeight = BACKEND_START_Y + ROW_HEIGHT * 2 + 12;
        contentScroll = Math.clamp(contentScroll, 0, maxContentScroll());
    }

    private void layoutWidgets() {
        if (proxyField == null || ytdlPathField == null || mpvPlatform == null || skip == null || done == null) {
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

        layoutBackendButtons(mpvPlatform, mpvSelect, mpvDownload, mpvCopyLink, buttonX, y + BACKEND_START_Y, buttonW);
        layoutBackendButtons(vlcPlatform, vlcSelect, vlcDownload, vlcCopyLink, buttonX, y + BACKEND_START_Y + ROW_HEIGHT, buttonW);

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

    private void drawScrollableContent(DrawContext context, int mouseX, int mouseY, float delta) {
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
        drawText(context, trimToWidth(VpTexts.tr("label.videoplayer.current_backend", "Current backend: %s", VideoBackends.normalize(VideoPlayerClient.config.videoBackend)), contentRight - contentLeft), contentLeft, infoY + 14, THEME.secondaryTextColor());

        drawBackend(context, contentLeft, y + BACKEND_START_Y, VideoBackends.MPV, VpTexts.tr("label.videoplayer.mpv_recommended", "MPV Recommended"), mpvAvailable);
        renderWidget(mpvPlatform, context, mouseX, mouseY, delta);
        renderWidget(mpvSelect, context, mouseX, mouseY, delta);
        renderWidget(mpvDownload, context, mouseX, mouseY, delta);
        renderWidget(mpvCopyLink, context, mouseX, mouseY, delta);

        drawBackend(context, contentLeft, y + BACKEND_START_Y + ROW_HEIGHT, VideoBackends.VLC, Text.literal("VLC"), vlcAvailable);
        renderWidget(vlcPlatform, context, mouseX, mouseY, delta);
        renderWidget(vlcSelect, context, mouseX, mouseY, delta);
        renderWidget(vlcDownload, context, mouseX, mouseY, delta);
        renderWidget(vlcCopyLink, context, mouseX, mouseY, delta);
    }

    private void drawBackend(DrawContext context, int x, int y, String backend, Text label, boolean available) {
        int color = available ? THEME.executionColor() : THEME.errorColor();
        int count = sourceCount(backend);
        String platform = selectedPlatform(backend);
        Text installed = NativePackageManager.isInstalled(backend, platform)
                ? VpTexts.tr("label.videoplayer.installed", "Installed")
                : VpTexts.tr("label.videoplayer.not_installed", "Not installed");
        Text sources = count <= 0
                ? VpTexts.tr("label.videoplayer.no_sources", "No sources configured")
                : VpTexts.tr("label.videoplayer.source_count", "%s sources", count);
        int textW = Math.max(40, buttonGroupX() - x - GAP);
        Text visibleLabel = trimToWidth(label, Math.max(32, textW - 54));
        int statusX = x + Math.max(52, textRenderer.getWidth(visibleLabel) + 8);
        Text availability = available
                ? VpTexts.tr("label.videoplayer.available", "Available")
                : VpTexts.tr("label.videoplayer.unavailable", "Unavailable");
        int platformX = statusX + textRenderer.getWidth(availability) + 8;
        drawText(context, visibleLabel, x, y, THEME.primaryTextColor());
        drawText(context, availability, statusX, y, color);
        drawText(context, trimToWidth(platformText(platform), Math.max(24, textW - (platformX - x))), platformX, y, THEME.secondaryTextColor());
        drawText(context, trimToWidth(VpTexts.tr("label.videoplayer.install_source_status", "%s / %s", installed.getString(), sources.getString()), textW), x, y + 16, THEME.secondaryTextColor());
    }

    private void drawScrollbar(DrawContext context) {
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

    private void renderWidget(Drawable widget, DrawContext context, int mouseX, int mouseY, float delta) {
        if (widget != null) {
            widget.render(context, mouseX, mouseY, delta);
        }
    }

    private void drawText(DrawContext context, Text text, int x, int y, int color) {
        if (THEME.textShadow()) {
            context.drawTextWithShadow(textRenderer, text, x, y, color);
            return;
        }
        context.drawText(textRenderer, text, x, y, color, false);
    }

    private void drawCenteredText(DrawContext context, Text text, int centerX, int y, int color) {
        drawText(context, text, centerX - textRenderer.getWidth(text) / 2, y, color);
    }

    private Text trimToWidth(Text text, int maxWidth) {
        return Text.literal(trimToWidth(text.getString(), maxWidth));
    }

    private String trimToWidth(String text, int maxWidth) {
        String value = text == null ? "" : text;
        if (textRenderer.getWidth(value) <= maxWidth) return value;
        String suffix = "...";
        return textRenderer.trimToWidth(value, Math.max(0, maxWidth - textRenderer.getWidth(suffix))) + suffix;
    }

    private Text statusLine() {
        if (downloadTask == null) return VpTexts.text(status);
        String backend = activeBackend.equals(VideoBackends.MPV) ? "MPV" : "VLC";
        String source = sourceCount <= 0 ? "" : " " + sourceIndex + "/" + sourceCount;
        String progress = "";
        if (totalBytes > 0) {
            long percent = Math.clamp(Math.round(bytesRead * 100.0 / totalBytes), 0, 100);
            progress = " " + percent + "%";
        }
        return VpTexts.tr("message.videoplayer.native.status_line", "%s%s%s %s", backend, source, progress, VpTexts.text(status).getString());
    }

    private void syncButtons() {
        boolean idle = downloadTask == null;
        syncBackendButtons(VideoBackends.MPV, mpvPlatform, mpvSelect, mpvDownload, mpvCopyLink, idle);
        syncBackendButtons(VideoBackends.VLC, vlcPlatform, vlcSelect, vlcDownload, vlcCopyLink, idle);
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
        String platform = selectedPlatform(backend);
        List<NativeDownloadConfig.DownloadSource> sources = sources(backend);
        if (sources.isEmpty()) return;
        String proxy = persistProxy();
        persistYtdlPath();
        activeBackend = backend;
        status = VpTranslation.of("message.videoplayer.native.prepare_download", "Preparing download");
        sourceIndex = 0;
        sourceCount = sources.size();
        bytesRead = 0;
        totalBytes = -1;

        downloadTask = CompletableFuture.supplyAsync(() -> NativePackageManager.downloadAndInstall(backend, platform, sources, proxy, progress ->
                MinecraftClient.getInstance().execute(() -> {
                    sourceIndex = progress.sourceIndex();
                    sourceCount = progress.sourceCount();
                    bytesRead = progress.bytesRead();
                    totalBytes = progress.totalBytes();
                    status = progress.message();
                })));
        downloadTask.whenComplete((result, error) -> MinecraftClient.getInstance().execute(() -> {
            downloadTask = null;
            if (error != null) {
                status = VpTranslations.from(error, "error.videoplayer.native.download_failed", "Download failed: %s", error.getMessage() == null ? "" : error.getMessage());
                return;
            }
            status = result.message();
            if (result.success()) {
                resetLoaders();
                refreshAvailability();
                if (!backendAvailable(VideoPlayerClient.config.videoBackend) && backendAvailable(backend)) {
                    VideoPlayerClient.config.videoBackend = VideoBackends.normalize(backend);
                    VideoPlayerClient.saveConfig();
                }
            }
        }));
    }

    private void copySourceLink(String backend) {
        List<NativeDownloadConfig.DownloadSource> sources = sources(backend);
        if (sources.isEmpty()) return;
        int index = Math.floorMod(browserIndex.getOrDefault(backend, 0), sources.size());
        String url = sources.get(index).url;
        browserIndex.put(backend, (index + 1) % sources.size());
        try {
            MinecraftClient.getInstance().keyboard.setClipboard(url);
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
                    "Playback backend set to MPV, but libmpv failed to load on this system. New videos will fall back to VLC. Install system libmpv."
            );
        } else {
            status = VpTranslation.of("message.videoplayer.backend_set", "Playback backend set to %s. Only newly started videos are affected.", normalized);
        }
        syncButtons();
    }

    private int sourceCount(String backend) {
        return sources(backend).size();
    }

    private List<NativeDownloadConfig.DownloadSource> sources(String backend) {
        return sources(backend, selectedPlatform(backend));
    }

    private List<NativeDownloadConfig.DownloadSource> sources(String backend, String platform) {
        return nativeDownloads().sources(backend, platform);
    }

    private String currentProxy() {
        if (VideoPlayerClient.config == null || VideoPlayerClient.config.nativeDownloadProxy == null) return "";
        return VideoPlayerClient.config.nativeDownloadProxy;
    }

    private String persistProxy() {
        String proxy = proxyField == null ? currentProxy() : proxyField.getText().trim();
        if (VideoPlayerClient.config != null && !Objects.equals(VideoPlayerClient.config.nativeDownloadProxy, proxy)) {
            VideoPlayerClient.config.nativeDownloadProxy = proxy;
            VideoPlayerClient.saveConfig();
        }
        return proxy;
    }

    private String currentYtdlPath() {
        if (VideoPlayerClient.config == null || VideoPlayerClient.config.mpvYtdlPath == null) return "";
        return VideoPlayerClient.config.mpvYtdlPath;
    }

    private void persistYtdlPath() {
        String path = ytdlPathField == null ? currentYtdlPath() : ytdlPathField.getText().trim();
        if (VideoPlayerClient.config != null && !Objects.equals(VideoPlayerClient.config.mpvYtdlPath, path)) {
            VideoPlayerClient.config.mpvYtdlPath = path;
            VideoPlayerClient.saveConfig();
        }
    }

    private List<String> platformOptions(String backend) {
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
        resetLoaders();
        refreshAvailability();
        status = VpTranslation.of("message.videoplayer.native.platform_selected", "%s platform: %s",
                VideoBackends.MPV.equals(VideoBackends.normalize(backend)) ? "MPV" : "VLC", platformLabel(normalized));
    }

    private String selectedPlatform(String backend) {
        return VideoBackends.MPV.equals(VideoBackends.normalize(backend)) ? selectedMpvPlatform : selectedVlcPlatform;
    }

    private void syncSelectedPlatformsWithConfig() {
        if (VideoPlayerClient.config == null) return;
        selectedVlcPlatform = NativeDownloadConfig.normalizePlatformForCurrentOs(VideoPlayerClient.config.nativeVlcPlatform);
        selectedMpvPlatform = NativeDownloadConfig.normalizePlatformForCurrentOs(VideoPlayerClient.config.nativeMpvPlatform);
        VideoPlayerClient.config.nativeVlcPlatform = selectedVlcPlatform;
        VideoPlayerClient.config.nativeMpvPlatform = selectedMpvPlatform;
        VideoPlayerClient.applyNativePlatformConfig();
    }

    private String platformLabel(String platform) {
        return platformText(platform).getString();
    }

    private Text platformText(String platform) {
        String arch = NativeDownloadConfig.archFromPlatform(platform);
        if (arch.isBlank()) return Text.literal(platform == null ? "" : platform);
        return platform.equals(NativePackageManager.platformKey())
                ? VpTexts.tr("label.videoplayer.platform_recommended", "%s Recommended", arch)
                : Text.literal(arch);
    }

    private void refreshAvailability() {
        vlcAvailable = VlcDecoder.isAvailable();
        mpvAvailable = MpvVideoBackend.isAvailable();
    }

    private boolean backendAvailable(String backend) {
        return VideoBackends.MPV.equals(VideoBackends.normalize(backend)) ? mpvAvailable : vlcAvailable;
    }

    private void resetLoaders() {
        VlcDecoder.resetLoadState();
        MpvVideoBackend.resetAvailability();
        StreamListener.resetLoadState();
    }

    private void finish() {
        persistProxy();
        persistYtdlPath();
        VideoPlayerClient.markStartupGuideShown();
        if (client != null) {
            client.setScreen(parent);
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
