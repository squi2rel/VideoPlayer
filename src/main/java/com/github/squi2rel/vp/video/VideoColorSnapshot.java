package com.github.squi2rel.vp.video;

public record VideoColorSnapshot(Status status, int rgb, float luminance, long sampledAtMs) {
    public VideoColorSnapshot {
        status = status == null ? Status.WAITING : status;
        rgb = Math.clamp(rgb, 0, 0xFFFFFF);
        luminance = Float.isFinite(luminance) ? Math.clamp(luminance, 0f, 1f) : 0f;
        sampledAtMs = Math.max(0L, sampledAtMs);
    }

    public static VideoColorSnapshot available(int rgb, float luminance, long sampledAtMs) {
        return new VideoColorSnapshot(Status.AVAILABLE, rgb, luminance, sampledAtMs);
    }

    public static VideoColorSnapshot waiting() {
        return new VideoColorSnapshot(Status.WAITING, 0, 0f, 0L);
    }

    public static VideoColorSnapshot noVideo() {
        return new VideoColorSnapshot(Status.NO_VIDEO, 0, 0f, 0L);
    }

    public static VideoColorSnapshot unsupported() {
        return new VideoColorSnapshot(Status.UNSUPPORTED, 0, 0f, 0L);
    }

    public enum Status {
        AVAILABLE,
        WAITING,
        NO_VIDEO,
        UNSUPPORTED
    }
}
