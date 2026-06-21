package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.VideoPlayerMain;
import com.github.squi2rel.vp.provider.VideoInfo;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

public class VlcDecoder {
    private static final String HARDWARE_DECODING_FACTORY_OPTION = "--avcodec-hw=any";
    private static final String HARDWARE_DECODING_MEDIA_OPTION = ":avcodec-hw=any";
    private static final byte[] RV32 = {'R', 'V', '3', '2'};
    private static final int DROP_TOKEN = 0x7FFF_FFFF;
    private static final int[] MEDIA_PLAYER_EVENTS = {
            VlcLibrary.LIBVLC_MEDIA_PLAYER_PLAYING,
            VlcLibrary.LIBVLC_MEDIA_PLAYER_PAUSED,
            VlcLibrary.LIBVLC_MEDIA_PLAYER_STOPPED,
            VlcLibrary.LIBVLC_MEDIA_PLAYER_END_REACHED,
            VlcLibrary.LIBVLC_MEDIA_PLAYER_ENCOUNTERED_ERROR,
            VlcLibrary.LIBVLC_MEDIA_PLAYER_TIME_CHANGED,
            VlcLibrary.LIBVLC_MEDIA_PLAYER_LENGTH_CHANGED
    };

    private static Pointer instance;
    private static Throwable loadError;
    private static boolean loadAttempted;

