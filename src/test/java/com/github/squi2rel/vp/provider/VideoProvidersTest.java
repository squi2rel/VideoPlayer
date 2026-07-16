package com.github.squi2rel.vp.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class VideoProvidersTest {
    @Test
    void redactsCredentialsPathsAndQueriesFromLoggedSources() {
        String redacted = VideoProviders.redactedSource(
                "rtsp://camera-user:camera-password@Camera.Example:8554/private/live?token=secret");

        assertEquals("rtsp://camera.example:8554", redacted);
        assertFalse(redacted.contains("camera-user"));
        assertFalse(redacted.contains("camera-password"));
        assertFalse(redacted.contains("private"));
        assertFalse(redacted.contains("secret"));
    }

    @Test
    void fullyRedactsMalformedSources() {
        assertEquals("<redacted>", VideoProviders.redactedSource("not a uri with token=secret"));
        assertEquals("<empty>", VideoProviders.redactedSource("  "));
    }

    @Test
    void keepsYouTubePathVisibleWithoutLoggingVideoQueryValues() {
        String redacted = VideoProviders.redactedSource(
                "https://www.youtube.com/watch?v=video-id&si=secret"
        );

        assertEquals("https://www.youtube.com/watch?v=<redacted>", redacted);
        assertFalse(redacted.contains("video-id"));
        assertFalse(redacted.contains("secret"));
    }
}
