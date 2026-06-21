package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.provider.VideoInfo;
import com.github.squi2rel.vp.vivecraft.Vivecraft;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;

import static com.github.squi2rel.vp.VideoPlayerMain.LOGGER;
import static com.github.squi2rel.vp.VideoPlayerClient.config;

public class VideoPlayer extends AbstractScreenPlayer implements RateAdjustablePlayer, MetaListener {
    protected VideoBackend backend;
    protected boolean initialized = false;
    protected long targetTime = -1;
    protected boolean is3d = false;
    public int videoWidth, videoHeight;
    private String requestedBackend = VideoBackends.VLC;
    private final java.util.function.BiConsumer<Integer, Integer> sizeListener = (w, h) -> {
        videoWidth = w;
        videoHeight = h;
    };

    public VideoPlayer(ClientVideoScreen screen) {
        super(screen);
    }

    @Override
    public void updateTexture() {
        if (!initialized) return;
        backend.updateTexture();
    }

    @Override
    public synchronized void init() {
        if (initialized) throw new IllegalStateException("already initialized");

        requestedBackend = config == null ? VideoBackends.VLC : VideoBackends.normalize(config.videoBackend);
        backend = VideoBackends.create(requestedBackend, sizeListener);
        try {
            backend.init();
        } catch (Throwable e) {
            if (VideoBackends.MPV.equals(backend.name())) {
                LOGGER.warn("Failed to initialize MPV backend. Falling back to VLC.", e);
                backend.cleanup();
                backend = VideoBackends.createVlc(sizeListener);
                try {
                    backend.init();
                } catch (Throwable fallbackError) {
                    LOGGER.warn("Failed to initialize VLC fallback backend.", fallbackError);
                    backend.cleanup();
                    backend = new UnavailableVideoBackend(requestedBackend);
                    backend.init();
                }
            } else {
                LOGGER.warn("Failed to initialize video backend {}.", backend.name(), e);
                backend.cleanup();
                backend = new UnavailableVideoBackend(requestedBackend);
                backend.init();
            }
        }

        initialized = true;
    }

    @Override
    public int getWidth() {
        return is3d ? videoWidth / 2 : videoWidth;
    }

    @Override
    public int getHeight() {
        return videoHeight;
    }

    @Override
    public void play(VideoInfo info) {
        backend.play(info, targetTime, effectiveVolume());
    }

    @Override
    public int getTextureId() {
        if (initialized) {
            return backend.getTextureId();
        }
        return -1;
    }

    @Override
    public boolean hasVideoFrame() {
        return initialized && backend.hasVideoFrame();
    }

    @Override
    public void stop() {
        backend.stop();
    }

    @Override
    public boolean canPause() {
        return backend.canPause();
    }

    @Override
    public void pause(boolean pause) {
        backend.pause(pause);
    }

    @Override
    public boolean isPaused() {
        return backend.isPaused();
    }

    @Override
    public void setVolume(int volume) {
        int clamped = Math.clamp(volume, 0, 100);
        if (VideoBackends.MPV.equals(backendName())) {
            screen.volume = clamped;
        }
        backend.setVolume(screen.metadata.getBool("mute", false) ? 0 : clamped);
    }

    @Override
    public boolean canSetProgress() {
        return backend.canSetProgress();
    }

    @Override
    public void setProgress(long progress) {
        backend.setProgress(progress);
    }

    @Override
    public long getProgress() {
        return backend.getProgress();
    }

    @Override
    public long getTotalProgress() {
        return backend.getTotalProgress();
    }

    @Override
    public void setTargetTime(long targetTime) {
        this.targetTime = targetTime;
    }

    @Override
    public void setRate(float rate) {
        backend.setRate(rate);
    }

    @Override
    public float getRate() {
        return backend.getRate();
    }

    @Override
    public synchronized void cleanup() {
        initialized = false;
        if (backend != null) backend.cleanup();
    }

    @Override
    public void onMetaChanged() {
        is3d = screen.stereo3d;
        if (initialized && backend != null) {
            backend.setVolume(effectiveVolume());
        }
    }

    private int effectiveVolume() {
        if (screen.metadata.getBool("mute", false)) return 0;
        if (VideoBackends.MPV.equals(backendName())) return Math.clamp(screen.volume, 0, 100);
        return config.volume;
    }

    public String backendName() {
        return backend == null ? VideoBackends.VLC : backend.name();
    }

    public String requestedBackendName() {
        return requestedBackend;
    }

    @Override
    public boolean flippedX() {
        return initialized && backend.flippedX();
    }

    @Override
    public boolean flippedY() {
        return initialized && backend.flippedY();
    }

    @Override
    public boolean isPostUpdate() {
        return initialized && backend.isPostUpdate();
    }

    @Override
    public void draw(MatrixStack matrices, VertexConsumerProvider consumers, ClientVideoScreen s) {
        VideoPlayerRenderer.draw(this, matrices, consumers, s);
        if (s.surface == ScreenSurface.SPHERE_360 && s.spherePreset && getTextureId() >= 0) {
            Degree360Player.drawTexture(getTextureId(), matrices, consumers, s, is3d);
        }
    }

    @Override
    public void drawQuad(Matrix4f mat, VertexConsumer consumer, Vector3f p1, Vector3f p2, Vector3f p3, Vector3f p4, float u1, float v1, float u2, float v2) {
        if (is3d) {
            draw3D(mat, consumer, p1, p2, p3, p4, u1, v1, u2, v2);
            return;
        }
        super.drawQuad(mat, consumer, p1, p2, p3, p4, u1, v1, u2, v2);
    }

    public void draw3D(Matrix4f mat, VertexConsumer consumer, Vector3f p1, Vector3f p2, Vector3f p3, Vector3f p4, float u1, float v1, float u2, float v2) {
        if (Vivecraft.loaded && Vivecraft.isRightEye()) {
            super.drawQuad(mat, consumer, p1, p2, p3, p4, (u1 + u2) / 2, v1, u2, v2);
        } else {
            super.drawQuad(mat, consumer, p1, p2, p3, p4, u1, v1, (u1 + u2) / 2, v2);
        }
    }

    @Override
    public void drawVertex(Matrix4f mat, VertexConsumer consumer, Vector3f vertex, Vector2f uv, ClientVideoScreen target) {
        drawVertex(mat, consumer, vertex, uv, null, target);
    }

    @Override
    public void drawVertex(Matrix4f mat, VertexConsumer consumer, Vector3f vertex, Vector2f uv, Vector3f normal, ClientVideoScreen target) {
        if (is3d) {
            float split = (target.u1 + target.u2) * 0.5f;
            if (Vivecraft.loaded && Vivecraft.isRightEye()) {
                uv.x = split + (uv.x - target.u1) * 0.5f;
            } else {
                uv.x = target.u1 + (uv.x - target.u1) * 0.5f;
            }
        }
        super.drawVertex(mat, consumer, vertex, uv, normal);
    }
}
