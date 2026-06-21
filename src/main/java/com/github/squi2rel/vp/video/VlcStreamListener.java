package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.VideoPlayerMain;
import com.github.squi2rel.vp.provider.VideoInfo;
import com.sun.jna.Pointer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class VlcStreamListener implements IVideoListener {
    private static final long TIMEOUT_MS = 10_000;
    private static final long LOOP_SLEEP_MS = 50;
    private static final int[] MEDIA_PLAYER_EVENTS = {
            VlcLibrary.LIBVLC_MEDIA_PLAYER_PLAYING,
            VlcLibrary.LIBVLC_MEDIA_PLAYER_STOPPED,
            VlcLibrary.LIBVLC_MEDIA_PLAYER_END_REACHED,
            VlcLibrary.LIBVLC_MEDIA_PLAYER_ENCOUNTERED_ERROR,
            VlcLibrary.LIBVLC_MEDIA_PLAYER_TIME_CHANGED
    };

    private static Pointer instance;
    private static Throwable loadError;
    private static boolean loadAttempted;

    private final VideoInfo info;
    private final VlcLibrary.LibVlc lib;
    private final AtomicBoolean released = new AtomicBoolean(false);
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final Object commandLock = new Object();
    private final VlcLibrary.EventCallback eventCallback = this::handleEvent;

    private Thread thread;
    private volatile Pointer mediaPlayer;
    private volatile Pointer eventManager;
    private volatile long lastPlayTime;
    private volatile long lastPlayUpdateTime;
    private Consumer<Boolean> playing = seekable -> {};
    private Runnable stopped = () -> {};
    private Runnable errored = () -> {};
    private Runnable timeout = () -> {};

    VlcStreamListener(VideoInfo info) {
        this.info = info;
        this.lib = VlcLibrary.get();
    }

    static synchronized boolean load() {
        if (instance != null) return true;
        if (loadAttempted && loadError != null) return false;
        loadAttempted = true;

        Pointer created = null;
        try {
            List<String> options = new ArrayList<>();
            options.add("--no-video");
            options.add("--aout=none");
            options.add("--no-xlib");
            options.add("--intf=dummy");
            options.add("--quiet");

            created = VlcLibrary.createInstance(options);
            VlcLibrary.LibVlc loaded = VlcLibrary.get();
            Pointer testPlayer = loaded.libvlc_media_player_new(created);
            if (testPlayer == null) {
                throw new IllegalStateException("libvlc_media_player_new returned null: " + VlcLibrary.errmsg(loaded));
            }
            loaded.libvlc_media_player_release(testPlayer);

            instance = created;
            loadError = null;
            return true;
        } catch (Throwable t) {
            if (created != null) {
                try {
                    VlcLibrary.releaseInstance(created);
                } catch (Throwable releaseError) {
                    t.addSuppressed(releaseError);
                }
            }
            loadError = t;
            VideoPlayerMain.LOGGER.warn("VLC stream listener backend is not available", t);
            return false;
        }
    }

    static synchronized Throwable loadError() {
        load();
        return loadError;
    }

    static synchronized void resetLoadState() {
        Pointer oldInstance = instance;
        instance = null;
        loadError = null;
        loadAttempted = false;
        if (oldInstance != null) {
            try {
                VlcLibrary.releaseInstance(oldInstance);
            } catch (RuntimeException e) {
                VideoPlayerMain.LOGGER.warn("Failed to release VLC stream listener instance", e);
            }
        }
        VlcLibrary.resetLoadState();
    }

    @Override
    public long getProgress() {
        if (!isPlaying()) return -1;
        if (lastPlayTime == 0) return 0;
        return System.currentTimeMillis() - lastPlayUpdateTime + lastPlayTime;
    }

    @Override
    public void setProgress(long progress) {
        long target = Math.max(0, progress);
        updateProgress(target);
        synchronized (commandLock) {
            Pointer player = mediaPlayer;
            if (player == null || released.get() || finished.get()) return;
            try {
                lib.libvlc_media_player_set_time(player, target);
            } catch (RuntimeException e) {
                VideoPlayerMain.LOGGER.warn("Failed to seek VLC stream listener media player", e);
            }
        }
    }

    @Override
    public boolean isPlaying() {
        return mediaPlayer != null && !released.get() && !finished.get();
    }

    @Override
    public void playing(Consumer<Boolean> playing) {
        this.playing = playing == null ? seekable -> {} : playing;
    }

    @Override
    public void stopped(Runnable stopped) {
        this.stopped = stopped == null ? () -> {} : stopped;
    }

    @Override
    public void errored(Runnable errored) {
        this.errored = errored == null ? () -> {} : errored;
    }

    @Override
    public void timeout(Runnable timeout) {
        this.timeout = timeout == null ? () -> {} : timeout;
    }

    @Override
    public void listen() {
        if (!load()) {
            throw new IllegalStateException("VLC stream listener backend is not available", loadError);
        }
        released.set(false);
        finished.set(false);
        started.set(false);
        lastPlayTime = 0;
        lastPlayUpdateTime = System.currentTimeMillis();
        thread = new Thread(this::run, "VideoPlayer-VLC-stream");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void cancel() {
        if (!released.compareAndSet(false, true)) return;
        Thread running = thread;
        if (running != null) running.interrupt();
        synchronized (commandLock) {
            Pointer player = mediaPlayer;
            if (player == null) return;
            try {
                lib.libvlc_media_player_stop(player);
            } catch (RuntimeException e) {
                VideoPlayerMain.LOGGER.warn("Failed to stop VLC stream listener media player", e);
            }
        }
    }

    private void run() {
        Pointer player = null;
        Pointer manager = null;
        Pointer media = null;
        try {
            Pointer currentInstance = currentInstance();
            player = lib.libvlc_media_player_new(currentInstance);
            if (player == null) {
                throw new IllegalStateException("libvlc_media_player_new returned null: " + VlcLibrary.errmsg(lib));
            }

            manager = lib.libvlc_media_player_event_manager(player);
            attachEvents(manager);
            synchronized (commandLock) {
                if (released.get()) return;
                mediaPlayer = player;
                eventManager = manager;
            }

            media = VlcLibrary.createMedia(currentInstance, info.path(), VideoParams.vlcOptions(info.params()));
            lib.libvlc_media_player_set_media(player, media);
            lib.libvlc_media_release(media);
            media = null;

            int result = lib.libvlc_media_player_play(player);
            if (result != 0) {
                throw new IllegalStateException("libvlc_media_player_play failed: " + VlcLibrary.errmsg(lib));
            }

            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (!released.get() && !finished.get()) {
                long now = System.currentTimeMillis();
                if (!started.get() && now >= deadline) {
                    completeTimeout();
                    break;
                }
                try {
                    Thread.sleep(LOOP_SLEEP_MS);
                } catch (InterruptedException ignored) {
                }
            }
        } catch (Throwable t) {
            if (!released.get()) {
                VideoPlayerMain.LOGGER.warn("VLC stream listener failed", t);
                completeErrored();
            }
        } finally {
            if (media != null) {
                try {
                    lib.libvlc_media_release(media);
                } catch (RuntimeException e) {
                    VideoPlayerMain.LOGGER.warn("Failed to release VLC stream listener media", e);
                }
            }
            releasePlayer(player, manager);
        }
    }

    private static synchronized Pointer currentInstance() {
        if (!load()) {
            throw new IllegalStateException("VLC stream listener backend is not available", loadError);
        }
        return instance;
    }

    private void attachEvents(Pointer manager) {
        if (manager == null) return;
        for (int event : MEDIA_PLAYER_EVENTS) {
            int result = lib.libvlc_event_attach(manager, event, eventCallback, null);
            if (result != 0) {
                VideoPlayerMain.LOGGER.warn("Failed to attach VLC stream listener event {}", event);
            }
        }
    }

    private void detachEvents(Pointer manager) {
        if (manager == null) return;
        for (int event : MEDIA_PLAYER_EVENTS) {
            try {
                lib.libvlc_event_detach(manager, event, eventCallback, null);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void releasePlayer(Pointer player, Pointer manager) {
        if (player == null) return;
        synchronized (commandLock) {
            if (mediaPlayer == player) {
                mediaPlayer = null;
                eventManager = null;
            }
        }
        detachEvents(manager);
        try {
            lib.libvlc_media_player_stop(player);
        } catch (RuntimeException e) {
            VideoPlayerMain.LOGGER.warn("Failed to stop VLC stream listener media player", e);
        }
        try {
            lib.libvlc_media_player_release(player);
        } catch (RuntimeException e) {
            VideoPlayerMain.LOGGER.warn("Failed to release VLC stream listener media player", e);
        }
    }

    private void handleEvent(Pointer event, Pointer userData) {
        if (event == null || released.get() || finished.get()) return;
        int type = event.getInt(0);
        switch (type) {
            case VlcLibrary.LIBVLC_MEDIA_PLAYER_PLAYING -> handlePlaying();
            case VlcLibrary.LIBVLC_MEDIA_PLAYER_TIME_CHANGED -> handleTimeChanged();
            case VlcLibrary.LIBVLC_MEDIA_PLAYER_STOPPED, VlcLibrary.LIBVLC_MEDIA_PLAYER_END_REACHED -> completeStopped();
            case VlcLibrary.LIBVLC_MEDIA_PLAYER_ENCOUNTERED_ERROR -> {
                VideoPlayerMain.LOGGER.warn("VLC stream listener encountered a media error");
                completeErrored();
            }
            default -> {
            }
        }
    }

    private void handlePlaying() {
        boolean seekable;
        long time;
        synchronized (commandLock) {
            Pointer player = mediaPlayer;
            if (player == null || released.get() || finished.get()) return;
            seekable = safeSeekable(player);
            time = safeTime(player);
        }
        if (time >= 0) updateProgress(time);
        if (started.compareAndSet(false, true)) {
            playing.accept(seekable);
        }
    }

    private void handleTimeChanged() {
        long time;
        synchronized (commandLock) {
            Pointer player = mediaPlayer;
            if (player == null || released.get() || finished.get()) return;
            time = safeTime(player);
        }
        if (time >= 0) updateProgress(time);
    }

    private boolean safeSeekable(Pointer player) {
        try {
            return lib.libvlc_media_player_is_seekable(player) != 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private long safeTime(Pointer player) {
        try {
            return lib.libvlc_media_player_get_time(player);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private void updateProgress(long progress) {
        if (progress < 0) return;
        lastPlayTime = progress;
        lastPlayUpdateTime = System.currentTimeMillis();
    }

    private void completeStopped() {
        if (!finished.compareAndSet(false, true)) return;
        stopped.run();
    }

    private void completeErrored() {
        if (!finished.compareAndSet(false, true)) return;
        errored.run();
        stopped.run();
    }

    private void completeTimeout() {
        if (!finished.compareAndSet(false, true)) return;
        VideoPlayerMain.LOGGER.warn("VLC stream listener timed out before media started");
        timeout.run();
        stopped.run();
    }
}
