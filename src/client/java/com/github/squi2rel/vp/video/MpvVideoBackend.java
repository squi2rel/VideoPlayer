package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.VideoPlayerMain;
import com.github.squi2rel.vp.VideoPlayerClient;
import com.github.squi2rel.vp.filtergraph.MpvFilterGraphManager;
import com.github.squi2rel.vp.filtergraph.MpvLavfiFilterCatalog;
import com.github.squi2rel.vp.provider.VideoInfo;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import static com.github.squi2rel.vp.video.MpvLibrary.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL14.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL21.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL32.*;
import static org.lwjgl.system.MemoryUtil.NULL;

public class MpvVideoBackend implements VideoBackend {
    private static final int INITIAL_SIZE = 1;
    private static final int PROPERTY_POLL_INTERVAL_MS = 100;
    private static final int SHARED_TEXTURE_COUNT = 3;
    private static final int SINGLE_CONTEXT_TEXTURE_INDEX = 0;
    private static final Set<MpvVideoBackend> ACTIVE_BACKENDS = ConcurrentHashMap.newKeySet();

    private final LibMpv lib;
    private final boolean singleContext = VideoPlayerMain.android;
    private final BiConsumer<Integer, Integer> sizeListener;
    private final LinkedBlockingQueue<MpvTask> tasks = new LinkedBlockingQueue<>();
    private final AtomicBoolean released = new AtomicBoolean(false);
    private final AtomicBoolean renderUpdate = new AtomicBoolean(false);
    private final AtomicBoolean renderThreadStopped = new AtomicBoolean(true);
    private final Object renderLock = new Object();
    private final Object publishLock = new Object();
    private final MpvProgressClock progressClock = new MpvProgressClock();

    private Thread eventThread;
    private Thread renderThread;
    private volatile Pointer handle;
    private volatile Pointer renderContext;
    private volatile long sharedWindow = NULL;
    private MpvOpenGLProcAddressCallback glProcCallback;
    private MpvOpenGLInitParams glInitParams;
    private MpvRenderUpdateCallback updateCallback;

    private volatile int pendingWidth = INITIAL_SIZE;
    private volatile int pendingHeight = INITIAL_SIZE;
    private volatile int width = INITIAL_SIZE;
    private volatile int height = INITIAL_SIZE;
    private volatile int displayTextureId = -1;
    private volatile int publishedTextureId = -1;
    private long pendingReadySync = NULL;
    private final int[] textureIds = {-1, -1, -1};
    private final int[] fboIds = {-1, -1, -1};
    private int renderTextureIndex;

    private volatile boolean loaded;
    private volatile boolean paused = true;
    private volatile boolean seekable = true;
    private volatile boolean renderFailed;
    private volatile long targetTime = -1;
    private volatile int volume = 100;
    private volatile boolean currentVideoInputAvailable = true;
    private volatile boolean currentAudioInputAvailable = true;
    private VideoInfo pendingInfo;
    private long pendingTargetTime = -1;
    private int pendingVolume = 100;
    private boolean pendingPlay;

    public MpvVideoBackend(BiConsumer<Integer, Integer> sizeListener) {
        this.lib = MpvLibrary.get();
        this.sizeListener = sizeListener;
    }

    public static boolean isAvailable() {
        return MpvLibrary.isAvailable();
    }

    public static Throwable loadError() {
        return MpvLibrary.loadError();
    }

    public static void resetAvailability() {
        MpvLibrary.resetLoadState();
        MpvLavfiFilterCatalog.reset();
    }

    public static int applyLavfiComplexToAll(String graph) {
        String safeGraph = graph == null ? "" : graph;
        int count = 0;
        for (MpvVideoBackend backend : ACTIVE_BACKENDS) {
            if (backend.released.get()) continue;
            backend.submit(ctx -> backend.setString(ctx, "lavfi-complex", backend.lavfiComplexForCurrentMedia(safeGraph)));
            count++;
        }
        return count;
    }

    @Override
    public String name() {
        return VideoBackends.MPV;
    }

    @Override
    public void init() {
        CompletableFuture<Pointer> created = new CompletableFuture<>();
        eventThread = new Thread(() -> eventLoop(created), "VideoPlayer-MPV");
        eventThread.setDaemon(true);
        eventThread.start();

        handle = created.join();
        ACTIVE_BACKENDS.add(this);
        if (singleContext) {
            renderThreadStopped.set(true);
            notifySize(width, height);
            return;
        }

        sharedWindow = createSharedWindow();
        CompletableFuture<Void> rendererReady = new CompletableFuture<>();
        renderThreadStopped.set(false);
        renderThread = new Thread(() -> renderLoop(rendererReady), "VideoPlayer-MPV-GL");
        renderThread.setDaemon(true);
        renderThread.start();
        rendererReady.join();
        notifySize(width, height);
    }

    @Override
    public void play(VideoInfo info, long targetTime, int volume) {
        renderFailed = false;
        loaded = false;
        synchronized (this) {
            pendingInfo = info;
            pendingTargetTime = targetTime;
            pendingVolume = volume;
            pendingPlay = true;
        }
        flushPendingPlay();
    }

