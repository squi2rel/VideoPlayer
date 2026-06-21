package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.provider.VideoInfo;

import static com.github.squi2rel.vp.VideoPlayerMain.LOGGER;

final class UnavailableVideoBackend implements VideoBackend {
    private final String requestedBackend;
    private boolean warned;

    UnavailableVideoBackend(String requestedBackend) {
        this.requestedBackend = VideoBackends.normalize(requestedBackend);
    }

    @Override
    public String name() {
        return requestedBackend;
    }

    @Override
    public void init() {
    }

    @Override
    public void play(VideoInfo info, long targetTime, int volume) {
        if (warned) return;
        warned = true;
        LOGGER.warn("No available video backend for requested backend {}", requestedBackend);
    }

    @Override
    public void updateTexture() {
    }

    @Override
    public int getTextureId() {
        return -1;
    }

    @Override
    public int getWidth() {
        return 1;
    }

    @Override
    public int getHeight() {
        return 1;
    }

    @Override
    public void stop() {
    }

    @Override
    public boolean canPause() {
        return false;
    }

    @Override
    public void pause(boolean pause) {
    }

    @Override
    public boolean isPaused() {
        return true;
    }

    @Override
    public void setVolume(int volume) {
    }

    @Override
    public boolean canSetProgress() {
        return false;
    }

    @Override
    public void setProgress(long progress) {
    }

    @Override
    public long getProgress() {
        return 0;
    }

    @Override
    public long getTotalProgress() {
        return 0;
    }

    @Override
    public void setRate(float rate) {
    }

    @Override
    public float getRate() {
        return 1;
    }

    @Override
    public void cleanup() {
    }
}
