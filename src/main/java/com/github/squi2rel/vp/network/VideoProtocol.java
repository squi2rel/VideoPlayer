package com.github.squi2rel.vp.network;

import java.nio.charset.StandardCharsets;

public final class VideoProtocol {
    public static final String WIRE_REVISION = "vp5";
    public static final int MAX_TOKEN_BYTES = 16;
    private static final int LEGACY_WIRE_REVISION = 2;
    private static final int REPORTING_WIRE_REVISION = 4;
    private static final int IDLE_PLAY_MUTATION_WIRE_REVISION = 5;

    private VideoProtocol() {
    }

    public static String token(String version) {
        String token = safe(version) + "|" + WIRE_REVISION;
        int length = token.getBytes(StandardCharsets.UTF_8).length;
        if (length > MAX_TOKEN_BYTES) {
            throw new IllegalArgumentException("VideoPlayer protocol token exceeds " + MAX_TOKEN_BYTES + " UTF-8 bytes");
        }
        return token;
    }

    public static String handshakeToken(String version) {
        return "2.0.3".equals(releaseVersion(version)) ? token("2.0.2") : token(version);
    }

    public static String legacyToken() {
        return "2.0.1|vp" + LEGACY_WIRE_REVISION;
    }

    public static boolean compatible(String localVersion, String remoteToken) {
        String localRelease = releaseVersion(localVersion);
        String remoteRelease = releaseVersion(remoteToken);
        if (localRelease.isEmpty() || remoteRelease.isEmpty() || !supportedWireRevision(remoteToken)) return false;
        return localRelease.equals(remoteRelease)
                || optionalUpdateRelease(localRelease) && optionalUpdateRelease(remoteRelease);
    }

    public static String responseToken(String localVersion, String remoteToken) {
        String normalized = normalize(remoteToken);
        return compatible(localVersion, normalized) && !normalized.isEmpty() ? normalized : token(localVersion);
    }

    public static boolean allowedForRejectedClient(VideoPacketType type) {
        return type == VideoPacketType.PROTOCOL_REJECT
                || type == VideoPacketType.RESET_CLIENT
                || type == VideoPacketType.CONFIG;
    }

    public static String displayVersion(String token) {
        return releaseVersion(token);
    }

    public static boolean supportsClientPlaybackReporting(String token) {
        return wireRevision(token) >= REPORTING_WIRE_REVISION;
    }

    public static boolean supportsIdlePlayMutations(String token) {
        return wireRevision(token) >= IDLE_PLAY_MUTATION_WIRE_REVISION;
    }

    public static int wireRevision(String token) {
        String normalized = normalize(token);
        int separator = normalized.indexOf('|');
        if (separator < 0 || separator + 3 > normalized.length()) return -1;
        String revision = normalized.substring(separator + 1).trim();
        if (!revision.startsWith("vp") || revision.length() == 2) return -1;
        try {
            return Integer.parseInt(revision.substring(2));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static boolean supportedWireRevision(String token) {
        int revision = wireRevision(token);
        return revision == LEGACY_WIRE_REVISION
                || revision == REPORTING_WIRE_REVISION
                || revision == IDLE_PLAY_MUTATION_WIRE_REVISION;
    }

    private static boolean optionalUpdateRelease(String release) {
        return "2.0.1".equals(release) || "2.0.2".equals(release) || "2.0.3".equals(release);
    }

    private static String releaseVersion(String token) {
        String normalized = normalize(token);
        int separator = normalized.indexOf('|');
        return (separator < 0 ? normalized : normalized.substring(0, separator)).trim();
    }

    private static String normalize(String value) {
        return safe(value).trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
