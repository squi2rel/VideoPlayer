package com.github.squi2rel.vp.video;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeDependencyDiagnosticsTest {
    @Test
    void reportsMissingDependencyFromSuppressedPackageLoadFailure() {
        UnsatisfiedLinkError root = new UnsatisfiedLinkError("Unable to load library 'mpv'");
        root.addSuppressed(new UnsatisfiedLinkError(
                "/tmp/videoplayer/libmpv.so: libva.so.2: cannot open shared object file: No such file or directory"
        ));

        String recommendation = NativeDependencyDiagnostics.recommendation(root, "linux");
        assertEquals("missing native dependencies: libva.so.2", NativeDependencyDiagnostics.describe(root));
        assertTrue(recommendation.contains("libva.so.2"));
        assertTrue(recommendation.contains("apt-get install --no-install-recommends libmpv2"));
        assertFalse(recommendation.contains("VLC"));
    }

    @Test
    void reportsLoaderStyleMissingDependency() {
        UnsatisfiedLinkError error = new UnsatisfiedLinkError(
                "error while loading shared libraries: libpipewire-0.3.so.0: cannot open shared object file"
        );

        assertEquals("missing native dependencies: libpipewire-0.3.so.0", NativeDependencyDiagnostics.describe(error));
    }
}
