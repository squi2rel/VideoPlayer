package com.github.squi2rel.vp;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeDownloadConfigAndroidTest {
    @Test
    void androidPublishesOnlyArm64Vlc() {
        NativeDownloadConfig config = new NativeDownloadConfig();

        assertEquals(List.of(NativeDownloadConfig.ANDROID_ARM64), NativeDownloadConfig.platformsForOs("android"));
        assertTrue(NativeDownloadConfig.isSupportedBackendPlatform(
                NativeDownloadConfig.BACKEND_VLC, NativeDownloadConfig.ANDROID_ARM64));
        assertFalse(NativeDownloadConfig.isSupportedBackendPlatform(
                NativeDownloadConfig.BACKEND_MPV, NativeDownloadConfig.ANDROID_ARM64));
        assertFalse(NativeDownloadConfig.isSupportedBackendPlatform(
                NativeDownloadConfig.BACKEND_VLC, NativeDownloadConfig.ANDROID_ARMV7));
        assertFalse(config.sources(NativeDownloadConfig.BACKEND_MPV, NativeDownloadConfig.ANDROID_ARM64).iterator().hasNext());
        assertFalse(config.sources(NativeDownloadConfig.BACKEND_VLC, NativeDownloadConfig.ANDROID_ARMV7).iterator().hasNext());
        assertFalse(config.sources(NativeDownloadConfig.BACKEND_VLC, NativeDownloadConfig.ANDROID_X86).iterator().hasNext());
        assertFalse(config.sources(NativeDownloadConfig.BACKEND_VLC, NativeDownloadConfig.ANDROID_X86_64).iterator().hasNext());
    }

    @Test
    void unsupportedAndroidEntriesAreRemovedFromLoadedConfiguration() {
        NativeDownloadConfig config = new NativeDownloadConfig();
        NativeDownloadConfig.DownloadSource source = new NativeDownloadConfig.DownloadSource("https://example.invalid/runtime.zip", "");
        config.urls.get(NativeDownloadConfig.BACKEND_VLC)
                .put(NativeDownloadConfig.ANDROID_X86, new ArrayList<>(List.of(source)));
        config.urls.get(NativeDownloadConfig.BACKEND_MPV)
                .put(NativeDownloadConfig.ANDROID_ARM64, new ArrayList<>(List.of(source)));

        assertTrue(config.ensureDefaults());
        assertFalse(config.urls.get(NativeDownloadConfig.BACKEND_VLC).containsKey(NativeDownloadConfig.ANDROID_X86));
        assertFalse(config.urls.get(NativeDownloadConfig.BACKEND_MPV).containsKey(NativeDownloadConfig.ANDROID_ARM64));
    }

    @Test
    void bundledDownloadListContainsNoUnsupportedAndroidRuntime() throws Exception {
        try (InputStream input = NativeDownloadConfigAndroidTest.class.getResourceAsStream("/assets/videoplayer/native-downloads.json")) {
            assertTrue(input != null);
            String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(json.contains("\"android_arm64-v8a\""));
            assertFalse(json.contains("android_armeabi-v7a"));
            assertFalse(json.contains("android_x86"));
            int mpv = json.indexOf("\"mpv\"");
            int tools = json.indexOf("\"tools\"");
            assertTrue(mpv >= 0 && tools > mpv);
            assertFalse(json.substring(mpv, tools).contains("\"android_"));
        }
    }
}
