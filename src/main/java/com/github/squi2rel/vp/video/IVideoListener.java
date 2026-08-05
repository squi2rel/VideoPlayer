package com.github.squi2rel.vp.video;

import java.util.function.Consumer;

public interface IVideoListener {
    long getProgress();

    default void setProgress(long progress) {
    }

    boolean isPlaying();

    void playing(Consumer<Boolean> playing);

    void stopped(Runnable stopped);

    void errored(Runnable errored);

    void timeout(Runnable timeout);

    default AudioLevelSnapshot audioLevel() {
        return AudioLevelSnapshot.unsupported();
    }

    default VideoColorSnapshot videoColor() {
        return VideoColorSnapshot.unsupported();
    }

    void listen();

    void cancel();
}
