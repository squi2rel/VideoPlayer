package com.github.squi2rel.vp.video;

public interface RateAdjustablePlayer extends IVideoPlayer {
    void setRate(float rate);

    float getRate();
}
