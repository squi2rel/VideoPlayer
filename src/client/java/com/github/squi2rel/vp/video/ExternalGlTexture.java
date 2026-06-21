package com.github.squi2rel.vp.video;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.GlTexture;
import net.minecraft.client.texture.GlTextureView;

public final class ExternalGlTexture extends AbstractTexture {
    public ExternalGlTexture(int glId, int width, int height) {
        WrappedTexture texture = new WrappedTexture(glId, width, height);
        this.glTexture = texture;
        this.glTextureView = new WrappedTextureView(texture);
        this.sampler = RenderSystem.getSamplerCache().get(
                AddressMode.CLAMP_TO_EDGE,
                AddressMode.CLAMP_TO_EDGE,
                FilterMode.LINEAR,
                FilterMode.LINEAR,
                false
        );
    }

    @Override
    public void close() {
        if (glTexture instanceof WrappedTexture wrapped) {
            wrapped.markClosed();
        }
        glTexture = null;
        glTextureView = null;
    }

    private static final class WrappedTexture extends GlTexture {
        private WrappedTexture(int glId, int width, int height) {
            super(
                    GpuTexture.USAGE_TEXTURE_BINDING,
                    "VideoPlayer external texture " + glId,
                    TextureFormat.RGBA8,
                    Math.max(1, width),
                    Math.max(1, height),
                    1,
                    1,
                    glId
            );
        }

        @Override
        public void close() {
            markClosed();
        }

        private void markClosed() {
            this.closed = true;
        }
    }

    private static final class WrappedTextureView extends GlTextureView {
        private WrappedTextureView(WrappedTexture texture) {
            super(texture, 0, 1);
        }
    }
}
