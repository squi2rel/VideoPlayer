package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.provider.VideoInfo;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;

@SuppressWarnings("unused")
public interface IVideoPlayer {
    @Nullable ClientVideoScreen screen();

    default @Nullable ClientVideoScreen getTrackingScreen() {
        return screen();
    }

    default boolean canPause() {
        return false;
    }

    default void init() {
    }

    int getWidth();

    int getHeight();

    default void play(VideoInfo info) {
    }

    default void cleanup() {
    }

    int getTextureId();

    default boolean hasVideoFrame() {
        return getTextureId() >= 0 && getWidth() > 1 && getHeight() > 1;
    }

    default void stop() {
    }

    default void pause(boolean pause) {
    }

    default boolean isPaused() {
        return false;
    }

    default void setVolume(int volume) {
    }

    default boolean canSetProgress() {
        return false;
    }

    default void setProgress(long progress) {
    }

    default long getProgress() {
        return 0;
    }

    default long getTotalProgress() {
        return 0;
    }

    default void setTargetTime(long targetTime) {
    }

    default void swapTexture() {
    }

    default void updateTexture() {
    }

    default boolean isPostUpdate() {
        return false;
    }

    default boolean flippedX() {
        return false;
    }

    default boolean flippedY() {
        return false;
    }

    default void draw(MatrixStack matrices, VertexConsumerProvider consumers, ClientVideoScreen s) {
        VideoPlayerRenderer.draw(this, matrices, consumers, s);
    }

    default void drawVertex(Matrix4f mat, VertexConsumer consumer, Vector3f vertex, Vector2f uv, ClientVideoScreen target) {
        drawVertex(mat, consumer, vertex, uv);
    }

    default void drawVertex(Matrix4f mat, VertexConsumer consumer, Vector3f vertex, Vector2f uv, Vector3f normal, ClientVideoScreen target) {
        drawVertex(mat, consumer, vertex, uv, normal);
    }

    default void drawVertex(Matrix4f mat, VertexConsumer consumer, Vector3f vertex, Vector2f uv) {
        VideoPlayerRenderer.drawVertex(mat, consumer, vertex, uv.x, uv.y);
    }

    default void drawVertex(Matrix4f mat, VertexConsumer consumer, Vector3f vertex, Vector2f uv, Vector3f normal) {
        VideoPlayerRenderer.drawVertex(mat, consumer, vertex, uv.x, uv.y, normal);
    }

    default void drawQuad(Matrix4f mat, VertexConsumer consumer, Vector3f p1, Vector3f p2, Vector3f p3, Vector3f p4, float u1, float v1, float u2, float v2) {
        VideoPlayerRenderer.drawQuad(mat, consumer, p1, p2, p3, p4, u1, v1, u2, v2);
    }
}
