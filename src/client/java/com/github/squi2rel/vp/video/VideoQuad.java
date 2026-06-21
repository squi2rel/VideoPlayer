package com.github.squi2rel.vp.video;

import net.minecraft.client.MinecraftClient;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL21.*;
import static org.lwjgl.opengl.GL12.GL_BGRA;
import static org.lwjgl.opengl.GL33.GL_TEXTURE_SWIZZLE_A;

public class VideoQuad {

    private int textureId;
    private int width;
    private int height;
    private boolean textureInitialized = false;
    private final PBOManager pbo = new PBOManager();

    public VideoQuad(int width, int height) {
        this.width = width;
        this.height = height;
        initializeTexture();
        pbo.init(width, height);
    }

    public synchronized void resize(int width, int height) {
        this.width = width;
        this.height = height;
        regenTexture();
        pbo.init(width, height);
    }

    private void initializeTexture() {
        textureId = glGenTextures();
        regenTexture();
    }

    private void regenTexture() {
        glBindTexture(GL_TEXTURE_2D, textureId);

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_MIRRORED_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_MIRRORED_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        forceOpaqueAlpha();

        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);

        glBindTexture(GL_TEXTURE_2D, 0);
        textureInitialized = true;
    }

    public synchronized void updateTexture(ByteBuffer frameData) {
        updateTexture(frameData, GL_RGBA);
    }

    public synchronized void updateBgraTexture(ByteBuffer frameData) {
        updateTexture(frameData, GL_BGRA);
    }

    private void updateTexture(ByteBuffer frameData, int externalFormat) {
        if (!pbo.allocated()) return;
        glBindTexture(GL_TEXTURE_2D, textureId);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, width);
        glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
        glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
        int prevPBO = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
        try {
            pbo.upload(frameData, () -> glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, externalFormat, GL_UNSIGNED_BYTE, 0));
        } finally {
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, prevPBO);
            glBindTexture(GL_TEXTURE_2D, 0);
        }
    }

    private void forceOpaqueAlpha() {
        GLCapabilities capabilities = GL.getCapabilities();
        if (capabilities.OpenGL33 || capabilities.GL_ARB_texture_swizzle) {
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_A, GL_ONE);
        }
    }

    public void cleanup() {
        if (textureInitialized) {
            MinecraftClient.getInstance().execute(() -> {
                glDeleteTextures(textureId);
                pbo.release();
            });
            textureInitialized = false;
        }
    }

    public int getTextureId() {
        return textureId;
    }
}
