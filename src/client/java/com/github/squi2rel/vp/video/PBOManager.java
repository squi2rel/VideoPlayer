package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.VideoPlayerMain;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

import java.util.Arrays;
import java.nio.ByteBuffer;
import java.util.concurrent.locks.ReentrantLock;

import static org.lwjgl.opengl.ARBBufferStorage.GL_DYNAMIC_STORAGE_BIT;
import static org.lwjgl.opengl.ARBBufferStorage.GL_MAP_COHERENT_BIT;
import static org.lwjgl.opengl.ARBBufferStorage.GL_MAP_PERSISTENT_BIT;
import static org.lwjgl.opengl.ARBBufferStorage.glBufferStorage;
import static org.lwjgl.opengl.GL15C.GL_STREAM_DRAW;
import static org.lwjgl.opengl.GL21C.GL_PIXEL_UNPACK_BUFFER;
import static org.lwjgl.opengl.GL21C.GL_PIXEL_UNPACK_BUFFER_BINDING;
import static org.lwjgl.opengl.GL21C.glBindBuffer;
import static org.lwjgl.opengl.GL21C.glBufferData;
import static org.lwjgl.opengl.GL21C.glDeleteBuffers;
import static org.lwjgl.opengl.GL21C.glGenBuffers;
import static org.lwjgl.opengl.GL21C.glGetInteger;
import static org.lwjgl.opengl.GL21C.glUnmapBuffer;
import static org.lwjgl.opengl.GL30C.GL_MAP_INVALIDATE_BUFFER_BIT;
import static org.lwjgl.opengl.GL30C.GL_MAP_WRITE_BIT;
import static org.lwjgl.opengl.GL30C.glMapBufferRange;
import static org.lwjgl.opengl.GL32C.GL_ALREADY_SIGNALED;
import static org.lwjgl.opengl.GL32C.GL_CONDITION_SATISFIED;
import static org.lwjgl.opengl.GL32C.GL_SYNC_FLUSH_COMMANDS_BIT;
import static org.lwjgl.opengl.GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE;
import static org.lwjgl.opengl.GL32C.GL_TIMEOUT_EXPIRED;
import static org.lwjgl.opengl.GL32C.GL_WAIT_FAILED;
import static org.lwjgl.opengl.GL32C.glClientWaitSync;
import static org.lwjgl.opengl.GL32C.glDeleteSync;
import static org.lwjgl.opengl.GL32C.glFenceSync;

public class PBOManager {
    private static final int BUFFER_COUNT = 3;
    private static Boolean persistentSupported;

    private final int[] id = new int[BUFFER_COUNT];
    private final long[] fences = new long[BUFFER_COUNT];
    private final ByteBuffer[] persistentBuffers = new ByteBuffer[BUFFER_COUNT];

    private boolean allocated = false;
    private boolean persistent = false;
    private int next = 0;
    private int uploading = -1;
    private int bufferSize;
    private ByteBuffer mapBuffer;
    private final ReentrantLock lock = new ReentrantLock();

    private static boolean supportsPersistentMapping() {
        if (persistentSupported != null) return persistentSupported;
        GLCapabilities capabilities = GL.getCapabilities();
        persistentSupported = capabilities.OpenGL44 || capabilities.GL_ARB_buffer_storage;
        return persistentSupported;
    }

