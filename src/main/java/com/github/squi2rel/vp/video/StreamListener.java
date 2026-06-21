package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.NativeDownloadConfig;
import com.github.squi2rel.vp.NativePackageManager;
import com.github.squi2rel.vp.VideoPlayerMain;
import com.github.squi2rel.vp.provider.VideoInfo;

import java.util.function.Consumer;

public class StreamListener implements IVideoListener {
    private static volatile boolean mpvAvailable;
    private static volatile boolean vlcAvailable;
    private static volatile Throwable mpvError;
    private static volatile Throwable vlcError;
    private static volatile String preferredBackend = NativePackageManager.BACKEND_MPV;
    private static volatile String configuredProxy = "";
    private static volatile String configuredYtdlPath = "";

    private final IVideoListener delegate;

    public StreamListener(VideoInfo info) {
        this.delegate = create(info);
    }

    public static boolean accept(VideoInfo info) {
        return info != null && info.path() != null && !info.path().isEmpty();
    }

    public static synchronized boolean load() {
        if (mpvAvailable || vlcAvailable) return true;
        String first = NativeDownloadConfig.normalizeBackend(preferredBackend);
        String second = NativePackageManager.BACKEND_MPV.equals(first)
                ? NativePackageManager.BACKEND_VLC
                : NativePackageManager.BACKEND_MPV;

        if (loadBackend(first, false)) {
            return true;
        }

        Throwable firstError = NativePackageManager.BACKEND_MPV.equals(first) ? mpvError : vlcError;
        VideoPlayerMain.LOGGER.warn("{} stream listener backend is not available; trying {} fallback.", first.toUpperCase(), second.toUpperCase(), firstError);
        if (loadBackend(second, true)) {
            return true;
        }
        VideoPlayerMain.LOGGER.warn("No stream listener backend is available");
        return false;
    }

    public static synchronized void configurePreferredBackend(String backend) {
        String normalized = NativeDownloadConfig.normalizeBackend(backend);
        if (!normalized.equals(preferredBackend)) {
            preferredBackend = normalized;
            resetLoadState();
        }
    }

    public static void configureProxy(String proxy) {
        configuredProxy = proxy == null ? "" : proxy.trim();
    }

    static String configuredProxy() {
        return configuredProxy;
    }

    public static void configureYtdlPath(String ytdlPath) {
        configuredYtdlPath = ytdlPath == null ? "" : ytdlPath.trim();
    }

    static String configuredYtdlPath() {
        return configuredYtdlPath;
    }

    public static synchronized void resetLoadState() {
        mpvAvailable = false;
        vlcAvailable = false;
        mpvError = null;
        vlcError = null;
        MpvLibrary.resetLoadState();
        VlcStreamListener.resetLoadState();
    }

    private static boolean loadBackend(String backend, boolean fallback) {
        if (NativePackageManager.BACKEND_MPV.equals(NativeDownloadConfig.normalizeBackend(backend))) {
            mpvError = MpvLibrary.loadError();
            mpvAvailable = mpvError == null;
            if (mpvAvailable) {
                try {
                    MpvStreamListener.verifyAvailable();
                    VideoPlayerMain.LOGGER.info("Using MPV stream listener{} backend", fallback ? " fallback" : "");
                    return true;
                } catch (Throwable t) {
                    mpvError = t;
                    mpvAvailable = false;
                    VideoPlayerMain.LOGGER.warn("MPV stream listener backend failed self-test.", t);
                }
            }
            return false;
        }

        if (VlcStreamListener.load()) {
            vlcAvailable = true;
            vlcError = null;
            VideoPlayerMain.LOGGER.info("Using VLC stream listener{} backend", fallback ? " fallback" : "");
            return true;
        }
        vlcError = VlcStreamListener.loadError();
        return false;
    }

    private static IVideoListener create(VideoInfo info) {
        load();
        String first = NativeDownloadConfig.normalizeBackend(preferredBackend);
        if (NativePackageManager.BACKEND_MPV.equals(first)) {
            if (mpvAvailable) return new MpvStreamListener(info);
            if (vlcAvailable) return new VlcStreamListener(info);
        } else {
            if (vlcAvailable) return new VlcStreamListener(info);
            if (mpvAvailable) return new MpvStreamListener(info);
        }

        IllegalStateException error = new IllegalStateException("Stream listener backend is not loaded");
        if (mpvError != null) error.addSuppressed(mpvError);
        if (vlcError != null) error.addSuppressed(vlcError);
        throw error;
    }

    @Override
    public long getProgress() {
        return delegate.getProgress();
    }

    @Override
    public void setProgress(long progress) {
        delegate.setProgress(progress);
    }

    @Override
    public boolean isPlaying() {
        return delegate.isPlaying();
    }

    @Override
    public void playing(Consumer<Boolean> playing) {
        delegate.playing(playing);
    }

    @Override
    public void stopped(Runnable stopped) {
        delegate.stopped(stopped);
    }

    @Override
    public void errored(Runnable errored) {
        delegate.errored(errored);
    }

    @Override
    public void timeout(Runnable timeout) {
        delegate.timeout(timeout);
    }

    @Override
    public void listen() {
        delegate.listen();
    }

    @Override
    public void cancel() {
        delegate.cancel();
    }
}
