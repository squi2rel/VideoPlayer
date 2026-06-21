package com.github.squi2rel.vp.provider.bilibili;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class BiliQuality {
    public static final int UNLIMITED = -1;
    public static final int SERVER_LISTENER_QN = 16;
    public static final int DEFAULT_QN = 80;
    private static final int[] OPTIONS = {16, 32, 64, 74, 80, 112, 116, 120, 127};

    private BiliQuality() {
    }

    public static int[] options() {
        return OPTIONS.clone();
    }

    public static boolean isOption(int qn) {
        return rank(qn) >= 0;
    }

    public static int normalizeClient(int qn) {
        return isOption(qn) ? qn : DEFAULT_QN;
    }

    public static int normalizeScreenLimit(int qn) {
        return qn == UNLIMITED || isOption(qn) ? qn : UNLIMITED;
    }

    public static int providerLimit(int qn) {
        if (qn == UNLIMITED) return OPTIONS[OPTIONS.length - 1];
        return normalizeClient(qn);
    }

    public static int effective(int localQn, int screenLimit) {
        int local = normalizeClient(localQn);
        int limit = normalizeScreenLimit(screenLimit);
        if (limit == UNLIMITED) return local;
        return lower(local, limit);
    }

    public static int lower(int a, int b) {
        int rankA = rank(a);
        int rankB = rank(b);
        if (rankA < 0) return normalizeClient(b);
        if (rankB < 0) return normalizeClient(a);
        return rankA <= rankB ? a : b;
    }

    public static int bestAtOrBelow(Collection<Integer> qualities, int limit) {
        if (qualities == null || qualities.isEmpty()) return providerLimit(limit);
        int normalizedLimit = providerLimit(limit);
        int best = -1;
        int bestRank = -1;
        int lowest = -1;
        int lowestRank = Integer.MAX_VALUE;
        for (Integer quality : qualities) {
            if (quality == null) continue;
            int rank = rank(quality);
            if (rank < 0) continue;
            if (rank < lowestRank) {
                lowestRank = rank;
                lowest = quality;
            }
            if (rank <= rank(normalizedLimit) && rank > bestRank) {
                bestRank = rank;
                best = quality;
            }
        }
        return best >= 0 ? best : lowest >= 0 ? lowest : normalizedLimit;
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

    public static int rank(int qn) {
        for (int i = 0; i < OPTIONS.length; i++) {
            if (OPTIONS[i] == qn) return i;
        }
        return -1;
    }

    public static String translationKey(int qn) {
        return qn == UNLIMITED ? "label.videoplayer.bili_quality.unlimited" : "label.videoplayer.bili_quality." + qn;
    }

    public static String fallbackLabel(int qn) {
        return switch (qn) {
            case UNLIMITED -> "Default";
            case 16 -> "360P";
            case 32 -> "480P";
            case 64 -> "720P";
            case 74 -> "720P60";
            case 80 -> "1080P";
            case 112 -> "1080P+";
            case 116 -> "1080P60";
            case 120 -> "4K";
            case 127 -> "8K";
            default -> qn + "P";
        };
    }
}
