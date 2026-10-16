package com.github.squi2rel.vp.mixin.client;

import com.github.squi2rel.vp.VideoPlayerClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public class MinecraftClientMixin {
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void videoplayer$selectPoint(CallbackInfoReturnable<Boolean> ci) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null && net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback.EVENT.invoke(client, client.player, 1)) {
            ci.setReturnValue(false);
        }
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void videoplayer$holdSelection(boolean held, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if (held && client.player != null && net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback.EVENT.invoke(client, client.player, 0)) {
            ci.cancel();
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void videoplayer$stopping(CallbackInfo ci) {
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING.invoke(Minecraft.getInstance());
    }

    @Inject(method = "runTick", at = @At("HEAD"))
    public void render(boolean tick, CallbackInfo ci) {
        VideoPlayerClient.updated = false;
    }
}
