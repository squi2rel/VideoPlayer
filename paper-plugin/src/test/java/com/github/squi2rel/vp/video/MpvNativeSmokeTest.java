package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.NativeDownloadConfig;
import com.github.squi2rel.vp.NativePackageManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MpvNativeSmokeTest {
    @Test
    void downloadsAndInitializesLinuxX64Mpv(@TempDir Path configDir) {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("VIDEOPLAYER_MPV_SMOKE")));
        Assumptions.assumeTrue("linux".equals(NativeDownloadConfig.osKey()));
        Assumptions.assumeTrue(NativeDownloadConfig.LINUX_X64.equals(NativeDownloadConfig.platformKey()));
        System.setProperty("videoplayer.configDir", configDir.toString());
        try {
            NativeDownloadConfig downloads = NativeDownloadConfig.load();
            List<NativeDownloadConfig.DownloadSource> sources = downloads.sources(
                    NativeDownloadConfig.BACKEND_MPV,
                    NativeDownloadConfig.LINUX_X64
            );
            assertFalse(sources.isEmpty());
            NativePackageManager.selectPlatform(NativePackageManager.BACKEND_MPV, NativeDownloadConfig.LINUX_X64);
            NativePackageManager.DownloadResult result = NativePackageManager.downloadAndInstall(
                    NativePackageManager.BACKEND_MPV,
                    NativeDownloadConfig.LINUX_X64,
                    sources,
                    "",
                    null
            );
            assertTrue(result.success(), () -> result.message() + ": " + result.error());
            NativePackageManager.PreparedNativePackage prepared = NativePackageManager
                    .prepareForLoad(NativePackageManager.BACKEND_MPV)
                    .orElseThrow();
            System.load(prepared.library().toAbsolutePath().toString());
            StreamListener.resetLoadState();
            assertTrue(StreamListener.loadMpvOnly(), () -> String.valueOf(StreamListener.loadError()));
        } finally {
            StreamListener.shutdown();
            NativePackageManager.shutdown();
            System.clearProperty("videoplayer.configDir");
        }
    }
}
