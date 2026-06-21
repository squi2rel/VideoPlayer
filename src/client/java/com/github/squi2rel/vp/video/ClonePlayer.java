package com.github.squi2rel.vp.video;

import net.minecraft.client.render.VertexConsumer;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;

public record ClonePlayer(ClientVideoScreen screen, ClientVideoScreen source) implements IVideoPlayer {
    @Override
    public @Nullable ClientVideoScreen screen() {
        return source;
    }

    @Override
    public @Nullable ClientVideoScreen getTrackingScreen() {
        return screen;
    }

    @Override
    public int getTextureId() {
        return source.player.getTextureId();
    }

    @Override
    public boolean hasVideoFrame() {
        return source.player != null && source.player.hasVideoFrame();
    }

    @Override
    public void stop() {
        if (source.player != null) source.player.stop();
    }

    @Override
    public long getProgress() {
        return source.player == null ? 0 : source.player.getProgress();
    }

    @Override
    public long getTotalProgress() {
        return source.player == null ? 0 : source.player.getTotalProgress();
    }

    @Override
    public int getWidth() {
        return source.player.getWidth();
    }

    @Override
    public int getHeight() {
        return source.player.getHeight();
    }

    @Override
    public boolean flippedX() {
        return source.player != null && source.player.flippedX();
    }

    @Override
    public boolean flippedY() {
        return source.player != null && source.player.flippedY();
    }

    @Override
    public void drawQuad(Matrix4f mat, VertexConsumer consumer, Vector3f p1, Vector3f p2, Vector3f p3, Vector3f p4, float u1, float v1, float u2, float v2) {
        if (source.player == null) return;
        source.player.drawQuad(mat, consumer, p1, p2, p3, p4, u1, v1, u2, v2);
    }

    @Override
    public void drawVertex(Matrix4f mat, VertexConsumer consumer, Vector3f vertex, Vector2f uv, ClientVideoScreen target) {
        if (source.player == null) return;
        source.player.drawVertex(mat, consumer, vertex, uv, target);
    }

    @Override
    public void drawVertex(Matrix4f mat, VertexConsumer consumer, Vector3f vertex, Vector2f uv, Vector3f normal, ClientVideoScreen target) {
        if (source.player == null) return;
        source.player.drawVertex(mat, consumer, vertex, uv, normal, target);
    }
}
