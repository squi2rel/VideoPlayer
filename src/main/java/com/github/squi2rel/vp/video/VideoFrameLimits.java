package com.github.squi2rel.vp.video;

public final class VideoFrameLimits {
    public static final int MAX_DIMENSION = 8192;
    public static final long MAX_PIXELS = 16_777_216L;
    public static final long MAX_RGBA_BYTES = MAX_PIXELS * 4L;

    private VideoFrameLimits() {
    }

    public static boolean valid(int width, int height) {
        return width > 0
                && height > 0
                && width <= MAX_DIMENSION
                && height <= MAX_DIMENSION
                && (long) width * height <= MAX_PIXELS;
    }

    public static int rgbaBytes(int width, int height) {
        if (!valid(width, height)) throw new IllegalArgumentException("Video frame dimensions exceed limits");
        return Math.toIntExact((long) width * height * 4L);
    }
}
