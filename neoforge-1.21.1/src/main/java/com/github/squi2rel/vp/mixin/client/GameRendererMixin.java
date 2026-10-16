package com.github.squi2rel.vp.mixin.client;

import com.github.squi2rel.vp.CameraRenderer;
import com.github.squi2rel.vp.VideoPlayerClient;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void videoplayer$cameraFov(Camera camera, float tickProgress, boolean changingFov, CallbackInfoReturnable<Double> cir) {
        if (CameraRenderer.rendering) cir.setReturnValue((double) CameraRenderer.fov);
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void videoplayer$postUpdate(DeltaTracker tickCounter, CallbackInfo ci) {
        if (!CameraRenderer.rendering) VideoPlayerClient.postUpdate();
    }
}
