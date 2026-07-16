package com.github.squi2rel.vp.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class LocalPlaybackInfoTest {
    @Test
    void fallsBackToUnexpiredServerPathWhenLocalResolutionFails() {
        VideoInfo original = info("server", "https://example.com/server.mp4", "https://youtube.com/watch?v=test", System.currentTimeMillis() + 60_000);

        assertSame(original, LocalPlaybackInfo.select(original, null));
    }

    @Test
    void doesNotFallBackToExpiredServerPath() {
        VideoInfo original = info("server", "https://example.com/expired.mp4", "https://youtube.com/watch?v=test", System.currentTimeMillis() - 1);

        assertNull(LocalPlaybackInfo.select(original, null));
    }

    @Test
    void keepsServerIdentityAndDurationForResolvedLocalPath() {
        VideoInfo original = new VideoInfo("server", "title", "https://example.com/server.mp4", "raw", -1, true, new String[0], 42_000);
        VideoInfo resolved = new VideoInfo("client", "different", "https://example.com/client.mp4", "raw", -1, true, new String[]{"x=y"}, 0);

        VideoInfo selected = LocalPlaybackInfo.select(original, resolved);

        assertEquals("server", selected.playerName());
        assertEquals("title", selected.name());
        assertEquals("https://example.com/client.mp4", selected.path());
        assertEquals(42_000, selected.durationMs());
    }

    private static VideoInfo info(String player, String path, String rawPath, long expire) {
        return new VideoInfo(player, "title", path, rawPath, expire, true, new String[0], 1_000);
    }
}
