package com.github.squi2rel.vp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativePackageManagerInstallTest {
    @TempDir
    Path temporary;

    @Test
    void failedDirectorySwitchRestoresPreviousInstallation() throws Exception {
        Path installed = temporary.resolve("runtime");
        Path staging = temporary.resolve("runtime.installing-test");
        Files.createDirectories(installed);
        Files.createDirectories(staging);
        Files.writeString(installed.resolve("old.txt"), "old");
        Files.writeString(staging.resolve("new.txt"), "new");

        assertThrows(IOException.class, () -> NativePackageManager.replaceDirectory(
                staging,
                installed,
                () -> true,
                (source, target) -> {
                    if (source.equals(staging)) throw new IOException("controlled move failure");
                    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
        ));

        assertEquals("old", Files.readString(installed.resolve("old.txt")));
        assertFalse(Files.exists(installed.resolve("new.txt")));
        assertFalse(Files.exists(temporary.resolve("runtime.previous")));
        assertTrue(Files.exists(staging.resolve("new.txt")));
    }

    @Test
    void cancellationAfterDirectorySwitchRollsBack() throws Exception {
        Path installed = temporary.resolve("runtime");
        Path staging = temporary.resolve("runtime.installing-test");
        Files.createDirectories(installed);
        Files.createDirectories(staging);
        Files.writeString(installed.resolve("old.txt"), "old");
        Files.writeString(staging.resolve("new.txt"), "new");
        AtomicBoolean active = new AtomicBoolean(true);

        assertThrows(CancellationException.class, () -> NativePackageManager.replaceDirectory(
                staging,
                installed,
                active::get,
                (source, target) -> {
                    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                    if (source.equals(staging)) active.set(false);
                }
        ));

        assertEquals("old", Files.readString(installed.resolve("old.txt")));
        assertFalse(Files.exists(installed.resolve("new.txt")));
        assertFalse(Files.exists(temporary.resolve("runtime.previous")));
    }

    @Test
    void cancelledFileValidationKeepsExistingExecutable() throws Exception {
        Path destination = temporary.resolve("yt-dlp.exe");
        Path downloaded = temporary.resolve("download.tmp");
        Files.writeString(destination, "old");
        Files.writeString(downloaded, "new");
        AtomicBoolean active = new AtomicBoolean(true);

        assertThrows(CancellationException.class, () -> NativePackageManager.installFile(
                downloaded,
                destination,
                false,
                file -> active.set(false),
                active::get
        ));

        assertEquals("old", Files.readString(destination));
        assertFalse(Files.exists(downloaded));
        assertFalse(Files.exists(temporary.resolve("yt-dlp.exe.previous")));
    }

    @Test
    void cancellationAfterFileSwitchRollsBack() throws Exception {
        Path destination = temporary.resolve("yt-dlp.exe");
        Path staging = temporary.resolve("yt-dlp-installing.exe");
        Files.writeString(destination, "old");
        Files.writeString(staging, "new");
        AtomicBoolean active = new AtomicBoolean(true);

        assertThrows(CancellationException.class, () -> NativePackageManager.replaceFile(
                staging,
                destination,
                active::get,
                (source, target) -> {
                    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                    if (source.equals(staging)) active.set(false);
                }
        ));

        assertEquals("old", Files.readString(destination));
        assertFalse(Files.exists(temporary.resolve("yt-dlp.exe.previous")));
    }

    @Test
    void downloadTemporaryNamesAreUnique() {
        String first = NativePackageManager.temporaryDownloadName("yt-dlp", "windows_x64", ".tmp");
        String second = NativePackageManager.temporaryDownloadName("yt-dlp", "windows_x64", ".tmp");

        assertNotEquals(first, second);
        assertTrue(first.startsWith("yt-dlp_windows_x64_"));
        assertTrue(first.endsWith(".tmp"));
    }

    @Test
    void packageManifestDetectsChangedMissingAndExtraFiles() throws Exception {
        String platform = NativeDownloadConfig.platformKey();
        Path root = temporary.resolve("package");
        Files.createDirectories(root.resolve("plugins"));
        Path library = root.resolve(vlcLibraryName(platform));
        Path plugin = root.resolve("plugins").resolve("codec.dat");
        Files.writeString(library, "runtime-a");
        Files.writeString(plugin, "plugin-a");

        assertFalse(NativePackageManager.isValidInstallation(root, NativePackageManager.BACKEND_VLC, platform));
        NativePackageManager.writePackageManifest(root, NativePackageManager.BACKEND_VLC, platform);
        assertTrue(NativePackageManager.isValidInstallation(root, NativePackageManager.BACKEND_VLC, platform));

        String manifest = Files.readString(root.resolve(".videoplayer-package.json"));
        assertTrue(manifest.contains("\"version\":1"));
        assertTrue(manifest.contains("\"platform\":\"" + platform + "\""));
        assertTrue(manifest.contains("\"sha256\":"));

        Files.writeString(library, "runtime-b");
        assertFalse(NativePackageManager.isValidInstallation(root, NativePackageManager.BACKEND_VLC, platform));

        Files.writeString(library, "runtime-a");
        assertTrue(NativePackageManager.isValidInstallation(root, NativePackageManager.BACKEND_VLC, platform));
        Files.delete(plugin);
        assertFalse(NativePackageManager.isValidInstallation(root, NativePackageManager.BACKEND_VLC, platform));

        Files.writeString(plugin, "plugin-a");
        assertTrue(NativePackageManager.isValidInstallation(root, NativePackageManager.BACKEND_VLC, platform));
        Files.writeString(root.resolve("unexpected.dat"), "unexpected");
        assertFalse(NativePackageManager.isValidInstallation(root, NativePackageManager.BACKEND_VLC, platform));
    }

    @Test
    void preparedRootsAreIsolatedByProcessVersionBackendPlatformAndPackage() {
        Path first = NativePackageManager.preparedRoot(temporary, "vlc", "windows_x64", "2.0.1", "process-10", "package-a");
        Path otherProcess = NativePackageManager.preparedRoot(temporary, "vlc", "windows_x64", "2.0.1", "process-11", "package-a");
        Path otherVersion = NativePackageManager.preparedRoot(temporary, "vlc", "windows_x64", "2.0.2", "process-10", "package-a");
        Path otherBackend = NativePackageManager.preparedRoot(temporary, "mpv", "windows_x64", "2.0.1", "process-10", "package-a");
        Path otherPlatform = NativePackageManager.preparedRoot(temporary, "vlc", "linux_x64", "2.0.1", "process-10", "package-a");
        Path otherPackage = NativePackageManager.preparedRoot(temporary, "vlc", "windows_x64", "2.0.1", "process-10", "package-b");

        assertNotEquals(first, otherProcess);
        assertNotEquals(first, otherVersion);
        assertNotEquals(first, otherBackend);
        assertNotEquals(first, otherPlatform);
        assertNotEquals(first, otherPackage);
        assertTrue(first.endsWith(Path.of("process-10", "2.0.1", "vlc", "windows_x64", "package-a")));
    }

    @Test
    void fileLockWaitsForIndependentChannelAndContinuesAfterRelease() throws Exception {
        Path lockPath = temporary.resolve("locks").resolve("vlc_windows_x64.lock");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Boolean> waiter;
        try {
            try (NativePackageManager.InstallFileLock ignored = NativePackageManager.acquireFileLock(lockPath, () -> true)) {
                CountDownLatch started = new CountDownLatch(1);
                waiter = executor.submit(() -> {
                    started.countDown();
                    try (NativePackageManager.InstallFileLock acquired = NativePackageManager.acquireFileLock(lockPath, () -> true)) {
                        return true;
                    }
                });
                assertTrue(started.await(2, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> waiter.get(250, TimeUnit.MILLISECONDS));
            }
            assertTrue(waiter.get(2, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void fileLockWaitCanBeCancelled() throws Exception {
        Path lockPath = temporary.resolve("locks").resolve("vlc_windows_x64.lock");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicBoolean active = new AtomicBoolean(true);
        try (NativePackageManager.InstallFileLock ignored = NativePackageManager.acquireFileLock(lockPath, () -> true)) {
            CountDownLatch started = new CountDownLatch(1);
            Future<Boolean> waiter = executor.submit(() -> {
                started.countDown();
                try (NativePackageManager.InstallFileLock acquired = NativePackageManager.acquireFileLock(lockPath, active::get)) {
                    return true;
                }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> waiter.get(250, TimeUnit.MILLISECONDS));
            active.set(false);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> waiter.get(2, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof CancellationException);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void managedDestinationLockUsesNormalizedAbsolutePath() throws Exception {
        Path tools = temporary.resolve("tools");
        Files.createDirectories(tools);
        Path direct = tools.resolve("yt-dlp.exe");
        Path equivalent = tools.resolve("child").resolve("..").resolve("yt-dlp.exe");

        assertEquals(NativePackageManager.destinationLockPath(direct), NativePackageManager.destinationLockPath(equivalent));
        assertTrue(NativePackageManager.destinationLockPath(direct).getFileName().toString().startsWith("managed-file-"));
    }

    @Test
    void validManagedFileSkipsSourcesAfterDestinationLock() throws Exception {
        Path destination = temporary.resolve("tools").resolve("yt-dlp.exe");
        Files.createDirectories(destination.getParent());
        Files.writeString(destination, "valid");
        AtomicBoolean validated = new AtomicBoolean();

        NativePackageManager.DownloadResult result = NativePackageManager.downloadAndInstallFile(
                "yt-dlp",
                "windows_x64",
                List.of(),
                "",
                destination,
                false,
                file -> {
                    assertEquals(destination.toAbsolutePath().normalize(), file);
                    assertEquals("valid", Files.readString(file));
                    validated.set(true);
                },
                null
        );

        assertTrue(result.success());
        assertTrue(validated.get());
        assertEquals("valid", Files.readString(destination));
    }

    private static String vlcLibraryName(String platform) {
        String os = NativeDownloadConfig.osFromPlatform(platform);
        if ("windows".equals(os)) return "libvlc.dll";
        if ("macos".equals(os)) return "libvlc.dylib";
        return "libvlc.so";
    }
}
