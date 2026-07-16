package com.github.squi2rel.vp;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YtDlpManagerTest {
    @Test
    void loadsPinnedOfficialAssets() {
        NativeDownloadConfig config = new NativeDownloadConfig();
        NativeDownloadConfig.ToolDownload tool = config.tool(YtDlpManager.TOOL_NAME);

        assertEquals("2026.07.04", tool.version);
        assertTrue(YtDlpManager.isSupported(config, NativeDownloadConfig.WINDOWS_X64));
        assertTrue(YtDlpManager.isSupported(config, NativeDownloadConfig.LINUX_ARM64));
        assertTrue(YtDlpManager.isSupported(config, NativeDownloadConfig.MACOS_ARM64));
        assertFalse(YtDlpManager.isSupported(config, NativeDownloadConfig.ANDROID_ARM64));
        assertEquals("52fe3c26dcf71fbdc85b528589020bb0b8e383155cfa81b64dd447bbe35e24b8",
                tool.sources(NativeDownloadConfig.WINDOWS_X64).getFirst().sha256);
    }

    @Test
    void recognizesManagedExecutablePaths() {
        Path managed = YtDlpManager.managedExecutable();

        assertTrue(YtDlpManager.isManagedExecutable(managed.toString()));
        assertFalse(YtDlpManager.isManagedExecutable(Path.of("external", "yt-dlp").toString()));
    }

    @Test
    void expandsGithubMirrorsWithoutProxy() {
        NativeDownloadConfig.DownloadSource source = new NativeDownloadConfig.DownloadSource(
                "https://github.com/yt-dlp/yt-dlp/releases/download/2026.07.04/yt-dlp.exe", "0".repeat(64));

        List<NativeDownloadConfig.DownloadSource> selected = NativePackageManager.selectDownloadSources(List.of(source), "");

        assertEquals(4, selected.size());
        assertTrue(selected.get(0).url.startsWith("https://ghfast.top/"));
        assertTrue(selected.get(1).url.startsWith("https://gh-proxy.com/"));
        assertTrue(selected.get(2).url.startsWith("https://ghproxy.net/"));
        assertEquals(source.url, selected.get(3).url);
    }

    @Test
    void usesGithubDirectlyWithProxy() {
        NativeDownloadConfig.DownloadSource source = new NativeDownloadConfig.DownloadSource(
                "https://github.com/yt-dlp/yt-dlp/releases/download/2026.07.04/yt-dlp.exe", "0".repeat(64));

        List<NativeDownloadConfig.DownloadSource> selected = NativePackageManager.selectDownloadSources(
                List.of(source), "http://proxy.example:8080");

        assertEquals(1, selected.size());
        assertEquals(source.url, selected.getFirst().url);
    }

    @Test
    void keepsCustomSourcesWithProxy() {
        NativeDownloadConfig.DownloadSource source = new NativeDownloadConfig.DownloadSource(
                "https://downloads.example/yt-dlp.exe", "0".repeat(64));

        List<NativeDownloadConfig.DownloadSource> selected = NativePackageManager.selectDownloadSources(
                List.of(source), "http://proxy.example:8080");

        assertEquals(1, selected.size());
        assertEquals(source.url, selected.getFirst().url);
    }

    @Test
    void cancelledInstallationDoesNotStartDownload() {
        NativePackageManager.DownloadResult result = YtDlpManager.downloadAndInstall(
                new NativeDownloadConfig(), NativeDownloadConfig.WINDOWS_X64, "", null, () -> false
        );

        assertFalse(result.success());
        assertInstanceOf(CancellationException.class, result.error());
    }
}