    private void startPlayback(Pointer ctx, VideoInfo info, long targetTime, int volume) {
        if (released.get()) return;
        this.targetTime = targetTime;
        this.volume = volume;
        loaded = false;
        paused = false;
        progressClock.reset(false);
        pendingWidth = INITIAL_SIZE;
        pendingHeight = INITIAL_SIZE;
        MediaInputs inputs = mediaInputs(info);
        currentVideoInputAvailable = inputs.video();
        currentAudioInputAvailable = inputs.audio();
        String path = info.path().replace("rtspt://", "rtsp://");
        String graph = MpvFilterGraphManager.currentLavfiComplexForPlayback(inputs.video(), inputs.audio());
        String loadOptions = VideoParams.mpvLoadOptions(info.params(), configuredProxy(), configuredYtdlPath(), graph);
        setDouble(ctx, "volume", volume);
        setFlag(ctx, "pause", false);
        loadFile(ctx, path, loadOptions);
        if (released.get()) {
            stopNativePlayback(ctx);
        }
    }

    private static String configuredProxy() {
        return VideoPlayerClient.config == null ? "" : VideoPlayerClient.config.nativeDownloadProxy;
    }

    private static String configuredYtdlPath() {
        return VideoPlayerClient.config == null ? "" : VideoPlayerClient.config.mpvYtdlPath;
    }

    @Override
    public void updateTexture() {
        if (singleContext) {
            updateTextureSingleContext();
            return;
        }

        long readySync;
        int readyTextureId;
        synchronized (publishLock) {
            readySync = pendingReadySync;
            readyTextureId = publishedTextureId;
            pendingReadySync = NULL;
        }
        if (readySync == NULL) return;

        glWaitSync(readySync, 0, GL_TIMEOUT_IGNORED);
        glDeleteSync(readySync);
        displayTextureId = readyTextureId;
    }

    private void updateTextureSingleContext() {
        if (released.get() || renderFailed || handle == null) return;
        try {
            ensureSingleContextRenderer();
            if (renderContext == null) return;

            boolean shouldRender = false;
            if (renderUpdate.getAndSet(false)) {
                long flags = lib.mpv_render_context_update(renderContext);
                shouldRender = (flags & MPV_RENDER_UPDATE_FRAME) != 0;
            }

            applyPendingSize();

            if (shouldRender || loaded) {
                renderFrameSingleContext();
            }
        } catch (Throwable t) {
            renderFailed = true;
            VideoPlayerMain.LOGGER.error("MPV single-context render failed; disabling MPV rendering.", t);
        }
    }

    private void ensureSingleContextRenderer() {
        if (renderContext != null) return;
        initTexture();
        createRenderContext();
        notifySize(width, height);
        flushPendingPlay();
    }

    @Override
    public int getTextureId() {
        return displayTextureId;
    }

    @Override
    public boolean hasVideoFrame() {
        return loaded && displayTextureId >= 0 && width > 1 && height > 1;
    }

