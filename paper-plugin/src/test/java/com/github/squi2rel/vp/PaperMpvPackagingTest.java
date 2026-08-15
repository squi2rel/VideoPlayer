package com.github.squi2rel.vp;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperMpvPackagingTest {
    private static final String MPV_RESOURCE = "/assets/videoplayer/native/libmpv-windows-x64.zip";

    @Test
    void paperPluginDoesNotBundleMpvRuntime() {
        assertNull(PaperMpvPackagingTest.class.getResource(MPV_RESOURCE));
    }

    @Test
    void paperPluginKeepsWindowsX64MpvDownloadSource() throws Exception {
        try (InputStream input = PaperMpvPackagingTest.class.getResourceAsStream(
                "/assets/videoplayer/native-downloads.json")) {
            assertNotNull(input);
            String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(json.contains("libmpv-windows-x64.zip"));
            assertTrue(json.contains("0a1e614d3b3db315895d19b1e97013fd12da9bc20c50d02d5de3b71a959dfdfb"));
        }
    }
}
