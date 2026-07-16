package com.github.squi2rel.vp.video;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoParamsRtspTest {
    @Test
    void handlesRtspSchemesAndPreservesStreamPath() {
        String suffix = "user:pass@[2001:db8::1]:8554/live/camera?profile=main#video";

        assertFalse(VideoParams.isRtspTcp("RTSP://" + suffix));
        assertTrue(VideoParams.isRtspTcp("RTSPS://" + suffix));
        assertTrue(VideoParams.isRtspTcp(" RTSPT://" + suffix + " "));
        assertEquals("RTSP://" + suffix, VideoParams.normalizeStreamPath("RTSP://" + suffix));
        assertEquals("RTSPS://" + suffix, VideoParams.normalizeStreamPath("RTSPS://" + suffix));
        assertEquals("rtsp://" + suffix, VideoParams.normalizeStreamPath(" rtsp://" + suffix + " "));
        assertEquals("rtsps://" + suffix, VideoParams.normalizeStreamPath(" rtsps://" + suffix + " "));
        assertEquals("rtsp://" + suffix, VideoParams.normalizeStreamPath(" RTSPT://" + suffix + " "));
    }

    @Test
    void configuresMpvTransportByScheme() {
        String rtsp = VideoParams.mpvLoadOptionsForPath("rtsp://camera.example/live", new String[0], "", "");
        String rtsps = VideoParams.mpvLoadOptionsForPath("RTSPS://camera.example/live", new String[0], "", "");
        String rtspt = VideoParams.mpvLoadOptionsForPath("rtspt://camera.example/live", new String[0], "", "");

        assertFalse(List.of(rtsp.split(",")).contains("rtsp-transport=%3%tcp"));
        assertTrue(List.of(rtsps.split(",")).contains("rtsp-transport=%3%tcp"));
        assertTrue(List.of(rtspt.split(",")).contains("rtsp-transport=%3%tcp"));
    }

    @Test
    void configuresVlcTransportByScheme() {
        String[] rtsp = VideoParams.vlcOptions("rtsp://camera.example/live", new String[0]);
        String[] rtsps = VideoParams.vlcOptions("RTSPS://camera.example/live", new String[0]);
        String[] rtspt = VideoParams.vlcOptions("rtspt://camera.example/live", new String[0]);

        assertEquals(List.of(), List.of(rtsp));
        assertEquals(List.of(":rtsp-tcp"), List.of(rtsps));
        assertEquals(List.of(":rtsp-tcp"), List.of(rtspt));
    }

    @Test
    void appliesConfiguredProxyOnlyToYouTubeVlcMedia() {
        String proxy = "http://user:pass@127.0.0.1:7897";

        assertEquals(
                List.of(":http-proxy=" + proxy),
                List.of(VideoParams.vlcOptions("https://googlevideo.example/video", VideoParams.youtubeParams(), proxy))
        );
        assertEquals(
                List.of(),
                List.of(VideoParams.vlcOptions("https://example.com/video", new String[0], proxy))
        );
    }

    @Test
    void stripsProxyCredentialsFromMpvYtdlOptions() {
        String options = VideoParams.mpvLoadOptions(
                VideoParams.youtubeParams(),
                "http://user:pass@127.0.0.1:7897"
        );

        assertTrue(options.contains("http://user:pass@127.0.0.1:7897"));
        String ytdlOptions = List.of(options.split(",")).stream()
                .filter(option -> option.startsWith("ytdl-raw-options="))
                .findFirst()
                .orElseThrow();
        assertTrue(ytdlOptions.contains("http://127.0.0.1:7897"));
        assertFalse(ytdlOptions.contains("user"));
        assertFalse(ytdlOptions.contains("pass"));
    }
}
