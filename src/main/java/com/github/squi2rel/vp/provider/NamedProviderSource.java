package com.github.squi2rel.vp.provider;

import com.github.squi2rel.vp.provider.bilibili.BiliQuality;
import com.github.squi2rel.vp.provider.youtube.YouTubeQuality;

public record NamedProviderSource(String name, int bilibiliQualityLimit, int youtubeHeightLimit) implements IProviderSource {
    public NamedProviderSource(String name) {
        this(name, BiliQuality.SERVER_LISTENER_QN, YouTubeQuality.AUTO);
    }

    public NamedProviderSource(String name, int bilibiliQualityLimit) {
        this(name, bilibiliQualityLimit, YouTubeQuality.AUTO);
    }

    public NamedProviderSource {
        bilibiliQualityLimit = BiliQuality.providerLimit(bilibiliQualityLimit);
        youtubeHeightLimit = YouTubeQuality.normalizeScreenLimit(youtubeHeightLimit);
    }

    @Override
    public void reply(String text) {
    }
}
