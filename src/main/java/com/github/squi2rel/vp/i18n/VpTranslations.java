package com.github.squi2rel.vp.i18n;

public final class VpTranslations {
    private VpTranslations() {
    }

    public static VpTranslation from(Throwable throwable, String key, String fallback, Object... args) {
        if (throwable instanceof TranslatableVideoException translatable) {
            return translatable.translation();
        }
        return VpTranslation.of(key, fallback, args);
    }

    public static VpTranslation from(Throwable throwable) {
        if (throwable instanceof TranslatableVideoException translatable) {
            return translatable.translation();
        }
        String message = throwable == null || throwable.getMessage() == null ? "" : throwable.getMessage();
        return VpTranslation.literal(message);
    }
}
