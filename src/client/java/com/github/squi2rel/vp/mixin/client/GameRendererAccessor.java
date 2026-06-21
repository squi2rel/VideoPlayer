package com.github.squi2rel.vp.mixin.client;

import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
    @Accessor("fogRenderer")
    FogRenderer videoplayer$getFogRenderer();
}
