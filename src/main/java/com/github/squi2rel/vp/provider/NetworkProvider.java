package com.github.squi2rel.vp.provider;

import com.github.squi2rel.vp.i18n.VpTranslation;
import com.github.squi2rel.vp.video.IVideoListener;
import com.github.squi2rel.vp.video.StreamListener;
import com.github.squi2rel.vp.video.VideoParams;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.net.InetAddress;

public class NetworkProvider implements IVideoProvider {
    private final Function<VideoInfo, IVideoListener> listenerFactory;
    private final MediaAddressPolicy.HostResolver hostResolver;

    public NetworkProvider() {
        this(StreamListener::new, InetAddress::getAllByName);
    }

    NetworkProvider(Function<VideoInfo, IVideoListener> listenerFactory) {
        this(listenerFactory, InetAddress::getAllByName);
    }

    NetworkProvider(Function<VideoInfo, IVideoListener> listenerFactory,
                    MediaAddressPolicy.HostResolver hostResolver) {
        this.listenerFactory = listenerFactory;
        this.hostResolver = hostResolver;
    }

    @Override
    public @Nullable CompletableFuture<VideoInfo> from(String str, IProviderSource source) {
        String path = str == null ? "" : str.trim();
        if (path.length() < 2) return null;
        char first = path.charAt(0);
        if (first == '/' || first == '\\' || first == '.' || path.charAt(1) == ':') return null;
        if (!MediaAddressPolicy.isSyntacticallyAllowed(path)) return null;
        StreamResolutionFuture future = new StreamResolutionFuture();
        Thread worker = new Thread(() -> resolve(path, source, future), "VideoPlayer-stream-resolver");
        worker.setDaemon(true);
        future.bindWorker(worker);
        worker.start();
        return future;
    }

    private void resolve(String path, IProviderSource source, StreamResolutionFuture future) {
        try {
            if (future.isCancelled()) return;
            if (!MediaAddressPolicy.isAllowed(path, hostResolver)) {
                source.reply(VpTranslation.of("error.videoplayer.stream_address_not_allowed", "Video stream address is not allowed"));
                future.complete(null);
                return;
            }
            source.reply(VpTranslation.of("message.videoplayer.resolving_stream", "Resolving video stream"));
            StreamInfo info = getStreamInfo(path, future);
            if (future.isCancelled()) return;
            if (info == null) {
                source.reply(VpTranslation.of("message.videoplayer.resolve_stream_failed", "Failed to resolve video stream"));
                future.complete(null);
                return;
            }
            String[] params = VideoParams.looksAudioOnlyPath(path) ? VideoParams.audioOnlyParams() : NO_PARAMS;
            future.complete(new VideoInfo(source.name(), info.name, path, "", -1, info.seekable, params));
        } catch (CancellationException ignored) {
            future.cancel(false);
        } catch (Throwable error) {
            if (!future.isCancelled()) future.completeExceptionally(error);
        } finally {
            future.clearWorker(Thread.currentThread());
        }
    }

    private @Nullable StreamInfo getStreamInfo(String mrl, StreamResolutionFuture future) {
        IVideoListener listener = listenerFactory.apply(new VideoInfo(null, null, mrl, null, -1, false, NO_PARAMS));
        future.bindListener(listener);
        CompletableFuture<Boolean> lock = new CompletableFuture<>();
        try {
            if (future.isCancelled()) return null;
            listener.timeout(() -> lock.complete(null));
            listener.errored(() -> lock.complete(null));
            listener.playing(lock::complete);
            listener.listen();
            Boolean b = lock.get();
            if (future.isCancelled() || b == null) return null;
            return new StreamInfo(getName(mrl), b);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception error) {
            return null;
        } finally {
            future.clearListener(listener);
            cancelListener(listener);
        }
    }

    private static void cancelListener(IVideoListener listener) {
        try {
            listener.cancel();
        } catch (Throwable ignored) {
        }
    }

    private static String getName(String mrl) {
        String path = mrl.toLowerCase();
        String name = "Unknown Stream";
        if (path.startsWith("http") && path.contains(".m3u8")) {
            name = "HLS Stream";
        } else if (path.startsWith("rtsp://") || path.startsWith("rtsps://") || path.startsWith("rtspt://")) {
            name = "RTSP Stream";
        } else if (path.startsWith("http")) {
            name = "HTTP Stream";
        } else if (path.startsWith("rtp://")) {
            name = "RTP Stream";
        } else if (path.startsWith("mms://")) {
            name = "MMS Stream";
        }
        return name;
    }

    private record StreamInfo(String name, boolean seekable) {
    }

    private static final class StreamResolutionFuture extends CompletableFuture<VideoInfo> {
        private final AtomicReference<Thread> worker = new AtomicReference<>();
        private final AtomicReference<IVideoListener> listener = new AtomicReference<>();

        void bindWorker(Thread thread) {
            worker.set(thread);
            if (isCancelled()) thread.interrupt();
        }

        void clearWorker(Thread thread) {
            worker.compareAndSet(thread, null);
        }

        void bindListener(IVideoListener value) {
            listener.set(value);
            if (isCancelled() && listener.compareAndSet(value, null)) cancelListener(value);
        }

        void clearListener(IVideoListener value) {
            listener.compareAndSet(value, null);
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            if (cancelled) {
                IVideoListener activeListener = listener.getAndSet(null);
                if (activeListener != null) cancelListener(activeListener);
                Thread activeWorker = worker.get();
                if (activeWorker != null) activeWorker.interrupt();
            }
            return cancelled;
        }
    }
}
