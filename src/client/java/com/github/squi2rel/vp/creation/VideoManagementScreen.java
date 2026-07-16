package com.github.squi2rel.vp.creation;

import com.github.squi2rel.vp.ClientPacketHandler;
import com.github.squi2rel.vp.ClientPermissionCache;
import com.github.squi2rel.vp.ScreenRenderer;
import com.github.squi2rel.vp.VideoPlayerClient;
import com.github.squi2rel.vp.danmaku.ClientDanmakuController;
import com.github.squi2rel.vp.danmaku.ClientDanmakuRenderer;
import com.github.squi2rel.vp.danmaku.ClientSubtitleController;
import com.github.squi2rel.vp.i18n.VpTexts;
import com.github.squi2rel.vp.network.RequestResultStatus;
import com.github.squi2rel.vp.permission.VideoPermissionAction;
import com.github.squi2rel.vp.provider.VideoInfo;
import com.github.squi2rel.vp.provider.YouTubeProvider;
import com.github.squi2rel.vp.provider.bilibili.BiliBiliVideoProvider;
import com.github.squi2rel.vp.provider.bilibili.BiliQuality;
import com.github.squi2rel.vp.provider.youtube.YouTubeQuality;
import com.github.squi2rel.vp.video.ClientVideoArea;
import com.github.squi2rel.vp.video.ClientVideoScreen;
import com.github.squi2rel.vp.video.MetaType;
import com.github.squi2rel.vp.video.MetaValue;
import com.github.squi2rel.vp.video.MpvVideoBackend;
import com.github.squi2rel.vp.video.ScreenMetadata;
import com.github.squi2rel.vp.video.ScreenSurface;
import com.github.squi2rel.vp.video.ScreenVolumeCache;
import com.github.squi2rel.vp.video.VideoBackends;
import com.github.squi2rel.vp.video.VideoPlayer;
import com.github.squi2rel.vp.video.VideoScreen;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.Predicate;

public class VideoManagementScreen extends Screen implements ServerStateScreen {
    private static final int SIDEBAR_WIDTH = 96;
    private static final int ROW_HEIGHT = 20;
    private static final int GAP = 8;
    private static final int CONTROL_HEIGHT = 18;
    private static final int BUTTON_ROW_GAP = 24;
    private static final int FORM_ROW_GAP = 32;
    private static final int PARAM_ROW_GAP = 30;
    private static final int LABEL_OFFSET = 11;
    private static final int SCREEN_SETTINGS_DISPLAY_Y = 18;
    private static final int SCREEN_SETTINGS_META_Y = 188;
    private static final int SCREEN_SETTINGS_META_CONTENT_Y = SCREEN_SETTINGS_META_Y + 18;
    private static final int PLAYBACK_PROGRESS_HEIGHT = 18;
    private static final int PLAYBACK_PROGRESS_BOTTOM_MARGIN = 54;
    private static final int PLAYBACK_PROGRESS_GAP = 8;
    private static final int PLAYBACK_BOTTOM_CONTROLS_GAP = 6;
    private static final long PLAYBACK_SEEK_THROTTLE_MS = 250L;
    private static final long PLAYBACK_PREVIEW_END_GUARD_MS = 1000L;
    private static final float MIN_SCREEN_SCALE = 0.0625f;
    private static final float MAX_SCREEN_SCALE = 16f;
    private static final int DANMAKU_OVERLAY_WIDTH = 268;
    private static final int DANMAKU_OVERLAY_BASE_HEIGHT = 198;
    private static final int DANMAKU_OVERLAY_DENSITY_EXTRA_HEIGHT = 33;
    private static final int BILI_QUALITY_OVERLAY_MIN_WIDTH = 96;
    private static final int BILI_QUALITY_OVERLAY_HEADER_HEIGHT = 28;
    private static final int BILI_QUALITY_OVERLAY_PADDING = 10;
    private static final int BILI_QUALITY_OVERLAY_BUTTON_GAP = 5;
    private static final int BILI_QUALITY_OVERLAY_VISIBLE_ROWS = 5;
    private static final int[] DANMAKU_RANGE_OPTIONS = {25, 50, 75, 100};
    private static final String[] DANMAKU_SPEED_KEYS = {
            "label.videoplayer.danmaku_speed.slowest",
            "label.videoplayer.danmaku_speed.slow",
            "label.videoplayer.danmaku_speed.medium",
            "label.videoplayer.danmaku_speed.fast",
            "label.videoplayer.danmaku_speed.fastest"
    };
    private static final String[] DANMAKU_SPEED_FALLBACKS = {"Slowest", "Slow", "Medium", "Fast", "Fastest"};
    private static final String[] DANMAKU_DENSITY_KEYS = {
            "label.videoplayer.danmaku_density.normal",
            "label.videoplayer.danmaku_density.more",
            "label.videoplayer.danmaku_density.overlap"
    };
    private static final String[] DANMAKU_DENSITY_FALLBACKS = {"Normal", "More", "Overlap"};
    private static final VpUiTheme THEME = VpUiTheme.classic();
    private final VideoCreationEditor editor;
    private Tab tab;
    private String selectedAreaName;
    private String selectedScreenName;
    private int areaScroll;
    private int screenScroll;
    private int contentScroll;
    private boolean confirmDeleteArea;
    private boolean confirmDeleteScreen;
    private WidgetGroup widgetGroup = WidgetGroup.FIXED;
    private final List<Drawable> fixedDrawables = new ArrayList<>();
    private final List<Drawable> areaScrollDrawables = new ArrayList<>();
    private final List<Drawable> screenScrollDrawables = new ArrayList<>();
    private final List<Drawable> contentScrollDrawables = new ArrayList<>();
    private final List<Drawable> danmakuOverlayDrawables = new ArrayList<>();
    private final List<ClickableWidget> danmakuOverlayWidgets = new ArrayList<>();
    private int areaScrollContentHeight;
    private int screenScrollContentHeight;
    private int contentScrollContentHeight;

    private TextFieldWidget nameField;
    private TextFieldWidget sourceField;
    private TextFieldWidget urlField;
    private TextFieldWidget customKeyField;
    private TextFieldWidget customValueField;
    private TextFieldWidget sphereCenterXField;
    private TextFieldWidget sphereCenterYField;
    private TextFieldWidget sphereCenterZField;
    private TextFieldWidget sphereRadiusField;
    private TextFieldWidget sphereLatField;
    private TextFieldWidget sphereLonField;
    private TextFieldWidget sphereRotXField;
    private TextFieldWidget sphereRotYField;
    private TextFieldWidget sphereRotZField;
    private VpProgressSliderWidget playbackProgressSlider;
    private boolean playbackProgressPreview;
    private boolean playbackPreviewPinned;
    private boolean danmakuOverlayOpen;
    private boolean biliLocalQualityOverlayOpen;
    private boolean biliScreenQualityOverlayOpen;
    private boolean youtubeScreenQualityOverlay;
    private boolean ccSubtitleOverlayOpen;
    private int danmakuOverlayX;
    private int danmakuOverlayY;
    private int danmakuOverlayW;
    private int danmakuOverlayH;
    private int biliQualityOverlayScroll;
    private int biliQualityOverlayViewportTop;
    private int biliQualityOverlayViewportBottom;
    private int biliQualityOverlayContentHeight;
    private ClickableWidget activeDanmakuOverlayWidget;
    private ClientVideoScreen playbackProgressDragScreen;
    private boolean playbackProgressPausedBeforeDrag;
    private boolean playbackProgressPauseApplied;
    private long lastPlaybackPreviewSeekTime;
    private MetaType customMetaType = MetaType.INT;
    private String areaSignature = "";
    private String screenSignature = "";
    private String metadataSignature = "";
    private String ccSubtitleOverlaySignature = "";

    public VideoManagementScreen(VideoCreationEditor editor, ClientVideoScreen focusedScreen) {
        this(editor, focusedScreen, focusedScreen == null ? Tab.CREATE_EDIT : Tab.PLAYBACK);
    }

    private VideoManagementScreen(VideoCreationEditor editor, ClientVideoScreen focusedScreen, Tab tab) {
        this(editor, focusedScreen, tab, false);
    }

    private VideoManagementScreen(VideoCreationEditor editor, ClientVideoScreen focusedScreen, Tab tab, boolean danmakuOverlayOpen) {
        this(editor, focusedScreen, tab, danmakuOverlayOpen, false, false, false, false, false);
    }

    private VideoManagementScreen(VideoCreationEditor editor, ClientVideoScreen focusedScreen, Tab tab,
                                  boolean danmakuOverlayOpen, boolean biliLocalQualityOverlayOpen,
                                  boolean biliScreenQualityOverlayOpen, boolean youtubeScreenQualityOverlay,
                                  boolean ccSubtitleOverlayOpen,
                                  boolean playbackPreviewPinned) {
        super(VpTexts.tr("screen.videoplayer.management", "VideoPlayer Management"));
        this.editor = editor;
        this.tab = tab;
        this.playbackPreviewPinned = playbackPreviewPinned && tab == Tab.PLAYBACK;
        this.danmakuOverlayOpen = danmakuOverlayOpen && tab == Tab.PLAYBACK;
        this.biliLocalQualityOverlayOpen = biliLocalQualityOverlayOpen && tab == Tab.PLAYBACK;
        this.biliScreenQualityOverlayOpen = biliScreenQualityOverlayOpen && tab == Tab.SCREEN_SETTINGS;
        this.youtubeScreenQualityOverlay = youtubeScreenQualityOverlay && this.biliScreenQualityOverlayOpen;
        this.ccSubtitleOverlayOpen = ccSubtitleOverlayOpen && tab == Tab.PLAYBACK;
        VideoCreationEditor.Draft draft = editor.draft();
        if (focusedScreen != null) {
            selectedAreaName = focusedScreen.area.name;
            selectedScreenName = focusedScreen.name;
            draft.operation = VideoCreationEditor.Operation.EDIT_SCREEN_GEOMETRY;
            draft.areaName = selectedAreaName;
            draft.name = selectedScreenName;
            draft.source = focusedScreen.source == null ? "" : focusedScreen.source;
        } else {
            selectedAreaName = draft.areaName == null || draft.areaName.isBlank() ? firstAreaName() : draft.areaName;
            selectedScreenName = draft.name == null || draft.name.isBlank() ? firstScreenName(selectedAreaName) : draft.name;
        }
        ensureSelection();
        syncDraftFromSelection(false, focusedScreen != null || !preserveDraftForCurrentSelection());
    }

    private VideoManagementScreen(VideoCreationEditor editor, Tab tab, String selectedAreaName, String selectedScreenName,
                                  int areaScroll, int screenScroll, int contentScroll, boolean confirmDeleteArea, boolean confirmDeleteScreen,
        MetaType customMetaType, boolean preserveDraftDisplay, boolean danmakuOverlayOpen) {
        this(editor, tab, selectedAreaName, selectedScreenName, areaScroll, screenScroll, contentScroll, confirmDeleteArea, confirmDeleteScreen,
                customMetaType, preserveDraftDisplay, danmakuOverlayOpen, false, false, false, false, false);
    }

    private VideoManagementScreen(VideoCreationEditor editor, Tab tab, String selectedAreaName, String selectedScreenName,
                                  int areaScroll, int screenScroll, int contentScroll, boolean confirmDeleteArea, boolean confirmDeleteScreen,
                                  MetaType customMetaType, boolean preserveDraftDisplay,
                                  boolean danmakuOverlayOpen, boolean biliLocalQualityOverlayOpen,
                                  boolean biliScreenQualityOverlayOpen, boolean youtubeScreenQualityOverlay,
                                  boolean ccSubtitleOverlayOpen,
                                  boolean playbackPreviewPinned) {
        super(VpTexts.tr("screen.videoplayer.management", "VideoPlayer Management"));
        this.editor = editor;
        this.tab = tab;
        this.playbackPreviewPinned = playbackPreviewPinned && tab == Tab.PLAYBACK;
        this.danmakuOverlayOpen = danmakuOverlayOpen && tab == Tab.PLAYBACK;
        this.biliLocalQualityOverlayOpen = biliLocalQualityOverlayOpen && tab == Tab.PLAYBACK;
        this.biliScreenQualityOverlayOpen = biliScreenQualityOverlayOpen && tab == Tab.SCREEN_SETTINGS;
        this.youtubeScreenQualityOverlay = youtubeScreenQualityOverlay && this.biliScreenQualityOverlayOpen;
        this.ccSubtitleOverlayOpen = ccSubtitleOverlayOpen && tab == Tab.PLAYBACK;
        this.selectedAreaName = selectedAreaName;
        this.selectedScreenName = selectedScreenName;
        this.areaScroll = Math.max(0, areaScroll);
        this.screenScroll = Math.max(0, screenScroll);
        this.contentScroll = Math.max(0, contentScroll);
        this.confirmDeleteArea = confirmDeleteArea;
        this.confirmDeleteScreen = confirmDeleteScreen;
        if (customMetaType != null) this.customMetaType = customMetaType;
        ensureSelection();
        syncDraftFromSelection(false, !preserveDraftDisplay && !preserveDraftForCurrentSelection());
    }

    @Override
    protected void init() {
        ensureSelection();
        resetUiGroups();
        int margin = 14;
        int sidebarX = margin;
        int mainX = sidebarX + SIDEBAR_WIDTH + 14;
        int mainW = Math.max(220, width - mainX - margin);
        int top = 24;
        areaScroll = clampScroll(areaScroll, areaNames().size() * ROW_HEIGHT, sidebarAreaViewportHeight());
        screenScroll = clampScroll(screenScroll, screensForSelectedArea().size() * ROW_HEIGHT, sidebarScreenViewportHeight());
        contentScroll = clampScroll(contentScroll, estimateContentHeight(mainW), contentViewportHeight());

        addTabs(mainX, top, mainW);
        int contentTop = contentTop(mainW);
        addSidebar(sidebarX);

        widgetGroup = WidgetGroup.CONTENT_SCROLL;
        switch (tab) {
            case CREATE_EDIT -> initCreateEdit(mainX, contentTop - contentScroll, mainW);
            case PLAYBACK -> initPlayback(mainX, contentTop - contentScroll, mainW);
            case SCREEN_SETTINGS -> initScreenSettings(mainX, contentTop - contentScroll, mainW);
        }
        widgetGroup = WidgetGroup.FIXED;
        areaSignature = areaSignature();
        screenSignature = screenSignature();
        metadataSignature = metadataSignature();
        ccSubtitleOverlaySignature = ccSubtitleOverlaySignature();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        String currentAreaSignature = areaSignature();
        String currentScreenSignature = screenSignature();
        String currentMetadataSignature = metadataSignature();
        String currentCcSubtitleOverlaySignature = ccSubtitleOverlaySignature();
        if (!currentAreaSignature.equals(areaSignature)
                || !currentScreenSignature.equals(screenSignature)
                || !currentMetadataSignature.equals(metadataSignature)
                || ccSubtitleOverlayOpen && !currentCcSubtitleOverlaySignature.equals(ccSubtitleOverlaySignature)) {
            reopen(null);
        }
    }

    @Override
    public void close() {
        endPlaybackProgressDrag();
        client.setScreen(null);
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, VpUiRenderer.withAlpha(THEME.canvasBackgroundColor(), 0xCC));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        int margin = 14;
        int sidebarX = margin;
        int mainX = sidebarX + SIDEBAR_WIDTH + 14;
        int mainW = Math.max(220, width - mainX - margin);
        int top = 24;
        int panelBottom = height - 18;
        int contentTop = contentTop(mainW);

        VpUiRenderer.drawBox(context, sidebarX - 8, 16, SIDEBAR_WIDTH + 16, panelBottom - 16, THEME.panelBackgroundColor(), THEME.panelBorderColor());
        VpUiRenderer.drawBox(context, mainX - 8, 16, mainW + 16, panelBottom - 16, THEME.panelBackgroundColor(), THEME.panelBorderColor());
        context.drawText(textRenderer, title, sidebarX, 20, THEME.primaryTextColor(), false);
        drawSidebarLabels(context, sidebarX);

        renderClippedDrawables(context, areaScrollDrawables, mouseX, mouseY, delta,
                sidebarX, sidebarAreaViewportTop(), sidebarX + SIDEBAR_WIDTH, sidebarAreaViewportBottom());
        renderClippedDrawables(context, screenScrollDrawables, mouseX, mouseY, delta,
                sidebarX, sidebarScreenViewportTop(), sidebarX + SIDEBAR_WIDTH, sidebarScreenViewportBottom());
        renderContent(context, mouseX, mouseY, delta, mainX, contentTop - contentScroll, mainW);

