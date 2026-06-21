package com.github.squi2rel.vp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public final class VideoPlayerMain {
    public static final String MOD_ID = "videoplayer";
    public static String version = "unknown";
    public static Throwable error = null;
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static final boolean android = Files.exists(Path.of("/system/build.prop"));
    public static ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, VideoPlayerMain::newDaemon);
    public static ExecutorBridge server = Runnable::run;

    private VideoPlayerMain() {
    }

    public static void resetScheduler() {
        scheduler.shutdownNow();
        scheduler = Executors.newScheduledThreadPool(1, VideoPlayerMain::newDaemon);
    }

    private static Thread newDaemon(Runnable task) {
        Thread thread = new Thread(task, "VideoPlayer-scheduler");
        thread.setDaemon(true);
        return thread;
    }

    @FunctionalInterface
    public interface ExecutorBridge {
        void execute(Runnable runnable);
    }
}
