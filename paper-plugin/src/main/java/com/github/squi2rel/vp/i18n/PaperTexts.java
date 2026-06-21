package com.github.squi2rel.vp.i18n;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;

public final class PaperTexts {
    private PaperTexts() {
    }

    public static Component text(VpTranslation translation) {
        if (translation == null || translation.isEmpty()) {
            return Component.empty();
        }
        if (translation.isLiteral()) {
            return Component.text(translation.fallback());
        }
        ComponentLike[] args = translation.args().stream()
                .map(Component::text)
                .toArray(ComponentLike[]::new);
        return Component.translatable(translation.key(), translation.fallback(), args);
    }
}
