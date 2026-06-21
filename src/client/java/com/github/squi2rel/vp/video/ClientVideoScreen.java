package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.ClientPacketHandler;
import com.github.squi2rel.vp.ScreenRenderer;
import com.github.squi2rel.vp.VideoPlayerClient;
import com.github.squi2rel.vp.danmaku.ClientDanmakuController;
import com.github.squi2rel.vp.i18n.VpTexts;
import com.github.squi2rel.vp.danmaku.ClientDanmakuRenderer;
import com.github.squi2rel.vp.danmaku.ClientSubtitleController;
import com.github.squi2rel.vp.provider.VideoInfo;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.joml.Vector3f;

import java.util.*;

public class ClientVideoScreen extends VideoScreen {
    public IVideoPlayer player = null;
    private VideoInfo toPlay = null;
    private boolean toPlayIdle;
    private VideoInfo idleInfo;
    private VideoInfo playingInfo;
    private long toSeek = -1;
    private long startTime = System.currentTimeMillis();
    public boolean interactable = true;
    public int volume = 100;
    private boolean idlePlaying;
    private int appliedDefaultVolume = 100;
    private volatile boolean loaded;
    private volatile int playbackToken;
    private final ClientDanmakuController danmaku = new ClientDanmakuController(this);
    private final ClientSubtitleController subtitles = new ClientSubtitleController(this);

    private long lastAutoSync;
    private int syncFrames;

    private double srtt = -1;
    private double rttvar = -1;
    private static final double ALPHA = 0.125;
    private static final double BETA = 0.25;

    public ClientVideoScreen(VideoArea area, String name, Vector3f v1, Vector3f v2, Vector3f v3, Vector3f v4, String source) {
        super(area, name, v1, v2, v3, v4, source);
    }

    public ClientVideoScreen(VideoArea area, String name, List<Vector3f> vertices, String source) {
        super(area, name, vertices, source);
    }

    public void updatePlaylist(VideoInfo[] target) {
        infos.clear();
        for (VideoInfo info : target) {
            infos.offer(info);
        }
        if (!infos.isEmpty()) clearIdlePlayback();
        if (infos.isEmpty()) toPlay = null;
    }

    public void metadataChanged() {
        if (metadata == null) metadata = new ScreenMetadata();
        ensureValidState();
        metadata.ensureValid();
        applyCachedOrDefaultVolume();
        interactable = metadata.getBool("interactable", true);
        if (player instanceof MetaListener m) m.onMetaChanged();
    }

    public void metaChanged() {
        metadataChanged();
    }

    public int defaultVolume() {
        return Math.clamp(metadata == null ? 100 : metadata.getInt(ScreenMetadata.KEY_DEFAULT_VOLUME, 100), 0, 100);
    }

    private void applyCachedOrDefaultVolume() {
        int configured = defaultVolume();
        Integer cached = ScreenVolumeCache.get(this);
        if (cached != null) {
            appliedDefaultVolume = configured;
            volume = cached;
            return;
        }
        if (configured == appliedDefaultVolume) {
            return;
        }
        appliedDefaultVolume = configured;
        volume = configured;
    }

    public void applyUpdate(List<Vector3f> vertices, String source, VideoScreen displayConfig) {
        String normalizedSource = source == null ? "" : source;
        boolean sourceChanged = !Objects.equals(this.source == null ? "" : this.source, normalizedSource);
        setVertices(vertices);
        this.source = normalizedSource;
        if (displayConfig != null) copyDisplayConfigFrom(displayConfig);
        if (!sourceChanged) {
            if (player instanceof MetaListener m) m.onMetaChanged();
            return;
        }

        IVideoPlayer old = player;
        player = null;
        playingInfo = null;
        danmaku.stop();
        if (old != null) old.cleanup();
        srtt = -1;
        rttvar = -1;
        syncFrames = 0;

        if (!VideoPlayerClient.screens.contains(this)) return;
        if (this.source.isEmpty()) {
            if (toPlay != null) play(toPlay, toPlayIdle);
            return;
        }
        ClientVideoScreen parent = ((ClientVideoArea) area).getScreen(this.source);
        if (parent != null) {
            player = new ClonePlayer(this, parent);
        }
    }

    public ClientVideoScreen getScreen() {
        return player == null ? this : player.screen();
    }

    public void cleanup() {
        loaded = false;
        cancelPendingPlayback();
        IVideoPlayer old = player;
        player = null;
        if (old != null) old.cleanup();
    }

