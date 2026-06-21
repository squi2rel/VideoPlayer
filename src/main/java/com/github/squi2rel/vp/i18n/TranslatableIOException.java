package com.github.squi2rel.vp.i18n;

import java.io.IOException;

public class TranslatableIOException extends IOException implements TranslatableVideoException {
    private final VpTranslation translation;

    public TranslatableIOException(VpTranslation translation) {
        super(translation == null ? "" : translation.fallback());
        this.translation = translation == null ? VpTranslation.EMPTY : translation;
    }

    public TranslatableIOException(VpTranslation translation, Throwable cause) {
        super(translation == null ? "" : translation.fallback(), cause);
        this.translation = translation == null ? VpTranslation.EMPTY : translation;
    }

    @Override
    public VpTranslation translation() {
        return translation;
    }
}
