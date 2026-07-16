package com.github.squi2rel.vp.provider;

import com.github.squi2rel.vp.provider.bilibili.BiliQuality;
import com.github.squi2rel.vp.provider.youtube.YouTubeQuality;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NamedProviderSourceTest {
    @Test
    void keepsExistingConstructorsCompatible() {
        NamedProviderSource named = new NamedProviderSource("screen");
        NamedProviderSource bili = new NamedProviderSource("screen", 80);

        assertEquals(BiliQuality.SERVER_LISTENER_QN, named.bilibiliQualityLimit());
        assertEquals(YouTubeQuality.AUTO, named.youtubeHeightLimit());
        assertEquals(80, bili.bilibiliQualityLimit());
        assertEquals(YouTubeQuality.AUTO, bili.youtubeHeightLimit());
    }

    @Test
    void carriesNormalizedYouTubeHeightLimit() {
        assertEquals(1080, new NamedProviderSource("screen", 80, 1080).youtubeHeightLimit());
        assertEquals(YouTubeQuality.AUTO, new NamedProviderSource("screen", 80, 999).youtubeHeightLimit());
    }
}