    public void draw(MatrixStack matrices, VertexConsumerProvider consumers) {
        if (shouldDrawPlaceholder()) {
            if (surface == ScreenSurface.SPHERE_360 && spherePreset) {
                VideoPlayerRenderer.drawTexture(ScreenRenderer.placeholderTextureId(), 960, 540, matrices, consumers, this);
                Degree360Player.drawTexture(ScreenRenderer.placeholderTextureId(), matrices, consumers, this);
                return;
            }
            VideoPlayerRenderer.drawTexture(ScreenRenderer.placeholderTextureId(), 960, 540, matrices, consumers, this);
            return;
        }
        player.draw(matrices, consumers, this);
        ClientDanmakuRenderer.draw(matrices, consumers, this);
        ClientDanmakuRenderer.drawSubtitles(matrices, consumers, this);
    }

    public int displayTextureId() {
        return shouldDrawPlaceholder() ? ScreenRenderer.placeholderTextureId() : player.getTextureId();
    }

    public int displayTextureWidth() {
        return shouldDrawPlaceholder() ? 960 : Math.max(1, player.getWidth());
    }

    public int displayTextureHeight() {
        return shouldDrawPlaceholder() ? 540 : Math.max(1, player.getHeight());
    }

    public void swapTexture() {
        if (player != null) player.swapTexture();
    }

    public void update() {
        if (player != null) player.updateTexture();
        danmaku.update();
        subtitles.update();

        if (player instanceof RateAdjustablePlayer ratePlayer && !ratePlayer.isPaused()) {
            long syncTime = 150L * Math.max(syncFrames, 1);
            if (metadata.getBool("autoSync", false) && System.currentTimeMillis() - lastAutoSync >= syncTime) {
                lastAutoSync = System.currentTimeMillis();
                ClientPacketHandler.autoSync(this, System.currentTimeMillis());
            }
        }
    }

    public ClientVideoScreen getTrackingScreen() {
        return player == null ? this : player.getTrackingScreen();
    }

    public void load() {
        if (loaded) return;
        loaded = true;
        if (!VideoPlayerClient.screens.contains(this)) VideoPlayerClient.screens.add(this);
        applyCachedOrDefaultVolume();
        if (source.isEmpty()) {
            if (toPlay != null) play(toPlay, toPlayIdle);
            return;
        }
        ClientVideoScreen parent = (ClientVideoScreen) area.screens.stream().filter(v -> Objects.equals(v.name, source)).findAny().orElseThrow();
        ((ClientVideoArea) area).afterLoad(() -> player = new ClonePlayer(this, parent));
    }

    public void play(VideoInfo info) {
        play(info, false);
    }

    public void play(VideoInfo info, boolean idle) {
        if (!loaded) return;
        syncFrames = 0;
        if (source.isEmpty()) {
            applyCachedOrDefaultVolume();
            IVideoPlayer old = player;
            player = VideoPlayers.from(info, this, player);
            if (player == null) return;
            playingInfo = info;
            idlePlaying = idle;
            idleInfo = idle ? info : null;
            if (player != old) {
                if (old != null) old.cleanup();
                player.init();
            }
            if (player instanceof MetaListener m) m.onMetaChanged();
            if (toSeek > 0) {
                startTime = System.currentTimeMillis() - toSeek;
                player.setTargetTime(toSeek);
                toSeek = -1;
            } else {
                player.setTargetTime(-1);
                startTime = System.currentTimeMillis();
            }
            player.play(info);
        }
    }

    public void setToPlay(VideoInfo info) {
        setToPlay(info, false);
    }

    public void setToPlay(VideoInfo info, boolean idle) {
        toPlay = info;
        toPlayIdle = idle;
    }

    public int beginPlaybackRequest() {
        return ++playbackToken;
    }

    public boolean canAcceptPlayback(int token) {
        return loaded && playbackToken == token && VideoPlayerClient.screens.contains(this);
    }

    public boolean isPlaybackRequestCurrent(int token) {
        return playbackToken == token;
    }

    public void setToSeek(long seek) {
        toSeek = seek;
    }

    public long getStartTime() {
        return startTime;
    }

    public VideoInfo currentDisplayInfo() {
        VideoInfo queued = infos.peek();
        return queued == null ? idleInfo : queued;
    }

    public VideoInfo currentPlaybackInfo() {
        return playingInfo == null ? currentDisplayInfo() : playingInfo;
    }

    public ClientDanmakuController danmaku() {
        return danmaku;
    }

    public ClientSubtitleController subtitles() {
        return subtitles;
    }

    public boolean isIdlePlaying() {
        return idlePlaying;
    }

    public void clearPlaybackState() {
        cancelPendingPlayback();
    }

    private void cancelPendingPlayback() {
        playbackToken++;
        toPlay = null;
        toPlayIdle = false;
        playingInfo = null;
        danmaku.stop();
        subtitles.stop();
        clearIdlePlayback();
    }

