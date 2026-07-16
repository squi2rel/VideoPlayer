package com.github.squi2rel.vp.provider;

import com.github.squi2rel.vp.i18n.VpTranslation;
import com.github.squi2rel.vp.provider.bilibili.BiliQuality;
import com.github.squi2rel.vp.provider.youtube.YouTubeQuality;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public interface IProviderSource {
    String name();
    void reply(String text);

    default int bilibiliQualityLimit() {
        return BiliQuality.SERVER_LISTENER_QN;
    }

    default int youtubeHeightLimit() {
        return YouTubeQuality.AUTO;
    }

    default @Nullable UUID onlinePlayerUuid(String name) {
        return null;
    }

    default void reply(VpTranslation text) {
        reply(text == null ? "" : text.fallback());
    }
}
