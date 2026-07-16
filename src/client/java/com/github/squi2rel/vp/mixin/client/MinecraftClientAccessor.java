package com.github.squi2rel.vp.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MinecraftClient.class)
public interface MinecraftClientAccessor {
    @Accessor("framebuffer")
    Framebuffer videoplayer$getFramebuffer();

    @Accessor("framebuffer")
    @Mutable
    void videoplayer$setFramebuffer(Framebuffer framebuffer);
}
