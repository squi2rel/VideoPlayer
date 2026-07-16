package com.github.squi2rel.vp.video;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.texture.GlTexture;
import net.minecraft.client.util.Window;

public abstract class AbstractCameraPlayer extends AbstractScreenPlayer implements MetaListener {
    protected Framebuffer framebuffer;
    private Framebuffer framebuffer1;
    private Framebuffer framebuffer2;
    private boolean first = true;
    protected float aspect = 16f / 9f;
    protected int targetWidth = 16;
    protected int targetHeight = 9;
    protected boolean rendered;

    protected AbstractCameraPlayer(ClientVideoScreen screen) {
        super(screen);
    }

    @Override
    public void init() {
        framebuffer1 = new SimpleFramebuffer("VideoPlayer camera 1", targetWidth, targetHeight, true);
        framebuffer2 = new SimpleFramebuffer("VideoPlayer camera 2", targetWidth, targetHeight, true);
        framebuffer = framebuffer1;
    }

    @Override
    public void cleanup() {
        if (framebuffer1 != null) framebuffer1.delete();
        if (framebuffer2 != null) framebuffer2.delete();
        framebuffer1 = null;
        framebuffer2 = null;
        framebuffer = null;
        rendered = false;
    }

    @Override
    public void swapTexture() {
        framebuffer = first ? framebuffer1 : framebuffer2;
        first = !first;
    }

    @Override
    public void updateTexture() {
        Window window = MinecraftClient.getInstance().getWindow();
        int width = Math.max(1, window.getFramebufferWidth());
        int height = Math.max(1, Math.round(width / aspect));
        if (height > window.getFramebufferHeight()) {
            height = Math.max(1, window.getFramebufferHeight());
            width = Math.max(1, Math.round(height * aspect));
        }
        targetWidth = width;
        targetHeight = height;
        if (framebuffer != null && (framebuffer.textureWidth != width || framebuffer.textureHeight != height)) framebuffer.resize(width, height);
    }

    @Override
    public void onMetaChanged() {
        aspect = screen.metadata.getFloat(ScreenMetadata.KEY_CAMERA_ASPECT, 16f / 9f);
        if (!Float.isFinite(aspect) || aspect <= 0) aspect = 16f / 9f;
    }

    @Override
    public int getTextureId() {
        return framebuffer != null && framebuffer.getColorAttachment() instanceof GlTexture texture ? texture.getGlId() : -1;
    }

    @Override
    public boolean hasVideoFrame() {
        return rendered && getTextureId() >= 0;
    }

    @Override
    public int getWidth() {
        return targetWidth;
    }

    @Override
    public int getHeight() {
        return targetHeight;
    }

    @Override
    public boolean flippedY() {
        return true;
    }

    @Override
    public boolean isPostUpdate() {
        return true;
    }
}
