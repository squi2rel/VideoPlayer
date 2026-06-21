package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.provider.VideoInfo;

import static com.github.squi2rel.vp.VideoPlayerClient.config;

public class VideoPlayers {
    public static IVideoPlayer from(VideoInfo info, ClientVideoScreen screen, IVideoPlayer old) {
        if (StreamListener.accept(info)) {
            if (old != null && old.getClass() == VideoPlayer.class && matchesBackend((VideoPlayer) old)) return old;
            return new VideoPlayer(screen);
        }
        return null;
    }

    private static boolean matchesBackend(VideoPlayer player) {
        return player.requestedBackendName().equals(config == null ? VideoBackends.VLC : VideoBackends.normalize(config.videoBackend));
    }
}
