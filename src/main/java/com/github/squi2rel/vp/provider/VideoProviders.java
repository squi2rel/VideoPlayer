package com.github.squi2rel.vp.provider;

import com.github.squi2rel.vp.VideoPlayerMain;
import com.github.squi2rel.vp.provider.bilibili.BiliBiliLiveProvider;
import com.github.squi2rel.vp.provider.bilibili.BiliBiliVideoProvider;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

public class VideoProviders {
    public static ArrayList<IVideoProvider> providers = new ArrayList<>();

    public static void register() {
        providers.clear();
        providers.add(new BiliBiliVideoProvider());
        providers.add(new BiliBiliLiveProvider());
        providers.add(new CloudMusicProvider());
        providers.add(new CloudMusicMVProvider());
        providers.add(new YouTubeProvider());
        providers.add(new NetworkProvider());
    }

    public static @Nullable CompletableFuture<VideoInfo> from(String str, IProviderSource source) {
        VideoPlayerMain.LOGGER.info("Player {} requested {}", source.name(), str);
        try {
            for (IVideoProvider provider : providers) {
                CompletableFuture<VideoInfo> info = provider.from(str, source);
                if (info != null) {
                    VideoPlayerMain.LOGGER.info("Using {}", provider.getClass().getSimpleName());
                    return info;
                }
            }
            VideoPlayerMain.LOGGER.info("No suitable provider");
        } catch (Exception e) {
            VideoPlayerMain.LOGGER.error(e.toString());
            source.reply(e.toString());
        }
        return null;
    }
}
