package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.ScreenRenderer;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;

public abstract class AbstractCameraPlayer extends AbstractScreenPlayer implements MetaListener {
    protected RenderTarget framebuffer;
    private RenderTarget framebuffer1;
    private RenderTarget framebuffer2;
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
        framebuffer1 = new TextureTarget(targetWidth, targetHeight, true, Minecraft.ON_OSX);
        framebuffer2 = new TextureTarget(targetWidth, targetHeight, true, Minecraft.ON_OSX);
        framebuffer = framebuffer1;
    }

    @Override
    public void cleanup() {
        releaseFramebuffer(framebuffer1);
        releaseFramebuffer(framebuffer2);
        if (framebuffer1 != null) framebuffer1.destroyBuffers();
        if (framebuffer2 != null) framebuffer2.destroyBuffers();
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
        Window window = Minecraft.getInstance().getWindow();
        int width = Math.max(1, window.getWidth());
        int height = Math.max(1, Math.round(width / aspect));
        if (height > window.getHeight()) {
            height = Math.max(1, window.getHeight());
            width = Math.max(1, Math.round(height * aspect));
        }
        targetWidth = width;
        targetHeight = height;
        if (framebuffer != null && (framebuffer.width != width || framebuffer.height != height)) {
            releaseFramebuffer(framebuffer);
            framebuffer.resize(width, height, Minecraft.ON_OSX);
        }
    }

    @Override
    public void onMetaChanged() {
        aspect = screen.metadata.getFloat(ScreenMetadata.KEY_CAMERA_ASPECT, 16f / 9f);
        if (!Float.isFinite(aspect) || aspect <= 0) aspect = 16f / 9f;
    }

    @Override
    public int getTextureId() {
        return framebuffer != null ? framebuffer.getColorTextureId() : -1;
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

    private static void releaseFramebuffer(RenderTarget target) {
        if (target != null) {
            ScreenRenderer.releaseTexture(target.getColorTextureId());
        }
    }
}
