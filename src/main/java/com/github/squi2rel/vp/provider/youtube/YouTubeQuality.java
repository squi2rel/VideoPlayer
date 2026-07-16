package com.github.squi2rel.vp.provider.youtube;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class YouTubeQuality {
    public static final int AUTO = -1;
    private static final int[] OPTIONS = {360, 480, 720, 1080, 1440, 2160, 4320};

    private YouTubeQuality() {
    }

    public static int[] options() {
        return OPTIONS.clone();
    }

    public static boolean isOption(int height) {
        return rank(height) >= 0;
    }

    public static int normalizeClient(int height) {
        return height == AUTO || isOption(height) ? height : AUTO;
    }

    public static int normalizeScreenLimit(int height) {
        return height == AUTO || isOption(height) ? height : AUTO;
    }

    public static int effective(int localHeight, int screenLimit) {
        int local = normalizeClient(localHeight);
        int screen = normalizeScreenLimit(screenLimit);
        if (local == AUTO) return screen;
        if (screen == AUTO) return local;
        return lower(local, screen);
    }

    public static int lower(int a, int b) {
        int normalizedA = normalizeClient(a);
        int normalizedB = normalizeClient(b);
        if (normalizedA == AUTO) return normalizedB;
        if (normalizedB == AUTO) return normalizedA;
        return normalizedA <= normalizedB ? normalizedA : normalizedB;
    }

    public static int bestAtOrBelow(Collection<Integer> qualities, int limit) {
        int normalizedLimit = normalizeScreenLimit(limit);
        if (qualities == null || qualities.isEmpty()) return normalizedLimit;
        int best = AUTO;
        for (Integer quality : qualities) {
            if (quality == null || !isOption(quality)) continue;
            if ((normalizedLimit == AUTO || quality <= normalizedLimit) && quality > best) {
                best = quality;
            }
        }
        return best == AUTO ? normalizedLimit : best;
    }

    public static List<Integer> filterSupported(Collection<Integer> qualities) {
        if (qualities == null || qualities.isEmpty()) return List.of();
        ArrayList<Integer> result = new ArrayList<>();
        for (int i = OPTIONS.length - 1; i >= 0; i--) {
            int option = OPTIONS[i];
            if (qualities.contains(option)) result.add(option);
        }
        return List.copyOf(result);
    }

    public static int rank(int height) {
        for (int i = 0; i < OPTIONS.length; i++) {
            if (OPTIONS[i] == height) return i;
        }
        return -1;
    }

    public static String translationKey(int height) {
        return height == AUTO ? "label.videoplayer.youtube_quality.auto" : "label.videoplayer.youtube_quality." + height;
    }

    public static String fallbackLabel(int height) {
        return height == AUTO ? "Auto" : height + "P";
    }
}
