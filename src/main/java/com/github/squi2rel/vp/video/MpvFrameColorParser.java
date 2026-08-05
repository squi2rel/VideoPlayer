package com.github.squi2rel.vp.video;

import java.util.Locale;

public final class MpvFrameColorParser {
    private MpvFrameColorParser() {
    }

    public static VideoColorSnapshot parse(String metadata, String colorMatrix, String colorLevels, long sampledAtMs) {
        if (metadata == null || metadata.isBlank()) return VideoColorSnapshot.waiting();
        Double y = null;
        Double u = null;
        Double v = null;
        String normalized = metadata.replace("\\n", "\n");
        for (String line : normalized.split("[\\r\\n,]+")) {
            int separator = line.indexOf('=');
            if (separator < 0) separator = line.indexOf(':');
            if (separator <= 0) continue;
            String key = cleanToken(line.substring(0, separator)).toLowerCase(Locale.ROOT);
            Double value = parseNumber(cleanToken(line.substring(separator + 1)));
            if (value == null) continue;
            if (key.endsWith("signalstats.yavg")) y = value;
            if (key.endsWith("signalstats.uavg")) u = value;
            if (key.endsWith("signalstats.vavg")) v = value;
        }
        if (y == null || u == null || v == null) return VideoColorSnapshot.waiting();
        boolean full = "full".equalsIgnoreCase(cleanToken(colorLevels));
        double normalizedY = full ? y / 255.0 : (y - 16.0) / 219.0;
        double normalizedU = (u - 128.0) / (full ? 255.0 : 224.0);
        double normalizedV = (v - 128.0) / (full ? 255.0 : 224.0);
        Matrix matrix = Matrix.from(colorMatrix);
        double red = normalizedY + matrix.redV * normalizedV;
        double green = normalizedY + matrix.greenU * normalizedU + matrix.greenV * normalizedV;
        double blue = normalizedY + matrix.blueU * normalizedU;
        int r = channel(red);
        int g = channel(green);
        int b = channel(blue);
        int rgb = r << 16 | g << 8 | b;
        float luminance = (float) Math.clamp(0.2126 * red + 0.7152 * green + 0.0722 * blue, 0.0, 1.0);
        return VideoColorSnapshot.available(rgb, luminance, sampledAtMs);
    }

    private static int channel(double value) {
        return Math.clamp((int) Math.round(value * 255.0), 0, 255);
    }

    private static Double parseNumber(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            double parsed = Double.parseDouble(value);
            return Double.isFinite(parsed) ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String cleanToken(String value) {
        String clean = value == null ? "" : value.trim();
        int start = 0;
        int end = clean.length();
        while (start < end && isWrapper(clean.charAt(start))) start++;
        while (end > start && isWrapper(clean.charAt(end - 1))) end--;
        return clean.substring(start, end);
    }

    private static boolean isWrapper(char value) {
        return value == '"' || value == '\'' || value == '{' || value == '}' || value == '[' || value == ']';
    }

    private record Matrix(double redV, double greenU, double greenV, double blueU) {
        private static Matrix from(String value) {
            String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            if (normalized.contains("601") || normalized.contains("470") || normalized.contains("170")) {
                return new Matrix(1.402, -0.344136, -0.714136, 1.772);
            }
            if (normalized.contains("2020")) {
                return new Matrix(1.4746, -0.164553, -0.571353, 1.8814);
            }
            return new Matrix(1.5748, -0.187324, -0.468124, 1.8556);
        }
    }
}
