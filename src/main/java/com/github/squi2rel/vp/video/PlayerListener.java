package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.provider.EntityViewProvider;
import com.github.squi2rel.vp.provider.VideoInfo;

import java.util.function.Consumer;

public class PlayerListener implements IVideoListener {
    public static boolean accept(VideoInfo info) {
        return EntityViewProvider.isEntityView(info);
    }

    @Override
    public long getProgress() {
        return -1;
    }

    @Override
    public boolean isPlaying() {
        return true;
    }

    @Override
    public void playing(Consumer<Boolean> playing) {
        playing.accept(false);
    }

    @Override
    public void stopped(Runnable stopped) {
    }

    @Override
    public void errored(Runnable errored) {
    }

    @Override
    public void timeout(Runnable timeout) {
    }

    @Override
    public void listen() {
    }

    @Override
    public void cancel() {
    }
}
