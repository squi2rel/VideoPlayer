package com.github.squi2rel.vp;

import java.nio.file.Files;
import java.nio.file.Path;

public final class Android {
    private static boolean loaded;

    public static synchronized void load(Path libraryRoot) {
        if (loaded) return;
        Path bridge = libraryRoot.resolve(System.mapLibraryName("vlc_jvm_bridge"));
        try {
            if (Files.isRegularFile(bridge)) {
                System.load(bridge.toAbsolutePath().toString());
            } else {
                System.loadLibrary("vlc_jvm_bridge");
            }
            init();
            loaded = true;
        } catch (UnsatisfiedLinkError e) {
            throw new RuntimeException("Cannot initialize the Android VLC JVM bridge from " + bridge, e);
        }
    }

    private static native void init();
}