    private final VlcLibrary.LibVlc lib;
    private final Pointer mediaPlayer;
    private final Pointer eventManager;
    private volatile Thread controlThread;
    private final ExecutorService controlExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "VideoPlayer-VLC-control");
        thread.setDaemon(true);
        controlThread = thread;
        return thread;
    });
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicBoolean released = new AtomicBoolean(false);
    private final TextureRenderCallback callback = new TextureRenderCallback();

    private final VlcLibrary.VideoLockCallback videoLockCallback = callback::lock;
    private final VlcLibrary.VideoUnlockCallback videoUnlockCallback = callback::unlock;
    private final VlcLibrary.VideoDisplayCallback videoDisplayCallback = callback::display;
    private final VlcLibrary.VideoFormatCallback videoFormatCallback = callback::setup;
    private final VlcLibrary.VideoCleanupCallback videoCleanupCallback = callback::cleanup;
    private final VlcLibrary.EventCallback eventCallback = this::handleEvent;

    private volatile int width = 1;
    private volatile int height = 1;
    private volatile long durationMs;
    private volatile float rate = 1f;
    private volatile boolean paused = true;

    public volatile long lastPlayTime;
    public volatile long lastPlayUpdateTime;

    private BiConsumer<Integer, Integer> sizeListener = (a, b) -> {};
    private Runnable playListener = () -> {}, finishListener = () -> {};

    public VlcDecoder() {
        if (!ensureLoaded()) {
            throw new IllegalStateException("VLC is not available", loadError);
        }
        lib = VlcLibrary.get();
        mediaPlayer = lib.libvlc_media_player_new(instance);
        if (mediaPlayer == null) {
            throw new IllegalStateException("libvlc_media_player_new returned null: " + VlcLibrary.errmsg(lib));
        }
        lib.libvlc_video_set_format_callbacks(mediaPlayer, videoFormatCallback, videoCleanupCallback);
        lib.libvlc_video_set_callbacks(mediaPlayer, videoLockCallback, videoUnlockCallback, videoDisplayCallback, null);
        eventManager = lib.libvlc_media_player_event_manager(mediaPlayer);
        if (eventManager != null) {
            for (int event : MEDIA_PLAYER_EVENTS) {
                int result = lib.libvlc_event_attach(eventManager, event, eventCallback, null);
                if (result != 0) {
                    VideoPlayerMain.LOGGER.warn("Failed to attach VLC event {}", event);
                }
            }
        }
    }

    public static synchronized void load() {
        if (!ensureLoaded()) {
            throw new IllegalStateException("VLC is not available", loadError);
        }
    }

    public static synchronized boolean ensureLoaded() {
        if (instance != null) return true;
        if (loadAttempted && loadError != null) return false;
        loadAttempted = true;
        try {
            VideoPlayerMain.LOGGER.info("loading VLC library");
            List<String> options = new ArrayList<>();
            options.add("--audio-filter=scaletempo");
            options.add(HARDWARE_DECODING_FACTORY_OPTION);
            instance = VlcLibrary.createInstance(options);
            loadError = null;
            VideoPlayerMain.LOGGER.info("loaded VLC library");
            return true;
        } catch (Throwable t) {
            loadError = t;
            VideoPlayerMain.LOGGER.warn("VLC library is not available", t);
            return false;
        }
    }

    public static synchronized boolean isAvailable() {
        return ensureLoaded();
    }

    public static synchronized Throwable loadError() {
        ensureLoaded();
        return loadError;
    }

    public static synchronized void resetLoadState() {
        Pointer oldInstance = instance;
        instance = null;
        loadError = null;
        loadAttempted = false;
        if (oldInstance != null) {
            try {
                VlcLibrary.releaseInstance(oldInstance);
            } catch (RuntimeException e) {
                VideoPlayerMain.LOGGER.warn("Failed to release VLC instance", e);
            }
        }
        VlcLibrary.resetLoadState();
    }

    public void onSizeChanged(BiConsumer<Integer, Integer> sizeListener) {
        this.sizeListener = sizeListener == null ? (a, b) -> {} : sizeListener;
    }

    public void onFinish(Runnable finishListener) {
        this.finishListener = finishListener == null ? () -> {} : finishListener;
    }

    public void onPlay(Runnable playListener) {
        this.playListener = playListener == null ? () -> {} : playListener;
    }

    public void submit(Runnable r) {
        if (released.get() || r == null) return;
        try {
            controlExecutor.execute(() -> {
                if (!released.get()) r.run();
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    public void init(VideoInfo info) {
        if (released.get()) throw new IllegalStateException("decoder already released");
        stopped.set(false);
        paused = true;
        rate = 1f;
        durationMs = 0;
        lastPlayTime = 0;
        lastPlayUpdateTime = System.currentTimeMillis();
        submit(() -> startPlayback(info));
    }

    private void startPlayback(VideoInfo info) {
        if (released.get()) return;
        Pointer media = null;
        try {
            media = VlcLibrary.createMedia(instance, info.path(), withHardwareDecoding(VideoParams.vlcOptions(info.params())));
            lib.libvlc_media_player_set_media(mediaPlayer, media);
            int result = lib.libvlc_media_player_play(mediaPlayer);
            if (result != 0) {
                throw new IllegalStateException("libvlc_media_player_play failed: " + VlcLibrary.errmsg(lib));
            }
            if (released.get() || stopped.get()) {
                try {
                    lib.libvlc_media_player_stop(mediaPlayer);
                } catch (RuntimeException ignored) {
                }
            }
        } catch (Throwable t) {
            if (!released.get()) {
                VideoPlayerMain.LOGGER.warn("Failed to start VLC playback", t);
                stopped.set(true);
            }
        } finally {
            if (media != null) {
                lib.libvlc_media_release(media);
            }
        }
    }

    private static String[] withHardwareDecoding(String[] params) {
        if (params == null || params.length == 0) {
            return new String[] {HARDWARE_DECODING_MEDIA_OPTION};
        }
        for (String param : params) {
            if (param != null && param.contains("avcodec-hw=")) {
                return params;
            }
        }
        String[] result = new String[params.length + 1];
        result[0] = HARDWARE_DECODING_MEDIA_OPTION;
        System.arraycopy(params, 0, result, 1, params.length);
        return result;
    }

    public DecodedFrame decodeNextFrame() {
        return callback.poll();
    }

    public void cleanup() {
        if (!released.compareAndSet(false, true)) return;
        stopped.set(true);
        paused = true;
        Runnable releaseTask = () -> {
            detachEvents();
            try {
                lib.libvlc_media_player_stop(mediaPlayer);
            } catch (RuntimeException e) {
                VideoPlayerMain.LOGGER.warn("Failed to stop VLC media player", e);
            }
            disableVideoCallbacks();
            try {
                lib.libvlc_media_player_release(mediaPlayer);
            } catch (RuntimeException e) {
                VideoPlayerMain.LOGGER.warn("Failed to release VLC media player", e);
            }
        };
        boolean nativeReleased = false;
        try {
            if (Thread.currentThread() == controlThread) {
                releaseTask.run();
            } else {
                Future<?> release = controlExecutor.submit(releaseTask);
                release.get(5, TimeUnit.SECONDS);
            }
            nativeReleased = true;
        } catch (RejectedExecutionException ignored) {
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            VideoPlayerMain.LOGGER.warn("Interrupted while releasing VLC media player", e);
        } catch (Exception e) {
            VideoPlayerMain.LOGGER.warn("Timed out while releasing VLC media player", e);
        } finally {
            if (nativeReleased) {
                clearBuffers();
            }
            controlExecutor.shutdown();
        }
    }

    public void stop() {
        if (released.get() || !stopped.compareAndSet(false, true)) return;
        paused = true;
        submit(() -> {
            try {
                lib.libvlc_media_player_stop(mediaPlayer);
            } catch (RuntimeException e) {
                VideoPlayerMain.LOGGER.warn("Failed to stop VLC media player", e);
            } finally {
                clearBuffers();
            }
        });
    }

    private void detachEvents() {
        if (eventManager == null) return;
        for (int event : MEDIA_PLAYER_EVENTS) {
            try {
                lib.libvlc_event_detach(eventManager, event, eventCallback, null);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void disableVideoCallbacks() {
        try {
            lib.libvlc_video_set_callbacks(mediaPlayer, null, null, null, null);
        } catch (RuntimeException ignored) {
        }
        try {
            lib.libvlc_video_set_format_callbacks(mediaPlayer, null, null);
        } catch (RuntimeException ignored) {
        }
    }

    private void clearBuffers() {
        callback.clear();
    }

    public boolean canPause() {
        if (released.get()) return false;
        try {
            return lib.libvlc_media_player_can_pause(mediaPlayer) != 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public void pause(boolean pause) {
        if (released.get()) return;
        paused = pause;
        updateProgress(safeTime());
        submit(() -> lib.libvlc_media_player_set_pause(mediaPlayer, pause ? 1 : 0));
    }

    public boolean isPaused() {
        if (released.get()) return true;
        try {
            return paused || lib.libvlc_media_player_is_playing(mediaPlayer) == 0;
        } catch (RuntimeException e) {
            return true;
        }
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public void setVolume(int volume) {
        if (released.get()) return;
        int clamped = Math.clamp(volume, 0, 100);
        submit(() -> {
            int result = lib.libvlc_audio_set_volume(mediaPlayer, clamped);
            if (result != 0) {
                VideoPlayerMain.LOGGER.warn("Failed to set VLC volume: {}", VlcLibrary.errmsg(lib));
            }
        });
    }

    public boolean canSetProgress() {
        if (released.get()) return false;
        try {
            return lib.libvlc_media_player_is_seekable(mediaPlayer) != 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public void setProgress(long progress) {
        if (released.get()) return;
        long target = Math.max(0, progress);
        updateProgress(target);
        submit(() -> lib.libvlc_media_player_set_time(mediaPlayer, target));
    }

    public long getProgress() {
        if (lastPlayTime == 0) return 0;
        return paused ? lastPlayTime : System.currentTimeMillis() - lastPlayUpdateTime + lastPlayTime;
    }

    public long getTotalProgress() {
        if (released.get()) return 0;
        long known = durationMs;
        if (known > 0) return known;
        try {
            long length = lib.libvlc_media_player_get_length(mediaPlayer);
            return Math.max(0, length);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    public void setRate(float rate) {
        if (released.get()) return;
        if (!Float.isFinite(rate) || rate <= 0) return;
        this.rate = rate;
        submit(() -> {
            int result = lib.libvlc_media_player_set_rate(mediaPlayer, rate);
            if (result != 0) {
                VideoPlayerMain.LOGGER.warn("Failed to set VLC rate: {}", VlcLibrary.errmsg(lib));
            }
        });
    }

    public float getRate() {
        if (released.get()) return 1f;
        try {
            float nativeRate = lib.libvlc_media_player_get_rate(mediaPlayer);
            if (Float.isFinite(nativeRate) && nativeRate > 0) {
                rate = nativeRate;
                return nativeRate;
            }
        } catch (RuntimeException ignored) {
        }
        return rate;
    }

    private void handleEvent(Pointer event, Pointer userData) {
        if (event == null || released.get()) return;
        int type = event.getInt(0);
        switch (type) {
            case VlcLibrary.LIBVLC_MEDIA_PLAYER_PLAYING -> {
                if (released.get() || stopped.get()) return;
                paused = false;
                refreshProperties();
                playListener.run();
            }
            case VlcLibrary.LIBVLC_MEDIA_PLAYER_PAUSED -> {
                paused = true;
                refreshProperties();
            }
            case VlcLibrary.LIBVLC_MEDIA_PLAYER_TIME_CHANGED -> refreshProperties();
            case VlcLibrary.LIBVLC_MEDIA_PLAYER_LENGTH_CHANGED -> refreshDuration();
            case VlcLibrary.LIBVLC_MEDIA_PLAYER_END_REACHED -> finishPlayback();
            case VlcLibrary.LIBVLC_MEDIA_PLAYER_ENCOUNTERED_ERROR -> stop();
            default -> {
            }
        }
    }

    private void finishPlayback() {
        if (released.get() || stopped.get()) return;
        stopped.set(true);
        paused = true;
        finishListener.run();
    }

    private void refreshProperties() {
        long time = safeTime();
        if (time >= 0) updateProgress(time);
        refreshDuration();
    }

    private long safeTime() {
        try {
            return lib.libvlc_media_player_get_time(mediaPlayer);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private void refreshDuration() {
        try {
            long length = lib.libvlc_media_player_get_length(mediaPlayer);
            if (length > 0) durationMs = length;
        } catch (RuntimeException ignored) {
        }
    }

    private void updateProgress(long progress) {
        if (progress < 0) return;
        lastPlayTime = progress;
        lastPlayUpdateTime = System.currentTimeMillis();
    }

    public final class DecodedFrame implements AutoCloseable {
        private final int index;
        private final int generation;
        private final long id;
        private final ByteBuffer buffer;
        private boolean closed;

        private DecodedFrame(int index, int generation, long id, ByteBuffer buffer) {
            this.index = index;
            this.generation = generation;
            this.id = id;
            this.buffer = buffer;
        }

        public long id() {
            return id;
        }

        public ByteBuffer buffer() {
            return buffer;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            callback.release(index, generation);
        }
    }

    private final class TextureRenderCallback {
        private static final int BUFFER_COUNT = 3;
        private final ByteBuffer[] buffers = new ByteBuffer[BUFFER_COUNT];
        private final Pointer[] pointers = new Pointer[BUFFER_COUNT];
        private final boolean[] inUse = new boolean[BUFFER_COUNT];

        private ByteBuffer dropBuffer;
        private Pointer dropPointer;
        private int frameWidth = 1;
        private int frameHeight = 1;
        private int bufferSize = 4;
        private int nextWrite = 0;
        private int writing = -1;
        private int latest = -1;
        private int generation = 0;
        private long latestFrameId = 0;
        private long deliveredFrameId = 0;

        private synchronized int setup(com.sun.jna.ptr.PointerByReference opaque, Pointer chroma, Pointer widthPointer, Pointer heightPointer,
                                       Pointer pitches, Pointer lines) {
            int sourceWidth = widthPointer.getInt(0);
            int sourceHeight = heightPointer.getInt(0);
            if (sourceWidth <= 0 || sourceHeight <= 0) return 0;
            chroma.write(0, RV32, 0, RV32.length);
            pitches.setInt(0, sourceWidth * 4);
            lines.setInt(0, sourceHeight);
            if (!matches(sourceWidth, sourceHeight)) {
                resize(sourceWidth, sourceHeight);
            }
            return 1;
        }

        private synchronized void cleanup(Pointer opaque) {
            clear();
        }

        private synchronized Pointer lock(Pointer opaque, Pointer planes) {
            if (buffers[0] == null || bufferSize <= 0) {
                ensureDropBuffer();
                planes.setPointer(0, dropPointer);
                return Pointer.createConstant(DROP_TOKEN);
            }
            int index = acquireWriteBuffer();
            if (index < 0) {
                ensureDropBuffer();
                planes.setPointer(0, dropPointer);
                return Pointer.createConstant(DROP_TOKEN);
            }
            writing = index;
            planes.setPointer(0, pointers[index]);
            return Pointer.createConstant(index + 1L);
        }

        private synchronized void unlock(Pointer opaque, Pointer picture, Pointer planes) {
        }

        private synchronized void display(Pointer opaque, Pointer picture) {
            long token = VlcLibrary.pointerValue(picture);
            if (token == DROP_TOKEN) return;
            int index = (int) token - 1;
            if (index < 0 || index >= BUFFER_COUNT) return;
            if (writing == index) writing = -1;
            if (stopped.get() || released.get() || buffers[index] == null) return;
            latest = index;
            latestFrameId++;
        }

        private boolean matches(int width, int height) {
            return buffers[0] != null && frameWidth == width && frameHeight == height;
        }

        private void resize(int width, int height) {
            frameWidth = width;
            frameHeight = height;
            bufferSize = width * height * 4;
            for (int i = 0; i < BUFFER_COUNT; i++) {
                buffers[i] = BufferUtils.createByteBuffer(bufferSize);
                pointers[i] = Native.getDirectBufferPointer(buffers[i]);
                inUse[i] = false;
            }
            dropBuffer = BufferUtils.createByteBuffer(bufferSize);
            dropPointer = Native.getDirectBufferPointer(dropBuffer);
            nextWrite = 0;
            writing = -1;
            latest = -1;
            latestFrameId = 0;
            deliveredFrameId = 0;
            generation++;
            VlcDecoder.this.width = width;
            VlcDecoder.this.height = height;
            sizeListener.accept(width, height);
        }

        private synchronized void clear() {
            for (int i = 0; i < BUFFER_COUNT; i++) {
                buffers[i] = null;
                pointers[i] = null;
                inUse[i] = false;
            }
            dropBuffer = null;
            dropPointer = null;
            bufferSize = 0;
            nextWrite = 0;
            writing = -1;
            latest = -1;
            latestFrameId = 0;
            deliveredFrameId = 0;
            generation++;
        }

        private synchronized DecodedFrame poll() {
            if (stopped.get() || released.get()) return null;
            if (latest < 0 || latest == writing || latestFrameId == deliveredFrameId) return null;
            ByteBuffer source = buffers[latest];
            if (source == null) return null;

            int index = latest;
            inUse[index] = true;
            deliveredFrameId = latestFrameId;

            ByteBuffer view = source.asReadOnlyBuffer();
            view.position(0);
            view.limit(bufferSize);
            return new DecodedFrame(index, generation, latestFrameId, view);
        }

        private synchronized void release(int index, int frameGeneration) {
            if (frameGeneration != generation) return;
            if (index >= 0 && index < inUse.length) {
                inUse[index] = false;
            }
        }

        private int acquireWriteBuffer() {
            for (int i = 0; i < BUFFER_COUNT; i++) {
                int index = (nextWrite + i) % BUFFER_COUNT;
                if (!inUse[index] && index != writing) {
                    nextWrite = (index + 1) % BUFFER_COUNT;
                    return index;
                }
            }
            return -1;
        }

        private void ensureDropBuffer() {
            if (dropBuffer != null && dropPointer != null && dropBuffer.capacity() == Math.max(1, bufferSize)) return;
            int size = Math.max(4, bufferSize);
            dropBuffer = BufferUtils.createByteBuffer(size);
            dropPointer = Native.getDirectBufferPointer(dropBuffer);
        }
    }
}
