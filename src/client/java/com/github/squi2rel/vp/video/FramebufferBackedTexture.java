package com.github.squi2rel.vp.video;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.texture.AbstractTexture;

public final class FramebufferBackedTexture extends AbstractTexture {
    private final Framebuffer framebuffer;

    public FramebufferBackedTexture(Framebuffer framebuffer) {
        this(framebuffer, FilterMode.LINEAR);
    }

    public FramebufferBackedTexture(Framebuffer framebuffer, FilterMode filterMode) {
        this.framebuffer = framebuffer;
        this.sampler = RenderSystem.getSamplerCache().get(
                AddressMode.CLAMP_TO_EDGE,
                AddressMode.CLAMP_TO_EDGE,
                filterMode,
                filterMode,
                false
        );
        updateAttachment();
    }

    public void updateAttachment() {
        this.glTexture = framebuffer.getColorAttachment();
        this.glTextureView = framebuffer.getColorAttachmentView();
    }

    @Override
    public void close() {
        framebuffer.delete();
        glTexture = null;
        glTextureView = null;
    }
}
