package com.github.squi2rel.vp.video;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;

public final class FramebufferBackedTexture extends AbstractTexture {
    private final RenderTarget framebuffer;
    public FramebufferBackedTexture(RenderTarget framebuffer) { this.framebuffer = framebuffer; updateAttachment(); }
    public void updateAttachment() { id = framebuffer.getColorTextureId(); }
    @Override public void load(ResourceManager resources) { updateAttachment(); }
    @Override public void releaseId() { id = -1; }
    @Override public void close() { framebuffer.destroyBuffers(); id = -1; }
}
