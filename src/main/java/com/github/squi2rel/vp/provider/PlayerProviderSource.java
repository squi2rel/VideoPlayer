package com.github.squi2rel.vp.provider;

import com.github.squi2rel.vp.DataHolder;
import com.github.squi2rel.vp.i18n.VpTranslation;
import com.github.squi2rel.vp.provider.bilibili.BiliQuality;
import com.github.squi2rel.vp.provider.youtube.YouTubeQuality;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.UUID;

public class PlayerProviderSource implements IProviderSource {
    private final UUID playerUuid;
    private final String name;
    private final long lifecycleEpoch;
    private final int bilibiliQualityLimit;
    private final int youtubeHeightLimit;

    public PlayerProviderSource(ServerPlayerEntity entity) {
        this(entity, BiliQuality.SERVER_LISTENER_QN, YouTubeQuality.AUTO);
    }

    public PlayerProviderSource(ServerPlayerEntity entity, int bilibiliQualityLimit, int youtubeHeightLimit) {
        playerUuid = entity.getUuid();
        name = entity.getGameProfile().name();
        lifecycleEpoch = DataHolder.lifecycleEpoch();
        this.bilibiliQualityLimit = BiliQuality.normalizeScreenLimit(bilibiliQualityLimit);
        this.youtubeHeightLimit = YouTubeQuality.normalizeScreenLimit(youtubeHeightLimit);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public int bilibiliQualityLimit() {
        return bilibiliQualityLimit;
    }

    @Override
    public int youtubeHeightLimit() {
        return youtubeHeightLimit;
    }

    @Override
    public UUID onlinePlayerUuid(String name) {
        return DataHolder.lifecycleActive(lifecycleEpoch) ? DataHolder.onlinePlayerUuid(name) : null;
    }

    @Override
    public void reply(String text) {
        DataHolder.message(playerUuid, lifecycleEpoch, text);
    }

    @Override
    public void reply(VpTranslation text) {
        DataHolder.message(playerUuid, lifecycleEpoch, text);
    }
}
