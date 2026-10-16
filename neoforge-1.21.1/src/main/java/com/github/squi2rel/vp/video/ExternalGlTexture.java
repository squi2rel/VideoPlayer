package com.github.squi2rel.vp.video;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;

/** A non-owning view of the texture managed by the video backend. */
public final class ExternalGlTexture extends AbstractTexture {
    public ExternalGlTexture(int textureId, int width, int height) { this.id = textureId; }
    @Override public void load(ResourceManager resources) {}
    @Override public void releaseId() { id = -1; }
    @Override public void close() { id = -1; }
}
