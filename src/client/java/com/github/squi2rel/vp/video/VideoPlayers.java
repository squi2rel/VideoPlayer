package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.provider.VideoInfo;

public class VideoPlayers {
    public static IVideoPlayer from(VideoInfo info, ClientVideoScreen screen, IVideoPlayer old) {
        if (PlayerListener.accept(info)) {
            if (old != null && old.getClass() == EntityCameraPlayer.class) return old;
            return new EntityCameraPlayer(screen);
        }
        if (StreamListener.accept(info)) {
            return new VideoPlayer(screen);
        }
        return null;
    }
}
