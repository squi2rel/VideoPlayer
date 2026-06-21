package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.VideoPlayerMain;

import java.util.Locale;
import java.util.function.BiConsumer;

public final class VideoBackends {
    public static final String VLC = "vlc";
    public static final String MPV = "mpv";

    private VideoBackends() {
    }

    public static String normalize(String backend) {
        if (backend == null) return VLC;
        String normalized = backend.trim().toLowerCase(Locale.ROOT);
        return MPV.equals(normalized) ? MPV : VLC;
    }

    public static boolean isValid(String backend) {
        String normalized = backend == null ? "" : backend.trim().toLowerCase(Locale.ROOT);
        return VLC.equals(normalized) || MPV.equals(normalized);
    }

    public static VideoBackend create(String requestedBackend, BiConsumer<Integer, Integer> sizeListener) {
        String backend = normalize(requestedBackend);
        if (MPV.equals(backend)) {
            if (MpvVideoBackend.isAvailable()) {
                return new MpvVideoBackend(sizeListener);
            }
            VideoPlayerMain.LOGGER.warn("MPV backend requested, but libmpv is not available. Falling back to VLC.", MpvVideoBackend.loadError());
        }
        return createVlc(sizeListener);
    }

    public static VideoBackend createVlc(BiConsumer<Integer, Integer> sizeListener) {
        if (VlcDecoder.isAvailable()) {
            return new VlcVideoBackend(sizeListener);
        }
        VideoPlayerMain.LOGGER.warn("VLC backend is not available", VlcDecoder.loadError());
        return new UnavailableVideoBackend(VLC);
    }
}
