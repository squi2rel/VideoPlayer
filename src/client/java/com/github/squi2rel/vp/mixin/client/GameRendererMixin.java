package com.github.squi2rel.vp.mixin.client;

import com.github.squi2rel.vp.CameraRenderer;
import com.github.squi2rel.vp.VideoPlayerClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void videoplayer$cameraFov(Camera camera, float tickProgress, boolean changingFov, CallbackInfoReturnable<Float> cir) {
        if (CameraRenderer.rendering) cir.setReturnValue((float) CameraRenderer.fov);
    }

    @Inject(method = "renderWorld", at = @At("RETURN"))
    private void videoplayer$postUpdate(RenderTickCounter tickCounter, CallbackInfo ci) {
        if (!CameraRenderer.rendering) VideoPlayerClient.postUpdate();
    }
}
