package com.github.squi2rel.vp.provider;

public final class LocalPlaybackInfo {
    private LocalPlaybackInfo() {
    }

    public static VideoInfo select(VideoInfo original, VideoInfo resolved) {
        if (playable(resolved)) {
            if (original == null) return resolved;
            String rawPath = resolved.rawPath() == null || resolved.rawPath().isBlank()
                    ? original.rawPath()
                    : resolved.rawPath();
            long durationMs = resolved.durationMs() > 0 ? resolved.durationMs() : original.durationMs();
            return new VideoInfo(
                    original.playerName(), original.name(), resolved.path(), rawPath, resolved.expire(),
                    resolved.seekable(), resolved.params(), durationMs
            );
        }
        return playable(original) ? original : null;
    }

    public static boolean playable(VideoInfo info) {
        if (info == null) return false;
        if (EntityViewProvider.isEntityView(info)) return true;
        return info.path() != null
                && !info.path().isBlank()
                && (info.expire() <= 0 || System.currentTimeMillis() < info.expire());
    }
}