    public void init(int width, int height) {
        int nextBufferSize = VideoFrameLimits.rgbaBytes(width, height);
        lock.lock();
        try {
            int prevPBO = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
            if (allocated) {
                destroyCurrent();
            }
            glGenBuffers(id);
            allocated = true;
            bufferSize = nextBufferSize;
            persistent = supportsPersistentMapping() && initPersistent();
            if (!persistent) {
                initStreaming();
            }
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, prevPBO);
        } finally {
            lock.unlock();
        }
    }

    public void upload(ByteBuffer source, Runnable textureUpload) {
        lock.lock();
        try {
            if (!allocated || source.remaining() != bufferSize) return;
            uploading = next;
            next = (next + 1) % BUFFER_COUNT;
            if (persistent && !waitForFence(uploading)) return;

            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, id[uploading]);
            ByteBuffer target = mapForUpload();
            if (target == null) return;

            target.clear();
            target.put(source.slice());
            if (!persistent) {
                glUnmapBuffer(GL_PIXEL_UNPACK_BUFFER);
            }

            textureUpload.run();
            if (persistent) {
                fences[uploading] = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            }
        } finally {
            uploading = -1;
            lock.unlock();
        }
    }

    private ByteBuffer mapForUpload() {
        if (persistent) {
            return persistentBuffers[uploading];
        }
        if (mapBuffer == null || mapBuffer.capacity() != bufferSize) {
            mapBuffer = ByteBuffer.allocateDirect(bufferSize);
        }
        return glMapBufferRange(GL_PIXEL_UNPACK_BUFFER, 0, bufferSize, GL_MAP_WRITE_BIT | GL_MAP_INVALIDATE_BUFFER_BIT, mapBuffer);
    }

    public void release() {
        try {
            lock.lock();
            if (!allocated) return;
            BufferState state = detach();
            MinecraftClient.getInstance().execute(() -> destroy(state));
        } finally {
            lock.unlock();
        }
    }

    private boolean initPersistent() {
        int flags = GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT;
        try {
            for (int buffer : id) {
                glBindBuffer(GL_PIXEL_UNPACK_BUFFER, buffer);
                glBufferStorage(GL_PIXEL_UNPACK_BUFFER, bufferSize, flags | GL_DYNAMIC_STORAGE_BIT);
                ByteBuffer mapped = glMapBufferRange(GL_PIXEL_UNPACK_BUFFER, 0, bufferSize, flags);
                if (mapped == null) {
                    destroyCurrent();
                    return false;
                }
                persistentBuffers[indexOf(buffer)] = mapped;
            }
            return true;
        } catch (RuntimeException e) {
            VideoPlayerMain.LOGGER.warn("Persistent PBO mapping is unavailable, falling back to streaming PBO", e);
            destroyCurrent();
            return false;
        }
    }

    private int indexOf(int buffer) {
        for (int i = 0; i < id.length; i++) {
            if (id[i] == buffer) return i;
        }
        throw new IllegalArgumentException("Unknown PBO id: " + buffer);
    }

    private void initStreaming() {
        for (int buffer : id) {
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, buffer);
            glBufferData(GL_PIXEL_UNPACK_BUFFER, bufferSize, GL_STREAM_DRAW);
        }
        mapBuffer = ByteBuffer.allocateDirect(bufferSize);
    }

    private boolean waitForFence(int index) {
        long fence = fences[index];
        if (fence == 0) return true;
        int result = glClientWaitSync(fence, 0, 0);
        if (result == GL_TIMEOUT_EXPIRED) {
            result = glClientWaitSync(fence, GL_SYNC_FLUSH_COMMANDS_BIT, 1_000_000L);
        }
        if (result == GL_ALREADY_SIGNALED || result == GL_CONDITION_SATISFIED) {
            glDeleteSync(fence);
            fences[index] = 0;
            return true;
        }
        if (result == GL_WAIT_FAILED) {
            glDeleteSync(fence);
            fences[index] = 0;
        }
        return false;
    }

    private BufferState detach() {
        BufferState state = new BufferState(Arrays.copyOf(id, id.length), Arrays.copyOf(fences, fences.length), persistent);
        Arrays.fill(id, 0);
        Arrays.fill(fences, 0);
        Arrays.fill(persistentBuffers, null);
        allocated = false;
        persistent = false;
        next = 0;
        uploading = -1;
        bufferSize = 0;
        mapBuffer = null;
        return state;
    }

    private void destroyCurrent() {
        destroy(detach());
    }

    private void destroy(BufferState state) {
        int prevPBO = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
        for (long fence : state.fences) {
            if (fence != 0) {
                glDeleteSync(fence);
            }
        }
        if (state.persistent) {
            for (int buffer : state.ids) {
                if (buffer == 0) continue;
                glBindBuffer(GL_PIXEL_UNPACK_BUFFER, buffer);
                glUnmapBuffer(GL_PIXEL_UNPACK_BUFFER);
            }
        }
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, prevPBO);
        glDeleteBuffers(state.ids);
    }

    public boolean allocated() {
        return allocated;
    }

    private record BufferState(int[] ids, long[] fences, boolean persistent) {
    }
}
