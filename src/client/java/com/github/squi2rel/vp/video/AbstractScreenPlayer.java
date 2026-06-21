package com.github.squi2rel.vp.video;

import org.jetbrains.annotations.Nullable;

public abstract class AbstractScreenPlayer implements IVideoPlayer {
    protected final ClientVideoScreen screen;

    protected AbstractScreenPlayer(ClientVideoScreen screen) {
        this.screen = screen;
    }

    @Override
    public @Nullable ClientVideoScreen screen() {
        return screen;
    }
}
