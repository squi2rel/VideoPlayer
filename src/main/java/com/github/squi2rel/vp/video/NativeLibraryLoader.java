package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.NativeDownloadConfig;
import com.github.squi2rel.vp.VideoPlayerMain;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.WString;

import java.nio.file.Files;
import java.nio.file.Path;

final class NativeLibraryLoader {
    private NativeLibraryLoader() {
    }

    static void prepareWindowsDllDirectory(Path directory) {
        if (!"windows".equals(NativeDownloadConfig.osKey()) || directory == null) return;
        Path absolute = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(absolute)) return;
        try {
            if (!Kernel32Holder.KERNEL32.SetDllDirectoryW(new WString(absolute.toString()))) {
                VideoPlayerMain.LOGGER.warn("Failed to set Windows DLL directory: {}", absolute);
            }
        } catch (Throwable t) {
            VideoPlayerMain.LOGGER.warn("Failed to set Windows DLL directory: {}", absolute, t);
        }
    }

    static void prepareWindowsDllDirectoryForLibrary(Path library) {
        if (library == null) return;
        prepareWindowsDllDirectory(library.toAbsolutePath().getParent());
    }

    private static final class Kernel32Holder {
        private static final Kernel32 KERNEL32 = Native.load("kernel32", Kernel32.class);
    }

    private interface Kernel32 extends Library {
        boolean SetDllDirectoryW(WString lpPathName);
    }
}
