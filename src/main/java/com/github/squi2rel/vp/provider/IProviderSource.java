package com.github.squi2rel.vp.provider;

import com.github.squi2rel.vp.i18n.VpTranslation;
import com.github.squi2rel.vp.provider.bilibili.BiliQuality;

public interface IProviderSource {
    String name();
    void reply(String text);

    default int bilibiliQualityLimit() {
        return BiliQuality.SERVER_LISTENER_QN;
    }

    default void reply(VpTranslation text) {
        reply(text == null ? "" : text.fallback());
    }
}
