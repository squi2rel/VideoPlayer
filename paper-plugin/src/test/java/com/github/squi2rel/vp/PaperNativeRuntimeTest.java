package com.github.squi2rel.vp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperNativeRuntimeTest {
    @AfterEach
    void stopRuntime() {
        PaperNativeRuntime runtime = PaperNativeRuntime.current();
        if (runtime != null) runtime.stop();
        VideoPlayerMain.error = null;
    }

    @Test
    void transitionsThroughInstallAndLoadToReady() {
        AtomicReference<Runnable> task = new AtomicReference<>();
        AtomicReference<PaperNativeRuntime.State> installState = new AtomicReference<>();
        AtomicReference<PaperNativeRuntime.State> loadState = new AtomicReference<>();

        PaperNativeRuntime runtime = PaperNativeRuntime.start(
                active -> {
                    assertTrue(active.getAsBoolean());
                    installState.set(PaperNativeRuntime.currentState());
                },
                () -> {
                    loadState.set(PaperNativeRuntime.currentState());
                    return true;
                },
                runnable -> {
                    task.set(runnable);
                    return () -> {};
                }
        );

        assertEquals(PaperNativeRuntime.State.INITIALIZING, runtime.state());
        assertFalse(PaperNativeRuntime.isReady());
        task.get().run();

        assertEquals(PaperNativeRuntime.State.INSTALLING, installState.get());
        assertEquals(PaperNativeRuntime.State.LOADING, loadState.get());
        assertEquals(PaperNativeRuntime.State.READY, runtime.state());
        assertTrue(PaperNativeRuntime.isReady());
        assertNull(runtime.error());
    }

    @Test
    void stoppedRuntimeCannotRunDeferredInitialization() {
        AtomicReference<Runnable> task = new AtomicReference<>();
        AtomicBoolean installed = new AtomicBoolean();
        AtomicBoolean loaded = new AtomicBoolean();
        PaperNativeRuntime runtime = PaperNativeRuntime.start(
                active -> installed.set(true),
                () -> {
                    loaded.set(true);
                    return true;
                },
                runnable -> {
                    task.set(runnable);
                    return () -> {};
                }
        );

        runtime.stop();
        task.get().run();

        assertEquals(PaperNativeRuntime.State.STOPPED, runtime.state());
        assertFalse(installed.get());
        assertFalse(loaded.get());
        assertNull(PaperNativeRuntime.current());
    }

    @Test
    void replacementRuntimeRejectsOldTaskWriteback() {
        AtomicReference<Runnable> oldTask = new AtomicReference<>();
        AtomicBoolean oldInstalled = new AtomicBoolean();
        PaperNativeRuntime oldRuntime = PaperNativeRuntime.start(
                active -> oldInstalled.set(true),
                () -> true,
                runnable -> {
                    oldTask.set(runnable);
                    return () -> {};
                }
        );

        PaperNativeRuntime replacement = PaperNativeRuntime.start(
                active -> {},
                () -> true,
                runnable -> {
                    runnable.run();
                    return () -> {};
                }
        );
        oldTask.get().run();

        assertEquals(PaperNativeRuntime.State.STOPPED, oldRuntime.state());
        assertFalse(oldInstalled.get());
        assertEquals(PaperNativeRuntime.State.READY, replacement.state());
        assertEquals(replacement, PaperNativeRuntime.current());
    }

    @Test
    void failedLoadPublishesUnavailableState() {
        PaperNativeRuntime runtime = PaperNativeRuntime.start(
                active -> {},
                () -> false,
                runnable -> {
                    runnable.run();
                    return () -> {};
                }
        );

        assertEquals(PaperNativeRuntime.State.UNAVAILABLE, runtime.state());
        assertFalse(PaperNativeRuntime.isReady());
        assertNotNull(runtime.error());
        assertEquals(runtime.error(), PaperNativeRuntime.currentError());
    }
}
