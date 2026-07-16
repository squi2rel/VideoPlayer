package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.provider.VideoInfo;

import java.util.function.BiConsumer;

public class VlcVideoBackend implements VideoBackend {
    private final BiConsumer<Integer, Integer> sizeListener;
    private VlcDecoder decoder;
    private VideoQuad quad;
    private volatile boolean videoFrameAvailable;
    private int width = 1;
    private int height = 1;

    public VlcVideoBackend(BiConsumer<Integer, Integer> sizeListener) {
        this.sizeListener = sizeListener;
    }

    @Override
    public String name() {
        return VideoBackends.VLC;
    }

    @Override
    public void init() {
        decoder = new VlcDecoder();
        quad = new VideoQuad(decoder.getWidth(), decoder.getHeight());
        width = decoder.getWidth();
        height = decoder.getHeight();
        sizeListener.accept(width, height);
    }

    @Override
    public void play(VideoInfo info, long targetTime, int volume) {
        if (targetTime > 0) {
            String[] params = info.params() == null ? new String[0] : info.params();
            String[] newParams = new String[params.length + 1];
            System.arraycopy(params, 0, newParams, 0, params.length);
            newParams[newParams.length - 1] = ":start-time=" + targetTime / 1000f;
            info = new VideoInfo(info.playerName(), info.name(), info.path(), info.rawPath(), info.expire(), info.seekable(), newParams, info.durationMs());
        }
        decoder.onPlay(() -> decoder.submit(() -> decoder.setVolume(volume)));
        decoder.init(info);
    }

    @Override
    public void updateTexture() {
        VlcDecoder.DecodedFrame frame = decoder.decodeNextFrame();
        if (frame == null) return;
        try {
            if (frame.width() != width || frame.height() != height) {
                width = frame.width();
                height = frame.height();
                quad.resize(width, height);
                sizeListener.accept(width, height);
            }
            quad.updateBgraTexture(frame.buffer());
            videoFrameAvailable = width > 1 && height > 1;
        } finally {
            frame.close();
        }
    }

    @Override
    public int getTextureId() {
        return quad == null ? -1 : quad.getTextureId();
    }

    @Override
    public boolean hasVideoFrame() {
        return videoFrameAvailable && getTextureId() >= 0;
    }

    @Override
    public int getWidth() {
        return width;
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public void stop() {
        videoFrameAvailable = false;
        decoder.stop();
    }

    @Override
    public boolean canPause() {
        return decoder.canPause();
    }

    @Override
    public void pause(boolean pause) {
        decoder.pause(pause);
    }

    @Override
    public boolean isPaused() {
        return decoder.isPaused();
    }

    @Override
    public void setVolume(int volume) {
        decoder.setVolume(volume);
    }

    @Override
    public boolean canSetProgress() {
        return decoder.canSetProgress();
    }

    @Override
    public void setProgress(long progress) {
        decoder.setProgress(progress);
    }

    @Override
    public long getProgress() {
        return decoder.getProgress();
    }

    @Override
    public long getTotalProgress() {
        return decoder.getTotalProgress();
    }

    @Override
    public void setRate(float rate) {
        decoder.setRate(rate);
    }

    @Override
    public float getRate() {
        return decoder.getRate();
    }

    @Override
    public void cleanup() {
        videoFrameAvailable = false;
        if (decoder != null) decoder.cleanup();
        if (quad != null) quad.cleanup();
    }
}
