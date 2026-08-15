package com.github.squi2rel.vp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;

class AndroidVlcPackagingTest {
    private static final String ANDROID_VLC_RESOURCE =
            "/assets/videoplayer/native/vlc/android_arm64-v8a.zip";
    private static final String ANDROID_VLC_NOTICE =
            "/assets/videoplayer/native/vlc/android_arm64-v8a.NOTICE.txt";

    @Test
    void androidVlcRuntimeIsNotBundled() {
        assertNull(AndroidVlcPackagingTest.class.getResource(ANDROID_VLC_RESOURCE));
        assertNull(AndroidVlcPackagingTest.class.getResource(ANDROID_VLC_NOTICE));
    }
}
