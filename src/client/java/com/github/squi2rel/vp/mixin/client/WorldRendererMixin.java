package com.github.squi2rel.vp.mixin.client;

import com.github.squi2rel.vp.ScreenRenderer;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
public class WorldRendererMixin {
    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    public void noClouds(CallbackInfo ci) {
        if (ScreenRenderer.skybox) ci.cancel();
    }
}
