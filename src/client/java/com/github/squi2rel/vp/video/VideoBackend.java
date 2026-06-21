package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.provider.VideoInfo;

public interface VideoBackend {
    String name();

    void init();

    void play(VideoInfo info, long targetTime, int volume);

    void updateTexture();

    int getTextureId();

    default boolean hasVideoFrame() {
        return getTextureId() >= 0 && getWidth() > 1 && getHeight() > 1;
    }

    int getWidth();

    int getHeight();

    void stop();

    boolean canPause();

    void pause(boolean pause);

    boolean isPaused();

    void setVolume(int volume);

    boolean canSetProgress();

    void setProgress(long progress);

    long getProgress();

    long getTotalProgress();

    void setRate(float rate);

    float getRate();

    void cleanup();

    default boolean flippedX() {
        return false;
    }

    default boolean flippedY() {
        return false;
    }

    default boolean isPostUpdate() {
        return false;
    }
}