        renderDrawables(context, fixedDrawables, mouseX, mouseY, delta);
        drawScrollbar(context, sidebarX + SIDEBAR_WIDTH - 4, sidebarAreaViewportTop(), sidebarAreaViewportBottom(), areaScroll, areaScrollContentHeight);
        drawScrollbar(context, sidebarX + SIDEBAR_WIDTH - 4, sidebarScreenViewportTop(), sidebarScreenViewportBottom(), screenScroll, screenScrollContentHeight);
        if (!hidePlaybackScrollpane(mouseX, mouseY)) {
            drawScrollbar(context, mainX + mainW - 4, contentViewportTop(), contentViewportBottom(), contentScroll, contentScrollContentHeight);
        }
        renderActiveOverlay(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        if (overlayOpen() && !insideActiveOverlay(click.x(), click.y())) {
            closeOverlays();
            activeDanmakuOverlayWidget = null;
            clearAndInit();
            return true;
        }
        if (insideActiveOverlay(click.x(), click.y())) {
            for (int i = danmakuOverlayWidgets.size() - 1; i >= 0; i--) {
                ClickableWidget widget = danmakuOverlayWidgets.get(i);
                if (widget.mouseClicked(click, doubleClick)) {
                    activeDanmakuOverlayWidget = widget;
                    setFocused(widget);
                    if (click.button() == 0) setDragging(true);
                    return true;
                }
            }
            return true;
        }
        return super.mouseClicked(click, doubleClick);
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        if (activeDanmakuOverlayWidget != null) {
            return activeDanmakuOverlayWidget.mouseDragged(click, deltaX, deltaY) || insideActiveOverlay(click.x(), click.y());
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (activeDanmakuOverlayWidget != null) {
            boolean handled = activeDanmakuOverlayWidget.mouseReleased(click);
            activeDanmakuOverlayWidget = null;
            setDragging(false);
            return handled || insideActiveOverlay(click.x(), click.y());
        }
        if (insideActiveOverlay(click.x(), click.y())) {
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int delta = (int) Math.round(-verticalAmount * 24.0);
        if (insideActiveOverlay(mouseX, mouseY)) {
            if (delta != 0 && scrollableOverlayOpen()) {
                return scrollBiliQualityOverlay(delta);
            }
            return true;
        }
        if (delta == 0) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }

        int sidebarX = sidebarX();
        if (inside(mouseX, mouseY, sidebarX, sidebarAreaViewportTop(), sidebarX + SIDEBAR_WIDTH, sidebarAreaViewportBottom())) {
            return scrollArea(delta);
        }
        if (inside(mouseX, mouseY, sidebarX, sidebarScreenViewportTop(), sidebarX + SIDEBAR_WIDTH, sidebarScreenViewportBottom())) {
            return scrollScreen(delta);
        }

        int mainX = mainX();
        int mainW = mainW();
        if (inside(mouseX, mouseY, mainX, contentViewportTop(), mainX + mainW, contentViewportBottom())) {
            if (hidePlaybackScrollpane(mouseX, mouseY)) {
                return true;
            }
            return scrollContent(delta);
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private boolean scrollArea(int delta) {
        int next = clampScroll(areaScroll + delta, areaScrollContentHeight, sidebarAreaViewportHeight());
        if (next == areaScroll) {
            return false;
        }
        preserveCurrentFieldsForReopen();
        areaScroll = next;
        reopenPreservingDraft();
        return true;
    }

    private boolean scrollScreen(int delta) {
        int next = clampScroll(screenScroll + delta, screenScrollContentHeight, sidebarScreenViewportHeight());
        if (next == screenScroll) {
            return false;
        }
        preserveCurrentFieldsForReopen();
        screenScroll = next;
        reopenPreservingDraft();
        return true;
    }

    private boolean scrollContent(int delta) {
        int contentHeight = Math.max(contentScrollContentHeight, estimateContentHeight(mainW()));
        int next = clampScroll(contentScroll + delta, contentHeight, contentViewportHeight());
        if (next == contentScroll) {
            return false;
        }
        preserveCurrentFieldsForReopen();
        contentScroll = next;
        reopenPreservingDraft();
        return true;
    }

    private int clampScroll(int scroll, int contentHeight, int viewportHeight) {
        int maxScroll = Math.max(0, contentHeight - Math.max(1, viewportHeight));
        return Math.clamp(scroll, 0, maxScroll);
    }

    private boolean inside(double mouseX, double mouseY, int left, int top, int right, int bottom) {
        return mouseX >= left && mouseY >= top && mouseX < right && mouseY < bottom;
    }

    private void resetUiGroups() {
        widgetGroup = WidgetGroup.FIXED;
        fixedDrawables.clear();
        areaScrollDrawables.clear();
        screenScrollDrawables.clear();
        contentScrollDrawables.clear();
        danmakuOverlayDrawables.clear();
        danmakuOverlayWidgets.clear();
        activeDanmakuOverlayWidget = null;
        danmakuOverlayX = 0;
        danmakuOverlayY = 0;
        danmakuOverlayW = 0;
        danmakuOverlayH = 0;
        biliQualityOverlayScroll = 0;
        biliQualityOverlayViewportTop = 0;
        biliQualityOverlayViewportBottom = 0;
        biliQualityOverlayContentHeight = 0;
        areaScrollContentHeight = 0;
        screenScrollContentHeight = 0;
        contentScrollContentHeight = 0;
        endPlaybackProgressDrag();
        playbackProgressSlider = null;
    }

    private int sidebarX() {
        return 14;
    }

    private int mainX() {
        return sidebarX() + SIDEBAR_WIDTH + 14;
    }

    private int mainW() {
        return Math.max(220, width - mainX() - 14);
    }

    private int topY() {
        return 24;
    }

    private int contentTop(int mainW) {
        return topY() + 36;
    }

    private int contentViewportTop() {
        return contentViewportTop(mainW());
    }

    private int contentViewportTop(int mainW) {
        return topY() + CONTROL_HEIGHT + 6;
    }

    private int contentViewportBottom() {
        int bottom = height - 26;
        if (tab == Tab.PLAYBACK) {
            bottom = Math.min(bottom, playbackProgressY() - PLAYBACK_PROGRESS_GAP);
        }
        return Math.max(contentViewportTop() + 1, bottom);
    }

    private int contentViewportHeight() {
        return Math.max(1, contentViewportBottom() - contentViewportTop());
    }

    private int playbackProgressY() {
        return Math.max(contentViewportTop() + PLAYBACK_PROGRESS_HEIGHT + PLAYBACK_PROGRESS_GAP, height - PLAYBACK_PROGRESS_BOTTOM_MARGIN - PLAYBACK_PROGRESS_HEIGHT);
    }

    private int playbackBottomControlsY() {
        return playbackProgressY() + PLAYBACK_PROGRESS_HEIGHT + PLAYBACK_BOTTOM_CONTROLS_GAP;
    }

    private boolean hidePlaybackScrollpane(double mouseX, double mouseY) {
        return showPlaybackProgressPreview(mouseX, mouseY);
    }

    private boolean showPlaybackProgressPreview(double mouseX, double mouseY) {
        return tab == Tab.PLAYBACK
                && (playbackPreviewPinned || playbackProgressPreview || overlayOpen() || insidePlaybackProgressPreviewZone(mouseX, mouseY));
    }

    private boolean insidePlaybackProgressPreviewZone(double mouseX, double mouseY) {
        if (playbackProgressSlider == null) return false;
        int left = mainX();
        int right = left + mainW();
        int top = playbackProgressY();
        int bottom = playbackBottomControlsY() + CONTROL_HEIGHT;
        return inside(mouseX, mouseY, left, top, right, bottom);
    }

    private int sidebarAreaLabelY() {
        return topY() + 8;
    }

    private int sidebarAreaViewportTop() {
        return sidebarAreaLabelY() + 16;
    }

    private int sidebarListsBottom() {
        return Math.max(sidebarAreaLabelY() + 120, height - 78);
    }

    private int sidebarSectionHeight() {
        return Math.max(60, (sidebarListsBottom() - sidebarAreaLabelY() - GAP) / 2);
    }

    private int sidebarScreenLabelY() {
        return sidebarAreaLabelY() + sidebarSectionHeight() + GAP;
    }

    private int sidebarAreaViewportBottom() {
        return Math.max(sidebarAreaViewportTop() + 1, sidebarAreaLabelY() + sidebarSectionHeight());
    }

    private int sidebarScreenViewportTop() {
        return sidebarScreenLabelY() + 16;
    }

    private int sidebarScreenViewportBottom() {
        return Math.max(sidebarScreenViewportTop() + 1, sidebarListsBottom());
    }

    private int sidebarAreaViewportHeight() {
        return Math.max(1, sidebarAreaViewportBottom() - sidebarAreaViewportTop());
    }

    private int sidebarScreenViewportHeight() {
        return Math.max(1, sidebarScreenViewportBottom() - sidebarScreenViewportTop());
    }

    private int estimateContentHeight(int mainW) {
        int offset = contentTop(mainW) - contentViewportTop(mainW);
        return switch (tab) {
            case CREATE_EDIT -> estimateCreateEditHeight(offset);
            case PLAYBACK -> estimatePlaybackHeight(offset);
            case SCREEN_SETTINGS -> estimateScreenSettingsHeight(offset);
        };
    }

    private int estimateCreateEditHeight(int offset) {
        VideoCreationEditor.Draft draft = editor.draft();
        if (draft.operation == VideoCreationEditor.Operation.CREATE_AREA) {
            return offset + 196;
        }
        return offset + (draft.screenMode == VideoCreationEditor.ScreenMode.RECTANGLE ? 368 : 336);
    }

    private int estimatePlaybackHeight(int offset) {
        ClientVideoScreen screen = selectedPlaybackScreen();
        int queueRows = screen == null ? 1 : Math.max(1, screen.infos.size());
        int queueY = playbackQueueY(0);
        return Math.max(offset + queueY, offset + queueY + 18 + queueRows * 12);
    }

    private int estimateScreenSettingsHeight(int offset) {
        ClientVideoScreen screen = selectedScreen();
        int entries = screen == null ? 0 : screen.metadata.entries().size();
        int displayHeight = SCREEN_SETTINGS_DISPLAY_Y + 118;
        int metaHeight = SCREEN_SETTINGS_META_CONTENT_Y + Math.max(138, 156 + Math.max(1, entries) * 12);
        return offset + Math.max(displayHeight, metaHeight);
    }

    private void addTabs(int x, int y, int width) {
        int gap = 4;
        int buttonW = Math.max(64, (width - gap * 2) / 3);
        addTabButton(Tab.CREATE_EDIT, x, y, buttonW);
        addTabButton(Tab.PLAYBACK, x + buttonW + gap, y, buttonW);
        addTabButton(Tab.SCREEN_SETTINGS, x + (buttonW + gap) * 2, y, buttonW);
    }

    private void addTabButton(Tab target, int x, int y, int width) {
        VpButtonWidget button = button(target.label(), x, y, width, () -> {
            tab = target;
            contentScroll = 0;
            if (target != Tab.PLAYBACK && target != Tab.SCREEN_SETTINGS) closeOverlays();
            if (target != Tab.PLAYBACK) {
                danmakuOverlayOpen = false;
                biliLocalQualityOverlayOpen = false;
                ccSubtitleOverlayOpen = false;
            }
            if (target != Tab.SCREEN_SETTINGS) {
                biliScreenQualityOverlayOpen = false;
                youtubeScreenQualityOverlay = false;
            }
            reopen(null);
        }).selected(tab == target);
        button.active = tab != target;
    }

    private void addSidebar(int x) {
        List<String> areas = areaNames();
        areaScrollContentHeight = areas.size() * ROW_HEIGHT;
        int row = sidebarAreaViewportTop() - areaScroll;
        widgetGroup = WidgetGroup.AREA_SCROLL;
        for (String areaName : areas) {
            VpButtonWidget button = button(areaName, x, row, SIDEBAR_WIDTH, () -> {
                selectedAreaName = areaName;
                selectedScreenName = firstScreenName(selectedAreaName);
                screenScroll = 0;
                confirmDeleteArea = false;
                confirmDeleteScreen = false;
                syncDraftFromSelection(false);
                reopen(null);
            }).selected(areaName.equals(selectedAreaName));
            button.active = !areaName.equals(selectedAreaName);
            row += ROW_HEIGHT;
        }

        List<ClientVideoScreen> screens = screensForSelectedArea();
        screenScrollContentHeight = screens.size() * ROW_HEIGHT;
        row = sidebarScreenViewportTop() - screenScroll;
        widgetGroup = WidgetGroup.SCREEN_SCROLL;
        for (ClientVideoScreen screen : screens) {
            VpButtonWidget button = button(screen.name, x, row, SIDEBAR_WIDTH, () -> {
                selectedScreenName = screen.name;
                confirmDeleteScreen = false;
                syncDraftFromSelection(true);
                reopen(null);
            }).selected(screen.name.equals(selectedScreenName));
            button.active = !screen.name.equals(selectedScreenName);
            row += ROW_HEIGHT;
        }

        widgetGroup = WidgetGroup.FIXED;
        int bottom = height - 70;
        VpButtonWidget deleteScreen = button(confirmDeleteScreen
                ? VpTexts.tr("button.videoplayer.confirm_delete_screen", "Confirm Delete Screen")
                : VpTexts.tr("button.videoplayer.delete_screen", "Delete Screen"), x, bottom, SIDEBAR_WIDTH, this::deleteSelectedScreen)
                .danger(true)
                .selected(confirmDeleteScreen);
        deleteScreen.active = selectedScreen() != null && canScreen(VideoPermissionAction.REMOVE_SCREEN, selectedScreen());
        VpButtonWidget deleteArea = button(confirmDeleteArea
                ? VpTexts.tr("button.videoplayer.confirm_delete_area", "Confirm Delete Area")
                : VpTexts.tr("button.videoplayer.delete_area", "Delete Area"), x, bottom + 24, SIDEBAR_WIDTH, this::deleteSelectedArea)
                .danger(true)
                .selected(confirmDeleteArea);
        deleteArea.active = selectedArea() != null && canArea(VideoPermissionAction.REMOVE_AREA, selectedArea());
    }

    private void initCreateEdit(int x, int y, int width) {
        clearSphereFields();
        int row = y;
        int contentW = Math.max(180, width);
        int operationW = Math.max(86, (contentW - GAP * 2) / 3);
        button(operationLabel(VideoCreationEditor.Operation.CREATE_AREA), x, row, operationW, () -> {
            editor.draft().operation = VideoCreationEditor.Operation.CREATE_AREA;
            editor.draft().name = editor.suggestedAreaName();
            syncDraftFromSelection(false);
            reopen(null);
        }).selected(editor.draft().operation == VideoCreationEditor.Operation.CREATE_AREA);
        button(operationLabel(VideoCreationEditor.Operation.CREATE_SCREEN), x + operationW + GAP, row, operationW, () -> {
            editor.draft().operation = VideoCreationEditor.Operation.CREATE_SCREEN;
            editor.draft().areaName = selectedAreaName == null ? "" : selectedAreaName;
            editor.draft().name = editor.suggestedScreenName(editor.draft().areaName);
            syncDraftFromSelection(false);
            reopen(null);
        }).selected(editor.draft().operation == VideoCreationEditor.Operation.CREATE_SCREEN);
        VpButtonWidget editButton = button(operationLabel(VideoCreationEditor.Operation.EDIT_SCREEN_GEOMETRY), x + (operationW + GAP) * 2, row, operationW, () -> {
            editor.draft().operation = VideoCreationEditor.Operation.EDIT_SCREEN_GEOMETRY;
            syncDraftFromSelection(true);
            reopen(null);
        }).selected(editor.draft().operation == VideoCreationEditor.Operation.EDIT_SCREEN_GEOMETRY);
        editButton.active = selectedScreen() != null;

        VideoCreationEditor.Draft draft = editor.draft();
        row += 44;
        if (draft.operation == VideoCreationEditor.Operation.CREATE_AREA) {
            nameField = textField(x, row, Math.min(260, contentW), draft.name.isBlank() ? editor.suggestedAreaName() : draft.name, VideoScreen.MAX_NAME_BYTES, VideoScreen::validNameInput);
            row += FORM_ROW_GAP;
            int buttonW = actionButtonWidth(contentW, 3);
            VpButtonWidget selectArea = button(selectionButtonText(), x, row, buttonW, this::toggleSelection).selected(editor.selecting());
            selectArea.active = editor.selecting() || canGlobal(VideoPermissionAction.CREATE_AREA);
            button(VpTexts.tr("button.videoplayer.clear_selection", "Clear Selection"), x + buttonW + GAP, row, buttonW, () -> {
                editor.clearSelection();
                reopen(null);
            });
            VpButtonWidget create = button(VpTexts.tr("button.videoplayer.create_area", "Create Area"), x + (buttonW + GAP) * 2, row, buttonW, button -> {
                copyCreateEditFieldsToDraft();
                editor.confirm(result -> closeOnOk(button, result));
            });
            create.active = editor.ready() && canGlobal(VideoPermissionAction.CREATE_AREA);
            return;
        }

        ClientVideoArea area = selectedArea();
        ClientVideoScreen screen = selectedScreen();
        int nameW = Math.min(260, contentW);
        if (draft.operation == VideoCreationEditor.Operation.CREATE_SCREEN) {
            nameField = textField(x, row, nameW, draft.name.isBlank() ? editor.suggestedScreenName(selectedAreaName) : draft.name, VideoScreen.MAX_NAME_BYTES, VideoScreen::validNameInput);
        } else {
            nameField = textField(x, row, nameW, selectedScreenName == null ? "" : selectedScreenName, VideoScreen.MAX_NAME_BYTES, VideoScreen::validNameInput);
            nameField.active = false;
        }
        row += FORM_ROW_GAP;
        int sourceButtonW = 72;
        int sourceW = Math.max(72, Math.min(260, contentW - sourceButtonW - GAP));
        sourceField = textField(x, row, sourceW, draft.operation == VideoCreationEditor.Operation.EDIT_SCREEN_GEOMETRY && screen != null ? safe(screen.source) : safe(draft.source), VideoScreen.MAX_NAME_BYTES, VideoScreen::validNameInput);
        button(VpTexts.tr("button.videoplayer.select", "Select"), x + sourceW + GAP, row, sourceButtonW, this::cycleSource).active = area != null;
        row += FORM_ROW_GAP;
        int typeW = actionButtonWidth(contentW, 2);
        Text surfaceLabel = draft.operation == VideoCreationEditor.Operation.CREATE_SCREEN
                ? VpTexts.tr("label.videoplayer.surface_after_create", "After Create: %s", VpTexts.text(draft.surface.translation()).getString())
                : VpTexts.tr("label.videoplayer.display_surface", "Display: %s", VpTexts.text(draft.surface.translation()).getString());
        VpButtonWidget draftSurface = button(surfaceLabel, x, row, typeW, () -> {
            copyCreateEditFieldsToDraft();
            VideoCreationEditor.Draft d = editor.draft();
            if (d.surface == ScreenSurface.FLAT) {
                d.surface = ScreenSurface.SPHERE_360;
            } else {
                d.surface = ScreenSurface.FLAT;
            }
            reopenPreservingDraft();
        }).selected(draft.surface == ScreenSurface.SPHERE_360);
        draftSurface.active = draft.surface == ScreenSurface.SPHERE_360 || draft.spherePreset;
        button(VpTexts.tr("label.videoplayer.video_mode", "Video: %s", draft.stereo3d ? "3D" : "2D"), x + typeW + GAP, row, typeW, () -> {
            draft.stereo3d = !draft.stereo3d;
            editor.draft().stereo3d = draft.stereo3d;
            reopenPreservingDraft();
        }).selected(draft.stereo3d);
        row += FORM_ROW_GAP;

        button(VpTexts.tr("label.videoplayer.mode_value", "Mode: %s", draft.screenMode.label()), x, row, Math.min(260, contentW), () -> {
            draft.screenMode = draft.screenMode.next();
            editor.draft().screenMode = draft.screenMode;
            reopenPreservingDraft();
        });
        row += FORM_ROW_GAP;
        if (draft.screenMode == VideoCreationEditor.ScreenMode.RECTANGLE) {
            int transformW = actionButtonWidth(contentW, 3);
            button(VpTexts.tr("label.videoplayer.rotation_value", "Rotation: %sdeg", draft.rectangleRotation * 90), x, row, transformW, () -> {
                draft.rectangleRotation = Math.floorMod(draft.rectangleRotation + 1, 4);
                editor.draft().rectangleRotation = draft.rectangleRotation;
                reopenPreservingDraft();
            }).selected(draft.rectangleRotation != 0);
            button(VpTexts.tr("label.videoplayer.flip_horizontal", "Horizontal Flip: %s", onOff(draft.rectangleFlipHorizontal).getString()), x + transformW + GAP, row, transformW, () -> {
                draft.rectangleFlipHorizontal = !draft.rectangleFlipHorizontal;
                editor.draft().rectangleFlipHorizontal = draft.rectangleFlipHorizontal;
                reopenPreservingDraft();
            }).selected(draft.rectangleFlipHorizontal);
            button(VpTexts.tr("label.videoplayer.flip_vertical", "Vertical Flip: %s", onOff(draft.rectangleFlipVertical).getString()), x + (transformW + GAP) * 2, row, transformW, () -> {
                draft.rectangleFlipVertical = !draft.rectangleFlipVertical;
                editor.draft().rectangleFlipVertical = draft.rectangleFlipVertical;
                reopenPreservingDraft();
            }).selected(draft.rectangleFlipVertical);
            row += FORM_ROW_GAP;
        }

        int presetW = actionButtonWidth(contentW, 3);
        button(VpTexts.tr("label.videoplayer.sphere_preset_value", "360 Preset: %s", onOff(draft.spherePreset).getString()), x, row, presetW, () -> {
            copyCreateEditFieldsToDraft();
            VideoCreationEditor.Draft d = editor.draft();
            if (d.spherePreset) {
                d.spherePreset = false;
                d.sphereCenter = null;
                if (d.surface == ScreenSurface.SPHERE_360) d.surface = ScreenSurface.FLAT;
                if (editor.selectingSpherePreset()) editor.clearSelection();
            } else {
                ensureSpherePresetDefaults(d);
            }
            reopenPreservingDraft();
        }).selected(draft.spherePreset);
        if (draft.spherePreset) {
            VpButtonWidget pickSphere = button(VpTexts.tr("button.videoplayer.pick_sphere_center_radius", "Pick Center/Radius"), x + presetW + GAP, row, presetW, () -> {
                copyCreateEditFieldsToDraft();
                ensureSpherePresetDefaults(editor.draft());
                editor.beginSpherePresetSelection(editor.draft());
            }).selected(editor.selectingSpherePreset());
            pickSphere.active = area != null && !draft.sphereSkybox;
            button(VpTexts.tr("button.videoplayer.clear_preset", "Clear Preset"), x + (presetW + GAP) * 2, row, presetW, () -> {
                copyCreateEditFieldsToDraft();
                editor.draft().spherePreset = false;
                editor.draft().sphereCenter = null;
                if (editor.draft().surface == ScreenSurface.SPHERE_360) editor.draft().surface = ScreenSurface.FLAT;
                if (editor.selectingSpherePreset()) editor.clearSelection();
                reopenPreservingDraft();
            });
            row += PARAM_ROW_GAP;

            int smallW = actionButtonWidth(contentW, 3);
            sphereLatField = textField(x, row, smallW, String.valueOf(draft.sphereLat), 4);
            sphereLonField = textField(x + smallW + GAP, row, smallW, String.valueOf(draft.sphereLon), 4);
            button(VpTexts.tr("label.videoplayer.skybox_value", "Skybox: %s", onOff(draft.sphereSkybox).getString()), x + (smallW + GAP) * 2, row, smallW, () -> {
                copyCreateEditFieldsToDraft();
                boolean skybox = !editor.draft().sphereSkybox;
                editor.draft().sphereSkybox = skybox;
                if (skybox && editor.selectingSpherePreset()) editor.clearSelection();
                reopenPreservingDraft();
            }).selected(draft.sphereSkybox);
            row += PARAM_ROW_GAP;

            int centerW = Math.max(36, (contentW - GAP * 3) / 4);
            sphereCenterXField = textField(x, row, centerW, draft.sphereCenter == null ? "" : format(draft.sphereCenter.x), 16);
            sphereCenterYField = textField(x + centerW + GAP, row, centerW, draft.sphereCenter == null ? "" : format(draft.sphereCenter.y), 16);
            sphereCenterZField = textField(x + (centerW + GAP) * 2, row, centerW, draft.sphereCenter == null ? "" : format(draft.sphereCenter.z), 16);
            sphereRadiusField = textField(x + (centerW + GAP) * 3, row, centerW, format(draft.sphereRadius), 16);
            boolean spherePlacementActive = !draft.sphereSkybox;
            sphereCenterXField.active = spherePlacementActive;
            sphereCenterYField.active = spherePlacementActive;
            sphereCenterZField.active = spherePlacementActive;
            sphereRadiusField.active = spherePlacementActive;
            row += PARAM_ROW_GAP;

            sphereRotXField = textField(x, row, smallW, format(draft.sphereRotX), 16);
            sphereRotYField = textField(x + smallW + GAP, row, smallW, format(draft.sphereRotY), 16);
            sphereRotZField = textField(x + (smallW + GAP) * 2, row, smallW, format(draft.sphereRotZ), 16);
            row += FORM_ROW_GAP;
        } else {
            row += FORM_ROW_GAP;
        }

        int actionCount = draft.operation == VideoCreationEditor.Operation.EDIT_SCREEN_GEOMETRY ? 4 : 3;
        int buttonW = actionButtonWidth(contentW, actionCount);
        VpButtonWidget select = button(selectionButtonText(), x, row, buttonW, this::toggleSelection).selected(editor.selecting());
        select.active = editor.selecting() || canSelectForDraft(draft, area, screen);
        button(VpTexts.tr("button.videoplayer.clear_selection", "Clear Selection"), x + buttonW + GAP, row, buttonW, () -> {
            editor.clearSelection();
            reopen(null);
        });
        VpButtonWidget confirm = button(draft.operation == VideoCreationEditor.Operation.CREATE_SCREEN
                ? VpTexts.tr("button.videoplayer.create_screen", "Create Screen")
                : VpTexts.tr("button.videoplayer.save_geometry", "Save Geometry"), x + (buttonW + GAP) * 2, row, buttonW, button -> {
            copyCreateEditFieldsToDraft();
            editor.confirm(result -> closeOnOk(button, result));
        });
        confirm.active = editor.ready() && canSubmitDraft(draft, area, screen);
        if (draft.operation == VideoCreationEditor.Operation.EDIT_SCREEN_GEOMETRY) {
            VpButtonWidget configSave = button(VpTexts.tr("button.videoplayer.save_config", "Save Config"), x + (buttonW + GAP) * 3, row, buttonW, this::saveScreenConfig);
            configSave.active = canSaveScreenConfig(screen, draft) && canScreen(VideoPermissionAction.UPDATE_SCREEN, screen);
        }
    }

    private void initPlayback(int x, int y, int width) {
        int row = y;
        int contentW = Math.max(180, width);
        ClientVideoScreen selected = selectedScreen();
        int playButtonW = contentW < 260 ? 58 : 72;
        int idleListButtonW = contentW < 260 ? 72 : 84;
        int urlW = Math.max(70, contentW - playButtonW - idleListButtonW - GAP * 2);
        urlField = textField(x, row, urlW, "", VideoScreen.MAX_PLAY_URL_BYTES, VideoScreen::validPlayUrlInput);
        VpButtonWidget play = button(VpTexts.tr("button.videoplayer.play", "Play"), x + urlW + GAP, row, playButtonW, button -> {
            ClientVideoScreen screen = selectedScreen();
            if (screen == null || urlField.getText().isBlank()) return;
            ClientPacketHandler.request(screen.getScreen(), urlField.getText().trim(), permissionFeedback(button));
        });
        play.active = selected != null && canScreen(VideoPermissionAction.PLAY, selected.getScreen());
        VpButtonWidget idleList = button(VpTexts.tr("button.videoplayer.idle_list", "Idle List"), x + urlW + GAP + playButtonW + GAP, row, idleListButtonW, () -> {
            ClientVideoScreen screen = selectedPlaybackScreen();
            if (screen != null && client != null) client.setScreen(new IdlePlayListScreen(this, screen));
        });
        idleList.active = selected != null && canScreen(VideoPermissionAction.SET_IDLE_PLAY, selectedPlaybackScreen());
        row += BUTTON_ROW_GAP;
        int modeW = actionButtonWidth(contentW, 2);
        VpButtonWidget stereo = button(VpTexts.tr("label.videoplayer.video_mode", "Video: %s", selected == null || !selected.stereo3d ? "2D" : "3D"), x, row, modeW, this::togglePlaybackStereo)
                .selected(selected != null && selected.stereo3d);
        stereo.active = selected != null && canScreen(VideoPermissionAction.UPDATE_SCREEN, selected);
        VpButtonWidget surface = button(VpTexts.tr("label.videoplayer.display_surface", "Display: %s",
                        selected == null ? VpTexts.tr("label.videoplayer.surface.flat", "Flat").getString() : VpTexts.text(selected.surface.translation()).getString()),
                x + modeW + GAP, row, modeW, this::togglePlaybackSurface)
                .selected(selected != null && selected.surface == ScreenSurface.SPHERE_360);
        surface.active = selected != null && canScreen(VideoPermissionAction.UPDATE_SCREEN, selected);
        row += BUTTON_ROW_GAP;
        int buttonW = actionButtonWidth(contentW, 3);
        VpButtonWidget voteSkip = button(VpTexts.tr("button.videoplayer.vote_skip", "Vote Skip"), x, row, buttonW, button -> {
            ClientVideoScreen screen = selectedScreen();
            if (screen != null) ClientPacketHandler.skip(screen.getScreen(), false, permissionFeedback(button));
        });
        voteSkip.active = selected != null && canScreen(VideoPermissionAction.VOTE_SKIP, selected.getScreen());
        VpButtonWidget forceSkip = button(VpTexts.tr("button.videoplayer.force_skip", "Force Skip"), x + buttonW + GAP, row, buttonW, button -> {
            ClientVideoScreen screen = selectedScreen();
            if (screen != null) ClientPacketHandler.skip(screen.getScreen(), true, permissionFeedback(button));
        });
        forceSkip.active = selected != null && canScreen(VideoPermissionAction.FORCE_SKIP, selected.getScreen());
        VpButtonWidget sync = button(VpTexts.tr("button.videoplayer.sync_progress", "Sync Progress"), x + (buttonW + GAP) * 2, row, buttonW, button -> {
            ClientVideoScreen screen = selectedScreen();
            if (screen != null) ClientPacketHandler.sync(screen.getScreen(), permissionFeedback(button));
        });
        sync.active = selected != null && canScreen(VideoPermissionAction.SYNC, selected.getScreen());
        row += BUTTON_ROW_GAP;
        int sliderW = actionButtonWidth(contentW, 2);
        slider(VpTexts.tr("label.videoplayer.brightness", "Brightness"), x, row, sliderW, VideoPlayerClient.config.brightness, value -> {
            VideoPlayerClient.config.brightness = value;
        });
        ClientVideoScreen playbackScreen = selectedPlaybackScreen();
        if (usesMpvPlaybackVolume(playbackScreen)) {
            slider(VpTexts.tr("label.videoplayer.volume", "Volume"), x + sliderW + GAP, row, sliderW, playbackScreen == null ? 100 : playbackScreen.volume, value -> {
                ClientVideoScreen current = selectedPlaybackScreen();
                if (current == null) return;
                current.volume = Math.clamp(value, 0, 100);
                ScreenVolumeCache.put(current, current.volume);
                if (current.player instanceof VideoPlayer player) player.setVolume(current.volume);
            }, ignored -> {
            });
        } else {
            slider(VpTexts.tr("label.videoplayer.volume", "Volume"), x + sliderW + GAP, row, sliderW, VideoPlayerClient.config.volume, value -> {
                VideoPlayerClient.config.volume = value;
                VideoPlayerClient.applyConfiguredVolume();
            });
        }
        addPlaybackProgressSlider(x, width);
        addPlaybackBottomControls(x, width);
    }

    private void addPlaybackProgressSlider(int x, int width) {
        WidgetGroup previous = widgetGroup;
        widgetGroup = WidgetGroup.FIXED;
        playbackProgressSlider = progressSlider(x, playbackProgressY(), Math.max(120, width),
                this::playbackProgressState,
                this::previewPlaybackProgress,
                this::commitPlaybackProgress,
                this::beginPlaybackProgressDrag,
                this::endPlaybackProgressDrag);
        widgetGroup = previous;
    }

    private void addPlaybackBottomControls(int x, int width) {
        WidgetGroup previous = widgetGroup;
        widgetGroup = WidgetGroup.FIXED;
        int row = playbackBottomControlsY();
        int contentW = Math.max(180, width);
        int pinW = CONTROL_HEIGHT;
        int qualityW = Math.max(86, Math.min(150, (contentW - pinW - GAP) / 4));
        int qualityX = x + contentW - qualityW;
        int pinX = x + contentW - pinW;
        qualityX = pinX - GAP - qualityW;
        int leftW = Math.max(0, qualityX - x - GAP);
        int ccW = Math.max(44, Math.min(112, leftW / 3));
        int danmakuToggleW = Math.max(34, Math.min(150, leftW - ccW - CONTROL_HEIGHT - GAP * 2));
        int leftControlsW = danmakuToggleW + CONTROL_HEIGHT + ccW + GAP * 2;
        if (leftControlsW > leftW) {
            int overflow = leftControlsW - leftW;
            int ccShrink = Math.min(overflow, Math.max(0, ccW - 34));
            ccW -= ccShrink;
            overflow -= ccShrink;
            danmakuToggleW = Math.max(34, danmakuToggleW - overflow);
        }
        VpButtonWidget danmaku = button(VpTexts.tr("label.videoplayer.danmaku_value", "Danmaku: %s", onOff(ClientDanmakuController.isGlobalEnabled()).getString()), x, row, danmakuToggleW, () -> {
            ClientDanmakuController.toggleGlobal();
            reopen(null);
        }).selected(ClientDanmakuController.isGlobalEnabled());
        danmaku.active = true;
        int danmakuSettingsX = x + danmakuToggleW + GAP;
        VpButtonWidget danmakuSettings = squareButton(VpTexts.tr("button.videoplayer.danmaku_short", "D"), danmakuSettingsX, row, () -> {
            danmakuOverlayOpen = !danmakuOverlayOpen;
            biliLocalQualityOverlayOpen = false;
            biliScreenQualityOverlayOpen = false;
            youtubeScreenQualityOverlay = false;
            clearAndInit();
        }).selected(danmakuOverlayOpen);
        danmakuSettings.active = true;
        if (danmakuOverlayOpen) {
            initDanmakuOverlay(danmakuSettings.getRight(), row - 220, x, x + contentW);
        }
        ClientVideoScreen playbackScreen = selectedPlaybackScreen();
        int ccX = danmakuSettings.getRight() + GAP;
        VpButtonWidget ccSubtitle = button(ccSubtitleButtonText(playbackScreen), ccX, row, ccW, () -> {
            boolean open = !ccSubtitleOverlayOpen;
            ccSubtitleOverlayOpen = open;
            if (open) biliQualityOverlayScroll = 0;
            danmakuOverlayOpen = false;
            biliLocalQualityOverlayOpen = false;
            biliScreenQualityOverlayOpen = false;
            youtubeScreenQualityOverlay = false;
            clearAndInit();
        }).selected(ccSubtitleOverlayOpen || (playbackScreen != null && playbackScreen.subtitles().hasSelectedTrack()));
        ccSubtitle.active = playbackScreen != null && playbackScreen.subtitles().availableForCurrentVideo();
        if (ccSubtitleOverlayOpen && ccSubtitle.active) {
            initCcSubtitleOverlay(ccSubtitle.getRight(), row - 144, x, x + contentW);
        } else if (ccSubtitleOverlayOpen) {
            ccSubtitleOverlayOpen = false;
        }
        VpButtonWidget quality = button(localBiliQualityButtonText(), qualityX, row, qualityW, () -> {
            boolean open = !biliLocalQualityOverlayOpen;
            biliLocalQualityOverlayOpen = open;
            if (open) biliQualityOverlayScroll = 0;
            danmakuOverlayOpen = false;
            ccSubtitleOverlayOpen = false;
            biliScreenQualityOverlayOpen = false;
            youtubeScreenQualityOverlay = false;
            clearAndInit();
        }).selected(biliLocalQualityOverlayOpen);
        quality.active = currentBiliInfo(playbackScreen) != null || currentYouTubeInfo(playbackScreen) != null;
        if (biliLocalQualityOverlayOpen && quality.active) {
            initBiliLocalQualityOverlay(quality.getRight(), row - 144, x, x + contentW);
        } else if (biliLocalQualityOverlayOpen) {
            biliLocalQualityOverlayOpen = false;
        }
        VpButtonWidget pin = squareButton("钉", pinX, row, () -> {
            playbackPreviewPinned = !playbackPreviewPinned;
            clearAndInit();
        }).selected(playbackPreviewPinned);
        ClientVideoScreen pinnedScreen = selectedPlaybackScreen();
        pin.active = pinnedScreen != null && pinnedScreen.player != null;
        widgetGroup = previous;
    }

    private void initScreenSettings(int x, int y, int width) {
        initDisplay(x, y + SCREEN_SETTINGS_DISPLAY_Y, width);
        initMeta(x, y + SCREEN_SETTINGS_META_CONTENT_Y, width);
    }

    private void initDisplay(int x, int y, int width) {
        ClientVideoScreen screen = selectedScreen();
        int contentW = Math.max(180, width);
        int row = y;
        int displayModeW = actionButtonWidth(contentW, 2);
        VpButtonWidget stretch = button(VpTexts.tr("button.videoplayer.stretch", "Stretch"), x, row, displayModeW, button -> {
            ClientVideoScreen s = selectedScreen();
            if (s != null) setScaleAndRefresh(s, true, 1, 1, permissionFeedback(button));
        }).selected(screen != null && screen.fill);
        stretch.active = screen != null && canScreen(VideoPermissionAction.SET_SCALE, screen);
        VpButtonWidget auto = button(VpTexts.tr("button.videoplayer.auto_aspect", "Auto Aspect"), x + displayModeW + GAP, row, displayModeW, button -> {
            ClientVideoScreen s = selectedScreen();
            if (s != null) setScaleAndRefresh(s, false, 1, 1, permissionFeedback(button));
        }).selected(screen != null && !screen.fill);
        auto.active = screen != null && canScreen(VideoPermissionAction.SET_SCALE, screen);
        row += FORM_ROW_GAP;
        VpButtonWidget danmaku = button(VpTexts.tr("label.videoplayer.danmaku_value", "Danmaku: %s", boolLabel(screen, ScreenMetadata.KEY_DANMAKU_ENABLED, true).getString()), x, row, contentW, button -> toggleMeta(button, ScreenMetadata.KEY_DANMAKU_ENABLED, true))
                .selected(screen != null && screen.metadata.getBool(ScreenMetadata.KEY_DANMAKU_ENABLED, true));
        danmaku.active = screen != null && canScreen(VideoPermissionAction.SET_METADATA, screen);
        row += FORM_ROW_GAP;
        VpButtonWidget biliQuality = button(screenBiliQualityButtonText(screen), x, row, contentW, () -> {
            boolean open = !biliScreenQualityOverlayOpen || youtubeScreenQualityOverlay;
            biliScreenQualityOverlayOpen = open;
            youtubeScreenQualityOverlay = false;
            if (open) biliQualityOverlayScroll = 0;
            danmakuOverlayOpen = false;
            biliLocalQualityOverlayOpen = false;
            clearAndInit();
        }).selected(biliScreenQualityOverlayOpen && !youtubeScreenQualityOverlay);
        biliQuality.active = screen != null && canScreen(VideoPermissionAction.SET_METADATA, screen);
        if (biliScreenQualityOverlayOpen && !youtubeScreenQualityOverlay && biliQuality.active) {
            initBiliScreenQualityOverlay(biliQuality.getRight(), row + CONTROL_HEIGHT + 6, x, x + contentW);
        } else if (biliScreenQualityOverlayOpen && !youtubeScreenQualityOverlay && !biliQuality.active) {
            biliScreenQualityOverlayOpen = false;
        }
        row += FORM_ROW_GAP;
        VpButtonWidget youtubeQuality = button(screenYouTubeQualityButtonText(screen), x, row, contentW, () -> {
            boolean open = !biliScreenQualityOverlayOpen || !youtubeScreenQualityOverlay;
            biliScreenQualityOverlayOpen = open;
            youtubeScreenQualityOverlay = open;
            if (open) biliQualityOverlayScroll = 0;
            danmakuOverlayOpen = false;
            biliLocalQualityOverlayOpen = false;
            clearAndInit();
        }).selected(biliScreenQualityOverlayOpen && youtubeScreenQualityOverlay);
        youtubeQuality.active = screen != null && canScreen(VideoPermissionAction.SET_METADATA, screen);
        if (biliScreenQualityOverlayOpen && youtubeScreenQualityOverlay && youtubeQuality.active) {
            initYouTubeScreenQualityOverlay(youtubeQuality.getRight(), row + CONTROL_HEIGHT + 6, x, x + contentW);
        } else if (biliScreenQualityOverlayOpen && youtubeScreenQualityOverlay && !youtubeQuality.active) {
            biliScreenQualityOverlayOpen = false;
            youtubeScreenQualityOverlay = false;
        }
        row += FORM_ROW_GAP;
        VpButtonWidget mapping = button(VpTexts.tr("button.videoplayer.open_mapping_editor", "Open Mapping Editor"), x, row, contentW, () -> {
            ClientVideoScreen selected = selectedScreen();
            if (selected != null && selected.fill) client.setScreen(new VideoMappingScreen(this, selected));
        });
        mapping.active = screen != null && screen.fill && screen.vertices.size() >= 3 && canScreen(VideoPermissionAction.SET_METADATA, screen);
        row += FORM_ROW_GAP;
        int scaleSliderW = actionButtonWidth(contentW, 2);
        VpSliderWidget scaleX = slider("Scale X", x, row, scaleSliderW, scaleToSliderValue(screen == null ? 1 : screen.scaleX), value -> {
        }, value -> setScreenScaleX(sliderValueToScale(value)), value -> "Scale X: " + format(sliderValueToScale(value)));
        scaleX.active = screen != null && canScreen(VideoPermissionAction.SET_SCALE, screen);
        VpSliderWidget scaleY = slider("Scale Y", x + scaleSliderW + GAP, row, scaleSliderW, scaleToSliderValue(screen == null ? 1 : screen.scaleY), value -> {
        }, value -> setScreenScaleY(sliderValueToScale(value)), value -> "Scale Y: " + format(sliderValueToScale(value)));
        scaleY.active = screen != null && canScreen(VideoPermissionAction.SET_SCALE, screen);
    }

    private void initMeta(int x, int y, int width) {
        ClientVideoScreen screen = selectedScreen();
        int contentW = Math.max(180, width);
        int row = y;
        int toggleW = actionButtonWidth(contentW, 3);
        VpButtonWidget mute = button(VpTexts.tr("label.videoplayer.mute_value", "Mute: %s", boolLabel(screen, "mute", false).getString()), x, row, toggleW, button -> toggleMeta(button, "mute", false))
                .selected(screen != null && screen.metadata.getBool("mute", false));
        mute.active = screen != null && canScreen(VideoPermissionAction.SET_METADATA, screen);
        VpButtonWidget interactable = button(VpTexts.tr("label.videoplayer.interactable_value", "Interactable: %s", boolLabel(screen, "interactable", true).getString()), x + toggleW + GAP, row, toggleW, button -> toggleMeta(button, "interactable", true))
                .selected(screen != null && screen.metadata.getBool("interactable", true));
        interactable.active = screen != null && canScreen(VideoPermissionAction.SET_METADATA, screen);
        VpButtonWidget autoSync = button(VpTexts.tr("label.videoplayer.auto_sync_value", "Auto Sync: %s", boolLabel(screen, "autoSync", false).getString()), x + (toggleW + GAP) * 2, row, toggleW, button -> toggleMeta(button, "autoSync", false))
                .selected(screen != null && screen.metadata.getBool("autoSync", false));
        autoSync.active = screen != null && canScreen(VideoPermissionAction.SET_METADATA, screen);

        row += 30;
        VpSliderWidget defaultVolume = slider(VpTexts.tr("label.videoplayer.default_volume", "Default Volume"), x, row, contentW, screen == null ? 100 : screen.defaultVolume(), value -> {
        }, this::setDefaultVolume);
        defaultVolume.active = screen != null && canScreen(VideoPermissionAction.SET_METADATA, screen);

        row += 36;
        int keyW = Math.max(100, Math.min(220, (contentW - GAP) / 2));
        int typeW = Math.max(86, Math.min(120, contentW - keyW - GAP));
        customKeyField = textField(x, row, keyW, "", 64);
        VpButtonWidget type = button(VpTexts.tr("label.videoplayer.type_value", "Type: %s", customMetaType.label()), x + keyW + GAP, row, typeW, this::cycleCustomMetaType);
        type.active = screen != null;

        row += 30;
        int setW = 72;
        int removeW = 72;
        int valueW = Math.max(100, contentW - setW - removeW - GAP * 2);
        customValueField = textField(x, row, valueW, defaultValueFor(customMetaType), MetaValue.MAX_STRING_BYTES);
        VpButtonWidget set = button(VpTexts.tr("button.videoplayer.set", "Set"), x + valueW + GAP, row, setW, button -> setCustomMeta(button, false));
        set.active = screen != null && canScreen(VideoPermissionAction.SET_METADATA, screen);
        VpButtonWidget remove = button(VpTexts.tr("button.videoplayer.remove", "Remove"), x + valueW + GAP + setW + GAP, row, removeW, button -> setCustomMeta(button, true)).danger(true);
        remove.active = screen != null && canScreen(VideoPermissionAction.SET_METADATA, screen);
    }

    private void drawSidebarLabels(DrawContext context, int x) {
        drawLabel(context, "Area", x, sidebarAreaLabelY(), THEME.primaryTextColor());
        drawLabel(context, "Screen", x, sidebarScreenLabelY(), THEME.primaryTextColor());
    }

    private void drawTabContent(DrawContext context, int x, int y, int width, int mouseX, int mouseY) {
        switch (tab) {
            case CREATE_EDIT -> drawCreateEdit(context, x, y);
            case PLAYBACK -> drawPlayback(context, x, y, mouseX, mouseY);
            case SCREEN_SETTINGS -> drawScreenSettings(context, x, y, width);
        }
    }

    private void renderContent(DrawContext context, int mouseX, int mouseY, float delta, int x, int y, int width) {
        context.enableScissor(x, contentViewportTop(), x + width, contentViewportBottom());
        drawTabContent(context, x, y, width, mouseX, mouseY);
        if (!hidePlaybackScrollpane(mouseX, mouseY)) {
            renderDrawables(context, contentScrollDrawables, mouseX, mouseY, delta);
        }
        context.disableScissor();
    }

    private void renderClippedDrawables(DrawContext context, List<Drawable> drawables, int mouseX, int mouseY, float delta,
                                        int left, int top, int right, int bottom) {
        context.enableScissor(left, top, right, bottom);
        renderDrawables(context, drawables, mouseX, mouseY, delta);
        context.disableScissor();
    }

    private void renderDrawables(DrawContext context, List<Drawable> drawables, int mouseX, int mouseY, float delta) {
        for (Drawable drawable : drawables) {
            drawable.render(context, mouseX, mouseY, delta);
        }
    }

    private void renderActiveOverlay(DrawContext context, int mouseX, int mouseY, float delta) {
        if (!overlayOpen() || danmakuOverlayW <= 0 || danmakuOverlayH <= 0) {
            return;
        }
        if (danmakuOverlayOpen) {
            renderDanmakuOverlay(context, mouseX, mouseY, delta);
        } else if (ccSubtitleOverlayOpen) {
            renderCcSubtitleOverlay(context, mouseX, mouseY, delta);
        } else {
            renderBiliQualityOverlay(context, mouseX, mouseY, delta);
        }
    }

    private void renderDanmakuOverlay(DrawContext context, int mouseX, int mouseY, float delta) {
        if (!danmakuOverlayOpen || tab != Tab.PLAYBACK || danmakuOverlayW <= 0 || danmakuOverlayH <= 0) {
            return;
        }
        VpUiRenderer.drawBox(context, danmakuOverlayX, danmakuOverlayY, danmakuOverlayW, danmakuOverlayH,
                VpUiRenderer.withAlpha(VpUiRenderer.darken(THEME.panelBackgroundColor(), 0.04f), 0xF2),
                THEME.panelBorderColor());
        int innerX = danmakuOverlayX + 10;
        boolean showDensity = showDanmakuDensityControls();
        drawLabel(context, VpTexts.tr("label.videoplayer.danmaku_settings", "Danmaku Settings"), innerX, danmakuOverlayY + 8, THEME.primaryTextColor());
        drawLabel(context, VpTexts.tr("label.videoplayer.display_range", "Display Range"), innerX, danmakuOverlayY + 47, THEME.secondaryTextColor());
        if (showDensity) {
            drawLabel(context, VpTexts.tr("label.videoplayer.danmaku_density", "Danmaku Density"), innerX, danmakuOverlayY + 80, THEME.secondaryTextColor());
        }
        drawLabel(context, VpTexts.tr("label.videoplayer.rolling_speed", "Rolling Speed"), innerX, danmakuOverlayY + (showDensity ? 113 : 80), THEME.secondaryTextColor());
        renderDrawables(context, danmakuOverlayDrawables, mouseX, mouseY, delta);
    }

    private void renderBiliQualityOverlay(DrawContext context, int mouseX, int mouseY, float delta) {
        if (!biliLocalQualityOverlayOpen && !biliScreenQualityOverlayOpen) {
            return;
        }
        VpUiRenderer.drawBox(context, danmakuOverlayX, danmakuOverlayY, danmakuOverlayW, danmakuOverlayH,
                VpUiRenderer.withAlpha(VpUiRenderer.darken(THEME.panelBackgroundColor(), 0.04f), 0xF2),
                THEME.panelBorderColor());
        Text title;
        if (biliLocalQualityOverlayOpen) {
            title = currentYouTubeInfo(selectedPlaybackScreen()) != null
                    ? VpTexts.tr("label.videoplayer.youtube_quality.local", "YouTube Quality")
                    : VpTexts.tr("label.videoplayer.bili_quality.local", "Bili Quality");
        } else {
            title = youtubeScreenQualityOverlay
                    ? VpTexts.tr("label.videoplayer.youtube_quality.screen_limit", "YouTube Limit")
                    : VpTexts.tr("label.videoplayer.bili_quality.screen_limit", "Bili Limit");
        }
        drawLabel(context, title, danmakuOverlayX + 10, danmakuOverlayY + 8, THEME.primaryTextColor());
        int left = danmakuOverlayX + BILI_QUALITY_OVERLAY_PADDING;
        int right = danmakuOverlayX + danmakuOverlayW - BILI_QUALITY_OVERLAY_PADDING;
        context.enableScissor(left, biliQualityOverlayViewportTop, right, biliQualityOverlayViewportBottom);
        renderDrawables(context, danmakuOverlayDrawables, mouseX, mouseY, delta);
        context.disableScissor();
        drawScrollbar(context, right - 4, biliQualityOverlayViewportTop, biliQualityOverlayViewportBottom, biliQualityOverlayScroll, biliQualityOverlayContentHeight);
    }

    private void renderCcSubtitleOverlay(DrawContext context, int mouseX, int mouseY, float delta) {
        if (!ccSubtitleOverlayOpen) {
            return;
        }
        VpUiRenderer.drawBox(context, danmakuOverlayX, danmakuOverlayY, danmakuOverlayW, danmakuOverlayH,
                VpUiRenderer.withAlpha(VpUiRenderer.darken(THEME.panelBackgroundColor(), 0.04f), 0xF2),
                THEME.panelBorderColor());
        drawLabel(context, VpTexts.tr("label.videoplayer.cc_subtitle", "CC Subtitles"), danmakuOverlayX + 10, danmakuOverlayY + 8, THEME.primaryTextColor());
        int left = danmakuOverlayX + BILI_QUALITY_OVERLAY_PADDING;
        int right = danmakuOverlayX + danmakuOverlayW - BILI_QUALITY_OVERLAY_PADDING;
        context.enableScissor(left, biliQualityOverlayViewportTop, right, biliQualityOverlayViewportBottom);
        renderDrawables(context, danmakuOverlayDrawables, mouseX, mouseY, delta);
        context.disableScissor();
        drawScrollbar(context, right - 4, biliQualityOverlayViewportTop, biliQualityOverlayViewportBottom, biliQualityOverlayScroll, biliQualityOverlayContentHeight);
    }

    private boolean insideActiveOverlay(double mouseX, double mouseY) {
        return overlayOpen()
                && danmakuOverlayW > 0
                && danmakuOverlayH > 0
                && inside(mouseX, mouseY, danmakuOverlayX, danmakuOverlayY, danmakuOverlayX + danmakuOverlayW, danmakuOverlayY + danmakuOverlayH);
    }

    private boolean overlayOpen() {
        return tab == Tab.PLAYBACK && (danmakuOverlayOpen || biliLocalQualityOverlayOpen || ccSubtitleOverlayOpen)
                || tab == Tab.SCREEN_SETTINGS && biliScreenQualityOverlayOpen;
    }

    private boolean biliQualityOverlayOpen() {
        return biliLocalQualityOverlayOpen || biliScreenQualityOverlayOpen;
    }

    private boolean scrollableOverlayOpen() {
        return biliQualityOverlayOpen() || ccSubtitleOverlayOpen;
    }

    private void closeOverlays() {
        danmakuOverlayOpen = false;
        biliLocalQualityOverlayOpen = false;
        biliScreenQualityOverlayOpen = false;
        youtubeScreenQualityOverlay = false;
        ccSubtitleOverlayOpen = false;
    }

    private boolean scrollBiliQualityOverlay(int delta) {
        int viewportHeight = Math.max(1, biliQualityOverlayViewportBottom - biliQualityOverlayViewportTop);
        int next = clampScroll(biliQualityOverlayScroll + delta, biliQualityOverlayContentHeight, viewportHeight);
        if (next == biliQualityOverlayScroll) {
            return true;
        }
        biliQualityOverlayScroll = next;
        rebuildBiliQualityOverlayAtCurrentPosition();
        return true;
    }

    private void rebuildBiliQualityOverlayAtCurrentPosition() {
        int anchorRight = danmakuOverlayX + danmakuOverlayW;
        int anchorY = danmakuOverlayY;
        for (ClickableWidget widget : danmakuOverlayWidgets) {
            remove(widget);
        }
        danmakuOverlayDrawables.clear();
        danmakuOverlayWidgets.clear();
        activeDanmakuOverlayWidget = null;
        if (biliLocalQualityOverlayOpen) {
            initBiliLocalQualityOverlay(anchorRight, anchorY, mainX(), mainX() + mainW());
        } else if (biliScreenQualityOverlayOpen) {
            if (youtubeScreenQualityOverlay) {
                initYouTubeScreenQualityOverlay(anchorRight, anchorY, mainX(), mainX() + mainW());
            } else {
                initBiliScreenQualityOverlay(anchorRight, anchorY, mainX(), mainX() + mainW());
            }
        } else if (ccSubtitleOverlayOpen) {
            initCcSubtitleOverlay(anchorRight, anchorY, mainX(), mainX() + mainW());
        }
    }

    private void drawScrollbar(DrawContext context, int x, int top, int bottom, int scroll, int contentHeight) {
        int viewportHeight = Math.max(1, bottom - top);
        int maxScroll = Math.max(0, contentHeight - viewportHeight);
        if (maxScroll <= 0) {
            return;
        }
        int trackColor = VpUiRenderer.withAlpha(VpUiRenderer.blend(THEME.panelBorderColor(), THEME.panelBackgroundColor(), 0.55f), 0x88);
        int thumbColor = VpUiRenderer.withAlpha(VpUiRenderer.blend(THEME.secondaryTextColor(), THEME.accentColor(), 0.35f), 0xDD);
        int thumbHeight = Math.max(14, viewportHeight * viewportHeight / Math.max(viewportHeight, contentHeight));
        int thumbTravel = Math.max(1, viewportHeight - thumbHeight);
        int thumbY = top + thumbTravel * Math.clamp(scroll, 0, maxScroll) / maxScroll;
        VpUiRenderer.drawBox(context, x, top, 4, viewportHeight, trackColor, trackColor);
        VpUiRenderer.drawBox(context, x, thumbY, 4, thumbHeight, thumbColor, thumbColor);
    }

    private void drawCreateEdit(DrawContext context, int x, int y) {
        VideoCreationEditor.Draft draft = editor.draft();
        int contentW = Math.max(180, width - x - 14);
        int row = y + 44;
        if (draft.operation == VideoCreationEditor.Operation.CREATE_AREA) {
            drawLabel(context, VpTexts.tr("label.videoplayer.name", "Name"), x, row - LABEL_OFFSET, THEME.secondaryTextColor());
            row += FORM_ROW_GAP;
            drawLabel(context, VpTexts.tr("label.videoplayer.selection_points", "Selection: %s", editor.pointProgress()), x, row - LABEL_OFFSET, THEME.secondaryTextColor());
        } else {
            drawLabel(context, VpTexts.tr("label.videoplayer.name", "Name"), x, row - LABEL_OFFSET, THEME.secondaryTextColor());
            row += FORM_ROW_GAP;
            drawLabel(context, "Source", x, row - LABEL_OFFSET, THEME.secondaryTextColor());
            row += FORM_ROW_GAP;
            drawLabel(context, draft.operation == VideoCreationEditor.Operation.CREATE_SCREEN
                    ? VpTexts.tr("label.videoplayer.display_after_create_video", "Display After Create / Video")
                    : VpTexts.tr("label.videoplayer.display_video", "Display / Video"), x, row - LABEL_OFFSET, THEME.secondaryTextColor());
            row += FORM_ROW_GAP;
            drawLabel(context, VpTexts.tr("label.videoplayer.mode", "Mode"), x, row - LABEL_OFFSET, THEME.secondaryTextColor());
            row += FORM_ROW_GAP;
            if (draft.screenMode == VideoCreationEditor.ScreenMode.RECTANGLE) {
                drawLabel(context, VpTexts.tr("label.videoplayer.rectangle_direction", "Rectangle Direction"), x, row - LABEL_OFFSET, THEME.secondaryTextColor());
                row += FORM_ROW_GAP;
            }
            drawLabel(context, VpTexts.tr("label.videoplayer.sphere_preset", "360 Preset"), x, row - LABEL_OFFSET, THEME.secondaryTextColor());
            if (draft.spherePreset) {
                row += PARAM_ROW_GAP;

                int smallW = actionButtonWidth(contentW, 3);
                drawLabel(context, "Lat", x, row - LABEL_OFFSET, THEME.secondaryTextColor());
                drawLabel(context, "Lon", x + smallW + GAP, row - LABEL_OFFSET, THEME.secondaryTextColor());
                drawLabel(context, VpTexts.tr("label.videoplayer.skybox", "Skybox"), x + (smallW + GAP) * 2, row - LABEL_OFFSET, THEME.secondaryTextColor());
                row += PARAM_ROW_GAP;

                int centerW = Math.max(36, (contentW - GAP * 3) / 4);
                drawLabel(context, "X", x, row - LABEL_OFFSET, THEME.secondaryTextColor());
                drawLabel(context, "Y", x + centerW + GAP, row - LABEL_OFFSET, THEME.secondaryTextColor());
                drawLabel(context, "Z", x + (centerW + GAP) * 2, row - LABEL_OFFSET, THEME.secondaryTextColor());
                drawLabel(context, "Radius", x + (centerW + GAP) * 3, row - LABEL_OFFSET, THEME.secondaryTextColor());
                row += PARAM_ROW_GAP;

                drawLabel(context, "Rot X", x, row - LABEL_OFFSET, THEME.secondaryTextColor());
                drawLabel(context, "Rot Y", x + smallW + GAP, row - LABEL_OFFSET, THEME.secondaryTextColor());
                drawLabel(context, "Rot Z", x + (smallW + GAP) * 2, row - LABEL_OFFSET, THEME.secondaryTextColor());
                row += FORM_ROW_GAP;
            } else {
                row += FORM_ROW_GAP;
            }

            drawLabel(context, VpTexts.tr("label.videoplayer.selection_points", "Selection: %s", editor.pointProgress()), x, row - LABEL_OFFSET, THEME.secondaryTextColor());
        }
        int color = editor.statusError() ? THEME.errorColor() : THEME.executionColor();
        int statusY = draft.operation == VideoCreationEditor.Operation.CREATE_AREA ? y + 186 : row + 22;
        drawLabel(context, editor.status(), x, statusY, color);
        trackContentBottom(statusY + 10);
    }

    private void drawPlayback(DrawContext context, int x, int y, int mouseX, int mouseY) {
        if (showPlaybackProgressPreview(mouseX, mouseY)) {
            drawPlaybackProgressPreview(context, x);
            trackContentBottom(contentViewportBottom());
            return;
        }
        drawLabel(context, "URL", x, y - 12, THEME.secondaryTextColor());
        ClientVideoScreen screen = selectedPlaybackScreen();
        int queueY = playbackQueueY(y);
        drawLabel(context, VpTexts.tr("label.videoplayer.queue", "Queue"), x, queueY, THEME.secondaryTextColor());
        if (screen == null || screen.infos.isEmpty()) {
            drawLabel(context, VpTexts.tr("message.videoplayer.queue_empty", "Queue is empty"), x, queueY + 18, THEME.secondaryTextColor());
            trackContentBottom(queueY + 28);
            return;
        }
        int row = queueY + 18;
        int index = 1;
        for (VideoInfo info : screen.infos) {
            drawLabel(context, index + ". " + info.name() + " / " + info.playerName(), x, row, THEME.secondaryTextColor());
            row += 12;
            index++;
        }
        trackContentBottom(row + 4);
    }

    private int playbackQueueY(int y) {
        return y + BUTTON_ROW_GAP * 3 + CONTROL_HEIGHT + 14;
    }

    private VpProgressSliderWidget.ProgressState playbackProgressState() {
        ClientVideoScreen screen = selectedPlaybackScreen();
        if (screen == null || screen.player == null) {
            return VpProgressSliderWidget.ProgressState.disabled();
        }
        long total = screen.player.getTotalProgress();
        long progress = screen.player.getProgress();
        if (canSeekPlayback(screen)) {
            return VpProgressSliderWidget.ProgressState.of(progress, total);
        }
        return VpProgressSliderWidget.ProgressState.readonly(total);
    }

    private boolean canSeekPlayback(ClientVideoScreen screen) {
        if (screen == null || screen.player == null) return false;
        if (!canScreen(VideoPermissionAction.SEEK, screen)) return false;
        VideoInfo info = screen.currentDisplayInfo();
        return info != null && info.seekable() && screen.player.canSetProgress() && screen.player.getTotalProgress() > 0;
    }

    private void beginPlaybackProgressDrag() {
        playbackProgressPreview = true;
        lastPlaybackPreviewSeekTime = 0;
        playbackProgressDragScreen = selectedPlaybackScreen();
        playbackProgressPausedBeforeDrag = false;
        playbackProgressPauseApplied = false;
        if (canSeekPlayback(playbackProgressDragScreen) && playbackProgressDragScreen.player.canPause()) {
            playbackProgressPausedBeforeDrag = playbackProgressDragScreen.player.isPaused();
            if (!playbackProgressPausedBeforeDrag) {
                playbackProgressDragScreen.player.pause(true);
                playbackProgressPauseApplied = true;
            }
        }
    }

    private void previewPlaybackProgress(long progress) {
        long now = System.currentTimeMillis();
        if (lastPlaybackPreviewSeekTime != 0 && now - lastPlaybackPreviewSeekTime < PLAYBACK_SEEK_THROTTLE_MS) {
            return;
        }
        seekPlaybackLocal(clampPlaybackPreviewProgress(progress));
        lastPlaybackPreviewSeekTime = now;
    }

    private void commitPlaybackProgress(long progress) {
        ClientVideoScreen screen = selectedPlaybackScreen();
        if (!canSeekPlayback(screen)) return;
        seekPlaybackLocal(progress);
        ClientPacketHandler.seek(screen, progress);
    }

    private void endPlaybackProgressDrag() {
        if (playbackProgressPauseApplied && playbackProgressDragScreen != null && playbackProgressDragScreen.player != null && !playbackProgressPausedBeforeDrag) {
            playbackProgressDragScreen.player.pause(false);
        }
        playbackProgressPreview = false;
        playbackProgressDragScreen = null;
        playbackProgressPausedBeforeDrag = false;
        playbackProgressPauseApplied = false;
        lastPlaybackPreviewSeekTime = 0;
    }

    private void seekPlaybackLocal(long progress) {
        ClientVideoScreen screen = selectedPlaybackScreen();
        if (!canSeekPlayback(screen)) return;
        screen.setProgress(progress);
    }

    private long clampPlaybackPreviewProgress(long progress) {
        ClientVideoScreen screen = selectedPlaybackScreen();
        if (!canSeekPlayback(screen)) return Math.max(0, progress);
        long total = screen.player.getTotalProgress();
        if (total <= 0) return Math.max(0, progress);
        long guard = Math.min(PLAYBACK_PREVIEW_END_GUARD_MS, Math.max(1, total / 20));
        long maxPreview = Math.max(0, total - guard);
        return Math.clamp(progress, 0, maxPreview);
    }

    private boolean drawPlaybackProgressPreview(DrawContext context, int x) {
        ClientVideoScreen screen = selectedPlaybackScreen();
        if (screen == null || screen.player == null) return false;
        int textureId = screen.displayTextureId();
        if (textureId < 0) return false;
        int areaTop = contentViewportTop() + 10;
        int areaBottom = contentViewportBottom() - 10;
        int areaH = Math.max(1, areaBottom - areaTop);
        int areaW = Math.max(1, mainW() - 8);
        int textureW = Math.max(1, screen.displayTextureWidth());
        int textureH = Math.max(1, screen.displayTextureHeight());
        float aspect = textureW / (float) textureH;
        int previewW = areaW;
        int previewH = Math.round(previewW / aspect);
        if (previewH > areaH) {
            previewH = areaH;
            previewW = Math.round(previewH * aspect);
        }
        int previewX = x + Math.max(0, (areaW - previewW) / 2);
        int previewY = areaTop + Math.max(0, (areaH - previewH) / 2);
        VpUiRenderer.drawBox(context, previewX - 3, previewY - 3, previewW + 6, previewH + 6,
                VpUiRenderer.darken(THEME.nodeBodyColor(), 0.08f), THEME.panelBorderColor());
        drawPlaybackTexture(context, screen, textureId, previewX, previewY, previewW, previewH);
        ClientDanmakuRenderer.drawPreview(context, screen, previewX, previewY, previewW, previewH);
        ClientDanmakuRenderer.drawSubtitlePreview(context, screen, previewX, previewY, previewW, previewH);
        context.drawStrokedRectangle(previewX - 1, previewY - 1, previewW + 2, previewH + 2, THEME.panelBorderColor());
        return true;
    }

    private void drawPlaybackTexture(DrawContext context, ClientVideoScreen screen, int textureId, int x, int y, int width, int height) {
        float u2 = screen != null && screen.stereo3d ? 0.5f : 1f;
        context.drawTexturedQuad(
                ScreenRenderer.textureIdentifier(textureId),
                x,
                y,
                x + width,
                y + height,
                0,
                u2,
                0,
                1
        );
    }

    private void drawScreenSettings(DrawContext context, int x, int y, int width) {
        drawLabel(context, VpTexts.tr("label.videoplayer.display", "Display"), x, y, THEME.primaryTextColor());
        drawDisplay(context, x, y + SCREEN_SETTINGS_DISPLAY_Y);
        drawLabel(context, "Meta", x, y + SCREEN_SETTINGS_META_Y, THEME.primaryTextColor());
        drawMeta(context, x, y + SCREEN_SETTINGS_META_CONTENT_Y, width);
    }

    private void drawDisplay(DrawContext context, int x, int y) {
        int contentW = Math.max(180, width - x - 14);
        int scaleSliderW = actionButtonWidth(contentW, 2);
        int danmakuRow = y + FORM_ROW_GAP;
        int biliQualityRow = danmakuRow + FORM_ROW_GAP;
        int youtubeQualityRow = biliQualityRow + FORM_ROW_GAP;
        int mappingRow = youtubeQualityRow + FORM_ROW_GAP;
        int scaleRow = mappingRow + FORM_ROW_GAP;
        drawLabel(context, VpTexts.tr("label.videoplayer.danmaku", "Danmaku"), x, danmakuRow - LABEL_OFFSET, THEME.secondaryTextColor());
        drawLabel(context, VpTexts.tr("label.videoplayer.bili_quality.screen_limit", "Bili Limit"), x, biliQualityRow - LABEL_OFFSET, THEME.secondaryTextColor());
        drawLabel(context, VpTexts.tr("label.videoplayer.youtube_quality.screen_limit", "YouTube Limit"), x, youtubeQualityRow - LABEL_OFFSET, THEME.secondaryTextColor());
        drawLabel(context, VpTexts.tr("label.videoplayer.vertex_mapping", "Vertex Mapping"), x, mappingRow - LABEL_OFFSET, THEME.secondaryTextColor());
        drawLabel(context, "Scale X", x, scaleRow - LABEL_OFFSET, THEME.secondaryTextColor());
        drawLabel(context, "Scale Y", x + scaleSliderW + GAP, scaleRow - LABEL_OFFSET, THEME.secondaryTextColor());
        trackContentBottom(scaleRow + CONTROL_HEIGHT + 4);
    }

    private void drawMeta(DrawContext context, int x, int y, int width) {
        int contentW = Math.max(180, width);
        int keyW = Math.max(100, Math.min(220, (contentW - GAP) / 2));
        drawLabel(context, "Key", x, y + 56, THEME.secondaryTextColor());
        drawLabel(context, "Type", x + keyW + GAP, y + 56, THEME.secondaryTextColor());
        drawLabel(context, "Value", x, y + 86, THEME.secondaryTextColor());
        ClientVideoScreen screen = selectedScreen();
        if (screen == null) return;
        drawLabel(context, "Metadata", x, y + 138, THEME.secondaryTextColor());
        int row = y + 156;
        List<Map.Entry<String, MetaValue>> entries = screen.metadata.entries().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();
        if (entries.isEmpty()) {
            drawLabel(context, "{}", x, row, THEME.secondaryTextColor());
            trackContentBottom(row + 12);
            return;
        }
        for (Map.Entry<String, MetaValue> entry : entries) {
            MetaValue value = entry.getValue();
            String type = value.type == null ? "unknown" : value.type.label();
            drawLabel(context, entry.getKey() + " [" + type + "] = " + value.toDisplayString(), x, row, THEME.secondaryTextColor());
            row += 12;
        }
        trackContentBottom(row + 4);
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

    private String trimToWidth(String text, int maxWidth) {
        String value = text == null ? "" : text;
        if (textRenderer.getWidth(value) <= maxWidth) return value;
        String suffix = "...";
        return textRenderer.trimToWidth(value, Math.max(0, maxWidth - textRenderer.getWidth(suffix))) + suffix;
    }

    private TextFieldWidget textField(int x, int y, int width, String text, int maxLength) {
        return textField(x, y, width, text, maxLength, value -> true);
    }

    private TextFieldWidget textField(int x, int y, int width, String text, int maxLength, Predicate<String> predicate) {
        VpTextFieldWidget field = new VpTextFieldWidget(textRenderer, x, y, Math.max(40, width), CONTROL_HEIGHT, Text.empty(), THEME);
        field.setMaxLength(maxLength);
        field.setTextPredicate(predicate);
        field.setText(text == null ? "" : text);
        addDrawableChild(field);
        registerDrawable(field, y, CONTROL_HEIGHT);
        return field;
    }

    private VpButtonWidget button(String label, int x, int y, int width, Runnable action) {
        return button(Text.literal(label), x, y, width, action);
    }

    private VpButtonWidget button(Text label, int x, int y, int width, Runnable action) {
        VpButtonWidget button = new VpButtonWidget(x, y, Math.max(34, width), CONTROL_HEIGHT, label, b -> action.run(), THEME);
        addDrawableChild(button);
        registerDrawable(button, y, CONTROL_HEIGHT);
        return button;
    }

    private VpButtonWidget button(String label, int x, int y, int width, Consumer<VpButtonWidget> action) {
        return button(Text.literal(label), x, y, width, action);
    }

    private VpButtonWidget button(Text label, int x, int y, int width, Consumer<VpButtonWidget> action) {
        VpButtonWidget button = new VpButtonWidget(x, y, Math.max(34, width), CONTROL_HEIGHT, label, action, THEME);
        addDrawableChild(button);
        registerDrawable(button, y, CONTROL_HEIGHT);
        return button;
    }

    private VpButtonWidget squareButton(String label, int x, int y, Runnable action) {
        return squareButton(Text.literal(label), x, y, action);
    }

    private VpButtonWidget squareButton(Text label, int x, int y, Runnable action) {
        VpButtonWidget button = new VpButtonWidget(x, y, CONTROL_HEIGHT, CONTROL_HEIGHT, label, ignored -> action.run(), THEME);
        addDrawableChild(button);
        registerDrawable(button, y, CONTROL_HEIGHT);
        return button;
    }

    private VpButtonWidget danmakuOverlayButton(String label, int x, int y, int width, Consumer<VpButtonWidget> action) {
        return danmakuOverlayButton(Text.literal(label), x, y, width, action);
    }

    private VpButtonWidget danmakuOverlayButton(Text label, int x, int y, int width, Consumer<VpButtonWidget> action) {
        VpButtonWidget button = new VpButtonWidget(x, y, Math.max(34, width), CONTROL_HEIGHT, label, action, THEME);
        addDrawableChild(button);
        danmakuOverlayDrawables.add(button);
        danmakuOverlayWidgets.add(button);
        return button;
    }

    private VpSliderWidget danmakuOverlaySlider(String label, int x, int y, int width, int value,
                                                IntConsumer action, IntConsumer commit, IntFunction<String> messageFormatter) {
        VpSliderWidget slider = new VpSliderWidget(x, y, Math.max(60, width), CONTROL_HEIGHT, label, value, action, commit,
                (VpSliderWidget.TextFormatter) v -> Text.literal(messageFormatter.apply(v)), THEME);
        addDrawableChild(slider);
        danmakuOverlayDrawables.add(slider);
        danmakuOverlayWidgets.add(slider);
        return slider;
    }

    private VpSliderWidget danmakuOverlaySlider(Text label, int x, int y, int width, int value,
                                                IntConsumer action, IntConsumer commit, VpSliderWidget.TextFormatter messageFormatter) {
        VpSliderWidget slider = new VpSliderWidget(x, y, Math.max(60, width), CONTROL_HEIGHT, "", value, action, commit, messageFormatter, THEME);
        addDrawableChild(slider);
        danmakuOverlayDrawables.add(slider);
        danmakuOverlayWidgets.add(slider);
        return slider;
    }

    private void initDanmakuOverlay(int anchorRight, int anchorY, int minX, int maxX) {
        normalizeDanmakuOverlayConfig();
        boolean showDensity = showDanmakuDensityControls();
        int availableW = Math.max(180, maxX - minX);
        danmakuOverlayW = Math.min(DANMAKU_OVERLAY_WIDTH, availableW);
        danmakuOverlayH = DANMAKU_OVERLAY_BASE_HEIGHT + (showDensity ? DANMAKU_OVERLAY_DENSITY_EXTRA_HEIGHT : 0);
        int maxPanelX = Math.max(minX, maxX - danmakuOverlayW);
        danmakuOverlayX = Math.clamp(anchorRight - danmakuOverlayW, minX, maxPanelX);
        int maxPanelY = Math.max(contentViewportTop(), height - 18 - danmakuOverlayH);
        danmakuOverlayY = Math.clamp(anchorY, contentViewportTop(), maxPanelY);

        int padding = 10;
        int innerX = danmakuOverlayX + padding;
        int innerW = danmakuOverlayW - padding * 2;
        int blockRow = danmakuOverlayY + 26;
        int rangeRow = danmakuOverlayY + 58;
        int densityRow = danmakuOverlayY + 91;
        int speedRow = danmakuOverlayY + (showDensity ? 124 : 91);
        int opacityRow = danmakuOverlayY + (showDensity ? 157 : 124);
        int scaleRow = danmakuOverlayY + (showDensity ? 181 : 148);
        int guardRow = danmakuOverlayY + (showDensity ? 205 : 172);

        int blockW = (innerW - GAP * 2) / 3;
        addDanmakuToggle(VpTexts.tr("label.videoplayer.block_rolling", "Block Rolling"), innerX, blockRow, blockW,
                () -> VideoPlayerClient.config.danmakuBlockRolling,
                value -> VideoPlayerClient.config.danmakuBlockRolling = value);
        addDanmakuToggle(VpTexts.tr("label.videoplayer.block_fixed", "Block Fixed"), innerX + blockW + GAP, blockRow, blockW,
                () -> VideoPlayerClient.config.danmakuBlockFixed,
                value -> VideoPlayerClient.config.danmakuBlockFixed = value);
        addDanmakuToggle(VpTexts.tr("label.videoplayer.block_colored", "Block Colored"), innerX + (blockW + GAP) * 2, blockRow, innerW - (blockW + GAP) * 2,
                () -> VideoPlayerClient.config.danmakuBlockColored,
                value -> VideoPlayerClient.config.danmakuBlockColored = value);

        VpButtonWidget[] rangeButtons = new VpButtonWidget[DANMAKU_RANGE_OPTIONS.length];
        int rangeGap = 4;
        int rangeW = (innerW - rangeGap * (DANMAKU_RANGE_OPTIONS.length - 1)) / DANMAKU_RANGE_OPTIONS.length;
        for (int i = 0; i < DANMAKU_RANGE_OPTIONS.length; i++) {
            int option = DANMAKU_RANGE_OPTIONS[i];
            int buttonX = innerX + (rangeW + rangeGap) * i;
            int buttonW = i == DANMAKU_RANGE_OPTIONS.length - 1 ? innerX + innerW - buttonX : rangeW;
            rangeButtons[i] = danmakuOverlayButton(option + "%", buttonX, rangeRow, buttonW, button -> {
                boolean hadDensity = showDanmakuDensityControls();
                VideoPlayerClient.config.danmakuRollingRangePercent = option;
                saveDanmakuOverlayConfig();
                if (hadDensity != showDanmakuDensityControls()) {
                    rebuildDanmakuOverlayAtCurrentPosition();
                } else {
                    updateDanmakuRangeButtons(rangeButtons);
                }
            });
            rangeButtons[i].selected(VideoPlayerClient.config.danmakuRollingRangePercent == option);
        }

        if (showDensity) {
            VpButtonWidget[] densityButtons = new VpButtonWidget[DANMAKU_DENSITY_KEYS.length];
            int densityGap = 4;
            int densityW = (innerW - densityGap * (DANMAKU_DENSITY_KEYS.length - 1)) / DANMAKU_DENSITY_KEYS.length;
            for (int i = 0; i < DANMAKU_DENSITY_KEYS.length; i++) {
                int preset = i;
                int buttonX = innerX + (densityW + densityGap) * i;
                int buttonW = i == DANMAKU_DENSITY_KEYS.length - 1 ? innerX + innerW - buttonX : densityW;
                densityButtons[i] = danmakuOverlayButton(danmakuDensityLabel(i), buttonX, densityRow, buttonW, button -> {
                    VideoPlayerClient.config.danmakuDensityPreset = preset;
                    saveDanmakuOverlayConfig();
                    updateDanmakuDensityButtons(densityButtons);
                });
                densityButtons[i].selected(VideoPlayerClient.config.danmakuDensityPreset == i);
            }
        }

        VpButtonWidget[] speedButtons = new VpButtonWidget[DANMAKU_SPEED_KEYS.length];
        int speedGap = 4;
        int speedW = (innerW - speedGap * (DANMAKU_SPEED_KEYS.length - 1)) / DANMAKU_SPEED_KEYS.length;
        for (int i = 0; i < DANMAKU_SPEED_KEYS.length; i++) {
            int preset = i;
            int buttonX = innerX + (speedW + speedGap) * i;
            int buttonW = i == DANMAKU_SPEED_KEYS.length - 1 ? innerX + innerW - buttonX : speedW;
            speedButtons[i] = danmakuOverlayButton(danmakuSpeedLabel(i), buttonX, speedRow, buttonW, button -> {
                VideoPlayerClient.config.danmakuSpeedPreset = preset;
                saveDanmakuOverlayConfig();
                updateDanmakuSpeedButtons(speedButtons);
            });
            speedButtons[i].selected(VideoPlayerClient.config.danmakuSpeedPreset == i);
        }

        danmakuOverlaySlider(VpTexts.tr("label.videoplayer.opacity", "Opacity"), innerX, opacityRow, innerW, opacityToSliderValue(VideoPlayerClient.config.danmakuOpacity), value -> {
            VideoPlayerClient.config.danmakuOpacity = sliderValueToOpacity(value);
            saveDanmakuOverlayConfig();
        }, value -> {
            VideoPlayerClient.config.danmakuOpacity = sliderValueToOpacity(value);
            saveDanmakuOverlayConfig();
        }, value -> VpTexts.tr("label.videoplayer.opacity_percent", "Opacity: %s%%", sliderValueToOpacity(value)));

        danmakuOverlaySlider(VpTexts.tr("label.videoplayer.font_size", "Font Size"), innerX, scaleRow, innerW, danmakuScaleToSliderValue(VideoPlayerClient.config.danmakuScalePercent), value -> {
            VideoPlayerClient.config.danmakuScalePercent = sliderValueToDanmakuScale(value);
            saveDanmakuOverlayConfig();
        }, value -> {
            VideoPlayerClient.config.danmakuScalePercent = sliderValueToDanmakuScale(value);
            saveDanmakuOverlayConfig();
        }, value -> VpTexts.tr("label.videoplayer.font_size_percent", "Font Size: %s%%", sliderValueToDanmakuScale(value)));

        addDanmakuToggle(VpTexts.tr("label.videoplayer.bottom_guard", "Bottom Guard"), innerX, guardRow, innerW,
                () -> VideoPlayerClient.config.danmakuBottomGuard,
                value -> VideoPlayerClient.config.danmakuBottomGuard = value);
    }

    private void initBiliLocalQualityOverlay(int anchorRight, int anchorY, int minX, int maxX) {
        if (currentYouTubeInfo(selectedPlaybackScreen()) != null) {
            List<Integer> available = currentAvailableYouTubeQualities();
            ArrayList<Integer> options = new ArrayList<>();
            options.add(YouTubeQuality.AUTO);
            options.addAll(available);
            int displayQuality = displayedLocalYouTubeQuality(available);
            initQualityOverlay(anchorRight, anchorY, minX, maxX, options, displayQuality,
                    this::selectLocalYouTubeQuality, this::youtubeQualityText);
            return;
        }
        List<Integer> options = currentAvailableBiliQualities();
        int displayQuality = displayedLocalBiliQuality(options);
        int selected = options.contains(displayQuality) ? displayQuality : Integer.MIN_VALUE;
        initQualityOverlay(anchorRight, anchorY, minX, maxX, options, selected,
                this::selectLocalBiliQuality, this::biliQualityText);
    }

    private void initBiliScreenQualityOverlay(int anchorRight, int anchorY, int minX, int maxX) {
        ArrayList<Integer> options = new ArrayList<>();
        options.add(BiliQuality.UNLIMITED);
        for (int option : BiliQuality.options()) {
            options.add(option);
        }
        ClientVideoScreen screen = selectedScreen();
        int selected = screen == null ? BiliQuality.UNLIMITED : BiliQuality.normalizeScreenLimit(screen.metadata.getInt(ScreenMetadata.KEY_BILIBILI_QUALITY, BiliQuality.UNLIMITED));
        initQualityOverlay(anchorRight, anchorY, minX, maxX, options, selected,
                this::selectScreenBiliQuality, this::biliQualityText);
    }

    private void initYouTubeScreenQualityOverlay(int anchorRight, int anchorY, int minX, int maxX) {
        ArrayList<Integer> options = new ArrayList<>();
        options.add(YouTubeQuality.AUTO);
        for (int option : YouTubeQuality.options()) {
            options.add(option);
        }
        ClientVideoScreen screen = selectedScreen();
        int selected = screen == null ? YouTubeQuality.AUTO : YouTubeQuality.normalizeScreenLimit(
                screen.metadata.getInt(ScreenMetadata.KEY_YOUTUBE_QUALITY, YouTubeQuality.AUTO)
        );
        initQualityOverlay(anchorRight, anchorY, minX, maxX, options, selected,
                this::selectScreenYouTubeQuality, this::youtubeQualityText);
    }

    private void initCcSubtitleOverlay(int anchorRight, int anchorY, int minX, int maxX) {
        ClientVideoScreen screen = selectedPlaybackScreen();
        if (screen == null || !screen.subtitles().availableForCurrentVideo()) {
            closeOverlays();
            return;
        }
        ClientSubtitleController subtitles = screen.subtitles();
        ArrayList<SubtitleChoice> choices = new ArrayList<>();
        choices.add(new SubtitleChoice("", VpTexts.tr("label.videoplayer.cc_subtitle.off", "Off"), true));
        for (ClientSubtitleController.Option option : subtitles.options()) {
            choices.add(new SubtitleChoice(option.key(), ccSubtitleOptionText(option), true));
        }
        if (subtitles.options().isEmpty()) {
            Text label = subtitles.catalogLoaded()
                    ? VpTexts.tr("label.videoplayer.cc_subtitle.none", "No CC")
                    : VpTexts.tr("label.videoplayer.cc_subtitle.loading", "Loading");
            choices.add(new SubtitleChoice("\u0000", label, false));
        }
        initCcSubtitleOverlay(anchorRight, anchorY, minX, maxX, choices, subtitles.selectedKey(), subtitles);
    }

    private void initCcSubtitleOverlay(int anchorRight, int anchorY, int minX, int maxX,
                                       List<SubtitleChoice> choices, String selected, ClientSubtitleController subtitles) {
        int availableW = Math.max(180, maxX - minX);
        danmakuOverlayW = Math.max(BILI_QUALITY_OVERLAY_MIN_WIDTH, availableW / 4);
        int contentHeight = choices.size() * CONTROL_HEIGHT + Math.max(0, choices.size() - 1) * BILI_QUALITY_OVERLAY_BUTTON_GAP;
        int maxViewportHeight = BILI_QUALITY_OVERLAY_VISIBLE_ROWS * CONTROL_HEIGHT
                + Math.max(0, BILI_QUALITY_OVERLAY_VISIBLE_ROWS - 1) * BILI_QUALITY_OVERLAY_BUTTON_GAP;
        int viewportHeight = Math.min(contentHeight, maxViewportHeight);
        biliQualityOverlayContentHeight = contentHeight;
        biliQualityOverlayScroll = clampScroll(biliQualityOverlayScroll, biliQualityOverlayContentHeight, Math.max(1, viewportHeight));
        danmakuOverlayH = BILI_QUALITY_OVERLAY_HEADER_HEIGHT + viewportHeight + BILI_QUALITY_OVERLAY_PADDING;
        int maxPanelX = Math.max(minX, maxX - danmakuOverlayW);
        danmakuOverlayX = Math.clamp(anchorRight - danmakuOverlayW, minX, maxPanelX);
        int maxPanelY = Math.max(contentViewportTop(), height - 18 - danmakuOverlayH);
        danmakuOverlayY = Math.clamp(anchorY, contentViewportTop(), maxPanelY);

        biliQualityOverlayViewportTop = danmakuOverlayY + BILI_QUALITY_OVERLAY_HEADER_HEIGHT;
        biliQualityOverlayViewportBottom = biliQualityOverlayViewportTop + viewportHeight;
        int innerX = danmakuOverlayX + BILI_QUALITY_OVERLAY_PADDING;
        int innerW = danmakuOverlayW - BILI_QUALITY_OVERLAY_PADDING * 2;
        boolean needsScroll = contentHeight > viewportHeight;
        int buttonW = innerW - (needsScroll ? 8 : 0);
        int rowY = biliQualityOverlayViewportTop - biliQualityOverlayScroll;
        for (int i = 0; i < choices.size(); i++) {
            SubtitleChoice choice = choices.get(i);
            int buttonY = rowY + i * (CONTROL_HEIGHT + BILI_QUALITY_OVERLAY_BUTTON_GAP);
            VpButtonWidget button = danmakuOverlayButton(choice.label(), innerX, buttonY, buttonW, ignored -> {
                subtitles.select(choice.key());
                clearAndInit();
            });
            button.clip(innerX, biliQualityOverlayViewportTop, innerX + buttonW, biliQualityOverlayViewportBottom);
            button.selected(Objects.equals(choice.key(), selected));
            button.active = choice.active();
        }
    }

    private void initQualityOverlay(int anchorRight, int anchorY, int minX, int maxX, List<Integer> options,
                                    int selected, IntConsumer selector, IntFunction<Text> labeler) {
        if (options == null || options.isEmpty()) {
            closeOverlays();
            return;
        }
        int availableW = Math.max(180, maxX - minX);
        danmakuOverlayW = Math.max(BILI_QUALITY_OVERLAY_MIN_WIDTH, availableW / 4);
        int contentHeight = options.size() * CONTROL_HEIGHT + Math.max(0, options.size() - 1) * BILI_QUALITY_OVERLAY_BUTTON_GAP;
        int maxViewportHeight = BILI_QUALITY_OVERLAY_VISIBLE_ROWS * CONTROL_HEIGHT
                + Math.max(0, BILI_QUALITY_OVERLAY_VISIBLE_ROWS - 1) * BILI_QUALITY_OVERLAY_BUTTON_GAP;
        int viewportHeight = Math.min(contentHeight, maxViewportHeight);
        biliQualityOverlayContentHeight = contentHeight;
        biliQualityOverlayScroll = clampScroll(biliQualityOverlayScroll, biliQualityOverlayContentHeight, Math.max(1, viewportHeight));
        danmakuOverlayH = BILI_QUALITY_OVERLAY_HEADER_HEIGHT + viewportHeight + BILI_QUALITY_OVERLAY_PADDING;
        int maxPanelX = Math.max(minX, maxX - danmakuOverlayW);
        danmakuOverlayX = Math.clamp(anchorRight - danmakuOverlayW, minX, maxPanelX);
        int maxPanelY = Math.max(contentViewportTop(), height - 18 - danmakuOverlayH);
        danmakuOverlayY = Math.clamp(anchorY, contentViewportTop(), maxPanelY);

        biliQualityOverlayViewportTop = danmakuOverlayY + BILI_QUALITY_OVERLAY_HEADER_HEIGHT;
        biliQualityOverlayViewportBottom = biliQualityOverlayViewportTop + viewportHeight;
        int innerX = danmakuOverlayX + BILI_QUALITY_OVERLAY_PADDING;
        int innerW = danmakuOverlayW - BILI_QUALITY_OVERLAY_PADDING * 2;
        boolean needsScroll = contentHeight > viewportHeight;
        int buttonW = innerW - (needsScroll ? 8 : 0);
        int rowY = biliQualityOverlayViewportTop - biliQualityOverlayScroll;
        for (int i = 0; i < options.size(); i++) {
            int option = options.get(i);
            int buttonY = rowY + i * (CONTROL_HEIGHT + BILI_QUALITY_OVERLAY_BUTTON_GAP);
            VpButtonWidget button = danmakuOverlayButton(labeler.apply(option), innerX, buttonY, buttonW, ignored -> selector.accept(option));
            button.clip(innerX, biliQualityOverlayViewportTop, innerX + buttonW, biliQualityOverlayViewportBottom);
            button.selected(option == selected);
        }
    }

    private VpButtonWidget addDanmakuToggle(Text label, int x, int y, int width, BooleanSupplier getter, Consumer<Boolean> setter) {
        VpButtonWidget button = danmakuOverlayButton(label, x, y, width, widget -> {
            setter.accept(!getter.getAsBoolean());
            saveDanmakuOverlayConfig();
            widget.selected(getter.getAsBoolean());
        });
        return button.selected(getter.getAsBoolean());
    }

    private void updateDanmakuRangeButtons(VpButtonWidget[] buttons) {
        for (int i = 0; i < buttons.length; i++) {
            buttons[i].selected(VideoPlayerClient.config.danmakuRollingRangePercent == DANMAKU_RANGE_OPTIONS[i]);
        }
    }

    private void updateDanmakuSpeedButtons(VpButtonWidget[] buttons) {
        for (int i = 0; i < buttons.length; i++) {
            buttons[i].selected(VideoPlayerClient.config.danmakuSpeedPreset == i);
        }
    }

    private void updateDanmakuDensityButtons(VpButtonWidget[] buttons) {
        for (int i = 0; i < buttons.length; i++) {
            buttons[i].selected(VideoPlayerClient.config.danmakuDensityPreset == i);
        }
    }

    private boolean showDanmakuDensityControls() {
        return VideoPlayerClient.config != null && VideoPlayerClient.config.danmakuRollingRangePercent == 100;
    }

    private void rebuildDanmakuOverlayAtCurrentPosition() {
        int anchorRight = danmakuOverlayX + danmakuOverlayW;
        int anchorY = danmakuOverlayY;
        for (ClickableWidget widget : danmakuOverlayWidgets) {
            remove(widget);
        }
        danmakuOverlayDrawables.clear();
        danmakuOverlayWidgets.clear();
        activeDanmakuOverlayWidget = null;
        initDanmakuOverlay(anchorRight, anchorY, mainX(), mainX() + mainW());
    }

    private void saveDanmakuOverlayConfig() {
        normalizeDanmakuOverlayConfig();
        VideoPlayerClient.saveConfig();
    }

    private void normalizeDanmakuOverlayConfig() {
        VideoPlayerClient.config.danmakuRollingRangePercent = switch (VideoPlayerClient.config.danmakuRollingRangePercent) {
            case 25, 50, 75, 100 -> VideoPlayerClient.config.danmakuRollingRangePercent;
            default -> 50;
        };
        VideoPlayerClient.config.danmakuSpeedPreset = Math.clamp(VideoPlayerClient.config.danmakuSpeedPreset, 0, DANMAKU_SPEED_KEYS.length - 1);
        VideoPlayerClient.config.danmakuDensityPreset = Math.clamp(VideoPlayerClient.config.danmakuDensityPreset, 0, DANMAKU_DENSITY_KEYS.length - 1);
        VideoPlayerClient.config.danmakuOpacity = Math.clamp(VideoPlayerClient.config.danmakuOpacity, 20, 100);
        VideoPlayerClient.config.danmakuScalePercent = Math.clamp(VideoPlayerClient.config.danmakuScalePercent, 50, 170);
    }

    private int opacityToSliderValue(int opacity) {
        int clamped = Math.clamp(opacity, 20, 100);
        return Math.clamp(Math.round((clamped - 20) * 100.0f / 80.0f), 0, 100);
    }

    private int sliderValueToOpacity(int value) {
        return Math.clamp(20 + Math.round(Math.clamp(value, 0, 100) * 80.0f / 100.0f), 20, 100);
    }

    private int danmakuScaleToSliderValue(int scale) {
        int clamped = Math.clamp(scale, 50, 170);
        return Math.clamp(Math.round((clamped - 50) * 100.0f / 120.0f), 0, 100);
    }

    private int sliderValueToDanmakuScale(int value) {
        return Math.clamp(50 + Math.round(Math.clamp(value, 0, 100) * 120.0f / 100.0f), 50, 170);
    }

    private Text localBiliQualityButtonText() {
        if (currentYouTubeInfo(selectedPlaybackScreen()) != null) {
            List<Integer> available = currentAvailableYouTubeQualities();
            int quality = displayedLocalYouTubeQuality(available);
            return VpTexts.tr("label.videoplayer.youtube_quality.local_value", "YouTube: %s", youtubeQualityText(quality).getString());
        }
        if (currentBiliInfo(selectedPlaybackScreen()) == null) {
            return VpTexts.tr("label.videoplayer.quality", "Quality");
        }
        int quality = displayedLocalBiliQuality(currentAvailableBiliQualities());
        return VpTexts.tr("label.videoplayer.bili_quality.local_value", "Bili: %s", biliQualityText(quality).getString());
    }

    private Text ccSubtitleButtonText(ClientVideoScreen screen) {
        if (screen == null || !screen.subtitles().hasSelectedTrack()) {
            return VpTexts.tr("label.videoplayer.cc_subtitle.off_value", "CC: Off");
        }
        return VpTexts.tr("label.videoplayer.cc_subtitle.value", "CC: %s", screen.subtitles().selectedLabel());
    }

    private Text ccSubtitleOptionText(ClientSubtitleController.Option option) {
        String label = option == null ? "" : option.label();
        if (label == null || label.isBlank()) label = option == null ? "" : option.language();
        return Text.literal(label == null || label.isBlank() ? "CC" : label);
    }

    private String ccSubtitleOverlaySignature() {
        if (!ccSubtitleOverlayOpen) return "";
        ClientVideoScreen screen = selectedPlaybackScreen();
        if (screen == null) return "none";
        ClientSubtitleController subtitles = screen.subtitles();
        StringBuilder builder = new StringBuilder();
        builder.append(subtitles.catalogLoaded()).append('|').append(subtitles.selectedKey());
        for (ClientSubtitleController.Option option : subtitles.options()) {
            builder.append('|').append(option.key()).append('=').append(option.label());
        }
        return builder.toString();
    }

    private Text screenBiliQualityButtonText(ClientVideoScreen screen) {
        int quality = screen == null ? BiliQuality.UNLIMITED : BiliQuality.normalizeScreenLimit(screen.metadata.getInt(ScreenMetadata.KEY_BILIBILI_QUALITY, BiliQuality.UNLIMITED));
        return VpTexts.tr("label.videoplayer.bili_quality.screen_value", "Bili Limit: %s", biliQualityText(quality).getString());
    }

    private Text screenYouTubeQualityButtonText(ClientVideoScreen screen) {
        int quality = screen == null ? YouTubeQuality.AUTO : YouTubeQuality.normalizeScreenLimit(
                screen.metadata.getInt(ScreenMetadata.KEY_YOUTUBE_QUALITY, YouTubeQuality.AUTO)
        );
        return VpTexts.tr("label.videoplayer.youtube_quality.screen_value", "YouTube Limit: %s", youtubeQualityText(quality).getString());
    }

    private Text biliQualityText(int quality) {
        return VpTexts.tr(BiliQuality.translationKey(quality), BiliQuality.fallbackLabel(quality));
    }

    private Text youtubeQualityText(int quality) {
        return VpTexts.tr(YouTubeQuality.translationKey(quality), YouTubeQuality.fallbackLabel(quality));
    }

    private List<Integer> currentAvailableBiliQualities() {
        VideoInfo info = currentBiliInfo(selectedPlaybackScreen());
        if (info == null) return List.of();
        return BiliBiliVideoProvider.availableQualities(info.rawPath());
    }

    private List<Integer> currentAvailableYouTubeQualities() {
        VideoInfo info = currentYouTubeInfo(selectedPlaybackScreen());
        if (info == null) return List.of();
        return YouTubeProvider.availableQualities(info.rawPath());
    }

    private int displayedLocalBiliQuality(List<Integer> available) {
        int configured = VideoPlayerClient.config == null ? BiliQuality.DEFAULT_QN : BiliQuality.normalizeClient(VideoPlayerClient.config.bilibiliQuality);
        if (available == null || available.isEmpty() || available.contains(configured)) return configured;
        return BiliQuality.bestAtOrBelow(available, configured);
    }

    private int displayedLocalYouTubeQuality(List<Integer> available) {
        int configured = VideoPlayerClient.config == null
                ? YouTubeQuality.AUTO
                : YouTubeQuality.normalizeClient(VideoPlayerClient.config.youtubeQuality);
        if (configured == YouTubeQuality.AUTO || available == null || available.isEmpty() || available.contains(configured)) {
            return configured;
        }
        return YouTubeQuality.bestAtOrBelow(available, configured);
    }

    private VideoInfo currentBiliInfo(ClientVideoScreen screen) {
        if (screen == null) return null;
        VideoInfo info = screen.currentPlaybackInfo();
        if (info == null || info.rawPath() == null || info.rawPath().isBlank()) return null;
        return BiliBiliVideoProvider.isBiliVideoRawPath(info.rawPath()) ? info : null;
    }

    private VideoInfo currentYouTubeInfo(ClientVideoScreen screen) {
        if (screen == null) return null;
        VideoInfo info = screen.currentPlaybackInfo();
        if (info == null || info.rawPath() == null || info.rawPath().isBlank()) return null;
        return YouTubeProvider.isYouTubeRawPath(info.rawPath()) ? info : null;
    }

    private void selectLocalBiliQuality(int quality) {
        if (!currentAvailableBiliQualities().contains(quality)) return;
        ClientVideoScreen screen = selectedPlaybackScreen();
        VideoInfo info = currentBiliInfo(screen);
        if (screen == null || info == null) return;
        VideoPlayerClient.config.bilibiliQuality = BiliQuality.normalizeClient(quality);
        VideoPlayerClient.saveConfig();
        biliLocalQualityOverlayOpen = false;
        clearAndInit();
        ClientPacketHandler.reloadQualityPlayback(screen);
    }

    private void selectLocalYouTubeQuality(int quality) {
        List<Integer> available = currentAvailableYouTubeQualities();
        if (quality != YouTubeQuality.AUTO && !available.contains(quality)) return;
        ClientVideoScreen screen = selectedPlaybackScreen();
        VideoInfo info = currentYouTubeInfo(screen);
        if (screen == null || info == null) return;
        VideoPlayerClient.config.youtubeQuality = YouTubeQuality.normalizeClient(quality);
        VideoPlayerClient.saveConfig();
        biliLocalQualityOverlayOpen = false;
        clearAndInit();
        ClientPacketHandler.reloadQualityPlayback(screen);
    }

    private void selectScreenBiliQuality(int quality) {
        ClientVideoScreen screen = selectedScreen();
        if (screen == null) return;
        biliScreenQualityOverlayOpen = false;
        youtubeScreenQualityOverlay = false;
        setMetadata(screen, ScreenMetadata.KEY_BILIBILI_QUALITY, MetaValue.ofInt(BiliQuality.normalizeScreenLimit(quality)));
    }

    private void selectScreenYouTubeQuality(int quality) {
        ClientVideoScreen screen = selectedScreen();
        if (screen == null) return;
        biliScreenQualityOverlayOpen = false;
        youtubeScreenQualityOverlay = false;
        setMetadata(screen, ScreenMetadata.KEY_YOUTUBE_QUALITY, MetaValue.ofInt(YouTubeQuality.normalizeScreenLimit(quality)));
    }

    private Consumer<ClientPacketHandler.RequestResult> permissionFeedback(VpButtonWidget button) {
        return result -> {
            if (ClientPacketHandler.denied(result)) button.showPermissionDenied();
        };
    }

    private void closeOnOk(VpButtonWidget button, ClientPacketHandler.RequestResult result) {
        if (ClientPacketHandler.denied(result)) {
            button.showPermissionDenied();
            return;
        }
        if (result != null && result.status() == RequestResultStatus.OK) {
            close();
        }
    }

    private boolean canGlobal(VideoPermissionAction action) {
        return ClientPermissionCache.allowedOrUnknown(action, "", "");
    }

    private boolean canArea(VideoPermissionAction action, ClientVideoArea area) {
        return area == null || ClientPermissionCache.allowedOrUnknown(action, area.name, "");
    }

    private boolean canScreen(VideoPermissionAction action, ClientVideoScreen screen) {
        return screen == null || ClientPermissionCache.allowedOrUnknown(action, screen);
    }

    private VpSliderWidget slider(String label, int x, int y, int width, int value, IntConsumer action) {
        return slider(label, x, y, width, value, action, ignored -> VideoPlayerClient.saveConfig());
    }

    private VpSliderWidget slider(Text label, int x, int y, int width, int value, IntConsumer action) {
        return slider(label, x, y, width, value, action, ignored -> VideoPlayerClient.saveConfig());
    }

    private VpSliderWidget slider(String label, int x, int y, int width, int value, IntConsumer action, IntConsumer commit) {
        VpSliderWidget slider = new VpSliderWidget(x, y, Math.max(60, width), CONTROL_HEIGHT, label, value, action, commit, THEME);
        addDrawableChild(slider);
        registerDrawable(slider, y, CONTROL_HEIGHT);
        return slider;
    }

    private VpSliderWidget slider(Text label, int x, int y, int width, int value, IntConsumer action, IntConsumer commit) {
        VpSliderWidget slider = new VpSliderWidget(x, y, Math.max(60, width), CONTROL_HEIGHT, label, value, action, commit, THEME);
        addDrawableChild(slider);
        registerDrawable(slider, y, CONTROL_HEIGHT);
        return slider;
    }

    private VpSliderWidget slider(String label, int x, int y, int width, int value, IntConsumer action, IntConsumer commit, IntFunction<String> messageFormatter) {
        VpSliderWidget slider = new VpSliderWidget(x, y, Math.max(60, width), CONTROL_HEIGHT, label, value, action, commit,
                (VpSliderWidget.TextFormatter) v -> Text.literal(messageFormatter.apply(v)), THEME);
        addDrawableChild(slider);
        registerDrawable(slider, y, CONTROL_HEIGHT);
        return slider;
    }

    private VpProgressSliderWidget progressSlider(int x, int y, int width,
                                                  java.util.function.Supplier<VpProgressSliderWidget.ProgressState> source,
                                                  java.util.function.LongConsumer preview,
                                                  java.util.function.LongConsumer commit,
                                                  Runnable dragStart,
                                                  Runnable dragEnd) {
        VpProgressSliderWidget slider = new VpProgressSliderWidget(x, y, Math.max(80, width), PLAYBACK_PROGRESS_HEIGHT, source, preview, commit, dragStart, dragEnd, THEME);
        addDrawableChild(slider);
        registerDrawable(slider, y, PLAYBACK_PROGRESS_HEIGHT);
        return slider;
    }

    private void registerDrawable(Drawable drawable, int y, int height) {
        switch (widgetGroup) {
            case FIXED -> fixedDrawables.add(drawable);
            case AREA_SCROLL -> {
                applyClip(drawable, WidgetGroup.AREA_SCROLL);
                areaScrollDrawables.add(drawable);
                trackGroupBottom(WidgetGroup.AREA_SCROLL, y + height);
            }
            case SCREEN_SCROLL -> {
                applyClip(drawable, WidgetGroup.SCREEN_SCROLL);
                screenScrollDrawables.add(drawable);
                trackGroupBottom(WidgetGroup.SCREEN_SCROLL, y + height);
            }
            case CONTENT_SCROLL -> {
                applyClip(drawable, WidgetGroup.CONTENT_SCROLL);
                contentScrollDrawables.add(drawable);
                trackGroupBottom(WidgetGroup.CONTENT_SCROLL, y + height);
            }
        }
    }

    private void applyClip(Drawable drawable, WidgetGroup group) {
        int left = clipLeft(group);
        int top = clipTop(group);
        int right = clipRight(group);
        int bottom = clipBottom(group);
        if (drawable instanceof VpButtonWidget button) {
            button.clip(left, top, right, bottom);
        } else if (drawable instanceof VpTextFieldWidget field) {
            field.clip(left, top, right, bottom);
        } else if (drawable instanceof VpSliderWidget slider) {
            slider.clip(left, top, right, bottom);
        }
    }

    private void trackGroupBottom(WidgetGroup group, int visualBottom) {
        int contentBottom = visualBottom + scrollForGroup(group) - clipTop(group);
        switch (group) {
            case AREA_SCROLL -> areaScrollContentHeight = Math.max(areaScrollContentHeight, contentBottom);
            case SCREEN_SCROLL -> screenScrollContentHeight = Math.max(screenScrollContentHeight, contentBottom);
            case CONTENT_SCROLL -> contentScrollContentHeight = Math.max(contentScrollContentHeight, contentBottom);
            case FIXED -> {
            }
        }
    }

    private void trackContentBottom(int visualBottom) {
        trackGroupBottom(WidgetGroup.CONTENT_SCROLL, visualBottom);
    }

    private int scrollForGroup(WidgetGroup group) {
        return switch (group) {
            case AREA_SCROLL -> areaScroll;
            case SCREEN_SCROLL -> screenScroll;
            case CONTENT_SCROLL -> contentScroll;
            case FIXED -> 0;
        };
    }

    private int clipLeft(WidgetGroup group) {
        return switch (group) {
            case AREA_SCROLL, SCREEN_SCROLL -> sidebarX();
            case CONTENT_SCROLL -> mainX();
            case FIXED -> 0;
        };
    }

    private int clipTop(WidgetGroup group) {
        return switch (group) {
            case AREA_SCROLL -> sidebarAreaViewportTop();
            case SCREEN_SCROLL -> sidebarScreenViewportTop();
            case CONTENT_SCROLL -> contentViewportTop();
            case FIXED -> 0;
        };
    }

    private int clipRight(WidgetGroup group) {
        return switch (group) {
            case AREA_SCROLL, SCREEN_SCROLL -> sidebarX() + SIDEBAR_WIDTH;
            case CONTENT_SCROLL -> mainX() + mainW();
            case FIXED -> width;
        };
    }

    private int clipBottom(WidgetGroup group) {
        return switch (group) {
            case AREA_SCROLL -> sidebarAreaViewportBottom();
            case SCREEN_SCROLL -> sidebarScreenViewportBottom();
            case CONTENT_SCROLL -> contentViewportBottom();
            case FIXED -> height;
        };
    }

    private void preserveCurrentFieldsForReopen() {
        if (tab == Tab.CREATE_EDIT) {
            copyCreateEditFieldsToDraft();
        }
    }

    private int actionButtonWidth(int width, int count) {
        return Math.max(64, (width - GAP * (count - 1)) / count);
    }

    private void clearSphereFields() {
        sphereCenterXField = null;
        sphereCenterYField = null;
        sphereCenterZField = null;
        sphereRadiusField = null;
        sphereLatField = null;
        sphereLonField = null;
        sphereRotXField = null;
        sphereRotYField = null;
        sphereRotZField = null;
    }

    private void copyCreateEditFieldsToDraft() {
        VideoCreationEditor.Draft draft = editor.draft();
        if (nameField != null && draft.operation != VideoCreationEditor.Operation.EDIT_SCREEN_GEOMETRY) {
            draft.name = nameField.getText().trim();
        }
        if (draft.operation == VideoCreationEditor.Operation.EDIT_SCREEN_GEOMETRY) {
            draft.name = selectedScreenName == null ? "" : selectedScreenName;
        }
        draft.areaName = selectedAreaName == null ? "" : selectedAreaName;
        if (sourceField != null) draft.source = sourceField.getText().trim();
        Float centerX = parseFloat(sphereCenterXField);
        Float centerY = parseFloat(sphereCenterYField);
        Float centerZ = parseFloat(sphereCenterZField);
        Float sphereRadius = parseFloat(sphereRadiusField);
        Integer sphereLat = parseInt(sphereLatField);
        Integer sphereLon = parseInt(sphereLonField);
        Float sphereRotX = parseFloat(sphereRotXField);
        Float sphereRotY = parseFloat(sphereRotYField);
        Float sphereRotZ = parseFloat(sphereRotZField);
        if (centerX != null && centerY != null && centerZ != null) {
            draft.sphereCenter = new Vector3f(centerX, centerY, centerZ);
        }
        if (sphereRadius != null) draft.sphereRadius = sphereRadius;
        if (sphereLat != null) draft.sphereLat = VideoScreen.clampSphereSegments(sphereLat);
        if (sphereLon != null) draft.sphereLon = VideoScreen.clampSphereSegments(sphereLon);
        if (sphereRotX != null) draft.sphereRotX = sphereRotX;
        if (sphereRotY != null) draft.sphereRotY = sphereRotY;
        if (sphereRotZ != null) draft.sphereRotZ = sphereRotZ;
        draft.target = draft.operation.target();
    }

    private Text selectionButtonText() {
        return editor.selecting()
                ? VpTexts.tr("button.videoplayer.cancel_selection", "Cancel Selection")
                : VpTexts.tr("button.videoplayer.start_selection", "Start Selection");
    }

    private void toggleSelection() {
        if (editor.selecting()) {
            editor.clearSelection();
            reopen(null);
            return;
        }
        copyCreateEditFieldsToDraft();
        editor.beginSelection(editor.draft());
    }

    private void syncDraftFromSelection(boolean includeScreenSource) {
        syncDraftFromSelection(includeScreenSource, true);
    }

    private void syncDraftFromSelection(boolean includeScreenSource, boolean includeScreenDisplay) {
        VideoCreationEditor.Draft draft = editor.draft();
        if (draft.operation == null) draft.operation = VideoCreationEditor.Operation.CREATE_AREA;
        draft.areaName = selectedAreaName == null ? "" : selectedAreaName;
        if (draft.operation == VideoCreationEditor.Operation.EDIT_SCREEN_GEOMETRY) {
            draft.name = selectedScreenName == null ? "" : selectedScreenName;
        }
        if (includeScreenSource) {
            ClientVideoScreen screen = selectedScreen();
            if (screen != null) draft.source = safe(screen.source);
        }
        ClientVideoScreen screen = selectedScreen();
        if (includeScreenDisplay && draft.operation == VideoCreationEditor.Operation.EDIT_SCREEN_GEOMETRY && screen != null) {
            copyScreenDisplayToDraft(screen, draft);
        }
        draft.target = draft.operation.target();
    }

    private boolean preserveDraftForCurrentSelection() {
        VideoCreationEditor.Draft draft = editor.draft();
        return draft.operation == VideoCreationEditor.Operation.EDIT_SCREEN_GEOMETRY
                && Objects.equals(safe(draft.areaName), safe(selectedAreaName))
                && Objects.equals(safe(draft.name), safe(selectedScreenName));
    }

    private void copyScreenDisplayToDraft(ClientVideoScreen screen, VideoCreationEditor.Draft draft) {
        draft.surface = screen.surface == null ? ScreenSurface.FLAT : screen.surface;
        draft.stereo3d = screen.stereo3d;
        draft.spherePreset = screen.spherePreset;
        draft.sphereCenter = screen.spherePreset && screen.sphereCenter != null ? new Vector3f(screen.sphereCenter) : null;
        draft.sphereRadius = screen.sphereRadius;
        draft.sphereLat = screen.sphereLat;
        draft.sphereLon = screen.sphereLon;
        draft.sphereRotX = screen.sphereRotX;
        draft.sphereRotY = screen.sphereRotY;
        draft.sphereRotZ = screen.sphereRotZ;
        draft.sphereSkybox = screen.sphereSkybox;
    }

    private void ensureSpherePresetDefaults(VideoCreationEditor.Draft draft) {
        draft.spherePreset = true;
        if (draft.sphereCenter == null) draft.sphereCenter = defaultSphereCenter();
        if (!Float.isFinite(draft.sphereRadius) || draft.sphereRadius <= 0) draft.sphereRadius = 10;
        draft.sphereLat = VideoScreen.clampSphereSegments(draft.sphereLat);
        draft.sphereLon = VideoScreen.clampSphereSegments(draft.sphereLon);
    }

    private Vector3f defaultSphereCenter() {
        ClientVideoScreen screen = selectedScreen();
        if (screen != null && screen.spherePreset && screen.sphereCenter != null) {
            return new Vector3f(screen.sphereCenter);
        }
        ClientVideoArea area = selectedArea();
        if (area != null) {
            return new Vector3f(
                    (area.min.x + area.max.x) * 0.5f,
                    (area.min.y + area.max.y) * 0.5f,
                    (area.min.z + area.max.z) * 0.5f
            );
        }
        return new Vector3f();
    }

    private void cycleSource() {
        List<String> sources = sourceNames();
        if (sources.isEmpty()) {
            if (sourceField != null) sourceField.setText("");
            return;
        }
        String current = sourceField == null ? "" : sourceField.getText().trim();
        int index = sources.indexOf(current);
        String next = sources.get((index + 1 + sources.size()) % sources.size());
        if (sourceField != null) sourceField.setText(next);
        editor.draft().source = next;
    }

    private boolean canSaveScreenConfig(ClientVideoScreen screen, VideoCreationEditor.Draft draft) {
        if (screen == null) return false;
        return draft.surface == ScreenSurface.SPHERE_360 ? draft.spherePreset : screen.vertices.size() >= 3;
    }

    private boolean canSelectForDraft(VideoCreationEditor.Draft draft, ClientVideoArea area, ClientVideoScreen screen) {
        return switch (draft.operation) {
            case CREATE_AREA -> canGlobal(VideoPermissionAction.CREATE_AREA);
            case CREATE_SCREEN -> area != null && canArea(VideoPermissionAction.CREATE_SCREEN, area);
            case EDIT_SCREEN_GEOMETRY -> screen != null && canScreen(VideoPermissionAction.UPDATE_SCREEN, screen);
        };
    }

    private boolean canSubmitDraft(VideoCreationEditor.Draft draft, ClientVideoArea area, ClientVideoScreen screen) {
        return canSelectForDraft(draft, area, screen);
    }

    private void saveScreenConfig(VpButtonWidget button) {
        ClientVideoScreen screen = selectedScreen();
        if (screen == null) return;
        copyCreateEditFieldsToDraft();
        VideoCreationEditor.Draft draft = editor.draft();
        String source = sourceField == null ? "" : sourceField.getText().trim();
        if (draft.surface == ScreenSurface.SPHERE_360 && !draft.spherePreset) {
            sendLocalError(VpTexts.tr("error.videoplayer.sphere_preset_required", "Define 360 parameters first"));
            return;
        }
        List<Vector3f> vertices = copyVertices(screen.vertices);
        VideoScreen displayConfig = new VideoScreen(screen.area, screen.name, vertices, source);
        editor.applyDraftDisplay(displayConfig);
        ClientPacketHandler.updateScreen(screen, vertices, source, displayConfig, permissionFeedback(button));
        editor.draft().source = source;
    }

    private void togglePlaybackStereo(VpButtonWidget button) {
        ClientVideoScreen screen = selectedScreen();
        if (screen == null) return;
        VideoScreen displayConfig = displayConfigFromScreen(screen);
        displayConfig.stereo3d = !screen.stereo3d;
        sendDisplayConfig(screen, displayConfig, permissionFeedback(button));
    }

    private void togglePlaybackSurface(VpButtonWidget button) {
        ClientVideoScreen screen = selectedScreen();
        if (screen == null) return;
        VideoScreen displayConfig = displayConfigFromScreen(screen);
        if (screen.surface == ScreenSurface.SPHERE_360) {
            if (screen.vertices.size() < 3) {
                sendLocalError(VpTexts.tr("error.videoplayer.no_flat_vertices", "Current Screen has no available flat vertices"));
                return;
            }
            displayConfig.surface = ScreenSurface.FLAT;
        } else {
            if (!screen.spherePreset) {
                sendLocalError(VpTexts.tr("error.videoplayer.define_sphere_in_editor", "Define 360 parameters in the create/edit page first"));
                return;
            }
            displayConfig.surface = ScreenSurface.SPHERE_360;
        }
        sendDisplayConfig(screen, displayConfig, permissionFeedback(button));
    }

    private VideoScreen displayConfigFromScreen(ClientVideoScreen screen) {
        VideoScreen displayConfig = new VideoScreen(screen.area, screen.name, copyVertices(screen.vertices), safe(screen.source));
        displayConfig.copyDisplayConfigFrom(screen);
        return displayConfig;
    }

    private void sendDisplayConfig(ClientVideoScreen screen, VideoScreen displayConfig) {
        sendDisplayConfig(screen, displayConfig, null);
    }

    private void sendDisplayConfig(ClientVideoScreen screen, VideoScreen displayConfig, Consumer<ClientPacketHandler.RequestResult> callback) {
        ClientPacketHandler.updateScreen(screen, copyVertices(screen.vertices), safe(screen.source), displayConfig, callback);
    }

    private void sendLocalError(Text message) {
        if (client != null && client.player != null) {
            client.player.sendMessage(message.copy().formatted(Formatting.RED), false);
        }
    }

    private void setScreenScaleX(float scaleX) {
        ClientVideoScreen screen = selectedScreen();
        if (screen == null) return;
        setScaleAndRefresh(screen, false, scaleX, screen.scaleY);
    }

    private void setScreenScaleY(float scaleY) {
        ClientVideoScreen screen = selectedScreen();
        if (screen == null) return;
        setScaleAndRefresh(screen, false, screen.scaleX, scaleY);
    }

    private int scaleToSliderValue(float scale) {
        float clamped = Math.clamp(scale, MIN_SCREEN_SCALE, MAX_SCREEN_SCALE);
        double logMin = log2(MIN_SCREEN_SCALE);
        double range = log2(MAX_SCREEN_SCALE) - logMin;
        return Math.clamp((int) Math.round(((log2(clamped) - logMin) / range) * 100.0), 0, 100);
    }

    private float sliderValueToScale(int value) {
        double logMin = log2(MIN_SCREEN_SCALE);
        double range = log2(MAX_SCREEN_SCALE) - logMin;
        return (float) Math.pow(2.0, logMin + range * Math.clamp(value, 0, 100) / 100.0);
    }

    private double log2(float value) {
        return Math.log(value) / Math.log(2.0);
    }

    private void setScaleAndRefresh(ClientVideoScreen screen, boolean fill, float scaleX, float scaleY) {
        setScaleAndRefresh(screen, fill, scaleX, scaleY, null);
    }

    private void setScaleAndRefresh(ClientVideoScreen screen, boolean fill, float scaleX, float scaleY, Consumer<ClientPacketHandler.RequestResult> callback) {
        if (screen == null || scaleX < MIN_SCREEN_SCALE || scaleX > MAX_SCREEN_SCALE || scaleY < MIN_SCREEN_SCALE || scaleY > MAX_SCREEN_SCALE) {
            return;
        }
        ClientPacketHandler.setScale(screen, fill, scaleX, scaleY, callback);
        reopen(null);
    }

    private void setDefaultVolume(int volume) {
        ClientVideoScreen screen = selectedScreen();
        if (screen == null) return;
        ScreenVolumeCache.invalidate(screen);
        setMetadata(screen, ScreenMetadata.KEY_DEFAULT_VOLUME, MetaValue.ofInt(Math.clamp(volume, 0, 100)));
    }

    private void toggleMeta(VpButtonWidget button, String key, boolean defaultValue) {
        ClientVideoScreen screen = selectedScreen();
        if (screen == null) return;
        boolean value = !screen.metadata.getBool(key, defaultValue);
        setMetadata(screen, key, MetaValue.ofBool(value), permissionFeedback(button));
    }

    private void setCustomMeta(VpButtonWidget button, boolean remove) {
        ClientVideoScreen screen = selectedScreen();
        if (screen == null || customKeyField == null) return;
        String key = customKeyField.getText().trim();
        if (key.isEmpty()) return;
        if (remove) {
            removeMetadata(screen, key, permissionFeedback(button));
            return;
        }
        try {
            MetaValue value = MetaValue.parse(customMetaType, customValueField == null ? "" : customValueField.getText());
            setMetadata(screen, key, value, permissionFeedback(button));
        } catch (Exception ignored) {
        }
    }

    private void setMetadata(ClientVideoScreen screen, String key, MetaValue value) {
        setMetadata(screen, key, value, null);
    }

    private void setMetadata(ClientVideoScreen screen, String key, MetaValue value, Consumer<ClientPacketHandler.RequestResult> callback) {
        if (isReservedMetaKey(key)) return;
        try {
            ClientPacketHandler.setMetadata(screen, key, value, result -> {
                if (result != null && result.status() == RequestResultStatus.OK
                        && VideoPlayerClient.screens.contains(screen)
                        && client.currentScreen instanceof VideoManagementScreen) {
                    reopen(null);
                }
                if (callback != null) callback.accept(result);
            });
        } catch (Exception ignored) {
        }
    }

    private void removeMetadata(ClientVideoScreen screen, String key) {
        removeMetadata(screen, key, null);
    }

    private void removeMetadata(ClientVideoScreen screen, String key, Consumer<ClientPacketHandler.RequestResult> callback) {
        if (isReservedMetaKey(key)) return;
        try {
            ClientPacketHandler.removeMetadata(screen, key, result -> {
                if (result != null && result.status() == RequestResultStatus.OK
                        && VideoPlayerClient.screens.contains(screen)
                        && client.currentScreen instanceof VideoManagementScreen) {
                    reopen(null);
                }
                if (callback != null) callback.accept(result);
            });
        } catch (Exception ignored) {
        }
    }

    private boolean isReservedMetaKey(String key) {
        return switch (key) {
            case "3d", "360", "spherePreset", "skybox", "x", "y", "z", "radius", "lat", "lon", "rot", "rotX", "rotY", "rotZ", "aspect", "fov" -> true;
            default -> false;
        };
    }

    private void cycleCustomMetaType() {
        MetaType[] values = MetaType.values();
        customMetaType = values[(customMetaType.ordinal() + 1) % values.length];
        reopen(null);
    }

    private void deleteSelectedArea(VpButtonWidget button) {
        ClientVideoArea area = selectedArea();
        if (area == null) return;
        if (!confirmDeleteArea) {
            confirmDeleteArea = true;
            confirmDeleteScreen = false;
            reopen(null);
            return;
        }
        ClientPacketHandler.removeArea(area.name, result -> {
            if (ClientPacketHandler.denied(result)) {
                button.showPermissionDenied();
                return;
            }
            if (result != null && result.status() == RequestResultStatus.OK) {
                selectedAreaName = null;
                selectedScreenName = null;
                confirmDeleteArea = false;
                reopen(null);
            }
        });
    }

    private void deleteSelectedScreen(VpButtonWidget button) {
        ClientVideoScreen screen = selectedScreen();
        if (screen == null) return;
        if (!confirmDeleteScreen) {
            confirmDeleteScreen = true;
            confirmDeleteArea = false;
            reopen(null);
            return;
        }
        ClientPacketHandler.removeScreen(screen, result -> {
            if (ClientPacketHandler.denied(result)) {
                button.showPermissionDenied();
                return;
            }
            if (result != null && result.status() == RequestResultStatus.OK) {
                selectedScreenName = null;
                confirmDeleteScreen = false;
                reopen(null);
            }
        });
    }

    private void ensureSelection() {
        if (selectedAreaName == null || !VideoPlayerClient.areas.containsKey(selectedAreaName)) {
            selectedAreaName = firstAreaName();
        }
        ClientVideoArea area = selectedArea();
        if (area == null) {
            selectedScreenName = null;
            return;
        }
        if (selectedScreenName == null || area.getScreen(selectedScreenName) == null) {
            selectedScreenName = firstScreenName(selectedAreaName);
        }
    }

    private ClientVideoArea selectedArea() {
        if (selectedAreaName == null) return null;
        return VideoPlayerClient.areas.get(selectedAreaName);
    }

    private ClientVideoScreen selectedScreen() {
        ClientVideoArea area = selectedArea();
        if (area == null || selectedScreenName == null) return null;
        return area.getScreen(selectedScreenName);
    }

    private ClientVideoScreen selectedPlaybackScreen() {
        ClientVideoScreen screen = selectedScreen();
        return screen == null ? null : screen.getScreen();
    }

    private boolean usesMpvPlaybackVolume(ClientVideoScreen playbackScreen) {
        if (playbackScreen != null && playbackScreen.player instanceof VideoPlayer player) {
            return VideoBackends.MPV.equals(player.backendName());
        }
        return VideoBackends.MPV.equals(VideoBackends.normalize(VideoPlayerClient.config.videoBackend)) && MpvVideoBackend.isAvailable();
    }

    private List<String> areaNames() {
        return VideoPlayerClient.areas.values().stream()
                .map(area -> area.name)
                .sorted()
                .toList();
    }

    private String areaSignature() {
        return areaNames().toString();
    }

    private List<ClientVideoScreen> screensForSelectedArea() {
        ClientVideoArea area = selectedArea();
        if (area == null) return List.of();
        return area.screens.stream()
                .map(screen -> (ClientVideoScreen) screen)
                .sorted(Comparator.comparing(screen -> screen.name))
                .toList();
    }

    private String screenSignature() {
        return screensForSelectedArea().stream()
                .map(screen -> screen.name + ":" + safe(screen.source) + ":" + screen.fill + ":" + format(screen.scaleX) + ":" + format(screen.scaleY)
                        + ":" + screen.surface + ":" + screen.stereo3d + ":" + screen.spherePreset + ":" + format(screen.sphereRadius)
                        + ":" + screen.sphereLat + ":" + screen.sphereLon + ":" + format(screen.sphereRotX) + ":" + format(screen.sphereRotY) + ":" + format(screen.sphereRotZ) + ":" + screen.sphereSkybox)
                .toList()
                .toString();
    }

    private String metadataSignature() {
        ClientVideoScreen screen = selectedScreen();
        if (screen == null) return "";
        return screen.metadata.entries().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + ":" + entry.getValue().type + "=" + entry.getValue().toDisplayString())
                .toList()
                .toString();
    }

    private List<String> sourceNames() {
        ClientVideoArea area = selectedArea();
        if (area == null) return List.of();
        String self = editor.draft().operation == VideoCreationEditor.Operation.EDIT_SCREEN_GEOMETRY ? selectedScreenName : nameField == null ? "" : nameField.getText().trim();
        ArrayList<String> result = new ArrayList<>();
        result.add("");
        area.screens.stream()
                .filter(screen -> (screen.source == null || screen.source.isEmpty()) && !screen.name.equals(self))
                .map(screen -> screen.name)
                .sorted()
                .forEach(result::add);
        return result;
    }

    private String firstAreaName() {
        return areaNames().stream().findFirst().orElse("");
    }

    private String firstScreenName(String areaName) {
        ClientVideoArea area = VideoPlayerClient.areas.get(areaName);
        if (area == null) return "";
        return area.screens.stream()
                .map(screen -> screen.name)
                .sorted()
                .findFirst()
                .orElse("");
    }

    private void reopen(ClientVideoScreen focusedScreen) {
        reopen(focusedScreen, false);
    }

    private void reopenPreservingDraft() {
        reopen(null, true);
    }

    private void reopen(ClientVideoScreen focusedScreen, boolean preserveDraftDisplay) {
        if (focusedScreen != null) {
            client.setScreen(new VideoManagementScreen(editor, focusedScreen, tab,
                    danmakuOverlayOpen, biliLocalQualityOverlayOpen, biliScreenQualityOverlayOpen,
                    youtubeScreenQualityOverlay, ccSubtitleOverlayOpen,
                    playbackPreviewPinned));
            return;
        }
        client.setScreen(new VideoManagementScreen(
                editor,
                tab,
                selectedAreaName,
                selectedScreenName,
                areaScroll,
                screenScroll,
                contentScroll,
                confirmDeleteArea,
                confirmDeleteScreen,
                customMetaType,
                preserveDraftDisplay,
                danmakuOverlayOpen,
                biliLocalQualityOverlayOpen,
                biliScreenQualityOverlayOpen,
                youtubeScreenQualityOverlay,
                ccSubtitleOverlayOpen,
                playbackPreviewPinned
        ));
    }

    private Text operationLabel(VideoCreationEditor.Operation operation) {
        return switch (operation) {
            case CREATE_AREA -> VpTexts.tr("button.videoplayer.create_area", "Create Area");
            case CREATE_SCREEN -> VpTexts.tr("button.videoplayer.create_screen", "Create Screen");
            case EDIT_SCREEN_GEOMETRY -> VpTexts.tr("button.videoplayer.edit_screen", "Edit Screen");
        };
    }

    private Text boolLabel(ClientVideoScreen screen, String key, boolean defaultValue) {
        boolean value = screen == null ? defaultValue : screen.metadata.getBool(key, defaultValue);
        return onOff(value);
    }

    private Text onOff(boolean value) {
        return value ? VpTexts.tr("label.videoplayer.on", "On") : VpTexts.tr("label.videoplayer.off", "Off");
    }

    private Text danmakuSpeedLabel(int index) {
        int safeIndex = Math.clamp(index, 0, DANMAKU_SPEED_KEYS.length - 1);
        return VpTexts.tr(DANMAKU_SPEED_KEYS[safeIndex], DANMAKU_SPEED_FALLBACKS[safeIndex]);
    }

    private Text danmakuDensityLabel(int index) {
        int safeIndex = Math.clamp(index, 0, DANMAKU_DENSITY_KEYS.length - 1);
        return VpTexts.tr(DANMAKU_DENSITY_KEYS[safeIndex], DANMAKU_DENSITY_FALLBACKS[safeIndex]);
    }

    private String defaultValueFor(MetaType type) {
        return switch (type) {
            case BOOL -> "false";
            case INT -> "0";
            case LONG -> "0";
            case FLOAT -> "0";
            case DOUBLE -> "0";
            case STRING -> "";
            case BOOL_ARRAY -> "false, true";
            case INT_ARRAY -> "0, 1";
            case FLOAT_ARRAY -> "0, 1";
            case STRING_ARRAY -> "a, b";
        };
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String format(float value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private Float parseFloat(TextFieldWidget field) {
        if (field == null) return null;
        try {
            float value = Float.parseFloat(field.getText().trim());
            return Float.isFinite(value) ? value : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Integer parseInt(TextFieldWidget field) {
        if (field == null) return null;
        try {
            return Integer.parseInt(field.getText().trim());
        } catch (Exception e) {
            return null;
        }
    }

    private List<Vector3f> copyVertices(List<Vector3f> vertices) {
        ArrayList<Vector3f> copy = new ArrayList<>(vertices.size());
        for (Vector3f vertex : vertices) {
            copy.add(new Vector3f(vertex));
        }
        return copy;
    }

    private enum WidgetGroup {
        FIXED,
        AREA_SCROLL,
        SCREEN_SCROLL,
        CONTENT_SCROLL
    }

    private record SubtitleChoice(String key, Text label, boolean active) {
    }

    private enum Tab {
        CREATE_EDIT("tab.videoplayer.create_edit", "Create/Edit"),
        PLAYBACK("tab.videoplayer.playback", "Playback"),
        SCREEN_SETTINGS("tab.videoplayer.screen_settings", "Screen Settings");

        final String key;
        final String fallback;

        Tab(String key, String fallback) {
            this.key = key;
            this.fallback = fallback;
        }

        Text label() {
            return VpTexts.tr(key, fallback);
        }
    }
}
