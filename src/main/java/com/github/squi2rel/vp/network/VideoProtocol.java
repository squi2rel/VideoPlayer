package com.github.squi2rel.vp.network;

public final class VideoProtocol {
    public static final String WIRE_REVISION = "vp2";

    private VideoProtocol() {
    }

    public static String token(String version) {
        return safe(version) + "|" + WIRE_REVISION;
    }

    public static boolean compatible(String localVersion, String remoteToken) {
        return remoteToken != null && token(localVersion).equals(remoteToken);
    }

    public static boolean allowedForRejectedClient(VideoPacketType type) {
        return type == VideoPacketType.PROTOCOL_REJECT;
    }

    public static String displayVersion(String token) {
        String normalized = normalize(token);
        int separator = normalized.indexOf('|');
        return separator < 0 ? normalized : normalized.substring(0, separator);
    }

    private static String normalize(String value) {
        return safe(value).trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
