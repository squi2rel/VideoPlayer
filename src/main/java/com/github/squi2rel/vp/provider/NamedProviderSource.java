package com.github.squi2rel.vp.provider;

import com.github.squi2rel.vp.provider.bilibili.BiliQuality;

public record NamedProviderSource(String name, int bilibiliQualityLimit) implements IProviderSource {
    public NamedProviderSource(String name) {
        this(name, BiliQuality.SERVER_LISTENER_QN);
    }

    public NamedProviderSource {
        bilibiliQualityLimit = BiliQuality.providerLimit(bilibiliQualityLimit);
    }

    @Override
    public void reply(String text) {
    }
}