    private void clearIdlePlayback() {
        idlePlaying = false;
        idleInfo = null;
    }

    public void setProgress(long progress) {
        syncFrames = 0;
        player.setProgress(progress);
        startTime = System.currentTimeMillis() - progress;
        danmaku.seek(progress);
    }

    public void autoSync(long roundTrip, long syncProgress) {
        int clientDelay = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, roundTrip));
        if (srtt < 0) {
            srtt = clientDelay;
            rttvar = clientDelay / 2.0;
        } else {
            double delta = Math.abs(clientDelay - srtt);
            if (delta > 1000) return;
            rttvar = (1 - BETA) * rttvar + BETA * delta;
            srtt = (1 - ALPHA) * srtt + ALPHA * clientDelay;
        }

        int rtt = (int) Math.round(srtt);
        syncProgress += rtt / 2;

        if (player instanceof RateAdjustablePlayer ratePlayer && !ratePlayer.isPaused()) {
            if (syncProgress <= 0) return;
            long progress = ratePlayer.getProgress();
            if (progress <= 0) return;

            long delta = syncProgress - progress;
            if (delta > -25 && delta <= 25) {
                syncFrames++;
            } else {
                syncFrames--;
            }
            syncFrames = Math.clamp(syncFrames, 0, 7);

            if (syncFrames < 5) {
                if (delta > 10000) {
                    if (ratePlayer.getRate() != 3f) ratePlayer.setRate(3f);
                } else if (delta > 5000) {
                    if (ratePlayer.getRate() != 2f) ratePlayer.setRate(2f);
                } else if (delta > 3000) {
                    if (ratePlayer.getRate() != 1.5f) ratePlayer.setRate(1.5f);
                } else if (delta > 1500) {
                    if (ratePlayer.getRate() != 1.4f) ratePlayer.setRate(1.4f);
                } else if (delta > 500) {
                    if (ratePlayer.getRate() != 1.3f) ratePlayer.setRate(1.3f);
                } else if (delta > 100) {
                    if (ratePlayer.getRate() != 1.2f) ratePlayer.setRate(1.2f);
                } else if (delta > 25) {
                    if (ratePlayer.getRate() != 1.1f) ratePlayer.setRate(1.1f);
                } else if (delta > -25) {
                    if (ratePlayer.getRate() != 1f) ratePlayer.setRate(1f);
                } else if (delta > -1000) {
                    if (ratePlayer.getRate() != 0.9f) ratePlayer.setRate(0.9f);
                } else if (delta > -5000) {
                    if (ratePlayer.getRate() != 0.8f) ratePlayer.setRate(0.8f);
                } else if (delta > -10000) {
                    ratePlayer.stop();
                    MinecraftClient.getInstance().inGameHud.setOverlayMessage(VpTexts.tr("message.videoplayer.sync_lost_too_early", "Too early, lost synchronization").formatted(Formatting.RED), false);
                }
            }

            if (metadata.getBool("debug", false)) {
                MinecraftClient.getInstance().inGameHud.setOverlayMessage(Text.literal(
                        "local: %s, server: %s, rtt: %s, delta: %s, sync: %s/7, rate: %.2f".formatted(
                                progress, syncProgress, rtt, delta, syncFrames, ratePlayer.getRate()
                        )
                ).formatted(Formatting.GREEN), false);
            }
        }
    }

    public void unload() {
        loaded = false;
        cancelPendingPlayback();
        VideoPlayerClient.screens.remove(this);
        IVideoPlayer old = player;
        player = null;
        if (old != null) old.cleanup();
    }

    public boolean isPostUpdate() {
        return player != null && player.isPostUpdate();
    }

    private boolean shouldDrawPlaceholder() {
        if (source != null && !source.isEmpty()) {
            return player == null || player.screen() == null || player.screen().player == null || !player.hasVideoFrame() || !player.screen().hasPlaybackContent();
        }
        return player == null || !player.hasVideoFrame() || !hasPlaybackContent();
    }

    private boolean hasPlaybackContent() {
        return !infos.isEmpty() || idlePlaying;
    }

    public static ClientVideoScreen from(VideoScreen screen) {
        ClientVideoScreen client = new ClientVideoScreen(screen.area, screen.name, screen.vertices, screen.source);
        client.u1 = screen.u1;
        client.v1 = screen.v1;
        client.u2 = screen.u2;
        client.v2 = screen.v2;
        client.fill = screen.fill;
        client.scaleX = screen.scaleX;
        client.scaleY = screen.scaleY;
        client.skipPercent = screen.skipPercent;
        client.metadata = screen.metadata;
        client.copyDisplayConfigFrom(screen);
        client.metadataChanged();
        return client;
    }
}
