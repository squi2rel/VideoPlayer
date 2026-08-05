package com.github.squi2rel.vp.video;

import com.sun.jna.Pointer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

class VlcStreamListenerShutdownTest {
    @AfterEach
    void tearDown() throws Exception {
        set("instance", null);
        set("loadError", null);
        set("loadAttempted", false);
        set("shutDown", false);
    }

    @Test
    void shutdownReleasesGlobalInstanceAndResetsLoadState() throws Exception {
        Pointer instance = new Pointer(17L);
        AtomicReference<Pointer> released = new AtomicReference<>();
        CountDownLatch releaseCalled = new CountDownLatch(1);
        set("instance", instance);
        set("loadError", new IllegalStateException("stale"));
        set("loadAttempted", true);

        VlcStreamListener.shutdown(pointer -> {
            released.set(pointer);
            releaseCalled.countDown();
        });

        assertNull(get("instance"));
        assertNull(get("loadError"));
        assertFalse((boolean) get("loadAttempted"));
        assertFalse(VlcStreamListener.load());
        assertTrue(releaseCalled.await(1, TimeUnit.SECONDS));
        assertSame(instance, released.get());

        try (MockedStatic<VlcLibrary> library = mockStatic(VlcLibrary.class)) {
            VlcStreamListener.resetLoadState();
            library.verify(VlcLibrary::resetLoadState);
        }
        assertFalse((boolean) get("shutDown"));
    }

    @Test
    void releaseInstanceReleasesNativeState() {
        Pointer instance = new Pointer(17L);

        try (MockedStatic<VlcLibrary> library = mockStatic(VlcLibrary.class)) {
            VlcStreamListener.releaseInstance(instance);

            library.verify(() -> VlcLibrary.releaseInstance(instance));
            library.verify(VlcLibrary::resetLoadState);
        }
    }

    private static Object get(String name) throws Exception {
        Field field = VlcStreamListener.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static void set(String name, Object value) throws Exception {
        Field field = VlcStreamListener.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }
}
