package com.github.squi2rel.vp.i18n;

public class TranslatableIllegalArgumentException extends IllegalArgumentException implements TranslatableVideoException {
    private final VpTranslation translation;

    public TranslatableIllegalArgumentException(VpTranslation translation) {
        super(translation == null ? "" : translation.fallback());
        this.translation = translation == null ? VpTranslation.EMPTY : translation;
    }

    @Override
    public VpTranslation translation() {
        return translation;
    }
}
