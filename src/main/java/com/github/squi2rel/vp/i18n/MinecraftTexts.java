package com.github.squi2rel.vp.i18n;

import net.minecraft.text.Text;
import net.minecraft.text.MutableText;

public final class MinecraftTexts {
    private MinecraftTexts() {
    }

    public static MutableText tr(String key, String fallback, Object... args) {
        return text(VpTranslation.of(key, fallback, args));
    }

    public static MutableText text(VpTranslation translation) {
        if (translation == null || translation.isEmpty()) {
            return Text.empty();
        }
        if (translation.isLiteral()) {
            return Text.literal(translation.fallback());
        }
        return Text.translatableWithFallback(translation.key(), translation.fallback(), translation.argumentArray());
    }
}