    @Override
    public int getWidth() {
        return width;
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public void stop() {
        synchronized (this) {
            pendingInfo = null;
            pendingPlay = false;
        }
        loaded = false;
        paused = true;
        resetCurrentMediaInputs();
        progressClock.reset(true);
        submit(ctx -> command(ctx, "stop"));
    }

    @Override
    public boolean canPause() {
        return !released.get() && loaded;
    }

    @Override
    public void pause(boolean pause) {
        paused = pause;
        progressClock.setPaused(pause);
        submit(ctx -> setFlag(ctx, "pause", pause));
    }

    @Override
    public boolean isPaused() {
        return paused;
    }

    @Override
    public void setVolume(int volume) {
        this.volume = volume;
        submit(ctx -> setDouble(ctx, "volume", volume));
    }

    @Override
    public boolean canSetProgress() {
        return !released.get() && loaded && seekable;
    }

    @Override
    public void setProgress(long progress) {
        progressClock.seekTo(progress);
        submit(ctx -> seek(ctx, progress));
    }

    @Override
    public long getProgress() {
        return currentProgress();
    }

    @Override
    public long getTotalProgress() {
        return progressClock.durationMs();
    }

    @Override
    public void setRate(float rate) {
        progressClock.setRate(rate);
        submit(ctx -> setDouble(ctx, "speed", rate));
    }

    @Override
    public float getRate() {
        return progressClock.rate();
    }

    @Override
    public boolean isPostUpdate() {
        return singleContext;
    }

    @Override
    public void cleanup() {
        ACTIVE_BACKENDS.remove(this);
        if (singleContext) {
            renderThreadStopped.set(false);
            if (!released.compareAndSet(false, true)) {
                renderThreadStopped.set(true);
                return;
            }
            discardPendingPlayback();
            stopNativePlayback(handle);
            cleanupSingleContextRenderer();
            renderThreadStopped.set(true);
            Pointer ctx = handle;
            if (ctx != null) lib.mpv_wakeup(ctx);
            if (eventThread != null && eventThread != Thread.currentThread()) {
                try {
                    eventThread.join(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return;
        }

        if (!released.compareAndSet(false, true)) return;

        discardPendingPlayback();
        stopNativePlayback(handle);
        signalRenderThread();

        if (renderThread != null && renderThread != Thread.currentThread()) {
            try {
                renderThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        Pointer ctx = handle;
        if (ctx != null) lib.mpv_wakeup(ctx);
        if (eventThread != null && eventThread != Thread.currentThread()) {
            try {
                eventThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void discardPendingPlayback() {
        synchronized (this) {
            pendingInfo = null;
            pendingPlay = false;
        }
        loaded = false;
        paused = true;
        resetCurrentMediaInputs();
        progressClock.reset(true);
    }

    private void cleanupSingleContextRenderer() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.isOnThread()) {
            try {
                runSingleContextCleanup();
            } catch (Throwable t) {
                VideoPlayerMain.LOGGER.warn("Failed to clean up MPV single-context renderer", t);
            }
            return;
        }

        CompletableFuture<Void> cleaned = new CompletableFuture<>();
        client.execute(() -> {
            try {
                runSingleContextCleanup();
                cleaned.complete(null);
            } catch (Throwable t) {
                cleaned.completeExceptionally(t);
            }
        });
        try {
            cleaned.get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            VideoPlayerMain.LOGGER.warn("Failed or timed out while cleaning up MPV single-context renderer", e);
        }
    }

    private void runSingleContextCleanup() {
        try {
            freeRenderContext();
        } finally {
            cleanupTexture();
        }
    }

    private long currentProgress() {
        return progressClock.currentProgress();
    }

    private void eventLoop(CompletableFuture<Pointer> created) {
        Pointer ctx = null;
        try {
            ctx = lib.mpv_create();
            if (ctx == null) throw new IllegalStateException("mpv_create returned null");
            handle = ctx;
            setOptionString(ctx, "config", "no");
            setOptionString(ctx, "terminal", "no");
            setOptionString(ctx, "vo", "libmpv");
            setOptionString(ctx, "hwdec", "auto-safe");
            check(lib.mpv_initialize(ctx), "mpv_initialize");
            created.complete(ctx);

            long lastPoll = 0;
            while (!released.get() || !renderThreadStopped.get()) {
                runQueuedTasks(ctx);
                Pointer eventPointer = lib.mpv_wait_event(ctx, 0.02);
                if (eventPointer != null) {
                    handleEvent(ctx, new MpvEvent(eventPointer));
                }
                long now = System.currentTimeMillis();
                if (now - lastPoll >= PROPERTY_POLL_INTERVAL_MS) {
                    lastPoll = now;
                    refreshProperties(ctx);
                }
            }
        } catch (Throwable t) {
            created.completeExceptionally(t);
            if (!released.get()) {
                VideoPlayerMain.LOGGER.error("MPV backend stopped unexpectedly", t);
            }
        } finally {
            if (ctx != null) {
                try {
                    lib.mpv_terminate_destroy(ctx);
                } catch (RuntimeException e) {
                    VideoPlayerMain.LOGGER.warn("Failed to destroy MPV handle", e);
                }
            }
            handle = null;
        }
    }

    private void runQueuedTasks(Pointer ctx) {
        MpvTask task;
        while ((task = tasks.poll()) != null) {
            try {
                task.run(ctx);
            } catch (RuntimeException e) {
                if (!released.get()) {
                    VideoPlayerMain.LOGGER.warn("Failed to run MPV command", e);
                }
            }
        }
    }

    private void handleEvent(Pointer ctx, MpvEvent event) {
        switch (event.event_id) {
            case MPV_EVENT_NONE -> {
            }
            case MPV_EVENT_FILE_LOADED -> {
                if (released.get()) {
                    stopNativePlayback(ctx);
                    return;
                }
                loaded = true;
                refreshProperties(ctx);
                if (targetTime > 0) {
                    progressClock.seekTo(targetTime);
                    seek(ctx, targetTime);
                }
            }
            case MPV_EVENT_VIDEO_RECONFIG -> refreshProperties(ctx);
            case MPV_EVENT_END_FILE -> {
                loaded = false;
                paused = true;
                resetCurrentMediaInputs();
                progressClock.reset(true);
                if (event.data != null) {
                    MpvEventEndFile end = new MpvEventEndFile(event.data);
                    if (end.reason == MPV_END_FILE_REASON_EOF) {
                        pendingWidth = INITIAL_SIZE;
                        pendingHeight = INITIAL_SIZE;
                    } else if (end.error < 0) {
                        VideoPlayerMain.LOGGER.warn("MPV ended playback with error {}: {}", end.error, lib.mpv_error_string(end.error));
                    }
                }
            }
            case MPV_EVENT_SHUTDOWN -> {
                released.set(true);
                signalRenderThread();
            }
            default -> {
            }
        }
    }

    private void refreshProperties(Pointer ctx) {
        Double dwidth = getDouble(ctx, "dwidth");
        Double dheight = getDouble(ctx, "dheight");
        if (dwidth != null && dheight != null && dwidth > 0 && dheight > 0) {
            int nextWidth = Math.max(1, dwidth.intValue());
            int nextHeight = Math.max(1, dheight.intValue());
            if (nextWidth != pendingWidth || nextHeight != pendingHeight) {
                pendingWidth = nextWidth;
                pendingHeight = nextHeight;
                signalRenderThread();
            }
        }

        Double duration = getDouble(ctx, "duration");
        if (duration != null && duration > 0) {
            progressClock.setDurationMs(Math.round(duration * 1000));
        }

        Double timePos = getDouble(ctx, "time-pos");
        if (timePos != null && timePos >= 0) {
            progressClock.updateFromTimePos(timePos);
        }

        Boolean pause = getFlag(ctx, "pause");
        if (pause != null) {
            paused = pause;
            progressClock.setPaused(pause);
        }

        Boolean canSeek = getFlag(ctx, "seekable");
        if (canSeek != null) seekable = canSeek;

        Double speed = getDouble(ctx, "speed");
        if (speed != null && speed > 0) progressClock.setRate(speed.floatValue());
    }

    private void submit(MpvTask task) {
        if (released.get()) return;
        tasks.offer(task);
        Pointer ctx = handle;
        if (ctx != null) lib.mpv_wakeup(ctx);
    }

    private String lavfiComplexForCurrentMedia(String graph) {
        if (currentVideoInputAvailable && currentAudioInputAvailable) return graph == null ? "" : graph;
        return MpvFilterGraphManager.currentLavfiComplexForPlayback(currentVideoInputAvailable, currentAudioInputAvailable);
    }

    private void resetCurrentMediaInputs() {
        currentVideoInputAvailable = true;
        currentAudioInputAvailable = true;
    }

    private static MediaInputs mediaInputs(VideoInfo info) {
        if (info == null) return new MediaInputs(true, true);
        boolean audioOnly = VideoParams.isAudioOnly(info.params())
                || VideoParams.looksAudioOnlyPath(info.path())
                || VideoParams.looksAudioOnlyPath(info.rawPath());
        boolean videoOnly = VideoParams.isVideoOnly(info.params());
        return new MediaInputs(!audioOnly, !videoOnly);
    }

    private record MediaInputs(boolean video, boolean audio) {
    }

    private void seek(Pointer ctx, long progress) {
        command(ctx, "seek", Double.toString(Math.max(0, progress) / 1000.0), "absolute", "exact");
    }

    private long createSharedWindow() {
        long share = MinecraftClient.getInstance().getWindow().getHandle();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, glfwGetWindowAttrib(share, GLFW_CONTEXT_VERSION_MAJOR));
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, glfwGetWindowAttrib(share, GLFW_CONTEXT_VERSION_MINOR));
        glfwWindowHint(GLFW_OPENGL_PROFILE, glfwGetWindowAttrib(share, GLFW_OPENGL_PROFILE));
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, glfwGetWindowAttrib(share, GLFW_OPENGL_FORWARD_COMPAT));
        glfwWindowHint(GLFW_OPENGL_DEBUG_CONTEXT, glfwGetWindowAttrib(share, GLFW_OPENGL_DEBUG_CONTEXT));

        long window = glfwCreateWindow(1, 1, "VideoPlayer MPV", NULL, share);
        glfwDefaultWindowHints();
        if (window == NULL) {
            throw new IllegalStateException("Failed to create shared MPV OpenGL context");
        }
        return window;
    }

    private void renderLoop(CompletableFuture<Void> ready) {
        try {
            glfwMakeContextCurrent(sharedWindow);
            GL.createCapabilities();
            initTexture();
            createRenderContext();
            notifySize(width, height);
            ready.complete(null);
            flushPendingPlay();

            while (!released.get() && !renderFailed) {
                boolean shouldRender = false;
                if (renderUpdate.getAndSet(false)) {
                    long flags = lib.mpv_render_context_update(renderContext);
                    shouldRender = (flags & MPV_RENDER_UPDATE_FRAME) != 0;
                }

                applyPendingSize();

                if (shouldRender) {
                    renderFrame();
                }

                synchronized (renderLock) {
                    if (!released.get() && !renderFailed && !renderUpdate.get()) {
                        renderLock.wait(16);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (!ready.isDone()) ready.completeExceptionally(e);
        } catch (Throwable t) {
            renderFailed = true;
            if (!ready.isDone()) {
                ready.completeExceptionally(t);
            } else if (!released.get()) {
                VideoPlayerMain.LOGGER.error("MPV render thread failed; disabling MPV rendering.", t);
            }
        } finally {
            freeRenderContext();
            cleanupTexture();
            GL.setCapabilities(null);
            glfwMakeContextCurrent(NULL);
            long window = sharedWindow;
            sharedWindow = NULL;
            if (window != NULL) {
                glfwDestroyWindow(window);
            }
            renderThreadStopped.set(true);
            signalRenderThread();
            Pointer ctx = handle;
            if (ctx != null) lib.mpv_wakeup(ctx);
        }
    }

    private void notifySize(int w, int h) {
        MinecraftClient.getInstance().execute(() -> sizeListener.accept(w, h));
    }

    private void signalRenderThread() {
        synchronized (renderLock) {
            renderLock.notifyAll();
        }
    }

    private void freeRenderContext() {
        Pointer ctx = renderContext;
        if (ctx == null) return;
        try {
            lib.mpv_render_context_set_update_callback(ctx, null, null);
            lib.mpv_render_context_free(ctx);
        } catch (RuntimeException e) {
            VideoPlayerMain.LOGGER.warn("Failed to free MPV render context", e);
        } finally {
            renderContext = null;
        }
    }

    private void flushPendingPlay() {
        if (renderContext == null || released.get()) return;

        VideoInfo info;
        long startTime;
        int startVolume;
        synchronized (this) {
            if (!pendingPlay || pendingInfo == null) return;
            info = pendingInfo;
            startTime = pendingTargetTime;
            startVolume = pendingVolume;
            pendingInfo = null;
            pendingPlay = false;
        }

        submit(ctx -> startPlayback(ctx, info, startTime, startVolume));
    }

    private void createRenderContext() {
        glProcCallback = (context, name) -> {
            long address = GL.getFunctionProvider().getFunctionAddress(name);
            return isInvalidProcAddress(address) ? null : Pointer.createConstant(address);
        };

        glInitParams = new MpvOpenGLInitParams();
        glInitParams.get_proc_address = glProcCallback;
        glInitParams.get_proc_address_ctx = null;
        glInitParams.write();

        Memory apiType = utf8("opengl");
        Memory advancedControl = intMemory(1);

        MpvRenderParam[] params = renderParams(4);
        params[0].type = MPV_RENDER_PARAM_API_TYPE;
        params[0].data = apiType;
        params[1].type = MPV_RENDER_PARAM_OPENGL_INIT_PARAMS;
        params[1].data = glInitParams.getPointer();
        params[2].type = MPV_RENDER_PARAM_ADVANCED_CONTROL;
        params[2].data = advancedControl;
        params[3].type = MPV_RENDER_PARAM_INVALID;
        params[3].data = null;
        writeParams(params);

        PointerByReference result = new PointerByReference();
        check(lib.mpv_render_context_create(result, handle, params[0].getPointer()), "mpv_render_context_create");
        renderContext = result.getValue();
        if (renderContext == null) throw new IllegalStateException("mpv_render_context_create returned null");

        updateCallback = callbackCtx -> {
            renderUpdate.set(true);
            signalRenderThread();
        };
        lib.mpv_render_context_set_update_callback(renderContext, updateCallback, null);
    }

    private void initTexture() {
        for (int i = 0; i < textureCount(); i++) {
            textureIds[i] = glGenTextures();
            fboIds[i] = glGenFramebuffers();
        }
        resizeTexture(INITIAL_SIZE, INITIAL_SIZE);
    }

    private void applyPendingSize() {
        int targetWidth = Math.max(1, pendingWidth);
        int targetHeight = Math.max(1, pendingHeight);
        if (targetWidth == width && targetHeight == height) return;
        resizeTexture(targetWidth, targetHeight);
        notifySize(width, height);
    }

    private void resizeTexture(int targetWidth, int targetHeight) {
        width = targetWidth;
        height = targetHeight;

        int previousActiveTexture = glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE0);
        int previousTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
        int previousReadFramebuffer = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int previousDrawFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        try {
            for (int i = 0; i < textureCount(); i++) {
                glBindTexture(GL_TEXTURE_2D, textureIds[i]);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);

                glBindFramebuffer(GL_FRAMEBUFFER, fboIds[i]);
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, textureIds[i], 0);
                int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
                if (status != GL_FRAMEBUFFER_COMPLETE) {
                    throw new IllegalStateException("MPV framebuffer is incomplete: 0x" + Integer.toHexString(status));
                }
            }
            clearPublishedTexture();
            renderTextureIndex = 1;
        } finally {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, previousReadFramebuffer);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
            glBindTexture(GL_TEXTURE_2D, previousTexture);
            glActiveTexture(previousActiveTexture);
        }
    }

    private void renderFrame() {
        int textureIndex = nextRenderTextureIndex();
        renderFrameToTexture(textureIndex);
        long readySync = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        glFlush();
        publishRenderedTexture(textureIds[textureIndex], readySync);
    }

    private void renderFrameSingleContext() {
        int textureIndex = SINGLE_CONTEXT_TEXTURE_INDEX;
        renderFrameToTexture(textureIndex);
        glFlush();
        displayTextureId = textureIds[textureIndex];
    }

    private int textureCount() {
        return singleContext ? 1 : SHARED_TEXTURE_COUNT;
    }

    private void renderFrameToTexture(int textureIndex) {
        MpvOpenGLFbo target = new MpvOpenGLFbo();
        target.fbo = fboIds[textureIndex];
        target.w = width;
        target.h = height;
        target.internal_format = GL_RGBA8;
        target.write();

        Memory flipY = intMemory(0);
        Memory blockForTargetTime = intMemory(0);

        MpvRenderParam[] params = renderParams(4);
        params[0].type = MPV_RENDER_PARAM_OPENGL_FBO;
        params[0].data = target.getPointer();
        params[1].type = MPV_RENDER_PARAM_FLIP_Y;
        params[1].data = flipY;
        params[2].type = MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME;
        params[2].data = blockForTargetTime;
        params[3].type = MPV_RENDER_PARAM_INVALID;
        params[3].data = null;
        writeParams(params);

        GlStateSnapshot snapshot = singleContext ? GlStateSnapshot.capture() : null;
        try {
            prepareGlStateForMpv();
            check(lib.mpv_render_context_render(renderContext, params[0].getPointer()), "mpv_render_context_render");
        } finally {
            if (snapshot != null) snapshot.restore();
        }
    }

    private void publishRenderedTexture(int readyTextureId, long readySync) {
        long previousSync;
        synchronized (publishLock) {
            previousSync = pendingReadySync;
            pendingReadySync = readySync;
            publishedTextureId = readyTextureId;
        }
        if (previousSync != NULL) {
            glDeleteSync(previousSync);
        }
    }

    private int nextRenderTextureIndex() {
        int displayedTexture = displayTextureId;
        int pendingTexture;
        synchronized (publishLock) {
            pendingTexture = publishedTextureId;
        }
        for (int i = 0; i < SHARED_TEXTURE_COUNT; i++) {
            int index = (renderTextureIndex + i) % SHARED_TEXTURE_COUNT;
            if (textureIds[index] >= 0 && textureIds[index] != displayedTexture && textureIds[index] != pendingTexture) {
                renderTextureIndex = (index + 1) % SHARED_TEXTURE_COUNT;
                return index;
            }
        }
        int index = renderTextureIndex;
        renderTextureIndex = (renderTextureIndex + 1) % SHARED_TEXTURE_COUNT;
        return index;
    }

    private static boolean isInvalidProcAddress(long address) {
        return address == 0 || address == 1 || address == 2 || address == 3 || address == -1L;
    }

    private void prepareGlStateForMpv() {
        glUseProgram(0);
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, 0);
        glDisable(GL_BLEND);
        glDisable(GL_SCISSOR_TEST);
        glDisable(GL_CULL_FACE);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_FRAMEBUFFER_SRGB);
        glColorMask(true, true, true, true);
        glDepthMask(true);
        resetPixelStore();
        glViewport(0, 0, width, height);
    }

    private void resetPixelStore() {
        glPixelStorei(GL_PACK_ALIGNMENT, 4);
        glPixelStorei(GL_PACK_ROW_LENGTH, 0);
        glPixelStorei(GL_PACK_SKIP_PIXELS, 0);
        glPixelStorei(GL_PACK_SKIP_ROWS, 0);
        glPixelStorei(GL_PACK_SWAP_BYTES, 0);
        glPixelStorei(GL_PACK_LSB_FIRST, 0);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
        glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
        glPixelStorei(GL_UNPACK_IMAGE_HEIGHT, 0);
        glPixelStorei(GL_UNPACK_SKIP_IMAGES, 0);
        glPixelStorei(GL_UNPACK_SWAP_BYTES, 0);
        glPixelStorei(GL_UNPACK_LSB_FIRST, 0);
    }

    private static void setEnabled(int capability, boolean enabled) {
        if (enabled) {
            glEnable(capability);
        } else {
            glDisable(capability);
        }
    }

    private static class GlStateSnapshot {
        private static final int PIXEL_STORE_COUNT = 14;

        private final int activeTexture;
        private final int texture0Binding;
        private final int activeTextureBinding;
        private final int readFramebuffer;
        private final int drawFramebuffer;
        private final int program;
        private final int vertexArray;
        private final int arrayBuffer;
        private final int elementArrayBuffer;
        private final int pixelPackBuffer;
        private final int pixelUnpackBuffer;
        private final int[] viewport;
        private final int[] pixelStore;
        private final boolean blend;
        private final boolean scissor;
        private final boolean cullFace;
        private final boolean depthTest;
        private final boolean framebufferSrgb;
        private final boolean depthMask;
        private final boolean[] colorMask;

        private GlStateSnapshot(
                int activeTexture,
                int texture0Binding,
                int activeTextureBinding,
                int readFramebuffer,
                int drawFramebuffer,
                int program,
                int vertexArray,
                int arrayBuffer,
                int elementArrayBuffer,
                int pixelPackBuffer,
                int pixelUnpackBuffer,
                int[] viewport,
                int[] pixelStore,
                boolean blend,
                boolean scissor,
                boolean cullFace,
                boolean depthTest,
                boolean framebufferSrgb,
                boolean depthMask,
                boolean[] colorMask
        ) {
            this.activeTexture = activeTexture;
            this.texture0Binding = texture0Binding;
            this.activeTextureBinding = activeTextureBinding;
            this.readFramebuffer = readFramebuffer;
            this.drawFramebuffer = drawFramebuffer;
            this.program = program;
            this.vertexArray = vertexArray;
            this.arrayBuffer = arrayBuffer;
            this.elementArrayBuffer = elementArrayBuffer;
            this.pixelPackBuffer = pixelPackBuffer;
            this.pixelUnpackBuffer = pixelUnpackBuffer;
            this.viewport = viewport;
            this.pixelStore = pixelStore;
            this.blend = blend;
            this.scissor = scissor;
            this.cullFace = cullFace;
            this.depthTest = depthTest;
            this.framebufferSrgb = framebufferSrgb;
            this.depthMask = depthMask;
            this.colorMask = colorMask;
        }

        private static GlStateSnapshot capture() {
            int activeTexture = glGetInteger(GL_ACTIVE_TEXTURE);
            glActiveTexture(GL_TEXTURE0);
            int texture0Binding = glGetInteger(GL_TEXTURE_BINDING_2D);
            glActiveTexture(activeTexture);
            int activeTextureBinding = glGetInteger(GL_TEXTURE_BINDING_2D);

            return new GlStateSnapshot(
                    activeTexture,
                    texture0Binding,
                    activeTextureBinding,
                    glGetInteger(GL_READ_FRAMEBUFFER_BINDING),
                    glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING),
                    glGetInteger(GL_CURRENT_PROGRAM),
                    glGetInteger(GL_VERTEX_ARRAY_BINDING),
                    glGetInteger(GL_ARRAY_BUFFER_BINDING),
                    glGetInteger(GL_ELEMENT_ARRAY_BUFFER_BINDING),
                    glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING),
                    glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING),
                    getIntVector(GL_VIEWPORT, 4),
                    capturePixelStore(),
                    glIsEnabled(GL_BLEND),
                    glIsEnabled(GL_SCISSOR_TEST),
                    glIsEnabled(GL_CULL_FACE),
                    glIsEnabled(GL_DEPTH_TEST),
                    glIsEnabled(GL_FRAMEBUFFER_SRGB),
                    getBoolean(GL_DEPTH_WRITEMASK),
                    getBooleanVector(GL_COLOR_WRITEMASK, 4)
            );
        }

        private void restore() {
            glUseProgram(program);
            glBindVertexArray(vertexArray);
            glBindBuffer(GL_ARRAY_BUFFER, arrayBuffer);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, elementArrayBuffer);
            glBindBuffer(GL_PIXEL_PACK_BUFFER, pixelPackBuffer);
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, pixelUnpackBuffer);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, readFramebuffer);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            setEnabled(GL_BLEND, blend);
            setEnabled(GL_SCISSOR_TEST, scissor);
            setEnabled(GL_CULL_FACE, cullFace);
            setEnabled(GL_DEPTH_TEST, depthTest);
            setEnabled(GL_FRAMEBUFFER_SRGB, framebufferSrgb);
            glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
            glDepthMask(depthMask);
            restorePixelStore();

            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, texture0Binding);
            glActiveTexture(activeTexture);
            glBindTexture(GL_TEXTURE_2D, activeTextureBinding);
        }

        private static int[] capturePixelStore() {
            int[] state = new int[PIXEL_STORE_COUNT];
            state[0] = glGetInteger(GL_PACK_ALIGNMENT);
            state[1] = glGetInteger(GL_PACK_ROW_LENGTH);
            state[2] = glGetInteger(GL_PACK_SKIP_PIXELS);
            state[3] = glGetInteger(GL_PACK_SKIP_ROWS);
            state[4] = glGetInteger(GL_PACK_SWAP_BYTES);
            state[5] = glGetInteger(GL_PACK_LSB_FIRST);
            state[6] = glGetInteger(GL_UNPACK_ALIGNMENT);
            state[7] = glGetInteger(GL_UNPACK_ROW_LENGTH);
            state[8] = glGetInteger(GL_UNPACK_SKIP_PIXELS);
            state[9] = glGetInteger(GL_UNPACK_SKIP_ROWS);
            state[10] = glGetInteger(GL_UNPACK_IMAGE_HEIGHT);
            state[11] = glGetInteger(GL_UNPACK_SKIP_IMAGES);
            state[12] = glGetInteger(GL_UNPACK_SWAP_BYTES);
            state[13] = glGetInteger(GL_UNPACK_LSB_FIRST);
            return state;
        }

        private void restorePixelStore() {
            glPixelStorei(GL_PACK_ALIGNMENT, pixelStore[0]);
            glPixelStorei(GL_PACK_ROW_LENGTH, pixelStore[1]);
            glPixelStorei(GL_PACK_SKIP_PIXELS, pixelStore[2]);
            glPixelStorei(GL_PACK_SKIP_ROWS, pixelStore[3]);
            glPixelStorei(GL_PACK_SWAP_BYTES, pixelStore[4]);
            glPixelStorei(GL_PACK_LSB_FIRST, pixelStore[5]);
            glPixelStorei(GL_UNPACK_ALIGNMENT, pixelStore[6]);
            glPixelStorei(GL_UNPACK_ROW_LENGTH, pixelStore[7]);
            glPixelStorei(GL_UNPACK_SKIP_PIXELS, pixelStore[8]);
            glPixelStorei(GL_UNPACK_SKIP_ROWS, pixelStore[9]);
            glPixelStorei(GL_UNPACK_IMAGE_HEIGHT, pixelStore[10]);
            glPixelStorei(GL_UNPACK_SKIP_IMAGES, pixelStore[11]);
            glPixelStorei(GL_UNPACK_SWAP_BYTES, pixelStore[12]);
            glPixelStorei(GL_UNPACK_LSB_FIRST, pixelStore[13]);
        }

        private static int[] getIntVector(int name, int size) {
            IntBuffer buffer = BufferUtils.createIntBuffer(size);
            glGetIntegerv(name, buffer);
            int[] values = new int[size];
            buffer.get(values);
            return values;
        }

        private static boolean getBoolean(int name) {
            ByteBuffer buffer = BufferUtils.createByteBuffer(1);
            glGetBooleanv(name, buffer);
            return buffer.get(0) != 0;
        }

        private static boolean[] getBooleanVector(int name, int size) {
            ByteBuffer buffer = BufferUtils.createByteBuffer(size);
            glGetBooleanv(name, buffer);
            boolean[] values = new boolean[size];
            for (int i = 0; i < size; i++) {
                values[i] = buffer.get(i) != 0;
            }
            return values;
        }
    }

    private void cleanupTexture() {
        clearPublishedTexture();
        for (int i = 0; i < SHARED_TEXTURE_COUNT; i++) {
            if (fboIds[i] >= 0) {
                glDeleteFramebuffers(fboIds[i]);
                fboIds[i] = -1;
            }
            if (textureIds[i] >= 0) {
                glDeleteTextures(textureIds[i]);
                textureIds[i] = -1;
            }
        }
        renderTextureIndex = 0;
    }

    private void clearPublishedTexture() {
        long sync;
        synchronized (publishLock) {
            sync = pendingReadySync;
            pendingReadySync = NULL;
            publishedTextureId = -1;
            displayTextureId = -1;
        }
        if (sync != NULL) {
            glDeleteSync(sync);
        }
    }

    private void command(Pointer ctx, String... args) {
        ArrayList<Memory> strings = new ArrayList<>(args.length);
        Memory argv = new Memory((long) (args.length + 1) * Native.POINTER_SIZE);
        for (int i = 0; i < args.length; i++) {
            Memory string = utf8(args[i]);
            strings.add(string);
            argv.setPointer((long) i * Native.POINTER_SIZE, string);
        }
        argv.setPointer((long) args.length * Native.POINTER_SIZE, null);
        check(lib.mpv_command(ctx, argv), "mpv_command " + args[0]);
    }

    private void loadFile(Pointer ctx, String path, String loadOptions) {
        if (loadOptions == null || loadOptions.isEmpty()) {
            command(ctx, "loadfile", path, "replace");
            return;
        }
        command(ctx, "loadfile", path, "replace", "-1", loadOptions);
    }

    private void stopNativePlayback(Pointer ctx) {
        if (ctx == null) return;
        try {
            command(ctx, "stop");
        } catch (RuntimeException e) {
            if (!released.get()) {
                VideoPlayerMain.LOGGER.warn("Failed to stop MPV playback", e);
            }
        }
    }

    private void setString(Pointer ctx, String name, String value) {
        Memory string = utf8(value);
        Memory pointer = new Memory(Native.POINTER_SIZE);
        pointer.setPointer(0, string);
        check(lib.mpv_set_property(ctx, name, MPV_FORMAT_STRING, pointer), "mpv_set_property " + name);
    }

    private void setOptionString(Pointer ctx, String name, String value) {
        check(lib.mpv_set_option_string(ctx, name, value), "mpv_set_option_string " + name);
    }

    private void setFlag(Pointer ctx, String name, boolean value) {
        Memory data = intMemory(value ? 1 : 0);
        check(lib.mpv_set_property(ctx, name, MPV_FORMAT_FLAG, data), "mpv_set_property " + name);
    }

    private void setDouble(Pointer ctx, String name, double value) {
        Memory data = new Memory(Double.BYTES);
        data.setDouble(0, value);
        check(lib.mpv_set_property(ctx, name, MPV_FORMAT_DOUBLE, data), "mpv_set_property " + name);
    }

    private Double getDouble(Pointer ctx, String name) {
        Memory data = new Memory(Double.BYTES);
        int result = lib.mpv_get_property(ctx, name, MPV_FORMAT_DOUBLE, data);
        return result < 0 ? null : data.getDouble(0);
    }

    private Boolean getFlag(Pointer ctx, String name) {
        Memory data = intMemory(0);
        int result = lib.mpv_get_property(ctx, name, MPV_FORMAT_FLAG, data);
        return result < 0 ? null : data.getInt(0) != 0;
    }

    private static Memory intMemory(int value) {
        Memory data = new Memory(Integer.BYTES);
        data.setInt(0, value);
        return data;
    }

    private MpvRenderParam[] renderParams(int count) {
        return (MpvRenderParam[]) new MpvRenderParam().toArray(count);
    }

    private void writeParams(MpvRenderParam[] params) {
        for (MpvRenderParam param : params) {
            param.write();
        }
    }

    private void check(int result, String operation) {
        if (result >= 0) return;
        throw new IllegalStateException(operation + " failed: " + lib.mpv_error_string(result));
    }

    @FunctionalInterface
    private interface MpvTask {
        void run(Pointer ctx);
    }
}
