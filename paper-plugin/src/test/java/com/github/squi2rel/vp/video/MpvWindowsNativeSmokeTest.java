package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.NativeDownloadConfig;
import com.github.squi2rel.vp.NativePackageManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MpvWindowsNativeSmokeTest {
    private static final String RESOURCE = "/assets/videoplayer/native/libmpv-windows-x64.zip";
    private static final String SHA256 = "0a1e614d3b3db315895d19b1e97013fd12da9bc20c50d02d5de3b71a959dfdfb";

    @Test
    void installsAndInitializesWindowsX64Mpv(@TempDir Path configDir) {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("VIDEOPLAYER_MPV_WINDOWS_SMOKE")));
        Assumptions.assumeTrue("windows".equals(NativeDownloadConfig.osKey()));
        Assumptions.assumeTrue(NativeDownloadConfig.WINDOWS_X64.equals(NativeDownloadConfig.platformKey()));
        System.setProperty("videoplayer.configDir", configDir.toString());
        try {
            NativePackageManager.selectPlatform(NativePackageManager.BACKEND_MPV, NativeDownloadConfig.WINDOWS_X64);
            NativePackageManager.DownloadResult result = NativePackageManager.installBundled(
                    NativePackageManager.BACKEND_MPV,
                    NativeDownloadConfig.WINDOWS_X64,
                    RESOURCE,
                    SHA256
            );
            assertTrue(result.success(), () -> result.message() + ": " + result.error());
            NativePackageManager.PreparedNativePackage prepared = NativePackageManager
                    .prepareForLoad(NativePackageManager.BACKEND_MPV)
                    .orElseThrow();
            NativeLibraryLoader.prepareWindowsDllDirectory(prepared.root());
            System.load(prepared.library().toAbsolutePath().toString());
            StreamListener.resetLoadState();
            assertTrue(StreamListener.loadMpvOnly(), () -> String.valueOf(StreamListener.loadError()));
        } finally {
            StreamListener.shutdown();
            NativeLibraryLoader.clearWindowsDllDirectory();
            NativePackageManager.shutdown();
            System.clearProperty("videoplayer.configDir");
        }
    }
}
