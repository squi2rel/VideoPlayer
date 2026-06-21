package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.i18n.VpTranslation;

public enum ScreenSurface {
    FLAT,
    SPHERE_360;

    public static ScreenSurface fromId(int id) {
        ScreenSurface[] values = values();
        if (id < 0 || id >= values.length) return FLAT;
        return values[id];
    }

    public String label() {
        return switch (this) {
            case FLAT -> "Flat";
            case SPHERE_360 -> "360 Sphere";
        };
    }

    public VpTranslation translation() {
        return switch (this) {
            case FLAT -> VpTranslation.of("label.videoplayer.surface.flat", "Flat");
            case SPHERE_360 -> VpTranslation.of("label.videoplayer.surface.sphere_360", "360 Sphere");
        };
    }
}
